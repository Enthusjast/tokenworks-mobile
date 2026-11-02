package main

import (
	"context"
	"encoding/json"
	"errors"
	"flag"
	"io"
	"path/filepath"
	"time"

	"dsh-mobile-ustc/services/remote/internal/files"
)

var errFileCheckArguments = errors.New("remote files check requires --config with an absolute protected configuration path and an optional --timeout between 1s and 30s")
var errFileCheckScanner = errors.New("remote file scanner self-test is unavailable; no file service readiness was granted")

func runFileCommand(arguments []string, output io.Writer) error {
	if len(arguments) == 0 || arguments[0] != "check" {
		return errFileCheckArguments
	}
	flags := flag.NewFlagSet("files check", flag.ContinueOnError)
	flags.SetOutput(io.Discard)
	path := flags.String("config", "", "absolute protected file deployment configuration")
	timeout := flags.Duration("timeout", 10*time.Second, "overall scanner self-test timeout")
	if flags.Parse(arguments[1:]) != nil || flags.NArg() != 0 || !filepath.IsAbs(*path) || *timeout < time.Second || *timeout > 30*time.Second {
		return errFileCheckArguments
	}
	dependencies, err := loadFileDependencies(*path)
	if err != nil {
		return err
	}
	defer dependencies.storage.Close()
	ctx, cancel := context.WithTimeout(context.Background(), *timeout)
	defer cancel()
	if files.ProbeScanner(ctx, dependencies.scanner) != nil {
		return errFileCheckScanner
	}
	result := struct {
		Format             string `json:"format"`
		ConfigurationValid bool   `json:"configuration_valid"`
		ScannerSelfTest    string `json:"scanner_self_test"`
		PolicySHA256       string `json:"policy_sha256"`
		ExportsEnabled     bool   `json:"exports_enabled"`
		StorageCheck       string `json:"storage_check"`
	}{"dsh-remote-file-check/v1", true, "passed", dependencies.serviceConfig.PolicySHA256, dependencies.exportsEnabled, "configuration_only"}
	if json.NewEncoder(output).Encode(result) != nil {
		return errors.New("remote file dependency check result output failed")
	}
	return nil
}
