package relay

import (
	"context"
	"crypto/tls"
	"crypto/x509"
	"errors"
	"net/http"
	"time"

	"github.com/coder/websocket"

	"tokenworks-mobile/services/remote/internal/requestlog"
	"tokenworks-mobile/services/remote/internal/store"
)

const privateRelayPath = "/internal/relay/v2/carrier"
const relayNodeHeader = "X-TokenWorks-Mobile-Relay-Node"
const relayGenerationHeader = "X-TokenWorks-Mobile-Relay-Generation"
const relayEpochHeader = "X-TokenWorks-Mobile-Relay-Epoch"
const relayConnectionHeader = "X-TokenWorks-Mobile-Relay-Connection"
const relayRuntimeHeader = "X-TokenWorks-Runtime"
const leaseHeader = "X-TokenWorks-Lease"

type RoutingDirectory interface {
	RelayIdentity() store.RelayNode
	LookupRuntimeRoute(context.Context, string) (store.RuntimeRoute, error)
	ValidateRelayNode(context.Context, string, string) error
}

type RoutingConfig struct {
	Directory           RoutingDirectory
	ClientTLS           *tls.Config
	MaxProxyConnections int
}

type nodeRouting struct {
	RoutingConfig
	identity store.RelayNode
	slots chan struct{}
}

// A dedicated cluster CA must issue exactly one node URI SAN in each leaf.
// Normal chain, hostname, lifetime and EKU checks remain TLS responsibilities.
func RelayCertificateNode(certificate *x509.Certificate) (string, error) {
	if certificate == nil || len(certificate.URIs) != 1 {
		return "", errors.New("relay: node URI is required")
	}
	uri := certificate.URIs[0]
	prefix := "spiffe://tokenworks-mobile/relay/"
	value := uri.String()
	if len(value) != len(prefix)+36 || value[:len(prefix)] != prefix || !uuidPattern.MatchString(value[len(prefix):]) {
		return "", errors.New("relay: node URI is invalid")
	}
	return value[len(prefix):], nil
}

func (server *Server) ConfigureRouting(config RoutingConfig) error {
	if config.Directory == nil {
		return errors.New("relay: directory is required")
	}
	identity := config.Directory.RelayIdentity()
	if !uuidPattern.MatchString(identity.NodeID) || !uuidPattern.MatchString(identity.Generation) ||
		(identity.Origin != "" && !store.ValidRelayOrigin(identity.Origin)) {
		return errors.New("relay: node identity is invalid")
	}
	if config.MaxProxyConnections == 0 {
		config.MaxProxyConnections = 4096
	}
	if config.MaxProxyConnections < 1 || config.MaxProxyConnections > 12000 {
		return errors.New("relay: proxy connection budget is invalid")
	}
	if config.ClientTLS != nil {
		if identity.Origin == "" || config.ClientTLS.InsecureSkipVerify || config.ClientTLS.MinVersion < tls.VersionTLS13 ||
			config.ClientTLS.RootCAs == nil || len(config.ClientTLS.Certificates) != 1 || len(config.ClientTLS.Certificates[0].Certificate) == 0 || config.ClientTLS.ServerName != "" {
			return errors.New("relay: verified mutual TLS is required")
		}
		certificate, err := x509.ParseCertificate(config.ClientTLS.Certificates[0].Certificate[0])
		if err != nil {
			return errors.New("relay: node certificate is invalid")
		}
		node, err := RelayCertificateNode(certificate)
		if err != nil || node != identity.NodeID {
			return errors.New("relay: certificate does not identify this node")
		}
		config.ClientTLS = config.ClientTLS.Clone()
	} else if identity.Origin != "" {
		return errors.New("relay: private origin requires mutual TLS")
	}
	server.routing = &nodeRouting{RoutingConfig: config, identity: identity, slots: make(chan struct{}, config.MaxProxyConnections)}
	return nil
}


