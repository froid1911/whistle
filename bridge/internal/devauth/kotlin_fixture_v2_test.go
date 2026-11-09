package devauth

import (
	"encoding/json"
	"os"
	"path/filepath"
	"testing"
)

// TestKotlinFixtureV2 verifies a v2 request signed by the Android app
// (WhistleAuthTest.writesKotlinFixture, SoftwareDeviceKeys) with this side's
// canonicalization, header parser and verifier.
func TestKotlinFixtureV2(t *testing.T) {
	b, err := os.ReadFile(filepath.Join("testdata", "whistle_fixture_kotlin_v2.json"))
	if err != nil {
		t.Fatal(err)
	}
	var f fixtureV2
	if err := json.Unmarshal(b, &f); err != nil {
		t.Fatal(err)
	}
	pub, err := ParsePublicKey(f.PublicKey)
	if err != nil {
		t.Fatal(err)
	}
	if got := BodyHash([]byte(f.Body)); got != f.BodySHA256 {
		t.Fatalf("body hash %s != %s", got, f.BodySHA256)
	}
	if got := CanonicalV2(f.Method, f.Path, f.Host, f.TS, f.Nonce, f.BodySHA256, f.Challenge, f.DeviceID); got != f.Canonical {
		t.Fatalf("canonical mismatch:\n%q\n%q", got, f.Canonical)
	}
	if !Verify(pub, f.Canonical, f.Sig) {
		t.Fatal("signature does not verify")
	}
	h, err := ParseHeader(f.Authorization)
	if err != nil || h.Version != 2 || h.Device != f.DeviceID || h.TS != f.TS || h.Nonce != f.Nonce ||
		h.Challenge != f.Challenge || h.Body != f.BodySHA256 || h.Sig != f.Sig {
		t.Fatalf("header %+v %v", h, err)
	}
	if h.String() != f.Authorization {
		t.Fatalf("header round trip %q != %q", h.String(), f.Authorization)
	}
	// The device id and the challenge are covered by the signature.
	if Verify(pub, CanonicalV2(f.Method, f.Path, f.Host, f.TS, f.Nonce, f.BodySHA256, f.Challenge, "dother"), f.Sig) {
		t.Fatal("device id not signed")
	}
}
