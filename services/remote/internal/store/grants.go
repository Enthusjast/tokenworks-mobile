package store

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"regexp"
	"slices"
	"sort"
	"strings"
	"time"
	"unicode"
)

var ErrAccessDenied = errors.New("store: grant access denied")
var ErrGrantInvalid = errors.New("store: invalid runtime grant")
var ErrGrantNotFound = errors.New("store: runtime grant not found")

var grantUUIDPattern = regexp.MustCompile(`^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$`)
var nativeActionPattern = regexp.MustCompile(`^[A-Za-z0-9$][A-Za-z0-9$_.:-]*/[A-Za-z0-9$_.:-]+$`)

// GrantResource names one native dsh resource. Session and workspace identity
// strings stay exactly as dsh produced them; the Relay never re-keys them.
type GrantResource struct {
	Kind string `json:"kind"`
	ID   string `json:"id,omitempty"`
}

// GrantBinding grants native Remote endpoints on one resource. The Host's own
// remoteAccess policy enforces it; the Relay only records and forwards it.
type GrantBinding struct {
	Resource GrantResource `json:"resource"`
	Actions  []string      `json:"actions"`
}

type RuntimeGrantInput struct {
	Role      string         `json:"role"`
	Bindings  []GrantBinding `json:"bindings"`
	ExpiresAt *time.Time     `json:"expires_at,omitempty"`
}

func validNativeResource(resource GrantResource) bool {
	switch resource.Kind {
	case "global":
		return resource.ID == ""
	case "session", "workspace":
		if len(resource.ID) > 2048 {
			return false
		}
		return strings.IndexFunc(resource.ID, func(r rune) bool { return r == 0 || unicode.IsControl(r) }) < 0
	default:
		return false
	}
}

func normalizeBindings(bindings []GrantBinding) ([]GrantBinding, error) {
	if len(bindings) > 256 {
		return nil, ErrGrantInvalid
	}
	seen := make(map[string]struct{}, len(bindings))
	result := make([]GrantBinding, 0, len(bindings))
	for _, binding := range bindings {
		if !validNativeResource(binding.Resource) || len(binding.Actions) == 0 || len(binding.Actions) > 256 {
			return nil, ErrGrantInvalid
		}
		actions := slices.Clone(binding.Actions)
		for _, action := range actions {
			if !nativeActionPattern.MatchString(action) || len(action) > 256 {
				return nil, ErrGrantInvalid
			}
		}
		sort.Strings(actions)
		for index := 1; index < len(actions); index++ {
			if actions[index] == actions[index-1] {
				return nil, ErrGrantInvalid
			}
		}
		key := binding.Resource.Kind + "\x00" + binding.Resource.ID
		if _, duplicate := seen[key]; duplicate {
			return nil, ErrGrantInvalid
		}
		seen[key] = struct{}{}
		result = append(result, GrantBinding{Resource: binding.Resource, Actions: actions})
	}
	return result, nil
}

func NormalizeRuntimeGrantInput(input RuntimeGrantInput, now time.Time) (RuntimeGrantInput, error) {
	if input.Role != "viewer" && input.Role != "operator" && input.Role != "owner" {
		return RuntimeGrantInput{}, ErrGrantInvalid
	}
	if input.ExpiresAt != nil {
		expires := input.ExpiresAt.UTC().Truncate(time.Microsecond)
		if !expires.After(now) {
			return RuntimeGrantInput{}, ErrGrantInvalid
		}
		input.ExpiresAt = &expires
	}
	bindings, err := normalizeBindings(input.Bindings)
	if err != nil {
		return RuntimeGrantInput{}, err
	}
	input.Bindings = bindings
	return input, nil
}

func bindingSubset(candidate, parent []GrantBinding) bool {
	for _, want := range candidate {
		allowed := false
		for _, have := range parent {
			if have.Resource == want.Resource && stringSliceSubset(want.Actions, have.Actions) {
				allowed = true
				break
			}
		}
		if !allowed {
			return false
		}
	}
	return true
}

func stringSliceSubset(candidate, parent []string) bool {
	allowed := make(map[string]struct{}, len(parent))
	for _, value := range parent {
		allowed[value] = struct{}{}
	}
	for _, value := range candidate {
		if _, ok := allowed[value]; !ok {
			return false
		}
	}
	return true
}

