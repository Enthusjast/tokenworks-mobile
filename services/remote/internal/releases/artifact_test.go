package releases

import (
	"encoding/json"
	"testing"
)

func TestCatalogArtifactsExcludeDistributionPolicyAndOwnTheirValues(t *testing.T) {
	original, err := Parse(fixture(t))
	if err != nil {
		t.Fatal(err)
	}
	first, err := original.Artifacts()
	if err != nil || len(first) != 1 {
		t.Fatal(err)
	}
	digest, err := first[0].Digest()
	if err != nil {
		t.Fatal(err)
	}
	var config deployment
	if json.Unmarshal(fixture(t), &config) != nil {
		t.Fatal("fixture")
	}
	config.Publications[0].Release.ReleaseNotes = "updated release notes"
	config.Publications[0].Release.DownloadURL = "https://updates.example.invalid/moved.apk"
	*config.Publications[0].Release.RolloutBasisPoints = 0
	config.Publications[0].Organizations = []string{"22222222-2222-4222-8222-222222222222"}
	raw, _ := json.Marshal(config)
	changed, err := Parse(raw)
	if err != nil {
		t.Fatal(err)
	}
	artifacts, err := changed.Artifacts()
	if err != nil {
		t.Fatal(err)
	}
	got, err := artifacts[0].Digest()
	if err != nil || got != digest || artifacts[0] != first[0] {
		t.Fatal("distribution policy changed artifact identity", err)
	}
	artifacts[0].SHA256 = "changed"
	again, _ := changed.Artifacts()
	if again[0] != first[0] {
		t.Fatal("caller mutated catalog")
	}
	config.Publications = []publication{}
	raw, _ = json.Marshal(config)
	empty, err := Parse(raw)
	if err != nil {
		t.Fatal(err)
	}
	if artifacts, err = empty.Artifacts(); err != nil || len(artifacts) != 0 {
		t.Fatal("withdrawal catalog rejected", err)
	}
	for _, invalid := range []*Catalog{nil, {}} {
		if _, err = invalid.Artifacts(); err == nil {
			t.Fatal("unparsed catalog accepted")
		}
	}
}
