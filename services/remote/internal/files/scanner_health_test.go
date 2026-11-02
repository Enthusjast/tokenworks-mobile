package files

import (
	"context"
	"errors"
	"io"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

type healthCopyStorage struct {
	*S3
	after func()
	opens atomic.Int32
}

func (storage *healthCopyStorage) OpenEncrypted(ctx context.Context, key ObjectKey, size int64) (io.ReadCloser, error) {
	storage.opens.Add(1)
	return storage.S3.OpenEncrypted(ctx, key, size)
}
func (storage *healthCopyStorage) PutVerified(ctx context.Context, key ObjectKey, reader *VerifiedReader) error {
	if err := storage.S3.PutVerified(ctx, key, reader); err != nil {
		return err
	}
	if storage.after != nil {
		storage.after()
	}
	return nil
}
func scannerHealthFixture(t *testing.T) (*Service, *serviceRepository, *serviceScanner, *healthCopyStorage) {
	t.Helper()
	base, server := s3Fixture(t, &s3Peer{})
	data := []byte("health guarded bytes")
	record := serviceFile(data)
	key, _ := record.SourceKey()
	capability, err := base.PresignUpload(t.Context(), key, record.SizeBytes, record.SHA256, time.Minute)
	if err != nil {
		t.Fatal(err)
	}
	putCapability(t, server.Client(), capability, data, 200)
	claim := &ScanClaim{File: record, AttemptID: "55555555-5555-4555-8555-555555555555", LeaseID: "77777777-7777-4777-8777-777777777777", PolicySHA256: digest([]byte("policy")), LeaseUntil: time.Now().Add(3 * time.Second)}
	repo := &serviceRepository{record: record, claim: claim}
	scanner := &serviceScanner{}
	storage := &healthCopyStorage{S3: base}
	service, err := NewService(repo, storage, scanner, ServiceConfig{Workers: 1, PolicySHA256: claim.PolicySHA256, TaskTimeout: 3 * time.Second})
	if err != nil {
		t.Fatal(err)
	}
	if err = service.ProbeScanner(t.Context()); err != nil {
		t.Fatal(err)
	}
	return service, repo, scanner, storage
}
func TestScannerHealthPublicationRejectsFailedGenerationEvenAfterRecovery(t *testing.T) {
	for _, mode := range []string{"healthy-refresh", "failed", "recovered"} {
		t.Run(mode, func(t *testing.T) {
			service, repo, scanner, storage := scannerHealthFixture(t)
			storage.after = func() {
				if mode != "healthy-refresh" {
					scanner.alwaysClean = true
					if err := service.ProbeScanner(t.Context()); !errors.Is(err, ErrScanUnavailable) {
						t.Error("bad probe accepted", err)
					}
				}
				if mode != "failed" {
					scanner.alwaysClean = false
					if err := service.ProbeScanner(t.Context()); err != nil {
						t.Error(err)
					}
				}
			}
			processed, err := service.ScanNext(t.Context())
			if !processed {
				t.Fatal("scan was not claimed")
			}
			repo.mu.Lock()
			completed, failed, retry := repo.completed, repo.failed, repo.retry
			repo.mu.Unlock()
			if mode == "healthy-refresh" {
				if err != nil || completed != 1 || failed != "" {
					t.Fatal("healthy refresh invalidated active scan", err, completed, failed)
				}
				return
			}
			if !errors.Is(err, ErrScanUnavailable) || completed != 0 || failed != "scan_unavailable" || !retry {
				t.Fatal("old health proof published candidate", err, completed, failed, retry)
			}
			if mode == "recovered" {
				if !service.Ready() {
					t.Fatal("scanner did not recover")
				}
				storage.after = nil
				repo.mu.Lock()
				repo.failed = ""
				repo.claim = &ScanClaim{File: repo.record, AttemptID: "88888888-8888-4888-8888-888888888888", LeaseID: "99999999-9999-4999-8999-999999999999", PolicySHA256: service.config.PolicySHA256, LeaseUntil: time.Now().Add(3 * time.Second)}
				repo.mu.Unlock()
				if processed, err = service.ScanNext(t.Context()); !processed || err != nil {
					t.Fatal("fresh task did not use recovered health proof", err)
				}
			}
		})
	}
}

type healthClaimRepository struct {
	*serviceRepository
	after func()
}

func (repo *healthClaimRepository) ClaimScan(ctx context.Context, policy string) (*ScanClaim, error) {
	claim, err := repo.serviceRepository.ClaimScan(ctx, policy)
	if err == nil && claim != nil {
		repo.after()
	}
	return claim, err
}
func TestScannerHealthChangeDuringClaimRejectsBeforeObjectRead(t *testing.T) {
	service, repo, scanner, storage := scannerHealthFixture(t)
	service.repository = &healthClaimRepository{serviceRepository: repo, after: func() {
		scanner.alwaysClean = true
		_ = service.ProbeScanner(t.Context())
		scanner.alwaysClean = false
		_ = service.ProbeScanner(t.Context())
	}}
	processed, err := service.ScanNext(t.Context())
	if !processed || !errors.Is(err, ErrScanUnavailable) || storage.opens.Load() != 0 {
		t.Fatal("stale claim began object I/O", processed, err, storage.opens.Load())
	}
}

type cancelVerdictScanner struct {
	Scanner
	cancel context.CancelFunc
}

func (scanner cancelVerdictScanner) Begin(ctx context.Context) (Scan, error) {
	scan, err := scanner.Scanner.Begin(ctx)
	if err != nil {
		return nil, err
	}
	return cancelVerdictScan{Scan: scan, cancel: scanner.cancel}, nil
}

type cancelVerdictScan struct {
	Scan
	cancel context.CancelFunc
}

func (scan cancelVerdictScan) Finish() error {
	err := scan.Scan.Finish()
	if errors.Is(err, ErrRejected) {
		scan.cancel()
	}
	return err
}
func TestScannerHealthCancelledVerdictCannotPublishReady(t *testing.T) {
	service, _, _, _ := scannerHealthFixture(t)
	ctx, cancel := context.WithCancel(t.Context())
	defer cancel()
	service.scanner = cancelVerdictScanner{Scanner: service.scanner, cancel: cancel}
	if err := service.ProbeScanner(ctx); !errors.Is(err, ErrScanUnavailable) || service.Ready() {
		t.Fatal("cancelled self-test became ready", err)
	}
}

type heldHealthScanner struct {
	Scanner
	entered, resume chan struct{}
	begins          atomic.Int32
	once            sync.Once
}

func (scanner *heldHealthScanner) Begin(ctx context.Context) (Scan, error) {
	if scanner.begins.Add(1) == 1 {
		scanner.once.Do(func() { close(scanner.entered) })
		select {
		case <-scanner.resume:
		case <-ctx.Done():
			return nil, ErrScanUnavailable
		}
	}
	return scanner.Scanner.Begin(ctx)
}
func TestScannerHealthProbeWaitHonorsCancellationWithoutStartingAnotherProbe(t *testing.T) {
	service, _, _, _ := scannerHealthFixture(t)
	held := &heldHealthScanner{Scanner: service.scanner, entered: make(chan struct{}), resume: make(chan struct{})}
	service.scanner = held
	done := make(chan error, 1)
	go func() { done <- service.ProbeScanner(t.Context()) }()
	<-held.entered
	wait, cancel := context.WithTimeout(t.Context(), 20*time.Millisecond)
	defer cancel()
	if err := service.ProbeScanner(wait); !errors.Is(err, ErrScanUnavailable) {
		t.Fatal("second probe bypassed serialization", err)
	}
	if held.begins.Load() != 1 || !service.Ready() {
		t.Fatal("unstarted cancelled probe changed current proof", held.begins.Load())
	}
	close(held.resume)
	if err := <-done; err != nil {
		t.Fatal(err)
	}
}

func TestScannerHealthFreshnessCannotRelyOnlyOnWallTime(t *testing.T) {
	service, _, _, _ := scannerHealthFixture(t)
	service.healthMu.Lock()
	service.probeCompleted = time.Now().Add(-3 * time.Minute)
	service.probedAt.Store(time.Now().UnixMilli())
	service.healthMu.Unlock()
	if service.Ready() {
		t.Fatal("old health proof survived a fresh wall timestamp")
	}
}