func validGrantActor(deviceID, proofJKT, runtimeID, targetUserID string) bool {
	return grantUUIDPattern.MatchString(deviceID) && keyThumbprintPattern.MatchString(proofJKT) &&
		grantUUIDPattern.MatchString(runtimeID) && grantUUIDPattern.MatchString(targetUserID)
}

func (postgres *Postgres) PutRuntimeGrant(ctx context.Context, identity UserIdentity, deviceID, proofJKT, runtimeID, targetUserID string, input RuntimeGrantInput) (int64, error) {
	if !validGrantActor(deviceID, proofJKT, runtimeID, targetUserID) {
		return 0, ErrGrantInvalid
	}
	ctx, cancel := context.WithTimeout(ctx, 15*time.Second)
	defer cancel()
	return retrySerializable(ctx, func() (int64, error) {
		return postgres.putRuntimeGrantOnce(ctx, identity, deviceID, proofJKT, runtimeID, targetUserID, input)
	})
}

func (postgres *Postgres) putRuntimeGrantOnce(ctx context.Context, identity UserIdentity, deviceID, proofJKT, runtimeID, targetUserID string, input RuntimeGrantInput) (int64, error) {
	input, err := NormalizeRuntimeGrantInput(input, time.Now())
	if err != nil {
		return 0, err
	}
	transaction, err := postgres.DB.BeginTx(ctx, &sql.TxOptions{Isolation: sql.LevelSerializable})
	if err != nil {
		return 0, transactionFailure(err)
	}
	defer transaction.Rollback()

	actorID, orgID, actorRole, actorBindings, isAdmin, authorityExpires, err := postgres.authorizeGrantManager(ctx, transaction, identity, deviceID, proofJKT, runtimeID)
	if err != nil {
		return 0, err
	}
	if input.Role == "owner" && !isAdmin {
		return 0, ErrAccessDenied
	}
	if actorRole != "owner" && !isAdmin {
		return 0, ErrAccessDenied
	}
	if !isAdmin && !bindingSubset(input.Bindings, actorBindings) {
		return 0, ErrAccessDenied
	}
	targetID, err := activeOrganizationUser(ctx, transaction, orgID, targetUserID)
	if err != nil {
		return 0, err
	}

	var previousRole string
	var previousBindingsJSON []byte
	var previousVersion int64
	var previousExpiry, previousRevoked sql.NullTime
	err = transaction.QueryRowContext(ctx, `
		select role, bindings, grant_version, expires_at, revoked_at
		from runtime_grants where runtime_id = $1 and user_id = $2 for update /* grant_target_fence */`, runtimeID, targetID).
		Scan(&previousRole, &previousBindingsJSON, &previousVersion, &previousExpiry, &previousRevoked)
	hasPrevious := err == nil
	if err != nil && !errors.Is(err, sql.ErrNoRows) {
		return 0, transactionFailure(err)
	}
	writeTime, err := verifyGrantWriteTime(ctx, transaction, isAdmin, authorityExpires, input.ExpiresAt)
	if err != nil {
		return 0, err
	}
	if hasPrevious && previousRole == "owner" && !previousRevoked.Valid &&
		(!previousExpiry.Valid || previousExpiry.Time.After(writeTime)) &&
		(input.Role != "owner" || input.ExpiresAt != nil) {
		remaining, err := countOtherActiveOwners(ctx, transaction, runtimeID, orgID, targetID)
		if err != nil {
			return 0, transactionFailure(err)
		}
		if remaining == 0 && !isAdmin {
			return 0, ErrAccessDenied
		}
	}

	bindingsJSON, err := json.Marshal(input.Bindings)
	if err != nil {
		return 0, ErrGrantInvalid
	}
	if hasPrevious && !previousRevoked.Valid && previousRole == input.Role &&
		grantExpiryEqual(previousExpiry, input.ExpiresAt) && grantBindingsEqual(previousBindingsJSON, input.Bindings) {
		if err := commitRuntimeGrant(ctx, transaction, isAdmin, authorityExpires, input.ExpiresAt); err != nil {
			return 0, err
		}
		return previousVersion, nil
	}
	version := int64(1)
	if hasPrevious {
		version = previousVersion + 1
	}
	if _, err := transaction.ExecContext(ctx, `
		insert into runtime_grants (runtime_id, user_id, role, bindings, grant_version, granted_by, expires_at, revoked_at)
		values ($1, $2, $3, $4::jsonb, $5, $6, $7, null)
		on conflict (runtime_id, user_id) do update set
			role = excluded.role, bindings = excluded.bindings, grant_version = excluded.grant_version,
			granted_by = excluded.granted_by, expires_at = excluded.expires_at, revoked_at = null`,
		runtimeID, targetID, input.Role, string(bindingsJSON), version, actorID, input.ExpiresAt); err != nil {
		return 0, transactionFailure(err)
	}
	if err := writeGrantChange(ctx, transaction, actorID, deviceID, orgID, runtimeID, targetID, input.Role,
		version, input.ExpiresAt, "runtime_grant.updated", "runtime_grant.update", bindingsJSON); err != nil {
		return 0, transactionFailure(err)
	}
	if err := commitRuntimeGrant(ctx, transaction, isAdmin, authorityExpires, input.ExpiresAt); err != nil {
		return 0, err
	}
	return version, nil
}

