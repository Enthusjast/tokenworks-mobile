package releases

import (
	"crypto/sha256"
	"encoding/json"

	"tokenworks-mobile/services/remote/internal/canonical"
)

// Artifact fixes the identity and compatibility metadata of one APK. Distribution
// policy (URL, notes, organizations, rollout and client window) is deliberately
// excluded, so an unchanged artifact can be moved, withdrawn or rolled out.
type Artifact struct {
	ID                     string `json:"release_id"`
	PackageName            string `json:"package_name"`
	VersionCode            int64  `json:"version_code"`
	VersionName            string `json:"version_name"`
	SHA256                 string `json:"sha256"`
	SizeBytes              int64  `json:"size_bytes"`
	SigningCertificate     string `json:"signing_cert_sha256"`
	MinimumProtocolVersion int64  `json:"minimum_protocol_version"`
	MaximumProtocolVersion int64  `json:"maximum_protocol_version"`
	MinimumAndroidAPI      int64  `json:"minimum_android_api"`
}

// Artifacts returns independent values from a parsed catalog, including entries
// with zero rollout. An empty publication list is a valid withdrawal snapshot.
func (catalog *Catalog) Artifacts() ([]Artifact, error) {
	if catalog == nil || catalog.config.Package == "" {
		return nil, ErrInvalid
	}
	result := make([]Artifact, 0, len(catalog.config.Publications))
	for _, publication := range catalog.config.Publications {
		r := publication.Release
		result = append(result, Artifact{ID: r.ID, PackageName: r.PackageName, VersionCode: r.VersionCode, VersionName: r.VersionName, SHA256: r.SHA256, SizeBytes: r.SizeBytes,
			SigningCertificate: r.SigningCertificate, MinimumProtocolVersion: r.MinimumProtocolVersion, MaximumProtocolVersion: r.MaximumProtocolVersion, MinimumAndroidAPI: r.MinimumAndroidAPI})
	}
	return result, nil
}

// Digest is domain-separated RFC 8785 SHA-256 over the immutable artifact.
func (artifact Artifact) Digest() ([32]byte, error) {
	raw, err := json.Marshal(struct {
		Format   string   `json:"format"`
		Artifact Artifact `json:"artifact"`
	}{"tokenworks-mobile-app-release-artifact/v1", artifact})
	if err != nil {
		return [32]byte{}, ErrInvalid
	}
	body, err := canonical.Object(raw)
	if err != nil {
		return [32]byte{}, ErrInvalid
	}
	return sha256.Sum256(body), nil
}
