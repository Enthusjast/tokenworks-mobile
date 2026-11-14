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
		Version string `json:"dshVersion"`
		Actions map[string]struct {
			Resources []string `json:"resources"`
			Selected bool `json:"selected"`
		} `json:"actions"`
	}
	if err := json.Unmarshal(data, &canonical); err != nil {
		t.Fatal(err)
	}
	if canonical.Version != "0.2.1-rc.5" {
		t.Fatalf("unknown native version: %s", canonical.Version)
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
	if selected != 38 {
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

func TestOwnerBindingsReturnFreshSlices(t *testing.T) {
	first := OwnerBindings()
	expected := OwnerBindings()
	first[0].Resource.Kind = "invalid"
	first[0].Actions[0] = "unknown/action"
	if !reflect.DeepEqual(expected, OwnerBindings()) {
		t.Fatal("mutating a caller's bindings corrupted the canonical owner matrix")
	}
}
