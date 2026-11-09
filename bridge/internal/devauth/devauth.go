// Package devauth implements Whistle device pairing and request signing
// (docs/design/device-pairing.md):
// the canonical string and Authorization header, ECDSA P-256 signatures,
// the bridge identity key, one-time pairing tokens and the nonce cache.
package devauth

import (
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/sha256"
	"crypto/x509"
	"encoding/base64"
	"encoding/hex"
	"encoding/pem"
	"errors"
	"fmt"
	"net/url"
	"os"
	"path/filepath"
	"strconv"
	"strings"
)

// Scheme prefixes the Authorization header value (v1); SchemeV2 the v2 one.
const (
	Scheme   = "Whistle v1"
	SchemeV2 = "Whistle v2"
)

const (
	// MaxSkewSeconds is the allowed |now - ts|.
	MaxSkewSeconds = 60
	// NonceWindowSeconds is how long a seen nonce is remembered.
	NonceWindowSeconds = 120
)

var b64u = base64.RawURLEncoding

// BodyHash is sha256 hex of body (an empty body hashes the empty string).
func BodyHash(body []byte) string {
	h := sha256.Sum256(body)
	return hex.EncodeToString(h[:])
}

// Canonical is the string a device signs for one request.
func Canonical(method, pathAndQuery, host string, ts int64, nonce string, body []byte) string {
	return CanonicalHashed(method, pathAndQuery, host, ts, nonce, BodyHash(body))
}

// CanonicalHashed is Canonical with the body given as its sha256 hex.
func CanonicalHashed(method, pathAndQuery, host string, ts int64, nonce, bodySHA256 string) string {
	return "whistle-v1\n" + method + "\n" + pathAndQuery + "\n" + host + "\n" +
		strconv.FormatInt(ts, 10) + "\n" + nonce + "\n" + bodySHA256
}

// CanonicalV2 is the v2 string a device signs: the v1 fields under the
// "whistle-v2" prefix (body as its sha256 hex, as carried in the header's
// body=), then the server challenge and the device id.
func CanonicalV2(method, pathAndQuery, host string, ts int64, nonce, bodySHA256, challenge, device string) string {
	return "whistle-v2\n" + method + "\n" + pathAndQuery + "\n" + host + "\n" +
		strconv.FormatInt(ts, 10) + "\n" + nonce + "\n" + bodySHA256 + "\n" + challenge + "\n" + device
}

// WelcomeMessage is the string the bridge signs into the welcome frame: it
// binds the bridge identity to this server_id and the client's request nonce.
func WelcomeMessage(serverID, nonce string) string {
	return "whistle-welcome-v1\n" + serverID + "\n" + nonce
}

// Header is a parsed Authorization header. Version 0 means 1; Challenge and
// Body (sha256 hex of the body) are v2 only.
type Header struct {
	Version   int
	Device    string
	TS        int64
	Nonce     string
	Challenge string
	Body      string
	Sig       string
}

func (h Header) String() string {
	if h.Version == 2 {
		return fmt.Sprintf("%s device=%s, ts=%d, nonce=%s, challenge=%s, body=%s, sig=%s",
			SchemeV2, h.Device, h.TS, h.Nonce, h.Challenge, h.Body, h.Sig)
	}
	return fmt.Sprintf("%s device=%s, ts=%d, nonce=%s, sig=%s", Scheme, h.Device, h.TS, h.Nonce, h.Sig)
}

// IsWhistle reports whether an Authorization value uses this scheme.
func IsWhistle(v string) bool { return strings.HasPrefix(v, "Whistle ") }

// ParseHeader parses `Whistle v1 device=…, ts=…, nonce=…, sig=…` and
// `Whistle v2 device=…, ts=…, nonce=…, challenge=…, body=…, sig=…`.
func ParseHeader(v string) (Header, error) {
	var h Header
	rest, ok := strings.CutPrefix(v, Scheme+" ")
	h.Version = 1
	if !ok {
		if rest, ok = strings.CutPrefix(v, SchemeV2+" "); !ok {
			return h, errors.New("not a Whistle v1/v2 header")
		}
		h.Version = 2
	}
	seen := map[string]bool{}
	for _, part := range strings.Split(rest, ",") {
		k, val, ok := strings.Cut(strings.TrimSpace(part), "=")
		if !ok || seen[k] {
			return h, fmt.Errorf("bad header part %q", part)
		}
		seen[k] = true
		switch k {
		case "device":
			h.Device = val
		case "ts":
			n, err := strconv.ParseInt(val, 10, 64)
			if err != nil {
				return h, fmt.Errorf("bad ts: %w", err)
			}
			h.TS = n
		case "nonce":
			h.Nonce = val
		case "sig":
			h.Sig = val
		case "challenge", "body":
			if h.Version != 2 {
				return h, fmt.Errorf("field %q is v2 only", k)
			}
			if k == "challenge" {
				h.Challenge = val
			} else {
				h.Body = val
			}
		default:
			return h, fmt.Errorf("unknown header field %q", k)
		}
	}
	if h.Device == "" || h.Nonce == "" || h.Sig == "" || !seen["ts"] {
		return h, errors.New("missing header field")
	}
	if n, err := b64u.DecodeString(h.Nonce); err != nil || len(n) < 16 || len(n) > 32 {
		return h, errors.New("nonce must be 16-32 bytes base64url")
	}
	if h.Version == 2 {
		if c, err := b64u.DecodeString(h.Challenge); err != nil || len(c) != ChallengeBytes {
			return h, errors.New("challenge must be 16 bytes base64url")
		}
		if b, err := hex.DecodeString(h.Body); err != nil || len(b) != sha256.Size || strings.ToLower(h.Body) != h.Body {
			return h, errors.New("body must be lowercase sha256 hex")
		}
	}
	return h, nil
}

