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

type settlementRepository struct {
	*exportDownloadRepository
	err      error
	entered  chan struct{}
	resume   chan struct{}
	once     sync.Once
	finishes atomic.Int32
	mu       sync.Mutex
	outcome  string
}

func (repo *settlementRepository) AuditExportDownload(ctx context.Context, actor Actor, permit DownloadPermit, id, outcome string) error {
	if outcome == "opened" {
		return repo.exportDownloadRepository.AuditExportDownload(ctx, actor, permit, id, outcome)
	}
	repo.finishes.Add(1)
	repo.mu.Lock()
	repo.outcome = outcome
	repo.mu.Unlock()
	if repo.entered != nil {
		repo.once.Do(func() { close(repo.entered) })
	}
	if repo.resume != nil {
		select {
		case <-repo.resume:
		case <-ctx.Done():
			return ErrUnavailable
		}
	}
	return repo.err
}

type settlementBody struct {
	io.Reader
	closes atomic.Int32
	closed chan struct{}
	once   sync.Once
}

func (body *settlementBody) Close() error {
	body.closes.Add(1)
	body.once.Do(func() { close(body.closed) })
	return nil
}

type settlementStorage struct {
	*exportDownloadStorage
	body  io.ReadCloser
	err   error
	opens atomic.Int32
}

func (storage *settlementStorage) OpenEncrypted(context.Context, ObjectKey, int64) (io.ReadCloser, error) {
	storage.opens.Add(1)
	return storage.body, storage.err
}
func TestExportSettlementRetainsAuditErrorForEveryCloseCaller(t *testing.T) {
	data := []byte("verified response")
	controller, base, storage, actor := exportDownloadFixture(t, data)
	repo := &settlementRepository{exportDownloadRepository: base, err: ErrUnavailable, entered: make(chan struct{}), resume: make(chan struct{})}
	controller.repository = repo
	var resumeOnce sync.Once
	resumeAudit := func() { resumeOnce.Do(func() { close(repo.resume) }) }
	defer resumeAudit()
	body := &settlementBody{Reader: bytes.NewReader(data), closed: make(chan struct{})}
	counted := &settlementStorage{exportDownloadStorage: storage, body: body}
	controller.storage = counted
	stream, err := controller.Open(t.Context(), actor, base.file.ID)
	if err != nil {
		t.Fatal(err)
	}
	actual, err := io.ReadAll(stream)
	if err != nil || !bytes.Equal(actual, data) {
		t.Fatal("verified response changed", err)
	}
	if err = stream.Complete(); err != nil {
		t.Fatal(err)
	}
	results := make(chan error, 8)
	go func() { results <- stream.Close() }()
	<-repo.entered
	for range 7 {
		go func() { results <- stream.Close() }()
	}
	controller.mu.Lock()
	held := controller.count
	controller.mu.Unlock()
	if held != 1 {
		t.Fatal("ending audit prematurely released capacity", held)
	}
	resumeAudit()
	for range 8 {
		if err = <-results; !errors.Is(err, ErrUnavailable) {
			t.Fatal("repeated close hid audit failure", err)
		}
	}
	if err = stream.Close(); !errors.Is(err, ErrUnavailable) {
		t.Fatal("later close changed settlement result", err)
	}
	if repo.finishes.Load() != 1 || counted.opens.Load() != 1 || body.closes.Load() != 1 {
		t.Fatal("close repeated side effects", repo.finishes.Load(), counted.opens.Load(), body.closes.Load())
	}
	repo.mu.Lock()
	outcome := repo.outcome
	repo.mu.Unlock()
	if outcome != "served" {
		t.Fatal("audit failure rewrote actual served fact", outcome)
	}
	controller.mu.Lock()
	remaining := controller.count
	controller.mu.Unlock()
	if remaining != 0 {
		t.Fatal("failed settlement retained admission capacity")
	}
	if err = stream.Complete(); !errors.Is(err, ErrIntegrity) {
		t.Fatal("closed response accepted completion", err)
	}
}
func TestExportSettlementClosesBodyOnceAfterParentCancellation(t *testing.T) {
	controller, repo, storage, actor := exportDownloadFixture(t, []byte("bytes"))
	body := &settlementBody{Reader: bytes.NewReader(storage.data), closed: make(chan struct{})}
	controller.storage = &settlementStorage{exportDownloadStorage: storage, body: body}
	parent, cancel := context.WithCancel(t.Context())
	defer cancel()
	stream, err := controller.Open(parent, actor, repo.file.ID)
	if err != nil {
		t.Fatal(err)
	}
	defer stream.Close()
	cancel()
	select {
	case <-body.closed:
	case <-time.After(time.Second):
		t.Fatal("cancellation did not close body")
	}
	if err = stream.Close(); err != nil {
		t.Fatal(err)
	}
	if err = stream.Close(); err != nil {
		t.Fatal(err)
	}
	if body.closes.Load() != 1 {
		t.Fatal("explicit close repeated cancellation cleanup", body.closes.Load())
	}
}
func TestExportSettlementSealsOutcomeBeforeCancellationCleanup(t *testing.T) {
	controller, repo, _, actor := exportDownloadFixture(t, []byte("complete bytes"))
	stream, err := controller.Open(t.Context(), actor, repo.file.ID)
	if err != nil {
		t.Fatal(err)
	}
	if _, err = io.ReadAll(stream); err != nil {
		t.Fatal(err)
	}
	entered, resume := make(chan struct{}), make(chan struct{})
	var once sync.Once
	release := func() { once.Do(func() { close(resume) }) }
	defer release()
	cancel := stream.cancel
	stream.cancel = func() { close(entered); <-resume; cancel() }
	done := make(chan error, 1)
	go func() { done <- stream.Close() }()
	defer func() { release(); _ = stream.Close() }()
	<-entered
	if stream.Context().Err() != nil {
		t.Fatal("fixture missed pre-cancellation closing window")
	}
	if err = stream.Complete(); !errors.Is(err, ErrIntegrity) {
		t.Fatal("completion crossed sealed close outcome", err)
	}
	release()
	if err = <-done; err != nil {
		t.Fatal(err)
	}
	repo.mu.Lock()
	defer repo.mu.Unlock()
	if len(repo.audits) != 2 || repo.audits[1] != "cancelled" {
		t.Fatal("late completion changed settlement", repo.audits)
	}
}
func TestExportSettlementRejectsNilStorageBodyWithoutLeakingAdmission(t *testing.T) {
	controller, repo, storage, actor := exportDownloadFixture(t, []byte("bytes"))
	controller.storage = &settlementStorage{exportDownloadStorage: storage}
	stream, err := controller.Open(t.Context(), actor, repo.file.ID)
	if stream != nil || !errors.Is(err, ErrUnavailable) {
		t.Fatal("nil body admitted", err)
	}
	controller.mu.Lock()
	count := controller.count
	controller.mu.Unlock()
	if count != 0 {
		t.Fatal("nil body retained admission capacity")
	}
	repo.mu.Lock()
	defer repo.mu.Unlock()
	if len(repo.audits) != 2 || repo.audits[1] != "failed" {
		t.Fatal("nil body produced misleading outcome", repo.audits)
	}
}
