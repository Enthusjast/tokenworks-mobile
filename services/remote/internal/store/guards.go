package store

import (
	"context"
	"database/sql"
	"regexp"
	"sort"
)

var sha256HexPattern = regexp.MustCompile(`^[0-9a-f]{64}$`)

func validSHA256Hex(value string) bool { return sha256HexPattern.MatchString(value) }

// lockControlArchive fences cold moves against live control mutations. The
// native transport has no cold archive, but the advisory lock keeps the
// consumer incarnation sequence and the audit/outbox writers mutually exclusive.
func lockControlArchive(ctx context.Context, tx *sql.Tx, exclusive bool) error {
	query := `select pg_advisory_xact_lock_shared(1380012611,1)`
	if exclusive {
		query = `select pg_advisory_xact_lock(1380012611,1)`
	}
	if _, err := tx.ExecContext(ctx, query); err != nil {
		return ErrDatabaseUnavailable
	}
	return nil
}

func normalizeStrings(values []string, uuidValues bool) ([]string, error) {
	if len(values) == 0 {
		return []string{}, nil
	}
	result := append([]string(nil), values...)
	for _, value := range result {
		if value == "" || uuidValues && !grantUUIDPattern.MatchString(value) {
			return nil, ErrGrantInvalid
		}
	}
	sort.Strings(result)
	for index := 1; index < len(result); index++ {
		if result[index] == result[index-1] {
			return nil, ErrGrantInvalid
		}
	}
	return result, nil
}

// ValidDatabaseEpoch recognizes the deployment epoch carried by control rows.
func ValidDatabaseEpoch(epoch string) bool { return grantUUIDPattern.MatchString(epoch) }
