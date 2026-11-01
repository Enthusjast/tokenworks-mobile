//go:build !unix

package main

import (
	"os"
)

// No private-configuration guarantee is claimed without the platform checks.
func privateFileOwner(os.FileInfo) bool          { return false }
func openProtectedFile(string) (*os.File, error) { return nil, errFileConfiguration }
