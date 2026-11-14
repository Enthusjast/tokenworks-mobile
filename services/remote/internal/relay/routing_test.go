package relay

import (
	"bytes"
	"context"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/tls"
	"crypto/x509"
	"crypto/x509/pkix"
	"math/big"
	"net"
	"net/http"
	"net/http/httptest"
	"net/url"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/coder/websocket"
	"tokenworks-mobile/services/remote/internal/store"
)

type testRoutingDirectory struct {
	identity store.RelayNode
	route store.RuntimeRoute
	nodes map[string]string
}
func (directory *testRoutingDirectory) RelayIdentity() store.RelayNode { return directory.identity }
func (directory *testRoutingDirectory) LookupRuntimeRoute(context.Context, string) (store.RuntimeRoute, error) { return directory.route, nil }
func (directory *testRoutingDirectory) ValidateRelayNode(_ context.Context, node, generation string) error {
	if directory.nodes[node] != generation { return store.ErrRuntimeConnectionStale }
	return nil
}

func clusterCertificates(t *testing.T, nodes ...string) (*x509.CertPool, []tls.Certificate) {
	t.Helper()
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil { t.Fatal(err) }
	root := &x509.Certificate{SerialNumber: big.NewInt(1), Subject: pkix.Name{CommonName:"carrier test CA"},
		NotBefore:time.Now().Add(-time.Hour), NotAfter:time.Now().Add(time.Hour), IsCA:true, BasicConstraintsValid:true,
		KeyUsage:x509.KeyUsageCertSign | x509.KeyUsageDigitalSignature}
	der, err := x509.CreateCertificate(rand.Reader, root, root, &key.PublicKey, key)
	if err != nil { t.Fatal(err) }
	ca, err := x509.ParseCertificate(der)
	if err != nil { t.Fatal(err) }
	pool := x509.NewCertPool(); pool.AddCert(ca)
	certificates := make([]tls.Certificate, 0, len(nodes))
	for i, node := range nodes {
		leafKey, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
		if err != nil { t.Fatal(err) }
		uri, err := url.Parse("spiffe://tokenworks-mobile/relay/"+node)
		if err != nil { t.Fatal(err) }
		leaf := &x509.Certificate{SerialNumber:big.NewInt(int64(i+2)), Subject:pkix.Name{CommonName:node},
			NotBefore:time.Now().Add(-time.Hour), NotAfter:time.Now().Add(time.Hour),
			KeyUsage:x509.KeyUsageDigitalSignature, ExtKeyUsage:[]x509.ExtKeyUsage{x509.ExtKeyUsageClientAuth,x509.ExtKeyUsageServerAuth},
			URIs:[]*url.URL{uri}, IPAddresses:[]net.IP{net.ParseIP("127.0.0.1")}}
		der, err := x509.CreateCertificate(rand.Reader, leaf, ca, &leafKey.PublicKey, key)
		if err != nil { t.Fatal(err) }
		certificates = append(certificates, tls.Certificate{Certificate:[][]byte{der},PrivateKey:leafKey})
	}
	return pool, certificates
}

