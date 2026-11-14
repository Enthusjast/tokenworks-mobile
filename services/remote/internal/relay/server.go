// Package relay authenticates transport endpoints and splices opaque binary
// carriers. Native authentication and all business protocols stay end to end.
package relay

import (
    "context"
    "errors"
    "net/http"
    "sync"
    "time"

    "github.com/coder/websocket"
    "tokenworks-mobile/services/remote/internal/requestlog"
    "tokenworks-mobile/services/remote/internal/store"
)

var (
    ErrTunnelClosed     = errors.New("relay: runtime tunnel is closed")
    ErrTunnelLease      = errors.New("relay: tunnel lease refused")
    ErrTunnelLimits     = errors.New("relay: tunnel connection budget exhausted")
    ErrNotTunnelOwner   = errors.New("relay: runtime is served by another node")
)

const (
    maxConnectsPerRuntime = 128
    maxConnectsPerLease   = 8
    tunnelHeartbeat       = 15 * time.Second
)

type Repository interface {
    AttachRuntimeConnection(context.Context, store.RuntimeConnectionInput) (store.RuntimeConnection, error)
    TouchRuntimeConnection(context.Context, string, string, string, string) error
    DetachRuntimeConnection(context.Context, string, string, string, string) error
    VerifyTunnelLease(context.Context, store.UserIdentity, string, string, string) (store.TunnelLeaseIdentity, error)
    InspectTunnelLease(context.Context, string, string, string) (store.TunnelLeaseIdentity, error)
}

// Server owns the live Host tunnels on this node and the phone forwarding path.
type Server struct {
    database Repository
    routing  *nodeRouting
    mu       sync.Mutex
    tunnels  map[string]*hostTunnel
    draining bool
}

type hostTunnel struct {
    runtimeID      string
    installationID string
    connectionID   string
    epoch          string
    cancel         context.CancelFunc
    runtime        *TunnelRuntime
    done           chan struct{}
}

func New(database Repository) *Server {
    return &Server{database: database, tunnels: make(map[string]*hostTunnel)}
}

func writeError(response http.ResponseWriter, status int, code string) {
    requestlog.Error(response, status, code)
}

// ServeTunnel admits one already authenticated installation. Versions travel as
// transport metadata; the native wire itself carries no handshake frame.
func (server *Server) ServeTunnel(runtimeID string, installation store.HostCredentialIdentity, response http.ResponseWriter, request *http.Request) {
    if !uuidPattern.MatchString(runtimeID) || installation.InstallationID == "" || !installation.ExpiresAt.After(time.Now()) {
        writeError(response, http.StatusForbidden, "access.denied")
        return
    }
    epoch, err := randomUUID()
    if err != nil {
        writeError(response, http.StatusServiceUnavailable, "internal")
        return
    }
    connectionID, err := randomUUID()
    if err != nil {
        writeError(response, http.StatusServiceUnavailable, "internal")
        return
    }
    connection, err := server.database.AttachRuntimeConnection(request.Context(), store.RuntimeConnectionInput{
        InstallationID: installation.InstallationID, KeyVersion: installation.KeyVersion,
        RuntimeID: runtimeID, RuntimeEpoch: epoch, ConnectionID: connectionID,
        DSHVersion: singleHeader(request, "X-TokenWorks-Dsh-Version"),
        ApplicationVersion: singleHeader(request, "X-TokenWorks-Application-Version"),
        PluginVersion: singleHeader(request, "X-TokenWorks-Plugin-Version"),
    })
    if err != nil {
        switch {
        case errors.Is(err, store.ErrDatabaseUnavailable):
            writeError(response, http.StatusServiceUnavailable, "internal")
        default:
            writeError(response, http.StatusForbidden, "access.denied")
        }
        return
    }
    socket, err := websocket.Accept(response, request, &websocket.AcceptOptions{CompressionMode: websocket.CompressionDisabled})
    if err != nil {
        _ = server.database.DetachRuntimeConnection(context.Background(), installation.InstallationID, runtimeID, epoch, connectionID)
        return
    }
    if request.Header.Get("Sec-WebSocket-Protocol") != "" {
        _ = socket.Close(websocket.StatusProtocolError, "subprotocol not supported")
        return
    }
    ctx, cancel := context.WithCancel(context.Background())
    tunnel := &hostTunnel{runtimeID: runtimeID, installationID: installation.InstallationID, connectionID: connectionID,
        epoch: epoch, cancel: cancel, runtime: NewTunnelRuntime(ctx, socket), done: make(chan struct{})}
    if !server.register(tunnel) {
        cancel()
        socket.CloseNow()
        return
    }
    go server.keepAlive(ctx, tunnel)
    select { case <-ctx.Done(): case <-tunnel.runtime.ctx.Done(): cancel() }
    tunnel.runtime.Close()
    server.unregister(tunnel)
    close(tunnel.done)
    detach, detachCancel := context.WithTimeout(context.Background(), 2*time.Second)
    defer detachCancel()
    _ = server.database.DetachRuntimeConnection(detach, connection.InstallationID, connection.RuntimeID, connection.RuntimeEpoch, connection.ConnectionID)
}

