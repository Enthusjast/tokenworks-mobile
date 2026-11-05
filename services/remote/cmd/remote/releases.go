package main

import (
	"context"
	"errors"

	"tokenworks-mobile/services/remote/internal/releases"
	"tokenworks-mobile/services/remote/internal/store"
)

var errReleaseConfiguration = errors.New("remote service protected APK release configuration is invalid")

func loadAppReleases(path string) (*releases.Catalog, error) {
	if path == "" {
		return nil, nil
	}
	raw, err := readProtectedFile(path, 128<<10)
	if err != nil {
		return nil, errReleaseConfiguration
	}
	defer clear(raw)
	catalog, err := releases.Parse(raw)
	if err != nil {
		return nil, errReleaseConfiguration
	}
	return catalog, nil
}

// Parse and register before the catalog becomes visible to any API handler.
// Disabled publication does no registry I/O and never removes historical facts.
func loadRegisteredAppReleases(ctx context.Context, path string, database *store.Postgres, epoch string) (*releases.Catalog, error) {
	catalog, err := loadAppReleases(path)
	if err != nil || catalog == nil {
		return catalog, err
	}
	if err = database.RegisterAppReleaseCatalog(ctx, epoch, catalog); err != nil {
		return nil, errReleaseConfiguration
	}
	return catalog, nil
}
