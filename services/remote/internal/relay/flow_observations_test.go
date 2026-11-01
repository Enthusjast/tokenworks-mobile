package relay

import (
	"encoding/json"
	"net/http/httptest"
	"os"
	"slices"
	"strings"
	"testing"
	"time"

	"dsh-mobile-ustc/services/remote/internal/hostmetrics"
	"dsh-mobile-ustc/services/remote/internal/observability"
	"dsh-mobile-ustc/services/remote/internal/store"
)

func relayFlowGolden(t *testing.T, name string) hostmetrics.FlowReport {
	t.Helper()
	raw, err := os.ReadFile("../../../../contracts/ws/v1/golden/" + name + ".json")
	if err != nil {
		t.Fatal(err)
	}
	var frame struct {
		Payload hostmetrics.FlowReport `json:"payload"`
	}
	if json.Unmarshal(raw, &frame) != nil {
		t.Fatal("invalid flow golden")
	}
	return frame.Payload
}
func flowCount(t *testing.T, metrics *observability.Metrics, stage, kind string) float64 {
	t.Helper()
	for _, metric := range metricsSnapshot(t, metrics)["dsh_remote_host_flow_events_total"].GetMetric() {
		labels := map[string]string{}
		for _, label := range metric.Label {
			labels[label.GetName()] = label.GetValue()
		}
		if labels["stage"] == stage && labels["kind"] == kind {
			return metric.GetCounter().GetValue()
		}
	}
	return 0
}

func flowReports(t *testing.T, metrics *observability.Metrics, result string) float64 {
	t.Helper()
	for _, metric := range metricsSnapshot(t, metrics)["dsh_remote_host_flow_reports_total"].GetMetric() {
		for _, label := range metric.Label {
			if label.GetName() == "result" && label.GetValue() == result {
				return metric.GetCounter().GetValue()
			}
		}
	}
	return 0
}

