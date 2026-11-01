package store

import (
	"encoding/json"
	"strings"
	"testing"
)

func TestEnrollmentAdminPolicySnapshotCopiesOptionalEvidence(t *testing.T) {
	input := identitySnapshotFixture(t, 1)
	_, legacy, err := normalizeIdentitySnapshot(input, input.DatabaseEpoch, input.Issuer)
	if err != nil {
		t.Fatal(err)
	}
	raw, _ := json.Marshal(input)
	if strings.Contains(string(raw), "enrollment_admin_approval_required") {
		t.Fatal("omitted field changed legacy snapshot JSON")
	}
	required := true
	input.Organizations[0].EnrollmentAdminApprovalRequired = &required
	normalized, fingerprint, err := normalizeIdentitySnapshot(input, input.DatabaseEpoch, input.Issuer)
	if err != nil || fingerprint == legacy {
		t.Fatal("policy was not included in snapshot fingerprint", err)
	}
	required = false
	if !*normalized.Organizations[0].EnrollmentAdminApprovalRequired {
		t.Fatal("normalized policy aliases caller state")
	}
	_, relaxed, err := normalizeIdentitySnapshot(input, input.DatabaseEpoch, input.Issuer)
	if err != nil || relaxed == fingerprint || relaxed == legacy {
		t.Fatal("explicit relaxation is indistinguishable from omission", err)
	}
}