func (server *Server) register(tunnel *hostTunnel) bool {
    server.mu.Lock()
    defer server.mu.Unlock()
    if server.draining {
        return false
    }
    if previous := server.tunnels[tunnel.runtimeID]; previous != nil {
        previous.cancel()
    }
    server.tunnels[tunnel.runtimeID] = tunnel
    return true
}

func (server *Server) unregister(tunnel *hostTunnel) {
    server.mu.Lock()
    if server.tunnels[tunnel.runtimeID] == tunnel {
        delete(server.tunnels, tunnel.runtimeID)
    }
    server.mu.Unlock()
}

func (server *Server) tunnel(runtimeID string) *hostTunnel {
    server.mu.Lock()
    defer server.mu.Unlock()
    return server.tunnels[runtimeID]
}

func (server *Server) keepAlive(ctx context.Context, tunnel *hostTunnel) {
    ticker := time.NewTicker(tunnelHeartbeat)
    defer ticker.Stop()
    for {
        select {
        case <-ctx.Done():
            return
        case <-ticker.C:
            touch, cancel := context.WithTimeout(ctx, 5*time.Second)
            err := server.database.TouchRuntimeConnection(touch, tunnel.installationID, tunnel.runtimeID, tunnel.epoch, tunnel.connectionID)
            cancel()
            if err != nil {
                tunnel.cancel()
                return
            }
        }
    }
}

// AdmitPhone verifies the lease facts before anything is forwarded. The device
// identity comes from the access token, never from the lease header.
func (server *Server) AdmitPhone(ctx context.Context, principal store.UserIdentity, deviceID, runtimeID, leaseSecret string) (store.TunnelLeaseIdentity, error) {
    if principal.Issuer == "" || principal.Subject == "" || !uuidPattern.MatchString(runtimeID) || !uuidPattern.MatchString(deviceID) {
        return store.TunnelLeaseIdentity{}, ErrTunnelLease
    }
    lease, err := server.database.VerifyTunnelLease(ctx, principal, deviceID, runtimeID, leaseSecret)
    if err != nil {
        return store.TunnelLeaseIdentity{}, ErrTunnelLease
    }
    if lease.RuntimeID != runtimeID || lease.DeviceID != deviceID || !uuidPattern.MatchString(lease.LeaseID) || !lease.ExpiresAt.After(time.Now()) {
        return store.TunnelLeaseIdentity{}, ErrTunnelLease
    }
    return lease, nil
}

