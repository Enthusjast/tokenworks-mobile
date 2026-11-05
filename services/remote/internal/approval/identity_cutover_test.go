package approval

import (
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"encoding/base64"
	"encoding/json"
	"strings"
	"testing"

	"tokenworks-mobile/services/remote/internal/auth"
	"tokenworks-mobile/services/remote/internal/canonical"
	jose "github.com/go-jose/go-jose/v4"
)

func TestTokenWorksApprovalRejectsCorrectlySignedLegacyIdentity(t *testing.T) {
	data, registered, now := loadFixture(t)
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	registered.PublicJWK, err = json.Marshal(jose.JSONWebKey{Key: &key.PublicKey})
	if err != nil {
		t.Fatal(err)
	}
	registered.ApprovalKeyJKT, err = auth.PublicP256JWKThumbprintJSON(registered.PublicJWK)
	if err != nil {
		t.Fatal(err)
	}
	raw, err := base64.RawURLEncoding.DecodeString(strings.Split(data.Vectors[0].Compact, ".")[1])
	if err != nil {
		t.Fatal(err)
	}
	var decision Decision
	if err := json.Unmarshal(raw, &decision); err != nil {
		t.Fatal(err)
	}
	sign := func(value Decision, typ, kid string) string {
		t.Helper()
		raw, err := json.Marshal(value)
		if err != nil {
			t.Fatal(err)
		}
		body, err := canonical.Object(raw)
		if err != nil {
			t.Fatal(err)
		}
		signer, err := jose.NewSigner(jose.SigningKey{Algorithm: jose.ES256, Key: jose.JSONWebKey{Key: key, KeyID: kid}}, (&jose.SignerOptions{}).WithType(jose.ContentType(typ)))
		if err != nil {
			t.Fatal(err)
		}
		signed, err := signer.Sign(body)
		if err != nil {
			t.Fatal(err)
		}
		compact, err := signed.CompactSerialize()
		if err != nil {
			t.Fatal(err)
		}
		return compact
	}
	kid := KeyID(registered.DeviceID, registered.KeyVersion)
	if _, err := VerifyDecision(sign(decision, JWSType, kid), registered, data.Context, now); err != nil {
		t.Fatal("new identity signature rejected", err)
	}
	legacyDomain := decision
	legacyDomain.Domain = "dsh-mobile-ustc/approval-decision/v1"
	for _, compact := range []string{
		sign(legacyDomain, JWSType, kid),
		sign(decision, "dsh-approval-decision+jws", kid),
		sign(decision, JWSType, strings.Replace(kid, "tokenworks-mobile-approval/", "dsh-approval/", 1)),
	} {
		if _, err := VerifyDecision(compact, registered, data.Context, now); err == nil {
			t.Fatal("correctly signed legacy approval identity accepted")
		}
	}
	legacyContext := strings.Replace(string(data.Context), ContextDomain, "dsh-mobile-ustc/approval-context/v1", 1)
	if _, err := ContextBinding([]byte(legacyContext)); err == nil {
		t.Fatal("legacy context domain accepted")
	}
}
