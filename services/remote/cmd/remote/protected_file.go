package main

import "os"

func protectedFileShape(info os.FileInfo, limit int64) bool {
	return info != nil && info.Mode().IsRegular() && info.Mode().Perm()&0077 == 0 && info.Size() > 0 && info.Size() <= limit && privateFileOwner(info)
}

func sameProtectedFile(before, current os.FileInfo, limit int64) bool {
	return protectedFileShape(before, limit) && protectedFileShape(current, limit) && os.SameFile(before, current) &&
		before.Mode() == current.Mode() && before.Size() == current.Size() && before.ModTime().Equal(current.ModTime())
}
