package store

import (
	"context"
	"database/sql"
	"errors"
	"time"

	"dsh-mobile-ustc/services/remote/internal/releases"
)

var ErrAppReleaseConflict = errors.New("store: APK release identity conflicts with registered evidence")

// RegisterAppReleaseCatalog atomically binds all artifact identities before the
// service opens any listener. A missing/changed publication never erases old
// identities; retries after an unknown commit are safe with the same catalog.
func (postgres *Postgres) RegisterAppReleaseCatalog(parent context.Context, epoch string, catalog *releases.Catalog) error {
	artifacts, err := catalog.Artifacts()
	if err != nil || !grantUUIDPattern.MatchString(epoch) {
		return ErrDatabaseConfig
	}
	ctx, cancel := context.WithTimeout(parent, 5*time.Second)
	defer cancel()
	tx, err := postgres.DB.BeginTx(ctx, nil)
	if err != nil {
		return ErrDatabaseUnavailable
	}
	defer tx.Rollback()
	if _, err = tx.ExecContext(ctx, `set local lock_timeout='2s'`); err != nil {
		return ErrDatabaseUnavailable
	}
	if _, err = lockRecoveryReady(ctx, tx, epoch); err != nil {
		return err
	}
	// Startup-only, at most 64 artifacts. One short table lock orders all two-key
	// uniqueness checks across nodes, without a first-row race or lock inversion.
	if _, err = tx.ExecContext(ctx, `lock table app_release_artifacts in share row exclusive mode`); err != nil {
		return ErrDatabaseUnavailable
	}
	if err = registerReleaseArtifacts(ctx, tx, epoch, artifacts); err != nil {
		return err
	}
	ref, err := catalog.Policy()
	if err != nil {
		return ErrDatabaseConfig
	}
	if _, err = tx.ExecContext(ctx, `insert into app_release_policies(package_name,policy_sha256,revision,changed_epoch)
 values($1,decode($2,'hex'),1,$3) on conflict do nothing`, ref.PackageName, ref.SHA256, epoch); err != nil {
		return ErrDatabaseUnavailable
	}
	var current bool
	if err = tx.QueryRowContext(ctx, `select policy_sha256=decode($2,'hex') from app_release_policies where package_name=$1`, ref.PackageName, ref.SHA256).Scan(&current); err != nil {
		return ErrDatabaseUnavailable
	}
	if !current {
		return ErrAppReleasePolicyConflict
	}

	if tx.Commit() != nil {
		return ErrDatabaseUnavailable
	}
	return nil
}

// Caller holds recovery and the artifact table lock in that order.
func registerReleaseArtifacts(ctx context.Context, tx *sql.Tx, epoch string, artifacts []releases.Artifact) error {
	for _, artifact := range artifacts {
		digest, err := artifact.Digest()
		if err != nil {
			return ErrDatabaseConfig
		}
		args := []any{artifact.ID, artifact.PackageName, artifact.VersionCode, artifact.VersionName, artifact.SHA256, artifact.SizeBytes,
			artifact.SigningCertificate, artifact.MinimumProtocolVersion, artifact.MaximumProtocolVersion, artifact.MinimumAndroidAPI, digest[:], epoch}
		// ON CONFLICT does not fire AFTER INSERT, so idempotent starts create no audit.
		if _, err = tx.ExecContext(ctx, `insert into app_release_artifacts(release_id,package_name,version_code,version_name,sha256,size_bytes,
   signing_cert_sha256,minimum_protocol_version,maximum_protocol_version,minimum_android_api,identity_sha256,registered_epoch)
   values($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12) on conflict do nothing`, args...); err != nil {
			return ErrDatabaseUnavailable
		}
		var matches bool
		err = tx.QueryRowContext(ctx, `select version_name=$4 and sha256=$5 and size_bytes=$6 and signing_cert_sha256=$7
   and minimum_protocol_version=$8 and maximum_protocol_version=$9 and minimum_android_api=$10 and identity_sha256=$11
   from app_release_artifacts where release_id=$1 and package_name=$2 and version_code=$3`, args[:11]...).Scan(&matches)
		if errors.Is(err, sql.ErrNoRows) || err == nil && !matches {
			return ErrAppReleaseConflict
		}
		if err != nil {
			return ErrDatabaseUnavailable
		}
	}
	return nil
}
