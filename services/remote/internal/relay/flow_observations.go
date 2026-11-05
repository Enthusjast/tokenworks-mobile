package relay

import (
	"context"
	"time"

	"tokenworks-mobile/services/remote/internal/hostmetrics"
)

func (server *Server) routeHostFlow(ctx context.Context, live *liveConnection, envelope wireEnvelope) {
	if live.peer.role != "installation" {
		server.metrics.HostFlowReport("denied")
		server.sendProtocolError(ctx, live, "access.denied", &envelope)
		return
	}
	if server.metrics == nil || !live.supports(hostmetrics.FlowCapability) {
		server.sendProtocolError(ctx, live, "protocol.unsupported", &envelope)
		return
	}
	now := time.Now()
	if !live.lastFlowReport.IsZero() && now.Sub(live.lastFlowReport) < time.Second {
		server.metrics.HostFlowReport("throttled")
		return
	}
	live.lastFlowReport = now
	if envelope.RuntimeID != live.runtimeID || envelope.RuntimeEpoch != live.runtimeEpoch || envelope.RequestID != "" || envelope.SessionID != "" || envelope.DeviceID != "" || envelope.UserID != "" || envelope.PayloadSHA256 != "" || envelope.SubscriptionID != "" || envelope.SyncID != "" {
		server.metrics.HostFlowReport("rejected")
		return
	}
	var report hostmetrics.FlowReport
	if len(envelope.Payload) > 4096 || decodePayload(envelope.Payload, &report) != nil || report.ConnectionID != live.connectionID {
		server.metrics.HostFlowReport("rejected")
		return
	}
	delta, err := hostmetrics.FlowDifference(live.flowReport, report)
	if err != nil {
		server.metrics.HostFlowReport("rejected")
		return
	}
	server.mu.Lock()
	current := !server.draining && !live.closing && server.hosts[live.runtimeID] == live
	server.mu.Unlock()
	if !current {
		server.metrics.HostFlowReport("denied")
		return
	}
	check, cancel := context.WithTimeout(ctx, 2*time.Second)
	err = server.database.TouchRuntimeConnection(check, live.peer.installation.InstallationID, live.runtimeID, live.runtimeEpoch, live.connectionID)
	cancel()
	if err != nil {
		server.metrics.HostFlowReport("denied")
		return
	}
	if delta.Duplicate {
		server.metrics.HostFlowReport("duplicate")
		return
	}
	live.flowReport = &report
	server.metrics.HostFlow(delta)
	server.metrics.HostFlowReport("accepted")
}