// AdmitForwarded never impersonates a phone with an empty principal. Node mTLS
// authorizes forwarding only; the owning installation checks the current lease.
func (server *Server) AdmitForwarded(ctx context.Context, runtimeID, leaseID, secret string) (store.TunnelLeaseIdentity, error) {
    tunnel := server.tunnel(runtimeID)
    if tunnel == nil || !uuidPattern.MatchString(leaseID) { return store.TunnelLeaseIdentity{}, store.ErrTunnelLeaseInvalid }
    lease, err := server.database.InspectTunnelLease(ctx, tunnel.installationID, leaseID, secret)
    if err != nil { return store.TunnelLeaseIdentity{}, err }
    if lease.LeaseID != leaseID || lease.RuntimeID != runtimeID || lease.InstallationID != tunnel.installationID ||
        !uuidPattern.MatchString(lease.DeviceID) || !lease.ExpiresAt.After(time.Now()) {
        return store.TunnelLeaseIdentity{}, store.ErrTunnelLeaseInvalid
    }
    return lease, nil
}

func (server *Server) Carrier(response http.ResponseWriter, request *http.Request, lease store.TunnelLeaseIdentity, secret string) {
    tunnel := server.tunnel(lease.RuntimeID)
    if tunnel == nil || tunnel.installationID != lease.InstallationID {
        writeError(response, http.StatusServiceUnavailable, "runtime.offline")
        return
    }
    tunnel.runtime.Carrier(response, request, lease, secret)
}

// OwnsRuntime reports whether this node holds the runtime tunnel.
func (server *Server) OwnsRuntime(runtimeID string) bool { return server.tunnel(runtimeID) != nil }

func (server *Server) ApplyControlEvent(ctx context.Context, event store.ControlEvent) error {
    switch event.Topic {
    case "device.revoked", "runtime.revoked", "runtime_grant.updated", "runtime_grant.revoked":
    default:
        return nil
    }
    server.mu.Lock()
    tunnels := make([]*hostTunnel, 0)
    for _, tunnel := range server.tunnels {
        if event.RuntimeID != "" && tunnel.runtimeID != event.RuntimeID {
            continue
        }
        tunnel.runtime.revoke(func(lease store.TunnelLeaseIdentity) bool {
            switch event.Topic {
            case "device.revoked": return event.DeviceID != "" && lease.DeviceID == event.DeviceID
            case "runtime.revoked": return lease.RuntimeID == event.RuntimeID
            default: return event.UserID != "" && lease.UserID == event.UserID
            }
        })
        if event.Topic == "runtime.revoked" && event.RuntimeID == tunnel.runtimeID {
            tunnels = append(tunnels, tunnel)
        }
    }
    server.mu.Unlock()
    for _, tunnel := range tunnels {
        tunnel.cancel()
    }
    return nil
}

func (server *Server) RevalidateConnections(ctx context.Context) error {
    server.mu.Lock()
    tunnels := make([]*hostTunnel, 0, len(server.tunnels))
    for _, tunnel := range server.tunnels { tunnels = append(tunnels, tunnel) }
    server.mu.Unlock()
    var result error
    for _, tunnel := range tunnels {
        result = errors.Join(result, tunnel.runtime.Revalidate(ctx, func(ctx context.Context, id, secret string) (store.TunnelLeaseIdentity, error) {
            return server.database.InspectTunnelLease(ctx, tunnel.installationID, id, secret)
        }))
    }
    return result
}

func (server *Server) Drain(ctx context.Context) error {
    server.mu.Lock()
    server.draining = true
    tunnels := make([]*hostTunnel, 0, len(server.tunnels))
    for _, tunnel := range server.tunnels {
        tunnels = append(tunnels, tunnel)
    }
    server.mu.Unlock()
    for _, tunnel := range tunnels {
        tunnel.cancel()
    }
    deadline, cancel := context.WithTimeout(ctx, 10*time.Second)
    defer cancel()
    for _, tunnel := range tunnels {
        select {
        case <-tunnel.done:
        case <-deadline.Done():
            return deadline.Err()
        }
    }
    return nil
}