// ForwardNode opens one mTLS carrier WS to the current owning generation.
// Only opaque binary bytes cross this hop; it never retries or reroutes.
func (server *Server) ForwardNode(response http.ResponseWriter, request *http.Request, route store.RuntimeRoute, lease store.TunnelLeaseIdentity, secret string) {
	routing := server.routing
	if !validCarrierRequest(request) {
		writeError(response, http.StatusBadRequest, "protocol.invalid")
		return
	}
	if routing == nil || routing.ClientTLS == nil || !store.ValidRelayOrigin(route.Origin) || route.RuntimeID != lease.RuntimeID ||
		!uuidPattern.MatchString(route.RuntimeEpoch) || !uuidPattern.MatchString(route.ConnectionID) ||
		route.NodeID == routing.identity.NodeID && route.Generation == routing.identity.Generation {
		writeError(response, http.StatusServiceUnavailable, "runtime.offline")
		return
	}
	select { case routing.slots <- struct{}{}: defer func() { <-routing.slots }()
	default: writeError(response, http.StatusServiceUnavailable, "runtime.offline"); return }
	ctx, cancel := context.WithDeadline(request.Context(), lease.ExpiresAt)
	defer cancel()
	if err := routing.Directory.ValidateRelayNode(ctx, route.NodeID, route.Generation); err != nil {
		writeError(response, http.StatusConflict, "runtime.changed"); return
	}
	tlsConfig := routing.ClientTLS.Clone()
	verify := tlsConfig.VerifyConnection
	tlsConfig.VerifyConnection = func(state tls.ConnectionState) error {
		if verify != nil { if err := verify(state); err != nil { return err } }
		if len(state.PeerCertificates) == 0 { return errors.New("relay: missing node certificate") }
		node, err := RelayCertificateNode(state.PeerCertificates[0])
		if err != nil || node != route.NodeID { return errors.New("relay: wrong owning node certificate") }
		return nil
	}
	protocols := new(http.Protocols)
	protocols.SetHTTP1(true)
	transport := &http.Transport{TLSClientConfig: tlsConfig, Protocols: protocols, DisableCompression: true,
		MaxConnsPerHost: 1, MaxResponseHeaderBytes: 16 << 10, ResponseHeaderTimeout: 15*time.Second}
	defer transport.CloseIdleConnections()
	headers := make(http.Header)
	headers.Set(relayNodeHeader, routing.identity.NodeID)
	headers.Set(relayGenerationHeader, routing.identity.Generation)
	headers.Set(relayRuntimeHeader, lease.RuntimeID)
	headers.Set(relayEpochHeader, route.RuntimeEpoch)
	headers.Set(relayConnectionHeader, route.ConnectionID)
	headers.Set(leaseHeader, secret)
	headers.Set("X-TokenWorks-Lease-Id", lease.LeaseID)
	headers.Set(carrierPurposeHeader, CarrierPurpose(request))
	timer := time.AfterFunc(15*time.Second, cancel)
	upstream, upstreamResponse, err := websocket.Dial(ctx, "wss"+route.Origin[len("https"):]+privateRelayPath,
		&websocket.DialOptions{HTTPClient: &http.Client{Transport: transport, CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }},
			HTTPHeader: headers, CompressionMode: websocket.CompressionDisabled})
	timer.Stop()
	if err != nil {
		if upstreamResponse != nil && upstreamResponse.Body != nil { upstreamResponse.Body.Close() }
		writeError(response, http.StatusServiceUnavailable, "runtime.offline"); return
	}
	defer upstream.CloseNow()
	socket, err := websocket.Accept(response, request, &websocket.AcceptOptions{CompressionMode: websocket.CompressionDisabled})
	if err != nil { return }
	defer socket.CloseNow()
	closeBoth := func() { socket.CloseNow(); upstream.CloseNow(); cancel() }
	stop := context.AfterFunc(ctx, closeBoth)
	defer stop()
	left := websocket.NetConn(ctx, socket, websocket.MessageBinary)
	right := websocket.NetConn(ctx, upstream, websocket.MessageBinary)
	socket.SetReadLimit(carrierWindowBytes)
	upstream.SetReadLimit(carrierWindowBytes)
	splice(left, right, closeBoth)
}

// PrivateRelayHandler serves node-to-node forwarding only after the peer's
// mutual-TLS node identity and the current route ownership are checked.
func (server *Server) PrivateRelayHandler(next http.Handler) http.Handler {
	return http.HandlerFunc(func(response http.ResponseWriter, request *http.Request) {
		if request.URL.Path != privateRelayPath || !validCarrierRequest(request) {
			writeError(response, http.StatusForbidden, "access.denied")
			return
		}
		node, generation := singleHeader(request, relayNodeHeader), singleHeader(request, relayGenerationHeader)
		runtimeID := singleHeader(request, relayRuntimeHeader)
		if server.routing == nil || !uuidPattern.MatchString(node) || !uuidPattern.MatchString(generation) || !uuidPattern.MatchString(runtimeID) ||
			request.TLS == nil || len(request.TLS.PeerCertificates) == 0 {
			writeError(response, http.StatusForbidden, "access.denied")
			return
		}
		peerNode, err := RelayCertificateNode(request.TLS.PeerCertificates[0])
		if err != nil || peerNode != node || len(request.TLS.VerifiedChains) == 0 {
			writeError(response, http.StatusForbidden, "access.denied")
			return
		}
		if err := server.routing.Directory.ValidateRelayNode(request.Context(), node, generation); err != nil {
			writeError(response, http.StatusConflict, "runtime.changed")
			return
		}
		route, err := server.routing.Directory.LookupRuntimeRoute(request.Context(), runtimeID)
		if err != nil || route.NodeID != server.routing.identity.NodeID || route.Generation != server.routing.identity.Generation ||
			route.RuntimeEpoch != singleHeader(request, relayEpochHeader) || route.ConnectionID != singleHeader(request, relayConnectionHeader) {
			writeError(response, http.StatusConflict, "runtime.changed")
			return
		}
		if route.Origin != "" && !store.ValidRelayOrigin(route.Origin) {
			writeError(response, http.StatusConflict, "runtime.changed")
			return
		}
		tunnel := server.tunnel(runtimeID)
		if tunnel == nil || tunnel.epoch != route.RuntimeEpoch || tunnel.connectionID != route.ConnectionID {
			writeError(response, http.StatusConflict, "runtime.changed")
			return
		}
		requestlog.AdoptForwarded(response, request)
		next.ServeHTTP(response, request)
	})
}

func singleHeader(request *http.Request, name string) string {
	values := request.Header.Values(name)
	if len(values) != 1 {
		return ""
	}
	return values[0]
}


