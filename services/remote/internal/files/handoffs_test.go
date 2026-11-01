package files

import (
	"bytes"
	"context"
	"errors"
	"io"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

type handoffWatchRepository struct {
	HandoffRepository
	permit  ReadPermit
	block   bool
	entered chan struct{}
	once    sync.Once
}

func (repo *handoffWatchRepository) RedeemFileRead(_ context.Context, _ string, _ string, _ string, verify func(ReadPermit) error) (ReadPermit, error) {
	if err := verify(repo.permit); err != nil {
		return ReadPermit{}, err
	}
	return repo.permit, nil
}
func (repo *handoffWatchRepository) CheckFileRead(ctx context.Context, _ HostBinding, _ string, _ string) (Handoff, error) {
	if repo.block {
		repo.once.Do(func() { close(repo.entered) })
		<-ctx.Done()
		return Handoff{}, ctx.Err()
	}
	return repo.permit.Handoff, nil
}

type handoffWatchStorage struct {
	BlobStore
	open  func(context.Context) (io.ReadCloser, error)
	calls atomic.Int32
}

func (storage *handoffWatchStorage) OpenEncrypted(ctx context.Context, _ ObjectKey, _ int64) (io.ReadCloser, error) {
	storage.calls.Add(1)
	return storage.open(ctx)
}

type handoffWatchBody struct {
	entered, ready chan struct{}
	reads          sync.Once
	closes         atomic.Int32
	releaseOnClose bool
}

func (body *handoffWatchBody) Read(target []byte) (int, error) {
	body.reads.Do(func() { close(body.entered) })
	<-body.ready
	return copy(target, []byte("late bytes")), nil
}
func (body *handoffWatchBody) Close() error {
	if body.closes.Add(1) == 1 && body.releaseOnClose {
		close(body.ready)
	}
	return nil
}
func handoffWatchFixture(t *testing.T, lease time.Duration) (*Handoffs, *handoffWatchRepository, *handoffWatchStorage, chan struct{}) {
	t.Helper()
	record := serviceFile([]byte("late bytes"))
	record.State = "clean"
	record.CommittedAttempt = record.QuarantineAttempt
	host := HostBinding{InstallationID: "11111111-1111-4111-8111-111111111111", RuntimeID: record.RuntimeID, RuntimeEpoch: "22222222-2222-4222-8222-222222222222", ConnectionID: "33333333-3333-4333-8333-333333333333", KeyVersion: 1, CredentialExpiresAt: time.Now().Add(time.Minute)}
	repo := &handoffWatchRepository{permit: ReadPermit{Handoff: Handoff{ID: "44444444-4444-4444-8444-444444444444", File: record, Host: host, State: "downloading", LeaseUntil: time.Now().Add(lease), ExpiresAt: time.Now().Add(time.Minute)}}, entered: make(chan struct{})}
	storage := &handoffWatchStorage{open: func(context.Context) (io.ReadCloser, error) {
		return io.NopCloser(bytes.NewReader([]byte("late bytes"))), nil
	}}
	controller, err := NewHandoffs(repo, storage, digest([]byte("policy")))
	if err != nil {
		t.Fatal(err)
	}
	return controller, repo, storage, make(chan struct{})
}
func openWatchedHandoff(t *testing.T, controller *Handoffs, repo *handoffWatchRepository, host chan struct{}) (*FileRead, error) {
	t.Helper()
	return controller.Open(t.Context(), repo.permit.Handoff.ID, "fixture", func(ReadPermit) error { return nil }, func(HostBinding) (<-chan struct{}, error) { return host, nil })
}
func TestHandoffCancellationDoesNotWaitForBlockedMetadata(t *testing.T) {
	for _, kind := range []string{"lease", "host"} {
		for _, phase := range []string{"headers", "body"} {
			t.Run(kind+"/"+phase, func(t *testing.T) {
				lease := 5 * time.Second
				if kind == "lease" {
					lease = 900 * time.Millisecond
				}
				controller, repo, storage, host := handoffWatchFixture(t, lease)
				repo.block = true
				body := &handoffWatchBody{entered: make(chan struct{}), ready: make(chan struct{}), releaseOnClose: true}
				if phase == "headers" {
					storage.open = func(ctx context.Context) (io.ReadCloser, error) { <-ctx.Done(); return nil, ctx.Err() }
				} else {
					storage.open = func(context.Context) (io.ReadCloser, error) { return body, nil }
				}
				outcome := make(chan error, 1)
				go func() {
					stream, err := openWatchedHandoff(t, controller, repo, host)
					if err == nil {
						_, err = stream.Read(make([]byte, 32))
						_ = stream.Close()
					}
					outcome <- err
				}()
				select {
				case <-repo.entered:
				case <-time.After(time.Second):
					t.Fatal("metadata check did not begin")
				}
				if kind == "host" {
					close(host)
				}
				select {
				case err := <-outcome:
					if err == nil {
						t.Fatal("invalid lease returned bytes")
					}
				case <-time.After(600 * time.Millisecond):
					t.Fatal("metadata blocked independent cancellation")
				}
				if phase == "body" && body.closes.Load() != 1 {
					t.Fatal("cancel and explicit close raced underlying Close", body.closes.Load())
				}
			})
		}
	}
}
func TestHandoffDiscardsBytesReturnedAfterCancellation(t *testing.T) {
	controller, repo, storage, host := handoffWatchFixture(t, 5*time.Second)
	body := &handoffWatchBody{entered: make(chan struct{}), ready: make(chan struct{})}
	storage.open = func(context.Context) (io.ReadCloser, error) { return body, nil }
	stream, err := openWatchedHandoff(t, controller, repo, host)
	if err != nil {
		t.Fatal(err)
	}
	defer stream.Close()
	done := make(chan struct {
		n   int
		err error
	}, 1)
	go func() {
		n, err := stream.Read(make([]byte, 32))
		done <- struct {
			n   int
			err error
		}{n, err}
	}()
	<-body.entered
	close(host)
	select {
	case <-stream.Context().Done():
	case <-time.After(time.Second):
		t.Fatal("host retirement did not cancel")
	}
	close(body.ready)
	result := <-done
	if result.n != 0 || !errors.Is(result.err, context.Canceled) && !errors.Is(result.err, ErrAccess) {
		t.Fatal("late bytes escaped cancelled stream", result)
	}
	var closes sync.WaitGroup
	for range 4 {
		closes.Go(func() { _ = stream.Close() })
	}
	closes.Wait()
	if body.closes.Load() != 1 {
		t.Fatal("underlying body closed more than once", body.closes.Load())
	}
}
func TestHandoffRejectsExpiredAdmissionBeforeObjectIO(t *testing.T) {
	controller, repo, storage, host := handoffWatchFixture(t, 50*time.Millisecond)
	if stream, err := openWatchedHandoff(t, controller, repo, host); stream != nil || !errors.Is(err, ErrAccess) {
		t.Fatal("insufficient lease admitted", err)
	}
	if storage.calls.Load() != 0 {
		t.Fatal("expired permit opened storage")
	}
}
func TestHandoffClosesBodyReturnedWithStorageErrorAndRejectsNilBody(t *testing.T) {
	for _, mode := range []string{"error", "nil"} {
		t.Run(mode, func(t *testing.T) {
			controller, repo, storage, host := handoffWatchFixture(t, 5*time.Second)
			body := &handoffWatchBody{entered: make(chan struct{}), ready: make(chan struct{})}
			storage.open = func(context.Context) (io.ReadCloser, error) {
				if mode == "nil" {
					return nil, nil
				}
				return body, ErrSource
			}
			stream, err := openWatchedHandoff(t, controller, repo, host)
			if stream != nil || err == nil {
				t.Fatal("unusable body admitted", err)
			}
			if mode == "error" && body.closes.Load() != 1 {
				t.Fatal("error response body leaked")
			}
		})
	}
}

func TestHandoffRejectsRetiredHostBeforeObjectIO(t *testing.T) {
	controller, repo, storage, host := handoffWatchFixture(t, 5*time.Second)
	close(host)
	if stream, err := openWatchedHandoff(t, controller, repo, host); stream != nil || !errors.Is(err, ErrAccess) {
		t.Fatal("retired Host admitted", err)
	}
	if storage.calls.Load() != 0 {
		t.Fatal("retired Host opened storage")
	}
}
