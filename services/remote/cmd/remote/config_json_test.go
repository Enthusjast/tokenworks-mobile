package main

import (
	"encoding/json"
	"errors"
	"strings"
	"testing"
	"time"

	"dsh-mobile-ustc/services/remote/internal/store"
)

func TestStrictConfigurationUsesLiteralFieldsAndPreservesMapKeysAndOptionalPolicy(t *testing.T) {
	type entry struct {
		Enabled bool `json:"enabled"`
	}
	type configuration struct {
		Version  int              `json:"v"`
		Items    []entry          `json:"items"`
		Keys     map[string]entry `json:"keys"`
		At       time.Time        `json:"at"`
		Optional *bool            `json:"optional,omitempty"`
	}
	good := `{"v":1,"items":[{"enabled":true}],"keys":{"Key-ID":{"enabled":false}},"at":"2026-11-02T12:00:00Z","optional":null}`
	var decoded configuration
	if err := strictFileJSON([]byte(good), &decoded); err != nil || !decoded.Items[0].Enabled || decoded.Keys["Key-ID"].Enabled || decoded.Optional != nil || decoded.At.IsZero() {
		t.Fatal("valid typed config representation was changed", err)
	}
	for _, raw := range []string{
		strings.Replace(good, `"v":1`, `"V":1`, 1),
		strings.Replace(good, `"v":1`, `"v":1,"V":1`, 1),
		strings.Replace(good, `"items"`, `"Items"`, 1),
		strings.Replace(good, `"enabled":true`, `"Enabled":true`, 1),
		strings.Replace(good, `"enabled":false`, `"Enabled":false`, 1),
		strings.Replace(good, `"at"`, `"At"`, 1),
		strings.Replace(good, `"optional"`, `"Optional"`, 1),
		strings.Replace(good, `"v":1`, `"v":1,"v":1`, 1),
	} {
		var candidate configuration
		if err := strictFileJSON([]byte(raw), &candidate); !errors.Is(err, errFileConfiguration) {
			t.Fatal("aliased/ambiguous policy accepted", err)
		}
	}
	// Explicit null remains omission, preserving the existing no-downgrade
	// identity snapshot semantics rather than silently writing false.
	var organization store.IdentityOrganization
	if err := strictFileJSON([]byte(`{"organization_id":"aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa","slug":"fixture","name":"Fixture","enrollment_admin_approval_required":null}`), &organization); err != nil || organization.EnrollmentAdminApprovalRequired != nil {
		t.Fatal("optional organization policy changed", err)
	}
	var secret map[string]json.RawMessage
	if err := strictFileJSON([]byte(`{"provider-key-ID":{"opaque":true}}`), &secret); err != nil {
		t.Fatal("dynamic map keys were treated as struct aliases", err)
	}
}
