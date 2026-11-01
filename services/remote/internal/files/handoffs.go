package files

import (
	"context"
	"io"
	"sync"
	"time"
)

type Handoffs struct {
	repository HandoffRepository
	storage    BlobStore
	policy     string
}

func (controller *Handoffs) PolicySHA256() string { return controller.policy }
func (controller *Handoffs) MessageReference(ctx context.Context, id string) (*Delivery, error) {
	return controller.repository.FileMessageReference(ctx, id)
}

func NewHandoffs(repository HandoffRepository, storage BlobStore, policy string) (*Handoffs, error) {
	if repository == nil || storage == nil {
		return nil, ErrInvalid
	}
	if _, valid := checksum(policy); !valid {
		return nil, ErrInvalid
	}
	return &Handoffs{repository: repository, storage: storage, policy: policy}, nil
}
func (controller *Handoffs) Offer(ctx context.Context, host HostBinding, file string) (*FileOffer, error) {
	return controller.repository.IssueHandoff(ctx, host, file, controller.policy)
}
func (controller *Handoffs) Pending(ctx context.Context, host HostBinding, limit int) ([]string, error) {
	return controller.repository.PendingHandoffFiles(ctx, host, controller.policy, limit)
}
func (controller *Handoffs) Renew(ctx context.Context, host HostBinding, id string) (Handoff, error) {
	return controller.repository.RenewHandoff(ctx, host, id, controller.policy)
}
func (controller *Handoffs) Staged(ctx context.Context, host HostBinding, id, sha string, size int64, name string) error {
	return controller.repository.StageHandoff(ctx, host, id, sha, size, name, controller.policy)
}
func (controller *Handoffs) End(ctx context.Context, host HostBinding, id string) error {
	return controller.repository.EndHandoff(ctx, host, id)
}

type FileRead struct {
	Handoff  Handoff
	body     io.ReadCloser
	ctx      context.Context
	cancel   context.CancelFunc
	done     chan struct{}
	hostDone <-chan struct{}
	once     sync.Once
	bodyOnce sync.Once
}

func (read *FileRead) Context() context.Context { return read.ctx }
func (read *FileRead) closeBody() {
	read.bodyOnce.Do(func() {
		if read.body != nil {
			_ = read.body.Close()
		}
	})
}
func (read *FileRead) currentErr() error {
	if err := read.ctx.Err(); err != nil {
		return err
	}
	select {
	case <-read.hostDone:
		read.cancel()
		return ErrAccess
	default:
		return nil
	}
}
func (read *FileRead) Read(target []byte) (int, error) {
	if err := read.currentErr(); err != nil {
		return 0, err
	}
	n, err := read.body.Read(target[:min(len(target), ChunkBytes)])
	if current := read.currentErr(); current != nil {
		return 0, current
	}
	return n, err
}
func (read *FileRead) Close() error {
	read.once.Do(func() { read.cancel(); read.closeBody(); <-read.done })
	return nil
}

// Open consumes a sender-constrained ticket only after verify succeeds. The
// host lifetime and periodically renewed database authority both own the body.
func (controller *Handoffs) Open(parent context.Context, id, token string, verify func(ReadPermit) error, live func(HostBinding) (<-chan struct{}, error)) (*FileRead, error) {
	var hostDone <-chan struct{}
	permit, err := controller.repository.RedeemFileRead(parent, id, token, controller.policy, func(permit ReadPermit) error {
		if verify == nil || live == nil {
			return ErrAccess
		}
		if err := verify(permit); err != nil {
			return err
		}
		var err error
		hostDone, err = live(permit.Handoff.Host)
		return err
	})
	if err != nil {
		return nil, err
	}
	select {
	case <-hostDone:
		return nil, ErrAccess
	default:
	}
	if !permit.Handoff.LeaseUntil.After(time.Now().Add(100*time.Millisecond)) || !permit.Handoff.ExpiresAt.After(time.Now()) {
		return nil, ErrAccess
	}
	ctx, closeDeadline := context.WithDeadline(parent, permit.Handoff.ExpiresAt)
	ctx, cancel := context.WithCancel(ctx)
	key, err := permit.Handoff.File.CommittedKey()
	if err != nil {
		cancel()
		closeDeadline()
		return nil, ErrUnavailable
	}
	read := &FileRead{Handoff: permit.Handoff, ctx: ctx, cancel: func() { cancel(); closeDeadline() }, done: make(chan struct{}), hostDone: hostDone}
	go func() {
		defer close(read.done)
		// Neither the old lease nor Host retirement may wait behind a database call.
		lease := time.AfterFunc(max(0, time.Until(permit.Handoff.LeaseUntil)-100*time.Millisecond), cancel)
		hostStopped := make(chan struct{})
		go func() {
			defer close(hostStopped)
			select {
			case <-ctx.Done():
			case <-hostDone:
				cancel()
			}
		}()
		defer func() { lease.Stop(); cancel(); <-hostStopped }()
		ticker := time.NewTicker(500 * time.Millisecond)
		defer ticker.Stop()
		for {
			select {
			case <-ctx.Done():
				return
			case <-ticker.C:
				if _, err := live(permit.Handoff.Host); err != nil {
					return
				}
				attempt, closeAttempt := context.WithTimeout(ctx, time.Second)
				handoff, err := controller.repository.CheckFileRead(attempt, permit.Handoff.Host, id, controller.policy)
				closeAttempt()
				if err != nil {
					return
				}
				if !handoff.LeaseUntil.After(time.Now().Add(100*time.Millisecond)) || !lease.Stop() || ctx.Err() != nil {
					return
				}
				lease.Reset(time.Until(handoff.LeaseUntil) - 100*time.Millisecond)
			}
		}
	}()
	read.body, err = controller.storage.OpenEncrypted(ctx, key, permit.Handoff.File.SizeBytes)
	if err == nil && read.body == nil {
		err = ErrUnavailable
	}
	if err == nil {
		err = read.currentErr()
	}
	if err != nil {
		read.cancel()
		read.closeBody()
		<-read.done
		return nil, err
	}
	context.AfterFunc(ctx, read.closeBody)
	return read, nil
}
