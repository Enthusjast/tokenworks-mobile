package httpapi

import (
	"bytes"
	"context"
	"io"
	"net/http"
	"sync"
	"testing"
	"time"

	"tokenworks-mobile/services/remote/internal/files"
)

type hostWriteRepository struct {
	files.HandoffRepository
	permit files.ReadPermit
}

func (repo *hostWriteRepository) RedeemFileRead(_ context.Context, _ string, _ string, _ string, verify func(files.ReadPermit) error) (files.ReadPermit, error) {
	if err := verify(repo.permit); err != nil {
		return files.ReadPermit{}, err
	}
	return repo.permit, nil
}
func (repo *hostWriteRepository) CheckFileRead(context.Context, files.HostBinding, string, string) (files.Handoff, error) {
	return repo.permit.Handoff, nil
}

type hostWriteStorage struct{ files.BlobStore }

func (hostWriteStorage) OpenEncrypted(context.Context, files.ObjectKey, int64) (io.ReadCloser, error) {
	return io.NopCloser(bytes.NewReader([]byte("body"))), nil
}

type blockedHostWriter struct {
	header                     http.Header
	entered, expired, released chan struct{}
	start, expire, release     sync.Once
}

func (writer *blockedHostWriter) Header() http.Header { return writer.header }
func (*blockedHostWriter) WriteHeader(int)            {}
func (writer *blockedHostWriter) Write([]byte) (int, error) {
	writer.start.Do(func() { close(writer.entered) })
	select {
	case <-writer.expired:
	case <-writer.released:
	}
	return 0, context.DeadlineExceeded
}
func (writer *blockedHostWriter) SetWriteDeadline(until time.Time) error {
	if !until.After(time.Now()) {
		writer.expire.Do(func() { close(writer.expired) })
	}
	return nil
}
func TestHostFileWriteDeadlineCancelsBlockedResponseOnHostRetirement(t *testing.T) {
	now := time.Now()
	record := files.Record{ID: "11111111-1111-4111-8111-111111111111", OrganizationID: "22222222-2222-4222-8222-222222222222", SessionID: "33333333-3333-4333-8333-333333333333", CommittedAttempt: "44444444-4444-4444-8444-444444444444", SizeBytes: 4}
	repo := &hostWriteRepository{permit: files.ReadPermit{Handoff: files.Handoff{File: record, LeaseUntil: now.Add(5 * time.Second), ExpiresAt: now.Add(time.Minute)}}}
	controller, err := files.NewHandoffs(repo, hostWriteStorage{}, "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
	if err != nil {
		t.Fatal(err)
	}
	host := make(chan struct{})
	stream, err := controller.Open(t.Context(), "fixture", "ticket", func(files.ReadPermit) error { return nil }, func(files.HostBinding) (<-chan struct{}, error) { return host, nil })
	if err != nil {
		t.Fatal(err)
	}
	writer := &blockedHostWriter{header: make(http.Header), entered: make(chan struct{}), expired: make(chan struct{}), released: make(chan struct{})}
	done := make(chan any, 1)
	go func() {
		defer func() { done <- recover() }()
		defer stream.Close()
		writeHostFileContent(writer, stream)
	}()
	defer func() { writer.release.Do(func() { close(writer.released) }); _ = stream.Close() }()
	select {
	case <-writer.entered:
	case <-time.After(time.Second):
		t.Fatal("response did not start")
	}
	close(host)
	select {
	case result := <-done:
		if result != nil && result != http.ErrAbortHandler {
			t.Fatal("unexpected panic", result)
		}
	case <-time.After(time.Second):
		t.Fatal("slow response write survived cancelled file authority")
	}
	select {
	case <-writer.expired:
	default:
		t.Fatal("response deadline was not interrupted")
	}
}