// Sign signs msg with ECDSA P-256/SHA-256; DER signature, base64url.
func Sign(key *ecdsa.PrivateKey, msg string) (string, error) {
	d := sha256.Sum256([]byte(msg))
	sig, err := ecdsa.SignASN1(rand.Reader, key, d[:])
	if err != nil {
		return "", err
	}
	return b64u.EncodeToString(sig), nil
}

// Verify checks a base64url DER signature of msg.
func Verify(pub *ecdsa.PublicKey, msg, sig string) bool {
	b, err := b64u.DecodeString(sig)
	if err != nil {
		return false
	}
	d := sha256.Sum256([]byte(msg))
	return ecdsa.VerifyASN1(pub, d[:], b)
}

// ParsePublicKey decodes a standard-base64 SPKI (X.509 SubjectPublicKeyInfo)
// P-256 public key.
func ParsePublicKey(spkiB64 string) (*ecdsa.PublicKey, error) {
	der, err := base64.StdEncoding.DecodeString(spkiB64)
	if err != nil {
		return nil, fmt.Errorf("public_key: %w", err)
	}
	k, err := x509.ParsePKIXPublicKey(der)
	if err != nil {
		return nil, fmt.Errorf("public_key: %w", err)
	}
	ec, ok := k.(*ecdsa.PublicKey)
	if !ok || ec.Curve != elliptic.P256() {
		return nil, errors.New("public_key: want an EC P-256 key")
	}
	return ec, nil
}

// SPKI is the DER SubjectPublicKeyInfo of pub.
func SPKI(pub *ecdsa.PublicKey) []byte {
	b, err := x509.MarshalPKIXPublicKey(pub)
	if err != nil {
		panic(err) // P-256 keys always marshal
	}
	return b
}

// Fingerprint is base64url(sha256(SPKI DER)), as carried in the pairing QR.
func Fingerprint(spki []byte) string {
	h := sha256.Sum256(spki)
	return b64u.EncodeToString(h[:])
}

// LoadOrCreateKey reads the bridge identity key (PKCS#8 PEM) from path,
// creating it (0600, directory 0700) when missing. An existing key readable
// or writable by group/other is refused: it may have leaked.
func LoadOrCreateKey(path string) (*ecdsa.PrivateKey, error) {
	if st, err := os.Stat(path); err == nil && st.Mode().Perm()&0o077 != 0 {
		return nil, fmt.Errorf("%s has mode %04o: the bridge key must be private (chmod 600 %s; if others could read it, delete it and pair your devices again)",
			path, st.Mode().Perm(), path)
	}
	b, err := os.ReadFile(path)
	if errors.Is(err, os.ErrNotExist) {
		key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
		if err != nil {
			return nil, err
		}
		der, err := x509.MarshalPKCS8PrivateKey(key)
		if err != nil {
			return nil, err
		}
		if err := os.MkdirAll(filepath.Dir(path), 0o700); err != nil {
			return nil, err
		}
		f, err := os.OpenFile(path, os.O_WRONLY|os.O_CREATE|os.O_EXCL, 0o600)
		if err != nil {
			if errors.Is(err, os.ErrExist) { // lost a creation race: use the winner's key
				return LoadOrCreateKey(path)
			}
			return nil, err
		}
		if err := pem.Encode(f, &pem.Block{Type: "PRIVATE KEY", Bytes: der}); err != nil {
			f.Close()
			return nil, err
		}
		return key, f.Close()
	}
	if err != nil {
		return nil, err
	}
	blk, _ := pem.Decode(b)
	if blk == nil {
		return nil, fmt.Errorf("%s: no PEM block", path)
	}
	k, err := x509.ParsePKCS8PrivateKey(blk.Bytes)
	if err != nil {
		return nil, fmt.Errorf("%s: %w", path, err)
	}
	ec, ok := k.(*ecdsa.PrivateKey)
	if !ok || ec.Curve != elliptic.P256() {
		return nil, fmt.Errorf("%s: not an EC P-256 key", path)
	}
	return ec, nil
}

// PairURI is the QR payload. It is kept as short as the app accepts (the
// QR must fit small popups): no machine name (the app falls back to the
// address) and a bare ip:port when that needs no escaping.
func PairURI(addr, token, fingerprint string) string {
	if strings.Trim(addr, "0123456789.:") != "" {
		addr = url.QueryEscape(addr)
	}
	return "whistle://pair?v=1&u=" + addr + "&t=" + token + "&f=" + fingerprint
}

// GroupCode formats a token for reading aloud/typing: groups of 4.
func GroupCode(token string) string {
	var b strings.Builder
	for i, r := range token {
		if i > 0 && i%4 == 0 {
			b.WriteByte('-')
		}
		b.WriteRune(r)
	}
	return b.String()
}

// NormalizeCode undoes GroupCode and user typing (spaces, dashes, case).
func NormalizeCode(s string) string {
	var b strings.Builder
	for _, r := range strings.ToUpper(s) {
		if (r >= 'A' && r <= 'Z') || (r >= '2' && r <= '7') {
			b.WriteRune(r)
		}
	}
	return b.String()
}
