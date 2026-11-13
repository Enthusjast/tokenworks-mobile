package main

import (
	"bytes"
	"encoding/json"
	"errors"
	"io"
	"os"
	"path/filepath"

	"tokenworks-mobile/services/remote/internal/auth"
)

var errFileConfiguration = errors.New("remote service protected file configuration is invalid")

func readProtectedFile(path string, limit int64) (body []byte, resultErr error) {
	info, err := os.Lstat(path)
	if err != nil || !protectedFileShape(info, limit) {
		return nil, errFileConfiguration
	}
	file, err := openProtectedFile(path)
	if err != nil {
		return nil, errFileConfiguration
	}
	defer func() {
		if err := file.Close(); err != nil {
			clear(body)
			body = nil
			resultErr = errFileConfiguration
		}
	}()
	actual, err := file.Stat()
	if err != nil || !sameProtectedFile(info, actual, limit) {
		return nil, errFileConfiguration
	}
	body, err = io.ReadAll(io.LimitReader(file, limit+1))
	if err != nil || int64(len(body)) != info.Size() || int64(len(body)) > limit {
		clear(body)
		return nil, errFileConfiguration
	}
	after, err := file.Stat()
	if err != nil || !sameProtectedFile(info, after, limit) {
		clear(body)
		return nil, errFileConfiguration
	}
	current, err := os.Lstat(path)
	if err != nil || !sameProtectedFile(info, current, limit) {
		clear(body)
		return nil, errFileConfiguration
	}
	return body, nil
}
func strictFileJSON(body []byte, target any) error {
	if auth.ValidateJSONObject(body) != nil {
		return errFileConfiguration
	}
	decoder := json.NewDecoder(bytes.NewReader(body))
	decoder.DisallowUnknownFields()
	if decoder.Decode(target) != nil {
		return errFileConfiguration
	}
	var trailing any
	if !errors.Is(decoder.Decode(&trailing), io.EOF) {
		return errFileConfiguration
	}
	if !exactConfigFieldNames(body, target) {
		return errFileConfiguration
	}
	return nil
}
func relativeFile(base, path string) string {
	if path == "" {
		return ""
	}
	if filepath.IsAbs(path) {
		return path
	}
	return filepath.Join(filepath.Dir(base), path)
}
