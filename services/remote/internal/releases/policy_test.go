package releases

import (
	"encoding/json"
	"slices"
	"testing"
)

func TestCatalogPolicyDigestBindsEffectivePolicyAndNormalizesSets(t *testing.T) {
	var config deployment
	if json.Unmarshal(fixture(t), &config) != nil {
		t.Fatal("fixture")
	}
	config.DownloadOrigins = append(config.DownloadOrigins, "https://other.example.invalid")
	config.Publications[0].Organizations = append(config.Publications[0].Organizations, "22222222-2222-4222-8222-222222222222")
	raw, _ := json.Marshal(config)
	first, err := Parse(raw)
	if err != nil {
		t.Fatal(err)
	}
	ref, _ := first.Policy()
	slices.Reverse(config.DownloadOrigins)
	slices.Reverse(config.Publications[0].Organizations)
	raw, _ = json.MarshalIndent(config, "", "  ")
	reordered, err := Parse(raw)
	if err != nil {
		t.Fatal(err)
	}
	got, _ := reordered.Policy()
	if got != ref || !ref.Valid() {
		t.Fatal("set ordering changed policy digest")
	}
	changes := []func(*deployment){
		func(c *deployment) { *c.Publications[0].Release.RolloutBasisPoints = 0 },
		func(c *deployment) { c.Publications[0].Release.ReleaseNotes = "corrected notes" },
		func(c *deployment) { c.Publications[0].Release.DownloadURL = "https://other.example.invalid/moved.apk" },
		func(c *deployment) { c.Publications[0].Organizations = c.Publications[0].Organizations[:1] },
		func(c *deployment) { c.ClientWindow.Maximum++ },
		func(c *deployment) { c.Publications = []publication{} },
	}
	for _, change := range changes {
		var candidate deployment
		_ = json.Unmarshal(raw, &candidate)
		change(&candidate)
		body, _ := json.Marshal(candidate)
		parsed, err := Parse(body)
		if err != nil {
			t.Fatal(err)
		}
		next, _ := parsed.Policy()
		if next == ref {
			t.Fatal("changed publication policy kept old digest")
		}
	}
	for _, invalid := range []*Catalog{nil, {}} {
		if _, err := invalid.Policy(); err == nil {
			t.Fatal("unparsed catalog accepted")
		}
	}
}
