package observability

import (
	"dsh-mobile-ustc/services/remote/internal/hostmetrics"
)

func (m *Metrics) HostFlow(delta hostmetrics.FlowDelta) {
	if m == nil {
		return
	}
	for _, cell := range delta.Cells {
		if hostmetrics.FlowPair(cell.Stage, cell.Kind) {
			m.flowEvents.WithLabelValues(cell.Stage, cell.Kind).Add(float64(cell.Count))
		}
	}
	m.flowDropped.Add(float64(delta.Dropped))
}
func (m *Metrics) HostFlowReport(result string) {
	if m != nil {
		m.flowReports.WithLabelValues(fixed(result, "accepted", "duplicate", "rejected", "throttled", "denied")).Inc()
	}
}
