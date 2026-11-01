package auth

import (
	"net/http"
	"strconv"
	"strings"
	"time"
)

func cacheSeconds(raw string) (uint64, bool) {
	if len(raw) == 0 || len(raw) > 20 {
		return 0, false
	}
	for _, character := range raw {
		if character < '0' || character > '9' {
			return 0, false
		}
	}
	value, err := strconv.ParseUint(raw, 10, 64)
	return value, err == nil
}

// A verifier cache is bounded to one minute and never extends an issuer's
// explicit lifetime. Invalid/ambiguous freshness metadata disables reuse.
func cacheDuration(control string) time.Duration {
	lifetime := uint64(60)
	seen := false
	for _, part := range strings.Split(control, ",") {
		name, raw, hasValue := strings.Cut(strings.TrimSpace(part), "=")
		name = strings.ToLower(strings.TrimSpace(name))
		if name == "no-cache" || name == "no-store" {
			return 0
		}
		if name != "max-age" {
			continue
		}
		if seen || !hasValue {
			return 0
		}
		seen = true
		raw = strings.TrimSpace(raw)
		if len(raw) >= 2 && raw[0] == '"' && raw[len(raw)-1] == '"' {
			raw = raw[1 : len(raw)-1]
		}
		seconds, ok := cacheSeconds(raw)
		if !ok {
			return 0
		}
		if seconds < lifetime {
			lifetime = seconds
		}
	}
	return time.Duration(lifetime) * time.Second
}

func jwksFreshness(headers http.Header, started, received, ready time.Time) time.Duration {
	lifetime := cacheDuration(strings.Join(headers.Values("Cache-Control"), ","))
	if lifetime <= 0 || received.Before(started) || ready.Before(received) || received.Round(0).Before(started.Round(0)) || ready.Round(0).Before(received.Round(0)) {
		return 0
	}

	responseDelay, resident := received.Sub(started), ready.Sub(received)
	if responseDelay >= lifetime || resident >= lifetime {
		return 0
	}
	var age time.Duration
	if values := headers.Values("Age"); len(values) > 0 {
		if len(values) != 1 {
			return 0
		}
		seconds, ok := cacheSeconds(values[0])
		if !ok || seconds >= uint64(lifetime/time.Second) {
			return 0
		}
		age = time.Duration(seconds) * time.Second
	}
	initial := age + responseDelay
	if values := headers.Values("Date"); len(values) > 0 {
		if len(values) != 1 {
			return 0
		}
		date, err := http.ParseTime(values[0])
		if err != nil || date.UTC().Format(http.TimeFormat) != values[0] {
			return 0
		}
		if apparent := received.Sub(date); apparent > initial {
			initial = apparent
		}
	}
	if initial >= lifetime {
		return 0
	}
	remaining := lifetime - initial - resident
	if remaining <= 0 {
		return 0
	}
	return remaining
}
