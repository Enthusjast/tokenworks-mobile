package observability

import (
	"errors"
	"sync"
	"time"

	"github.com/prometheus/client_golang/prometheus"
)

// ScannerSnapshot must be produced from local memory; collection never probes
// a daemon, claims a file or performs database/object I/O.
type ScannerSnapshot struct {
	Ready, Probed bool
	Age           time.Duration
}
type scannerCollector struct {
	mu                  sync.RWMutex
	source              func() ScannerSnapshot
	enabled, ready, age *prometheus.Desc
}

func newScannerCollector() *scannerCollector {
	return &scannerCollector{
		enabled: prometheus.NewDesc("tokenworks_mobile_file_scanner_enabled", "One when the optional file service is configured in this process.", nil, nil),
		ready:   prometheus.NewDesc("tokenworks_mobile_file_scanner_ready", "One when the configured scanner has a current successful local self-test proof; not file or engine acceptance.", nil, nil),
		age:     prometheus.NewDesc("tokenworks_mobile_file_scanner_probe_age_seconds", "Conservative local age of the last completed self-test, including failed tests; absent before first completion.", nil, nil),
	}
}
func (c *scannerCollector) Describe(out chan<- *prometheus.Desc) {
	out <- c.enabled
	out <- c.ready
	out <- c.age
}
func (c *scannerCollector) Collect(out chan<- prometheus.Metric) {
	c.mu.RLock()
	source := c.source
	c.mu.RUnlock()
	if source == nil {
		out <- prometheus.MustNewConstMetric(c.enabled, prometheus.GaugeValue, 0)
		return
	}
	snapshot := source()
	ready := float64(0)
	if snapshot.Ready && snapshot.Probed && snapshot.Age >= 0 {
		ready = 1
	}
	out <- prometheus.MustNewConstMetric(c.enabled, prometheus.GaugeValue, 1)
	out <- prometheus.MustNewConstMetric(c.ready, prometheus.GaugeValue, ready)
	if snapshot.Probed {
		out <- prometheus.MustNewConstMetric(c.age, prometheus.GaugeValue, max(0, snapshot.Age.Seconds()))
	}
}
func (m *Metrics) BindFileScanner(source func() ScannerSnapshot) error {
	if m == nil || source == nil {
		return errors.New("observability: invalid scanner source")
	}
	m.scanner.mu.Lock()
	defer m.scanner.mu.Unlock()
	if m.scanner.source != nil {
		return errors.New("observability: scanner source already configured")
	}
	m.scanner.source = source
	return nil
}
func (m *Metrics) FileScannerProbe(result string, elapsed time.Duration) {
	if m != nil {
		m.scannerProbes.WithLabelValues(fixed(result, "ok", "unavailable", "cancelled", "timeout")).Observe(elapsed.Seconds())
	}
}
