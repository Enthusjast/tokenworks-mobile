package push

import (
	"context"
	"time"
)

type Actor struct {
	Issuer string
	Subject string
	DeviceID string
	ProofJKT string
}

type Installation struct {
	ID string
	KeyVersion int64
	ExpiresAt time.Time
}

type Provider interface { Send(context.Context, string, Reference) error }

type Repository interface {
	RegisterPush(context.Context, Actor, Registration, *TokenCipher) (string, error)
	RevokePush(context.Context, Actor, string) error
	EnqueuePush(context.Context, Installation, Emit, string) error
	DeliverPush(context.Context, string, *TokenCipher, Provider) (bool, error)
}

type Service struct {
	repository Repository
	deployment string
	cipher *TokenCipher
	provider Provider
}

func New(repository Repository, deployment string, cipher *TokenCipher, provider Provider) (*Service, error) {
	if repository == nil || !digest.MatchString(deployment) || cipher == nil || provider == nil { return nil, ErrInvalid }
	return &Service{repository: repository, deployment: deployment, cipher: cipher, provider: provider}, nil
}

func (service *Service) Register(ctx context.Context, actor Actor, registration Registration) (string, error) {
	if !registration.Valid(service.deployment) { return "", ErrInvalid }
	return service.repository.RegisterPush(ctx, actor, registration, service.cipher)
}

func (service *Service) Revoke(ctx context.Context, actor Actor, binding string) error {
	if !uuid.MatchString(binding) { return ErrInvalid }
	return service.repository.RevokePush(ctx, actor, binding)
}

func (service *Service) Emit(ctx context.Context, installation Installation, emit Emit) error {
	if !emit.Valid(time.Now()) { return ErrInvalid }
	return service.repository.EnqueuePush(ctx, installation, emit, service.deployment)
}

// Run polls durable pending work. Each delivery is reauthorized and fenced by
// database row locks through the bounded provider request. At-least-once delivery
// after a crash is deduplicated on the phone by binding_id + notification_id.
func (service *Service) Run(ctx context.Context) {
	for ctx.Err() == nil {
		worked, err := service.repository.DeliverPush(ctx, service.deployment, service.cipher, service.provider)
		if worked && err == nil { continue }
		timer := time.NewTimer(time.Second)
		select {
		case <-ctx.Done(): timer.Stop(); return
		case <-timer.C:
		}
	}
}
