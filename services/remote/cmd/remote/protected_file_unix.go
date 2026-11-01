//go:build unix

package main

import (
	"os"
	"syscall"
)

func privateFileOwner(info os.FileInfo) bool {
	value, ok := info.Sys().(*syscall.Stat_t)
	return ok && value != nil && value.Uid == uint32(os.Geteuid()) && value.Nlink == 1
}

func openProtectedFile(path string) (*os.File, error) {
	// The flags close the leaf replacement races after lstat: a substituted
	// symlink is refused, and opening a FIFO cannot wait for another writer.
	return os.OpenFile(path, os.O_RDONLY|syscall.O_NOFOLLOW|syscall.O_NONBLOCK, 0)
}
