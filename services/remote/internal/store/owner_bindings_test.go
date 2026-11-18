package store

import (
	"encoding/json"
	"os"
	"path/filepath"
	"reflect"
	"runtime"
	"slices"
	"testing"
)

func TestOwnerBindingsMatchCanonicalNativeProductAccess(t *testing.T) {
	_, source, _, ok := runtime.Caller(0)
	if !ok {
		t.Fatal("locate canonical product access contract")
	}
	data, err := os.ReadFile(filepath.Join(filepath.Dir(source), "../../../../contracts/typert/product-access.json"))
	if err != nil {
		t.Fatal(err)
	}
	var canonical struct {
		Version      string `json:"dshVersion"`
		VersionRange string `json:"dshVersionRange"`
		Actions map[string]struct {
			Resources []string `json:"resources"`
			Selected bool `json:"selected"`
		} `json:"actions"`
	}
	if err := json.Unmarshal(data, &canonical); err != nil {
		t.Fatal(err)
	}
	if !IsSupportedDshVersion(canonical.Version) || canonical.Version != ProductNativeVersion || canonical.VersionRange != ProductNativeVersionRange {
		t.Fatalf("unknown native version/range: %s %s", canonical.Version, canonical.VersionRange)
	}
	selected := 0
	expected := make([]GrantBinding, 0, 3)
	for _, kind := range []string{"global", "session", "workspace"} {
		actions := []string{}
		for action, policy := range canonical.Actions {
			if slices.Contains(policy.Resources, kind) {
				actions = append(actions, action)
			}
		}
		slices.Sort(actions)
		expected = append(expected, GrantBinding{Resource: GrantResource{Kind: kind}, Actions: actions})
	}
	for _, policy := range canonical.Actions {
		if policy.Selected {
			selected++
		}
	}
	if selected != 40 {
		t.Fatalf("selected native method drift: %d", selected)
	}
	actual := OwnerBindings()
	for index := range actual {
		slices.Sort(actual[index].Actions)
	}
	if !reflect.DeepEqual(expected, actual) {
		t.Fatalf("owner matrix differs from canonical policy:\nwant %#v\ngot %#v", expected, actual)
	}
}

func TestNativeVersionAdmissionMatchesCanonicalCompatibilityCases(t *testing.T) {
	_, source, _, ok := runtime.Caller(0)
	if !ok {
		t.Fatal("locate canonical compatibility contract")
	}
	data, err := os.ReadFile(filepath.Join(filepath.Dir(source), "../../../../contracts/typert/golden/compatibility.json"))
	if err != nil {
		t.Fatal(err)
	}
	var manifest struct {
		Version      string `json:"dshVersion"`
		VersionRange string `json:"dshVersionRange"`
		Cases        []struct {
			AdvertisedVersion any    `json:"advertisedVersion"`
			Decision          string `json:"decision"`
		} `json:"cases"`
	}
	if err := json.Unmarshal(data, &manifest); err != nil {
		t.Fatal(err)
	}
	if manifest.Version != ProductNativeVersion || manifest.VersionRange != ProductNativeVersionRange || len(manifest.Cases) == 0 {
		t.Fatal("native compatibility manifest drift")
	}
	for _, entry := range manifest.Cases {
		version, isString := entry.AdvertisedVersion.(string)
		actual := isString && IsSupportedDshVersion(version)
		if actual != (entry.Decision == "compatible") {
			t.Errorf("version %#v: admitted %v, want %s", entry.AdvertisedVersion, actual, entry.Decision)
		}
	}
}

func TestOwnerBindingsIncludeTerminalAttachOnlyOnNativeSessionResource(t *testing.T) {
	found := false
	for _, binding := range OwnerBindings() {
		for _, action := range binding.Actions {
			if len(action) >= len("terminal/") && action[:len("terminal/")] == "terminal/" && binding.Resource.Kind != "session" {
				t.Fatalf("terminal action widened to %s: %s", binding.Resource.Kind, action)
			}
			if action == "terminal/attach" {
				found = true
			}
		}
	}
	if !found {
		t.Fatal("native terminal/attach omitted from owner policy")
	}
}

func TestOwnerBindingsScopeSubagentHistoryToSessionResource(t *testing.T) {
	// The native address resolves to the child session. An owner must be able
	// to follow and page that resource without an unscoped grant.
	required := map[string]bool{"session/follow": false, "session/page": false}
	for _, binding := range OwnerBindings() {
		for _, action := range binding.Actions {
			if _, history := required[action]; history {
				if binding.Resource.Kind != "session" {
					t.Fatalf("%s granted on %s, want session only", action, binding.Resource.Kind)
				}
				required[action] = true
			}
		}
	}
	for action, found := range required {
		if !found {
			t.Errorf("%s is missing from the owner session grant", action)
		}
	}
}

func TestOwnerBindingsReturnFreshSlices(t *testing.T) {
	first := OwnerBindings()
	expected := OwnerBindings()
	first[0].Resource.Kind = "invalid"
	first[0].Actions[0] = "unknown/action"
	if !reflect.DeepEqual(expected, OwnerBindings()) {
		t.Fatal("mutating a caller's bindings corrupted the canonical owner matrix")
	}
}