func (postgres *Postgres) RevokeRuntimeGrant(ctx context.Context, identity UserIdentity, deviceID, proofJKT, runtimeID, targetUserID string) (int64, error) {
	if !validGrantActor(deviceID, proofJKT, runtimeID, targetUserID) {
		return 0, ErrGrantInvalid
	}
	ctx, cancel := context.WithTimeout(ctx, 15*time.Second)
	defer cancel()
	return retrySerializable(ctx, func() (int64, error) {
		return postgres.revokeRuntimeGrantOnce(ctx, identity, deviceID, proofJKT, runtimeID, targetUserID)
	})
}

func (postgres *Postgres) revokeRuntimeGrantOnce(ctx context.Context, identity UserIdentity, deviceID, proofJKT, runtimeID, targetUserID string) (int64, error) {
	transaction, err := postgres.DB.BeginTx(ctx, &sql.TxOptions{Isolation: sql.LevelSerializable})
	if err != nil {
		return 0, transactionFailure(err)
	}
	defer transaction.Rollback()
	actorID, orgID, actorRole, _, isAdmin, authorityExpires, err := postgres.authorizeGrantManager(ctx, transaction, identity, deviceID, proofJKT, runtimeID)
	if err != nil {
		return 0, err
	}
	if actorRole != "owner" && !isAdmin {
		return 0, ErrAccessDenied
	}
	var role string
	var version int64
	var revoked, expires sql.NullTime
	err = transaction.QueryRowContext(ctx, `
		select role, grant_version, revoked_at, expires_at
		from runtime_grants where runtime_id = $1 and user_id = $2 for update /* grant_target_fence */`, runtimeID, targetUserID).
		Scan(&role, &version, &revoked, &expires)
	if errors.Is(err, sql.ErrNoRows) {
		return 0, ErrGrantNotFound
	}
	if err != nil {
		return 0, transactionFailure(err)
	}
	writeTime, err := verifyGrantWriteTime(ctx, transaction, isAdmin, authorityExpires, nil)
	if err != nil {
		return 0, err
	}
	if revoked.Valid {
		if err := commitRuntimeGrant(ctx, transaction, isAdmin, authorityExpires, nil); err != nil {
			return 0, err
		}
		return version, nil
	}
	if role == "owner" && (!expires.Valid || expires.Time.After(writeTime)) && !isAdmin {
		remaining, err := countOtherActiveOwners(ctx, transaction, runtimeID, orgID, targetUserID)
		if err != nil {
			return 0, transactionFailure(err)
		}
		if remaining == 0 {
			return 0, ErrAccessDenied
		}
	}
	version++
	if _, err := transaction.ExecContext(ctx, `
		update runtime_grants set revoked_at = now(), grant_version = $3
		where runtime_id = $1 and user_id = $2 and revoked_at is null`, runtimeID, targetUserID, version); err != nil {
		return 0, transactionFailure(err)
	}
	metadata, _ := json.Marshal(map[string]any{"runtime_id": runtimeID, "user_id": targetUserID, "grant_version": version})
	if _, err := transaction.ExecContext(ctx, `
		insert into control_outbox (org_id, topic, payload) values ($1, 'runtime_grant.revoked', $2::jsonb)`, orgID, string(metadata)); err != nil {
		return 0, transactionFailure(err)
	}
	if _, err := transaction.ExecContext(ctx, `
		insert into audit_log (org_id, actor_type, actor_id, device_id, runtime_id, action, result, metadata)
		values ($1, 'user', $2, $3, $4, 'runtime_grant.revoke', 'success', $5::jsonb)`,
		orgID, actorID, deviceID, runtimeID, string(metadata)); err != nil {
		return 0, transactionFailure(err)
	}
	if err := commitRuntimeGrant(ctx, transaction, isAdmin, authorityExpires, nil); err != nil {
		return 0, err
	}
	return version, nil
}

