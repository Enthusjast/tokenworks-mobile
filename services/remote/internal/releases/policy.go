package releases

import (
	"bytes"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"

	"dsh-mobile-ustc/services/remote/internal/canonical"
)

// PolicyRef identifies the entire effective publication policy, including empty
// catalogs, URLs, rollout, organizations, notes and client compatibility window.
type PolicyRef struct {
	PackageName string `json:"package_name"`
	SHA256      string `json:"policy_sha256"`
}

func ValidPackageName(name string) bool { return packageName.MatchString(name) && len(name) <= 255 }
func (ref PolicyRef) Valid() bool {
	return ValidPackageName(ref.PackageName) && digest.MatchString(ref.SHA256)
}
func (catalog *Catalog) Policy() (PolicyRef, error) {
	if catalog == nil || catalog.config.Package == "" || catalog.policySHA == [32]byte{} {
		return PolicyRef{}, ErrInvalid
	}
	return PolicyRef{catalog.config.Package, hex.EncodeToString(catalog.policySHA[:])}, nil
}
func (catalog *Catalog) policyDigest() ([32]byte, error) {
	var encoded bytes.Buffer
	encoder := json.NewEncoder(&encoded)
	encoder.SetEscapeHTML(false)
	if err := encoder.Encode(catalog.config); err != nil {
		return [32]byte{}, ErrInvalid
	}
	body, err := canonical.Object(encoded.Bytes())
	if err != nil {
		return [32]byte{}, ErrInvalid
	}
	return sha256.Sum256(append([]byte("dsh-app-release-policy/v1\x00"), body...)), nil
}
