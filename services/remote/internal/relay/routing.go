package relay

import (
	"context"
	"crypto/tls"
	"crypto/x509"
	"errors"
	"net/http"
	"net/http/httputil"
	"net/url"

	"tokenworks-mobile/services/remote/internal/requestlog"
	"tokenworks-mobile/services/remote/internal/store"
)

const privateRelayPath = "/internal/relay/v2/device"
const relayNodeHeader = "X-TokenWorks-Mobile-Relay-Node"
const relayGenerationHeader = "X-TokenWorks-Mobile-Relay-Generation"
const relayRouteHeader = "X-TokenWorks-Mobile-Relay-Route"
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
	server.routing = &nodeRouting{config, identity}
	return nil
}

func (server *Server) localRoute(runtimeID string) bool {
	return server.routing != nil && server.OwnsRuntime(runtimeID)
}

// ForwardNode sends one admitted phone request to the node that owns the
// runtime tunnel. The bytes are forwarded once; a disconnect is never replayed.
func (server *Server) ForwardNode(response http.ResponseWriter, request *http.Request, route store.RuntimeRoute) {
	routing := server.routing
	if routing == nil || routing.ClientTLS == nil || route.Origin == "" ||
		route.NodeID == routing.identity.NodeID && route.Generation == routing.identity.Generation {
		writeError(response, http.StatusServiceUnavailable, "runtime.offline")
		return
	}
	origin, err := url.Parse(route.Origin)
	if err != nil || origin.Scheme != "https" {
		writeError(response, http.StatusServiceUnavailable, "internal")
		return
	}
	proxy := httputil.ReverseProxy{
		Transport: &http.Transport{
			TLSClientConfig:     routing.ClientTLS.Clone(),
			MaxIdleConnsPerHost: 32,
			MaxConnsPerHost:     routing.MaxProxyConnections,
			ForceAttemptHTTP2:   true,
		},
		FlushInterval: -1,
		Rewrite: func(proxy *httputil.ProxyRequest) {
			proxy.Out.URL.Scheme = "https"
			proxy.Out.URL.Host = origin.Host
			proxy.Out.URL.Path = privateRelayPath
			proxy.Out.URL.RawPath = ""
			proxy.Out.URL.RawQuery = ""
			proxy.Out.Host = origin.Host
			proxy.Out.Header.Set(relayNodeHeader, routing.identity.NodeID)
			proxy.Out.Header.Set(relayGenerationHeader, routing.identity.Generation)
			proxy.Out.Header.Set(relayRouteHeader, request.URL.Path)
			proxy.Out.Header.Del("Cookie")
		},
		ErrorHandler: func(w http.ResponseWriter, _ *http.Request, _ error) {
			writeError(w, http.StatusServiceUnavailable, "runtime.offline")
		},
	}
	proxy.ServeHTTP(response, request)
}

// PrivateRelayHandler serves node-to-node forwarding only after the peer's
// mutual-TLS node identity and the current route ownership are checked.
func (server *Server) PrivateRelayHandler(next http.Handler) http.Handler {
	return http.HandlerFunc(func(response http.ResponseWriter, request *http.Request) {
		if request.URL.Path != privateRelayPath || request.Method != "GET" && request.Method != "POST" && request.Method != "HEAD" {
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
		if err != nil || peerNode != node {
			writeError(response, http.StatusForbidden, "access.denied")
			return
		}
		if err := server.routing.Directory.ValidateRelayNode(request.Context(), node, generation); err != nil {
			writeError(response, http.StatusConflict, "runtime.changed")
			return
		}
		route, err := server.routing.Directory.LookupRuntimeRoute(request.Context(), runtimeID)
		if err != nil || route.NodeID != server.routing.identity.NodeID || route.Generation != server.routing.identity.Generation {
			writeError(response, http.StatusConflict, "runtime.changed")
			return
		}
		if route.Origin != "" && !store.ValidRelayOrigin(route.Origin) {
			writeError(response, http.StatusConflict, "runtime.changed")
			return
		}
		path := singleHeader(request, relayRouteHeader)
		if path == "" || path[0] != '/' || len(path) > 4096 {
			writeError(response, http.StatusBadRequest, "protocol.invalid")
			return
		}
		request.URL.Path = path
		request.URL.RawPath = ""
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

// Drain closes live tunnels; pending node forwards finish through their own
// request contexts and are bounded by the node transport.
func (server *Server) proxySlots() int {
	if server.routing == nil || server.routing.MaxProxyConnections == 0 {
		return 4096
	}
	return server.routing.MaxProxyConnections
}