func TestMultiNodeCarrierMTLSOpaqueAndRouteFencing(t *testing.T) {
	roots, certificates := clusterCertificates(t, testDeviceID, testInstanceID)
	lease := futureLease()
	var mu sync.Mutex
	inspections := 0
	repository := &fakeRepository{leases:func(context.Context, store.UserIdentity, string, string, string)(store.TunnelLeaseIdentity,error) {
		t.Error("owning node attempted phone admission instead of lease inspection")
		return store.TunnelLeaseIdentity{}, store.ErrTunnelLeaseInvalid
	}, inspect:func(_ context.Context, installation, id, secret string)(store.TunnelLeaseIdentity,error) {
		mu.Lock(); inspections++; mu.Unlock()
		if installation != testInstanceID || id != testLeaseID || secret != strings.Repeat("a",43) {
			t.Error("owning node did not inspect installation-scoped full lease")
			return store.TunnelLeaseIdentity{}, store.ErrTunnelLeaseInvalid
		}
		return lease,nil
	}}
	owner := New(repository)
	runtime := newTestRuntime(t, echoConnect(t,nil))
	owner.register(&hostTunnel{runtimeID:testRuntimeID, installationID:testInstanceID, epoch:testUserID,
		connectionID:testLeaseID, runtime:runtime, cancel:func(){}, done:make(chan struct{})})
	private := httptest.NewUnstartedServer(owner.PrivateRelayHandler(http.HandlerFunc(func(w http.ResponseWriter,r *http.Request) {
		if r.Header.Get("Authorization") != "" || r.Header.Get("DPoP") != "" || r.Header.Get("Cookie") != "" { t.Error("public credentials crossed node hop") }
		facts, err := owner.AdmitForwarded(r.Context(),singleHeader(r,relayRuntimeHeader),singleHeader(r,"X-TokenWorks-Lease-Id"),singleHeader(r,leaseHeader))
		if err != nil { w.WriteHeader(http.StatusUnauthorized); return }
		owner.Carrier(w,r,facts,singleHeader(r,leaseHeader))
	})))
	private.TLS = &tls.Config{MinVersion:tls.VersionTLS13,Certificates:[]tls.Certificate{certificates[1]},ClientAuth:tls.RequireAndVerifyClientCert,ClientCAs:roots}
	private.StartTLS()
	t.Cleanup(private.Close)
	route := store.RuntimeRoute{RelayNode:store.RelayNode{NodeID:testInstanceID,Generation:testRuntimeID,Origin:private.URL},
		RuntimeID:testRuntimeID,RuntimeEpoch:testUserID,ConnectionID:testLeaseID}
	nodes := map[string]string{testDeviceID:testRuntimeID,testInstanceID:testRuntimeID}
	ownerDirectory := &testRoutingDirectory{identity:route.RelayNode,route:route,nodes:nodes}
	if err := owner.ConfigureRouting(RoutingConfig{Directory:ownerDirectory,ClientTLS:&tls.Config{MinVersion:tls.VersionTLS13,RootCAs:roots,Certificates:[]tls.Certificate{certificates[1]}}}); err != nil { t.Fatal(err) }
	source := New(&fakeRepository{})
	sourceDirectory := &testRoutingDirectory{identity:store.RelayNode{NodeID:testDeviceID,Generation:testRuntimeID,Origin:"https://source.invalid"},route:route,nodes:nodes}
	if err := source.ConfigureRouting(RoutingConfig{Directory:sourceDirectory,ClientTLS:&tls.Config{MinVersion:tls.VersionTLS13,RootCAs:roots,Certificates:[]tls.Certificate{certificates[0]}}}); err != nil { t.Fatal(err) }
	// Select the stale route by request header without modifying shared state.
	public := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter,r *http.Request) {
		selected := route
		if r.Header.Get("Test-Stale") == "epoch" { selected.RuntimeEpoch = testDeviceID }
		if r.Header.Get("Test-Stale") == "generation" { selected.Generation = testDeviceID }
		source.ForwardNode(w,r,selected,lease,strings.Repeat("a",43))
	}))
	t.Cleanup(public.Close)
	ctx,cancel := context.WithTimeout(context.Background(),5*time.Second)
	defer cancel()
	socket,_,err := websocket.Dial(ctx,public.URL,&websocket.DialOptions{HTTPHeader:http.Header{carrierPurposeHeader:{"native"},"Authorization":{"Bearer must-not-forward"},"Cookie":{"secret=yes"}}})
	if err != nil { t.Fatal(err) }
	defer socket.CloseNow()
	payload := []byte{0,255,1,0,17,128,42}
	if err := socket.Write(ctx,websocket.MessageBinary,payload); err != nil { t.Fatal(err) }
	_,actual,err := socket.Read(ctx)
	if err != nil || !bytes.Equal(actual,payload) { t.Fatalf("multi-node opaque bytes: %x %v",actual,err) }
	for _,stale := range []string{"epoch","generation"} {
		other,response,err := websocket.Dial(ctx,public.URL,&websocket.DialOptions{HTTPHeader:http.Header{carrierPurposeHeader:{"native"},"Test-Stale":{stale}}})
		if other != nil { other.CloseNow() }
		if response != nil && response.Body != nil { response.Body.Close() }
		if err == nil { t.Fatalf("stale %s forwarded",stale) }
	}
	mu.Lock(); count := inspections; mu.Unlock()
	if count != 1 { t.Fatalf("stale routes reached lease admission: %d inspections",count) }
	if err := owner.ApplyControlEvent(ctx,store.ControlEvent{Topic:"runtime_grant.revoked",RuntimeID:testRuntimeID,UserID:testUserID}); err != nil { t.Fatal(err) }
	if _,_,err := socket.Read(ctx); err == nil { t.Fatal("grant revocation left forwarded carrier alive") }
}

func TestAdmitForwardedRejectsChangedLeaseFacts(t *testing.T) {
	for _,change := range []func(*store.TunnelLeaseIdentity){
		func(lease *store.TunnelLeaseIdentity){lease.RuntimeID=testDeviceID},
		func(lease *store.TunnelLeaseIdentity){lease.InstallationID=testDeviceID},
		func(lease *store.TunnelLeaseIdentity){lease.LeaseID=testDeviceID},
		func(lease *store.TunnelLeaseIdentity){lease.DeviceID=""},
		func(lease *store.TunnelLeaseIdentity){lease.ExpiresAt=time.Now().Add(-time.Second)},
	} {
		lease:=futureLease(); change(&lease)
		server:=New(&fakeRepository{inspect:func(context.Context,string,string,string)(store.TunnelLeaseIdentity,error){return lease,nil}})
		server.tunnels[testRuntimeID]=&hostTunnel{installationID:testInstanceID}
		if _,err:=server.AdmitForwarded(context.Background(),testRuntimeID,testLeaseID,strings.Repeat("a",43)); err==nil {t.Fatal("changed lease facts admitted")}
	}
}

func TestCarrierRevalidationClosesChangedAuthorization(t *testing.T) {
	for _, change := range []string{"revoke", "grant", "ownership"} {
		t.Run(change, func(t *testing.T) {
			lease := futureLease()
			runtime := newTestRuntime(t, echoConnect(t, nil))
			stream, err := runtime.connect(context.Background(), lease, strings.Repeat("a",43), "native")
			if err != nil { t.Fatal(err) }
			defer stream.Close()
			inspect := func(context.Context,string,string)(store.TunnelLeaseIdentity,error) { return lease,nil }
			if err := runtime.Revalidate(context.Background(),inspect); err != nil || stream.ctx.Err() != nil { t.Fatalf("valid carrier retired: %v",err) }
			switch change {
			case "revoke": inspect = func(context.Context,string,string)(store.TunnelLeaseIdentity,error) { return store.TunnelLeaseIdentity{},store.ErrTunnelLeaseInvalid }
			case "grant": lease.GrantVersion++
			case "ownership": lease.IsInstallationOwner = false
			}
			_ = runtime.Revalidate(context.Background(),inspect)
			if stream.ctx.Err() == nil { t.Fatal("changed lease remained live") }
		})
	}
}
