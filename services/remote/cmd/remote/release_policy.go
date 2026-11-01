package main

import (
	"context"
	"encoding/json"
	"errors"
	"flag"
	"io"
	"os"
	"time"

	"dsh-mobile-ustc/services/remote/internal/releases"
	"dsh-mobile-ustc/services/remote/internal/store"
)

func runAppReleaseCommand(args []string, output io.Writer) error {
	if len(args) == 0 || args[0] != "describe" && args[0] != "status" && args[0] != "replace" {
		return errReleaseConfiguration
	}
	command := args[0]
	flags := flag.NewFlagSet("app-releases "+command, flag.ContinueOnError)
	flags.SetOutput(io.Discard)
	var path, id, expected, name *string
	if command == "status" {
		name = flags.String("package-name", "", "application package")
	} else {
		path = flags.String("catalog-file", "", "protected publication catalog")
	}
	if command == "replace" {
		id = flags.String("operation-id", "", "stable operation UUID")
		expected = flags.String("expected-sha256", "", "current policy digest")
	}
	if flags.Parse(args[1:]) != nil || flags.NArg() != 0 {
		return errReleaseConfiguration
	}
	var catalog *releases.Catalog
	var ref releases.PolicyRef
	var err error
	if command != "status" {
		if *path == "" {
			return errReleaseConfiguration
		}
		catalog, err = loadAppReleases(*path)
		if err != nil {
			return errReleaseConfiguration
		}
		ref, err = catalog.Policy()
		if err != nil {
			return errReleaseConfiguration
		}
	} else {
		ref.PackageName = *name
		if !releases.ValidPackageName(ref.PackageName) {
			return errReleaseConfiguration
		}
	}
	if command == "describe" {
		return writeAppReleaseResult(output, struct {
			Format string `json:"format"`
			releases.PolicyRef
		}{"dsh-app-release-policy/v1", ref})
	}
	epoch := os.Getenv("REMOTE_DATABASE_EPOCH")
	if !commandRateEpochPattern.MatchString(epoch) || command == "replace" && !store.ValidAppReleasePolicyChange(epoch, *id, *expected, catalog) {
		return errReleaseConfiguration
	}
	ctx, cancel := context.WithTimeout(context.Background(), 8*time.Second)
	defer cancel()
	database, err := store.Open(ctx, os.Getenv("REMOTE_DATABASE_URL"))
	if err != nil {
		return errors.New("remote APK release policy database is unavailable or insecurely configured")
	}
	defer database.Close()
	var result any
	if command == "status" {
		result, err = database.AppReleasePolicyStatus(ctx, epoch, ref.PackageName)
	} else {
		result, err = database.ReplaceAppReleasePolicy(ctx, epoch, *id, *expected, catalog)
	}
	if err != nil {
		return err
	}
	return writeAppReleaseResult(output, result)
}
func writeAppReleaseResult(output io.Writer, result any) error {
	if json.NewEncoder(output).Encode(result) != nil {
		return errors.New("remote APK release policy output failed; reconcile changes with the same operation UUID and parameters")
	}
	return nil
}
