package main

import (
	"os"
	"path/filepath"
	"testing"
)

func TestPushConfigurationAbsentOrUnsafeNeverEnablesProvider(t *testing.T) {
	service,err:=loadPush("",nil,nil)
	if err!=nil || service!=nil { t.Fatal("absent operator configuration enabled push") }
	path:=filepath.Join(t.TempDir(),"push.json")
	if err:=os.WriteFile(path,[]byte(`{"deployment_sha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","service_account_file":"account.json","token_encryption_key_file":"key"}`),0644);err!=nil { t.Fatal(err) }
	if service,err:=loadPush(path,nil,nil);err==nil || service!=nil { t.Fatal("unprotected credentials enabled provider") }
}
