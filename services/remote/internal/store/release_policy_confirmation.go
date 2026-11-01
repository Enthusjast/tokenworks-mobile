package store

import (
	"context"
	"database/sql"
	"errors"
	"time"

	"dsh-mobile-ustc/services/remote/internal/releases"
)

var errAppReleaseConfirmationMissing = errors.New("store: APK publication confirmation is not recorded")

type AppReleasePolicyConfirmation struct {
	OperationID   string    `json:"operation_id"`
	DatabaseEpoch string    `json:"database_epoch"`
	PackageName   string    `json:"package_name"`
	PolicySHA256  string    `json:"policy_sha256"`
	Revision      int64     `json:"revision"`
	ConfirmedAt   time.Time `json:"confirmed_at"`
}

func ValidAppReleasePolicyConfirmation(epoch, id, expected string, catalog *releases.Catalog) bool {
	ref, err := catalog.Policy()
	return err == nil && grantUUIDPattern.MatchString(epoch) && grantUUIDPattern.MatchString(id) && validSHA256Hex(expected) && ref.SHA256 == expected
}
func readAppReleasePolicyConfirmation(ctx context.Context, tx *sql.Tx, id string) (AppReleasePolicyConfirmation, error) {
	var result AppReleasePolicyConfirmation
	err := tx.QueryRowContext(ctx, `select operation_id::text,database_epoch::text,package_name,encode(policy_sha256,'hex'),revision,confirmed_at
 from app_release_policy_confirmations where operation_id=$1`, id).Scan(&result.OperationID, &result.DatabaseEpoch, &result.PackageName, &result.PolicySHA256, &result.Revision, &result.ConfirmedAt)
	if errors.Is(err, sql.ErrNoRows) {
		return result, errAppReleaseConfirmationMissing
	}
	if err != nil {
		return result, ErrDatabaseUnavailable
	}
	return result, nil
}

// ConfirmAppReleasePolicy explicitly revalidates an unchanged, externally
// reviewed catalog after recovery. It preserves policy contents and revision.
func (postgres *Postgres) ConfirmAppReleasePolicy(parent context.Context, epoch, id, expected string, catalog *releases.Catalog) (AppReleasePolicyConfirmation, error) {
	if !ValidAppReleasePolicyConfirmation(epoch, id, expected, catalog) {
		return AppReleasePolicyConfirmation{}, ErrDatabaseConfig
	}
	ctx, cancel := context.WithTimeout(parent, 5*time.Second)
	defer cancel()
	tx, err := postgres.DB.BeginTx(ctx, nil)
	if err != nil {
		return AppReleasePolicyConfirmation{}, ErrDatabaseUnavailable
	}
	defer tx.Rollback()
	if _, err = tx.ExecContext(ctx, `set local lock_timeout='2s'`); err != nil {
		return AppReleasePolicyConfirmation{}, ErrDatabaseUnavailable
	}
	if _, err = lockRecoveryReady(ctx, tx, epoch); err != nil {
		return AppReleasePolicyConfirmation{}, err
	}
	if _, err = tx.ExecContext(ctx, `lock table app_release_artifacts in share row exclusive mode`); err != nil {
		return AppReleasePolicyConfirmation{}, ErrDatabaseUnavailable
	}
	ref, _ := catalog.Policy()
	if result, err := readAppReleasePolicyConfirmation(ctx, tx, id); err == nil {
		if result.DatabaseEpoch != epoch || result.PackageName != ref.PackageName || result.PolicySHA256 != expected {
			return AppReleasePolicyConfirmation{}, ErrAppReleasePolicyConflict
		}
		return result, nil
	} else if !errors.Is(err, errAppReleaseConfirmationMissing) {
		return AppReleasePolicyConfirmation{}, err
	}
	var current, validated string
	if err = tx.QueryRowContext(ctx, `select encode(policy_sha256,'hex'),validated_epoch::text from app_release_policies where package_name=$1 for update`, ref.PackageName).Scan(&current, &validated); errors.Is(err, sql.ErrNoRows) || err == nil && (current != expected || validated == epoch) {
		return AppReleasePolicyConfirmation{}, ErrAppReleasePolicyConflict
	}
	if err != nil {
		return AppReleasePolicyConfirmation{}, ErrDatabaseUnavailable
	}
	artifacts, _ := catalog.Artifacts()
	if err = registerReleaseArtifacts(ctx, tx, epoch, artifacts); err != nil {
		return AppReleasePolicyConfirmation{}, err
	}
	var recorded string
	if err = tx.QueryRowContext(ctx, `insert into app_release_policy_confirmations(operation_id,database_epoch,package_name,policy_sha256,revision,confirmed_at)
 values($1,$2,$3,decode($4,'hex'),1,clock_timestamp()) returning operation_id::text`, id, epoch, ref.PackageName, expected).Scan(&recorded); err != nil || recorded != id {
		return AppReleasePolicyConfirmation{}, ErrDatabaseUnavailable
	}
	result, err := readAppReleasePolicyConfirmation(ctx, tx, id)
	if err != nil {
		return result, err
	}
	if tx.Commit() != nil {
		return AppReleasePolicyConfirmation{}, ErrDatabaseUnavailable
	}
	return result, nil
}
