package store

import (
	"encoding/json"
	"errors"
	"sort"
	"strings"
	"testing"
)

func TestEnrollmentAdminDirectoryPagesOnlyCurrentOrganizationAndDevice(t *testing.T) {
	ctx, db, input, first, admin, proof := adminEnrollmentFixture(t)
	ids := []string{first.EnrollmentID}
	for _, profile := range []string{"waiting-two", "waiting-three", "expired", "retired"} {
		input.RequestID = approvalTestUUID(t)
		input.ProfileRef = profile
		input.Code = validEnrollmentInput(t).Code
		created, err := db.CreateEnrollment(ctx, input)
		if err != nil {
			t.Fatal(err)
		}
		switch profile {
		case "expired":
			if _, err := db.DB.ExecContext(ctx, `update enrollments set created_at=clock_timestamp()-interval '2 minutes',expires_at=clock_timestamp()-interval '1 second' where id=$1`, created.EnrollmentID); err != nil {
				t.Fatal(err)
			}
		case "retired":
			if _, err := db.DB.ExecContext(ctx, `update runtimes set revoked_at=clock_timestamp() where id=$1`, created.RuntimeID); err != nil {
				t.Fatal(err)
			}
		default:
			ids = append(ids, created.EnrollmentID)
		}
	}
	if _, err := db.DB.ExecContext(ctx, `update orgs set enrollment_admin_approval_required=false where id=$1`, input.OrganizationID); err != nil {
		t.Fatal(err)
	}
	input.RequestID = approvalTestUUID(t)
	input.ProfileRef = "policy-optional"
	input.Code = validEnrollmentInput(t).Code
	if _, err := db.CreateEnrollment(ctx, input); err != nil {
		t.Fatal(err)
	}
	if _, err := db.DB.ExecContext(ctx, `update enrollments set created_at=statement_timestamp()-interval '3 minutes' where id=any($1::uuid[])`, ids); err != nil {
		t.Fatal(err)
	}
	sort.Strings(ids)
	var seen []string
	cursor := ""
	var firstCursor string
	for pageNumber := 0; pageNumber < 3; pageNumber++ {
		page, err := db.ListEnrollmentApprovals(ctx, admin, enrollmentAdminDevice, proof, input.OrganizationID, 1, cursor)
		if err != nil || len(page.Items) != 1 || page.OrganizationID != input.OrganizationID {
			t.Fatal("administrator page failed", err)
		}
		item := page.Items[0]
		seen = append(seen, item.EnrollmentID)
		if item.InstallationID != first.InstallationID || item.OrganizationID != input.OrganizationID || item.OwnerUserID != "22222222-2222-4222-8222-222222222222" {
			t.Fatal("page leaked another installation/organization")
		}
		raw, _ := json.Marshal(page)
		for _, private := range []string{input.Code, "code_hash", "request_sha256", "proof_key_jkt", "issuer", "subject"} {
			if strings.Contains(string(raw), private) {
				t.Fatal("directory disclosed review-only or secret data")
			}
		}
		if pageNumber < 2 {
			if page.NextCursor == nil {
				t.Fatal("missing next cursor")
			}
			cursor = *page.NextCursor
			if pageNumber == 0 {
				firstCursor = cursor
			}
		} else if page.NextCursor != nil {
			t.Fatal("terminal page had another cursor")
		}
	}
	if strings.Join(seen, ",") != strings.Join(ids, ",") {
		t.Fatal("oldest-first tie pagination repeated or omitted requests")
	}
	if _, err := db.ListEnrollmentApprovals(ctx, admin, enrollmentAdminDevice, proof, "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb", 1, firstCursor); !errors.Is(err, ErrAccessDenied) {
		t.Fatal("cursor escaped organization", err)
	}
	if _, err := db.ListEnrollmentApprovals(ctx, admin, enrollmentAdminDevice, proof, "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb", 1, ""); !errors.Is(err, ErrAccessDenied) {
		t.Fatal("non-admin organization was visible", err)
	}
	const otherDevice = "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee"
	otherProof := strings.Repeat("B", 43)
	if _, err := db.DB.ExecContext(ctx, `insert into devices(id,user_id,name,approval_public_jwk,approval_key_thumbprint,proof_public_jwk,proof_key_thumbprint)
	 values($1,'22222222-2222-4222-8222-222222222222','other admin device','{}','other-admin-approval','{}',$2)`, otherDevice, otherProof); err != nil {
		t.Fatal(err)
	}
	if _, err := db.ListEnrollmentApprovals(ctx, admin, otherDevice, otherProof, input.OrganizationID, 1, ""); err != nil {
		t.Fatal("current second device cannot list", err)
	}
	if _, err := db.ListEnrollmentApprovals(ctx, admin, otherDevice, otherProof, input.OrganizationID, 1, firstCursor); !errors.Is(err, ErrAccessDenied) {
		t.Fatal("cursor transferred devices", err)
	}
	if _, err := db.DB.ExecContext(ctx, `update org_memberships set role='member' where user_id='22222222-2222-4222-8222-222222222222'`); err != nil {
		t.Fatal(err)
	}
	if _, err := db.ListEnrollmentApprovals(ctx, admin, enrollmentAdminDevice, proof, input.OrganizationID, 1, firstCursor); !errors.Is(err, ErrAccessDenied) {
		t.Fatal("cursor retained lost administrator authority", err)
	}
}

func TestEnrollmentAdminDirectoryRemovesDeniedRequestsWithoutRevivingCursorAuthority(t *testing.T) {
	ctx, db, input, created, admin, proof := adminEnrollmentFixture(t)
	review, err := db.ReviewEnrollmentAdmin(ctx, admin, enrollmentAdminDevice, proof, created.EnrollmentID)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := db.DenyEnrollmentAdmin(ctx, admin, enrollmentAdminDevice, proof, created.EnrollmentID, review.ReviewSHA256); err != nil {
		t.Fatal(err)
	}
	page, err := db.ListEnrollmentApprovals(ctx, admin, enrollmentAdminDevice, proof, input.OrganizationID, 50, "")
	if err != nil || len(page.Items) != 0 || page.NextCursor != nil {
		t.Fatal("terminal denial remained in live directory", err)
	}
	if _, err := db.DB.ExecContext(ctx, `update devices set revoked_at=clock_timestamp() where id=$1`, enrollmentAdminDevice); err != nil {
		t.Fatal(err)
	}
	if _, err := db.ListEnrollmentApprovals(ctx, admin, enrollmentAdminDevice, proof, input.OrganizationID, 50, ""); !errors.Is(err, ErrAccessDenied) {
		t.Fatal("revoked phone retained directory access", err)
	}
}