// The original manager grant stays locked, but its expiry can pass while target,
// audit or outbox SQL waits. Preserve its original expiry even for self-revocation.
func verifyGrantWriteTime(ctx context.Context, tx *sql.Tx, isAdmin bool, authorityExpires, candidateExpires *time.Time) (time.Time, error) {
	var now time.Time
	if err := tx.QueryRowContext(ctx, `select clock_timestamp()`).Scan(&now); err != nil {
		return time.Time{}, transactionFailure(err)
	}
	if !isAdmin && authorityExpires != nil && !authorityExpires.After(now) {
		return now, ErrAccessDenied
	}
	if candidateExpires != nil && !candidateExpires.After(now) {
		return now, ErrGrantInvalid
	}
	return now, nil
}

func commitRuntimeGrant(ctx context.Context, tx *sql.Tx, isAdmin bool, authorityExpires, candidateExpires *time.Time) error {
	if _, err := verifyGrantWriteTime(ctx, tx, isAdmin, authorityExpires, candidateExpires); err != nil {
		return err
	}
	if err := tx.Commit(); err != nil {
		return transactionFailure(err)
	}
	return nil
}

func (postgres *Postgres) authorizeGrantManager(ctx context.Context, tx *sql.Tx, identity UserIdentity, deviceID, proofJKT, runtimeID string) (actorID, orgID, role string, bindings []GrantBinding, isAdmin bool, expires *time.Time, err error) {
	if _, err = tx.ExecContext(ctx, `set local lock_timeout='2s'`); err != nil {
		return "", "", "", nil, false, nil, transactionFailure(err)
	}
	if _, err = lockRecoveryReady(ctx, tx, ""); err != nil {
		return "", "", "", nil, false, nil, err
	}
	actorID, err = postgres.userIDForSerializable(ctx, tx, identity.Issuer, identity.Subject)
	if err != nil {
		return "", "", "", nil, false, nil, err
	}
	var current string
	err = tx.QueryRowContext(ctx, `select id::text from devices
	 where id=$1 and user_id=$2 and proof_key_thumbprint=$3 and revoked_at is null
	 for share /* grant_actor_device_fence */`, deviceID, actorID, proofJKT).Scan(&current)
	if errors.Is(err, sql.ErrNoRows) {
		return "", "", "", nil, false, nil, ErrAccessDenied
	}
	if err != nil {
		return "", "", "", nil, false, nil, transactionFailure(err)
	}
	err = tx.QueryRowContext(ctx, `
		select h.org_id::text from runtimes r
		join installations i on i.id = r.installation_id and i.revoked_at is null
		join hosts h on h.id = i.host_id and h.state = 'active'
		where r.id = $1 and r.revoked_at is null and `+runtimeEnrollmentApprovedSQL+`
		for share of r, i, h`, runtimeID).Scan(&orgID)
	if errors.Is(err, sql.ErrNoRows) {
		return "", "", "", nil, false, nil, ErrAccessDenied
	}
	if err != nil {
		return "", "", "", nil, false, nil, transactionFailure(err)
	}
	var membershipRole string
	err = tx.QueryRowContext(ctx, `
		select role from org_memberships where org_id = $1 and user_id = $2 for share`, orgID, actorID).Scan(&membershipRole)
	if errors.Is(err, sql.ErrNoRows) {
		return "", "", "", nil, false, nil, ErrAccessDenied
	}
	if err != nil {
		return "", "", "", nil, false, nil, transactionFailure(err)
	}
	isAdmin = membershipRole == "admin"
	var bindingsJSON []byte
	err = tx.QueryRowContext(ctx, `
		select role, bindings, expires_at from runtime_grants
		where runtime_id = $1 and user_id = $2 and revoked_at is null
		  and (expires_at is null or expires_at > clock_timestamp())
		for update`, runtimeID, actorID).Scan(&role, &bindingsJSON, &expires)
	if errors.Is(err, sql.ErrNoRows) {
		role = ""
	} else if err != nil {
		return "", "", "", nil, false, nil, transactionFailure(err)
	} else if err := json.Unmarshal(bindingsJSON, &bindings); err != nil {
		return "", "", "", nil, false, nil, transactionFailure(err)
	}
	if role != "owner" && !isAdmin {
		return "", "", "", nil, false, nil, ErrAccessDenied
	}
	return actorID, orgID, role, bindings, isAdmin, expires, nil
}

