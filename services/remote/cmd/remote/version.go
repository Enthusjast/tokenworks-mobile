package main

import (
	"encoding/json"
	"errors"
	"io"
	"regexp"
	"runtime"
)

// Set only by the release builder. Ordinary builds remain explicitly unversioned.
// Deployment environment and secrets cannot supply these values.
var buildRevision string
var buildSourceSHA256 string

type serviceVersion struct {
	Format       string `json:"format"`
	Go           string `json:"go"`
	Platform     string `json:"platform"`
	Revision     string `json:"revision"`
	SourceSHA256 string `json:"source_sha256"`
}

func runVersionCommand(arguments []string, output io.Writer) error {
	if len(arguments) != 0 {
		return errors.New("remote version accepts no arguments")
	}
	if (buildRevision == "") != (buildSourceSHA256 == "") ||
		(buildRevision != "" && (!regexp.MustCompile(`^(?:[0-9a-f]{40}|[0-9a-f]{64})$`).MatchString(buildRevision) ||
			!regexp.MustCompile(`^[0-9a-f]{64}$`).MatchString(buildSourceSHA256))) {
		return errors.New("remote build metadata is invalid")
	}
	return json.NewEncoder(output).Encode(serviceVersion{
		Format: "tokenworks-mobile-service-version/v1", Go: runtime.Version(), Platform: runtime.GOOS + "/" + runtime.GOARCH,
		Revision: buildRevision, SourceSHA256: buildSourceSHA256,
	})
}