func TestHostFlowReportsAreOptionalPrivateAndDeltasOnly(t *testing.T) {
	key := relayTestKey(t)
	jwk, jkt := relayTestPublicJWK(t, key)
	const installation = "11111111-1111-4111-8111-111111111111"
	const runtime = "33333333-3333-4333-8333-333333333333"
	const epoch = "44444444-4444-4444-8444-444444444444"
	fake := &relayTestStore{hostCredential: store.HostCredentialIdentity{InstallationID: installation, KeyVersion: 1, PublicJWK: jwk, KeyThumbprint: jkt, ExpiresAt: time.Now().Add(time.Minute)}, detached: make(chan store.RuntimeConnection, 4),
		ticket: store.TicketRedemption{UserID: installation, DeviceID: "22222222-2222-4222-8222-222222222222", RuntimeID: runtime, DeviceKeyVersion: 1, GrantVersion: 1, ProofPublicJWK: jwk, ProofKeyThumbprint: jkt, ExpiresAt: time.Now().Add(time.Minute), Scope: store.GrantScope{Actions: []string{"session.read"}, Workspaces: []string{}, Sessions: []string{}}}}
	if slices.Contains(New(fake).capabilities(), hostmetrics.FlowCapability) {
		t.Fatal("metrics-disabled flow capability")
	}
	relay := New(fake)
	metrics := observability.New()
	relay.ConfigureMetrics(metrics)
	server := httptest.NewServer(relay)
	defer server.Close()
	host := dialRelayHost(t, server.URL, key, installation, runtime, epoch, "connection.heartbeat.v1", hostmetrics.FlowCapability)
	defer host.CloseNow()
	live := relay.hostForRuntime(runtime)
	if !live.supports(hostmetrics.FlowCapability) {
		t.Fatal("flow not negotiated")
	}
	first := relayFlowGolden(t, "host-flow-observations")
	first.ConnectionID = live.connectionID
	frame := func(report hostmetrics.FlowReport) map[string]any {
		return map[string]any{"v": 1, "type": "host.flow.observations", "runtime_id": runtime, "runtime_epoch": epoch, "payload": report}
	}
	writeWireFrame(t, host, frame(first))
	metricUntil(t, func() bool { return flowCount(t, metrics, "agent", "chunk") == 1 })
	writeWireFrame(t, host, frame(first))
	metricUntil(t, func() bool {
		return flowReports(t, metrics, "throttled") == 1
	})
	time.Sleep(1050 * time.Millisecond)
	writeWireFrame(t, host, frame(first))
	metricUntil(t, func() bool {
		return flowReports(t, metrics, "duplicate") == 1
	})
	time.Sleep(1050 * time.Millisecond)
	next := relayFlowGolden(t, "host-flow-observations-next")
	next.ConnectionID = live.connectionID
	writeWireFrame(t, host, frame(next))
	metricUntil(t, func() bool { return flowCount(t, metrics, "agent", "chunk") == 2 })
	if flowCount(t, metrics, "agent", "start") != 1 || flowCount(t, metrics, "projection", "checkpoint") != 1 {
		t.Fatal("flow cumulative values counted twice")
	}
	time.Sleep(1050 * time.Millisecond)
	bad := frame(next)
	payload, _ := json.Marshal(next)
	var object map[string]any
	_ = json.Unmarshal(payload, &object)
	object["body"] = "PRIVATE_FLOW_BODY"
	bad["payload"] = object
	writeWireFrame(t, host, bad)
	metricUntil(t, func() bool {
		return flowReports(t, metrics, "rejected") == 1
	})
	writeEnvelope(t, host, "ping", map[string]any{})
	if readEnvelope(t, host).Type != "pong" {
		t.Fatal("telemetry failure interrupted traffic")
	}
	phone := dialRelayDevice(t, server.URL, key, fake.ticket, runtime, "connection.heartbeat.v1", hostmetrics.FlowCapability)
	defer phone.CloseNow()
	peers := relay.devicesForRuntime(runtime, fake.ticket.DeviceID)
	if len(peers) != 1 || peers[0].supports(hostmetrics.FlowCapability) {
		t.Fatal("phone acquired flow capability")
	}
	writeWireFrame(t, phone, frame(next))
	if denied := readEnvelope(t, phone); denied.Type != "error" || denied.Payload["code"] != "access.denied" {
		t.Fatal("phone forgery", denied)
	}
	w := httptest.NewRecorder()
	metrics.Handler().ServeHTTP(w, httptest.NewRequest("GET", "/metrics", nil))
	for _, secret := range []string{installation, runtime, epoch, live.connectionID, "PRIVATE_FLOW_BODY"} {
		if strings.Contains(w.Body.String(), secret) {
			t.Fatal("private metric label or text")
		}
	}
}
func TestHostFlowReportsRequireCurrentConnectionAuthority(t *testing.T) {
	repository := &nativeFencedRepository{Repository: &relayTestStore{}}
	repository.deny.Store(true)
	relay := New(repository)
	metrics := observability.New()
	relay.ConfigureMetrics(metrics)
	live := &liveConnection{peer: peer{role: "installation", installation: store.HostCredentialIdentity{InstallationID: "11111111-1111-4111-8111-111111111111"}}, runtimeID: "33333333-3333-4333-8333-333333333333", runtimeEpoch: "44444444-4444-4444-8444-444444444444", connectionID: "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", capabilities: capabilitySet([]string{hostmetrics.FlowCapability})}
	relay.hosts[live.runtimeID] = live
	report := relayFlowGolden(t, "host-flow-observations")
	raw, _ := json.Marshal(report)
	relay.routeHostFlow(t.Context(), live, wireEnvelope{Type: "host.flow.observations", RuntimeID: live.runtimeID, RuntimeEpoch: live.runtimeEpoch, Payload: raw})
	if live.flowReport != nil || flowCount(t, metrics, "agent", "chunk") != 0 {
		t.Fatal("stale authority reported flow")
	}
}