func activeOrganizationUser(ctx context.Context, tx *sql.Tx, orgID, userID string) (string, error) {
	var id string
	err := tx.QueryRowContext(ctx, `
		select u.id::text from users u
		join org_memberships m on m.user_id = u.id and m.org_id = $1
		where u.id = $2 and u.status = 'active'
		for share of u, m`, orgID, userID).Scan(&id)
	if errors.Is(err, sql.ErrNoRows) {
		return "", ErrAccessDenied
	}
	if err != nil {
		return "", transactionFailure(err)
	}
	return id, nil
}

func countOtherActiveOwners(ctx context.Context, tx *sql.Tx, runtimeID, orgID, excludedUserID string) (int, error) {
	var count int
	err := tx.QueryRowContext(ctx, `
		select count(*) from runtime_grants g
		join users u on u.id = g.user_id and u.status = 'active'
		join org_memberships m on m.user_id = u.id and m.org_id = $2
		where g.runtime_id = $1 and g.user_id <> $3 and g.role = 'owner'
		  and g.revoked_at is null and (g.expires_at is null or g.expires_at > clock_timestamp())`, runtimeID, orgID, excludedUserID).Scan(&count)
	return count, err
}

func grantExpiryEqual(previous sql.NullTime, next *time.Time) bool {
	if next == nil {
		return !previous.Valid
	}
	return previous.Valid && previous.Time.Equal(*next)
}

func grantBindingsEqual(previousJSON []byte, next []GrantBinding) bool {
	var previous []GrantBinding
	if json.Unmarshal(previousJSON, &previous) != nil {
		return false
	}
	if len(previous) != len(next) {
		return false
	}
	for index := range previous {
		if previous[index].Resource != next[index].Resource || !slices.Equal(previous[index].Actions, next[index].Actions) {
			return false
		}
	}
	return true
}

func writeGrantChange(ctx context.Context, tx *sql.Tx, actorID, deviceID, orgID, runtimeID, targetUserID, role string, version int64, expiresAt *time.Time, topic, action string, bindingsJSON []byte) error {
	payload, err := json.Marshal(map[string]any{
		"runtime_id": runtimeID, "user_id": targetUserID, "grant_version": version,
		"role": role, "bindings": json.RawMessage(bindingsJSON), "expires_at": expiresAt,
	})
	if err != nil {
		return err
	}
	if _, err := tx.ExecContext(ctx, `
		insert into control_outbox (org_id, topic, payload) values ($1, $2, $3::jsonb)`, orgID, topic, string(payload)); err != nil {
		return err
	}
	var actorDeviceID any
	if deviceID != "" {
		actorDeviceID = deviceID
	}
	_, err = tx.ExecContext(ctx, `
		insert into audit_log (org_id, actor_type, actor_id, device_id, runtime_id, action, result, metadata)
		values ($1, 'user', $2, $3, $4, $5, 'success', $6::jsonb)`,
		orgID, actorID, actorDeviceID, runtimeID, action, string(payload))
	return err
}
