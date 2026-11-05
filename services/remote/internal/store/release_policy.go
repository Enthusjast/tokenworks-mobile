package store

import (
	"context"
	"database/sql"
	"errors"
	"time"

	"tokenworks-mobile/services/remote/internal/releases"
)

var ErrAppReleasePolicyConflict = errors.New("store: APK publication policy or operation conflicts with current evidence")
var ErrAppReleasePolicyStale = errors.New("store: APK publication policy is not current")
var errAppReleaseOperationMissing = errors.New("store: APK publication operation is not recorded")

type AppReleasePolicyStatus struct {
	DatabaseEpoch  string `json:"database_epoch"`
	PackageName    string `json:"package_name"`
	Configured     bool   `json:"configured"`
	PolicySHA256   string `json:"policy_sha256,omitempty"`
	Revision       int64  `json:"revision"`
	ValidatedEpoch string `json:"validated_epoch,omitempty"`
	Current        bool   `json:"current"`
}
type AppReleasePolicyChange struct {
	OperationID    string    `json:"operation_id"`
	DatabaseEpoch  string    `json:"database_epoch"`
	PackageName    string    `json:"package_name"`
	PreviousSHA256 string    `json:"previous_sha256"`
	PolicySHA256   string    `json:"policy_sha256"`
	Revision       int64     `json:"revision"`
	ChangedAt      time.Time `json:"changed_at"`
}

func (postgres *Postgres) AppReleasePolicyStatus(parent context.Context, epoch, packageName string) (AppReleasePolicyStatus, error) {
	result := AppReleasePolicyStatus{DatabaseEpoch: epoch, PackageName: packageName}
	if !grantUUIDPattern.MatchString(epoch) || !releases.ValidPackageName(packageName) {
		return result, ErrDatabaseConfig
	}
	ctx, cancel := context.WithTimeout(parent, 3*time.Second)
	defer cancel()
	tx, err := postgres.DB.BeginTx(ctx, &sql.TxOptions{Isolation: sql.LevelRepeatableRead, ReadOnly: true})
	if err != nil {
		return result, ErrDatabaseUnavailable
	}
	defer tx.Rollback()
	state, err := recoveryState(ctx, tx, "")
	if err != nil {
		return result, err
	}
	if state.Epoch != epoch || state.Phase != "ready" {
		return result, ErrRecoveryUnavailable
	}
	err = tx.QueryRowContext(ctx, `select encode(policy_sha256,'hex'),revision,validated_epoch::text from app_release_policies where package_name=$1`, packageName).Scan(&result.PolicySHA256, &result.Revision, &result.ValidatedEpoch)
	if err != nil && !errors.Is(err, sql.ErrNoRows) {
		return result, ErrDatabaseUnavailable
	}
	result.Configured = err == nil
	result.Current = result.Configured && result.ValidatedEpoch == epoch
	if tx.Commit() != nil {
		return result, ErrDatabaseUnavailable
	}
	return result, nil
}
func readAppReleasePolicyChange(ctx context.Context, tx *sql.Tx, id string) (AppReleasePolicyChange, error) {
	var result AppReleasePolicyChange
	err := tx.QueryRowContext(ctx, `select operation_id::text,database_epoch::text,package_name,encode(previous_sha256,'hex'),encode(policy_sha256,'hex'),revision,changed_at
 from app_release_policy_changes where operation_id=$1`, id).Scan(&result.OperationID, &result.DatabaseEpoch, &result.PackageName, &result.PreviousSHA256, &result.PolicySHA256, &result.Revision, &result.ChangedAt)
	if errors.Is(err, sql.ErrNoRows) {
		return result, errAppReleaseOperationMissing
	}
	if err != nil {
		return result, ErrDatabaseUnavailable
	}
	return result, nil
}
func ValidAppReleasePolicyChange(epoch, id, expected string, catalog *releases.Catalog) bool {
	ref, err := catalog.Policy()
	return err == nil && grantUUIDPattern.MatchString(epoch) && grantUUIDPattern.MatchString(id) && validSHA256Hex(expected) && ref.SHA256 != expected
}

// Explicit operator replacement. Old nodes fail closed at their next metadata
// admission; historical operation retries return receipts without restoring policy.
func (postgres *Postgres) ReplaceAppReleasePolicy(parent context.Context, epoch, id, expected string, catalog *releases.Catalog) (AppReleasePolicyChange, error) {
	if !ValidAppReleasePolicyChange(epoch, id, expected, catalog) {
		return AppReleasePolicyChange{}, ErrDatabaseConfig
	}
	ctx, cancel := context.WithTimeout(parent, 5*time.Second)
	defer cancel()
	tx, err := postgres.DB.BeginTx(ctx, nil)
	if err != nil {
		return AppReleasePolicyChange{}, ErrDatabaseUnavailable
	}
	defer tx.Rollback()
	if _, err = tx.ExecContext(ctx, `set local lock_timeout='2s'`); err != nil {
		return AppReleasePolicyChange{}, ErrDatabaseUnavailable
	}
	if _, err = lockRecoveryReady(ctx, tx, epoch); err != nil {
		return AppReleasePolicyChange{}, err
	}
	if _, err = tx.ExecContext(ctx, `lock table app_release_artifacts in share row exclusive mode`); err != nil {
		return AppReleasePolicyChange{}, ErrDatabaseUnavailable
	}
	ref, _ := catalog.Policy()
	if result, err := readAppReleasePolicyChange(ctx, tx, id); err == nil {
		if result.DatabaseEpoch != epoch || result.PackageName != ref.PackageName || result.PreviousSHA256 != expected || result.PolicySHA256 != ref.SHA256 {
			return AppReleasePolicyChange{}, ErrAppReleasePolicyConflict
		}
		return result, nil
	} else if !errors.Is(err, errAppReleaseOperationMissing) {
		return AppReleasePolicyChange{}, err
	}
	var current string
	err = tx.QueryRowContext(ctx, `select encode(policy_sha256,'hex') from app_release_policies where package_name=$1 for update`, ref.PackageName).Scan(&current)
	if errors.Is(err, sql.ErrNoRows) || err == nil && current != expected {
		return AppReleasePolicyChange{}, ErrAppReleasePolicyConflict
	}
	if err != nil {
		return AppReleasePolicyChange{}, ErrDatabaseUnavailable
	}
	artifacts, _ := catalog.Artifacts()
	if err = registerReleaseArtifacts(ctx, tx, epoch, artifacts); err != nil {
		return AppReleasePolicyChange{}, err
	}
	var recorded string
	if err = tx.QueryRowContext(ctx, `insert into app_release_policy_changes(operation_id,database_epoch,package_name,previous_sha256,policy_sha256,revision,changed_at)
 values($1,$2,$3,decode($4,'hex'),decode($5,'hex'),2,clock_timestamp()) returning operation_id::text`, id, epoch, ref.PackageName, expected, ref.SHA256).Scan(&recorded); err != nil || recorded != id {
		return AppReleasePolicyChange{}, ErrDatabaseUnavailable
	}
	result, err := readAppReleasePolicyChange(ctx, tx, id)
	if err != nil {
		return result, err
	}
	if tx.Commit() != nil {
		return AppReleasePolicyChange{}, ErrDatabaseUnavailable
	}
	return result, nil
}
