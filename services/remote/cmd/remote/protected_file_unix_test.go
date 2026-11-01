//go:build unix

package main

import (
	"errors"
	"os"
	"path/filepath"
	"syscall"
	"testing"
	"time"
)

func TestProtectedConfigurationRejectsLinksAndTracksDescriptorMetadata(t *testing.T) {
	path := filepath.Join(t.TempDir(), "policy.json")
	if err := os.WriteFile(path, []byte(`{"v":1}`), 0600); err != nil {
		t.Fatal(err)
	}
	raw, err := readProtectedFile(path, 1024)
	if err != nil || string(raw) != `{"v":1}` {
		t.Fatal("private current-user config rejected", err)
	}
	before, err := os.Lstat(path)
	if err != nil {
		t.Fatal(err)
	}
	foreign := *before.Sys().(*syscall.Stat_t)
	foreign.Uid ^= 1
	if privateFileOwner(configOwnerInfo{FileInfo: before, metadata: &foreign}) {
		t.Fatal("foreign file ownership accepted")
	}
	if !sameProtectedFile(before, before, 1024) {
		t.Fatal("stable descriptor failed identity checks")
	}
	link := path + ".hard"
	if err := os.Link(path, link); err != nil {
		t.Fatal(err)
	}
	if _, err := readProtectedFile(path, 1024); !errors.Is(err, errFileConfiguration) {
		t.Fatal("hardlinked policy accepted", err)
	}
	if err := os.Remove(link); err != nil {
		t.Fatal(err)
	}
	if err := os.Symlink(path, link); err != nil {
		t.Fatal(err)
	}
	if _, err := readProtectedFile(link, 1024); !errors.Is(err, errFileConfiguration) {
		t.Fatal("symlink policy accepted", err)
	}
	if file, err := openProtectedFile(link); err == nil {
		file.Close()
		t.Fatal("descriptor open followed a replaced symlink")
	}
	if err := os.Chmod(path, 0644); err != nil {
		t.Fatal(err)
	}
	changed, err := os.Lstat(path)
	if err != nil {
		t.Fatal(err)
	}
	if sameProtectedFile(before, changed, 1024) {
		t.Fatal("exposed descriptor metadata was treated as stable")
	}
	if err := os.Chmod(path, 0600); err != nil {
		t.Fatal(err)
	}
	if err := os.Chtimes(path, time.Now(), before.ModTime().Add(time.Second)); err != nil {
		t.Fatal(err)
	}
	changed, err = os.Lstat(path)
	if err != nil {
		t.Fatal(err)
	}
	if sameProtectedFile(before, changed, 1024) {
		t.Fatal("in-place policy modification was ignored")
	}
	if err := os.Remove(path); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(path, []byte(`{"v":1}`), 0600); err != nil {
		t.Fatal(err)
	}
	changed, err = os.Lstat(path)
	if err != nil {
		t.Fatal(err)
	}
	if sameProtectedFile(before, changed, 1024) {
		t.Fatal("replacement inode accepted")
	}
}

type configOwnerInfo struct {
	os.FileInfo
	metadata *syscall.Stat_t
}

func (info configOwnerInfo) Sys() any { return info.metadata }

func TestProtectedConfigurationOpenDoesNotWaitOnAReplacedFIFO(t *testing.T) {
	path := filepath.Join(t.TempDir(), "substitute")
	if err := syscall.Mkfifo(path, 0600); err != nil {
		t.Fatal(err)
	}
	if _, err := readProtectedFile(path, 1024); !errors.Is(err, errFileConfiguration) {
		t.Fatal("FIFO accepted as policy", err)
	}
	// Exercise the open used after lstat independently: a replacement FIFO
	// must still return before any writer arrives, then fail descriptor checks.
	result := make(chan error, 1)
	go func() {
		file, err := openProtectedFile(path)
		if err == nil {
			defer file.Close()
			info, statErr := file.Stat()
			if statErr == nil && protectedFileShape(info, 1024) {
				err = errors.New("FIFO descriptor accepted as a regular private file")
			}
		}
		result <- err
	}()
	select {
	case err := <-result:
		if err != nil {
			t.Fatal(err)
		}
	case <-time.After(time.Second):
		t.Fatal("policy open waited for a FIFO writer")
	}
}
