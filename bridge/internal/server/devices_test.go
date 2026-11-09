package server

import (
	"bytes"
	"context"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"mime/multipart"
	"net/http"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/coder/websocket"

	"github.com/froid1911/whistle/bridge/internal/devauth"
)

// devicesOnly is a harness without the legacy authorizer ("devices" mode).
func devicesOnly(_ *Config, d *Deps) { d.Auth = nil }

type phone struct {
	key *ecdsa.PrivateKey
	id  string
}

func newKey(t *testing.T) *ecdsa.PrivateKey {
	t.Helper()
	k, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	return k
}

func spkiB64(k *ecdsa.PrivateKey) string {
	return base64.StdEncoding.EncodeToString(devauth.SPKI(&k.PublicKey))
}

func nonce() string {
	b := make([]byte, 16)
	rand.Read(b)
	return base64.RawURLEncoding.EncodeToString(b)
}

func postPair(t *testing.T, h *harness, token string, k *ecdsa.PrivateKey) (int, map[string]any) {
	t.Helper()
	body, _ := json.Marshal(map[string]string{"token": token, "device_name": "Pixel 8a", "public_key": spkiB64(k)})
	r, err := http.Post(h.hs.URL+"/v1/pair", "application/json", bytes.NewReader(body))
	if err != nil {
		t.Fatal(err)
	}
	defer r.Body.Close()
	var out map[string]any
	json.NewDecoder(r.Body).Decode(&out)
	return r.StatusCode, out
}

func errOf(m map[string]any) string {
	e, _ := m["error"].(map[string]any)
	c, _ := e["code"].(string)
	return c
}

// pairPhone pairs a fresh key and returns it.
func pairPhone(t *testing.T, h *harness) phone {
	t.Helper()
	p, err := h.srv.IssuePairingToken()
	if err != nil {
		t.Fatal(err)
	}
	k := newKey(t)
	code, out := postPair(t, h, p.Token, k)
	if code != 200 {
		t.Fatalf("pair: %d %v", code, out)
	}
	return phone{key: k, id: out["device_id"].(string)}
}

// sign sets the Authorization header of req (whose body is body).
func (p phone) sign(t *testing.T, req *http.Request, body []byte, ts int64, n string) {
	t.Helper()
	msg := devauth.Canonical(req.Method, req.URL.RequestURI(), req.Host, ts, n, body)
	sig, err := devauth.Sign(p.key, msg)
	if err != nil {
		t.Fatal(err)
	}
	req.Header.Set("Authorization", devauth.Header{Device: p.id, TS: ts, Nonce: n, Sig: sig}.String())
}

func (p phone) req(t *testing.T, h *harness, method, path string, body []byte) *http.Request {
	t.Helper()
	req, _ := http.NewRequest(method, h.hs.URL+path, bytes.NewReader(body))
	if body == nil {
		req, _ = http.NewRequest(method, h.hs.URL+path, nil)
	}
	p.sign(t, req, body, time.Now().Unix(), nonce())
	return req
}

func do(t *testing.T, req *http.Request) (int, map[string]any) {
	t.Helper()
	r, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	defer r.Body.Close()
	var out map[string]any
	json.NewDecoder(r.Body).Decode(&out)
	return r.StatusCode, out
}

func TestPairingTokenLifecycle(t *testing.T) {
	var mu sync.Mutex
	now := time.Now()
	clock := func() time.Time { mu.Lock(); defer mu.Unlock(); return now }
	advance := func(d time.Duration) { mu.Lock(); now = now.Add(d); mu.Unlock() }
	h := newHarness(t, func(c *Config, d *Deps) { devicesOnly(c, d); c.Now = clock; c.MachineName = "myserver" })

	p, _ := h.srv.IssuePairingToken()
	if len(p.Token) != 26 || !strings.Contains(p.URI, "t="+p.Token) {
		t.Fatalf("token %q uri %q", p.Token, p.URI)
	}
	k := newKey(t)
	code, out := postPair(t, h, p.Code, k) // grouped code works as well
	if code != 200 || out["server_id"] != h.st.ServerID() {
		t.Fatalf("pair: %d %v", code, out)
	}
	der, _ := base64.StdEncoding.DecodeString(out["bridge_public_key"].(string))
	if devauth.Fingerprint(der) != p.Fingerprint {
		t.Fatal("bridge key does not match the QR fingerprint")
	}
	if code, out = postPair(t, h, p.Token, newKey(t)); code != 403 || errOf(out) != "pairing_invalid" {
		t.Fatalf("reuse: %d %v", code, out)
	}

	p2, _ := h.srv.IssuePairingToken()
	advance(devauth.TokenTTL + time.Second)
	if code, out = postPair(t, h, p2.Token, newKey(t)); code != 403 {
		t.Fatalf("expired: %d %v", code, out)
	}
	advance(2 * time.Minute) // let the failures age out

	// 5 failures in a minute from one IP -> its further misses get 429; a
	// valid token still pairs (the owner cannot be locked out).
	p3, _ := h.srv.IssuePairingToken()
	for i := 0; i < devauth.MaxFailures; i++ {
		if code, _ = postPair(t, h, "AAAAAAAAAAAAAAAAAAAAAAAAAA", newKey(t)); code != 403 {
			t.Fatalf("bad token %d: %d", i, code)
		}
	}
	if code, out = postPair(t, h, "AAAAAAAAAAAAAAAAAAAAAAAAAA", newKey(t)); code != 429 || errOf(out) != "rate_limited" {
		t.Fatalf("rate limit: %d %v", code, out)
	}
	if code, _ = postPair(t, h, p3.Token, newKey(t)); code != 200 {
		t.Fatalf("valid token while limited: %d", code)
	}
	advance(devauth.FailureWindow + time.Second)
	if code, _ = postPair(t, h, "AAAAAAAAAAAAAAAAAAAAAAAAAA", newKey(t)); code != 403 {
		t.Fatalf("after window: %d", code)
	}
	ds, _ := h.st.Devices()
	if len(ds) != 2 || ds[0].Name != "Pixel 8a" {
		t.Fatalf("devices %v", ds)
	}
}

func TestPairRejectsBadKey(t *testing.T) {
	h := newHarness(t, devicesOnly)
	p, _ := h.srv.IssuePairingToken()
	body := `{"token":"` + p.Token + `","device_name":"x","public_key":"bm90IGEga2V5"}`
	r, _ := http.Post(h.hs.URL+"/v1/pair", "application/json", strings.NewReader(body))
	r.Body.Close()
	if r.StatusCode != 400 {
		t.Fatal(r.StatusCode)
	}
	// The token was not burned by the bad request.
	if code, _ := postPair(t, h, p.Token, newKey(t)); code != 200 {
		t.Fatal(code)
	}
}

func TestDevicesModeRefusesUnsigned(t *testing.T) {
	h := newHarness(t, devicesOnly)
	ph := pairPhone(t, h)
	r, _ := http.Get(h.hs.URL + "/v1/ws")
	var out map[string]any
	json.NewDecoder(r.Body).Decode(&out)
	r.Body.Close()
	if r.StatusCode != 401 || errOf(out) != "auth_required" {
		t.Fatalf("unsigned ws: %d %v", r.StatusCode, out)
	}
	// Health: minimal unsigned, full when signed.
	_, out = do(t, mustReq("GET", h.hs.URL+"/v1/health"))
	if _, leak := out["head_seq"]; leak {
		t.Fatalf("unsigned health: %v", out)
	}
	code, out := do(t, ph.req(t, h, "GET", "/v1/health", nil))
	if code != 200 || out["head_seq"] == nil || out["herdr_online"] != true {
		t.Fatalf("signed health: %d %v", code, out)
	}
}

func mustReq(method, url string) *http.Request {
	r, _ := http.NewRequest(method, url, nil)
	return r
}

func multipartBody(data string) ([]byte, string) {
	var buf bytes.Buffer
	mw := multipart.NewWriter(&buf)
	fw, _ := mw.CreateFormFile("file", "a.txt")
	io.WriteString(fw, data)
	mw.Close()
	return buf.Bytes(), mw.FormDataContentType()
}

func TestSignatureVerification(t *testing.T) {
	h := newHarness(t, devicesOnly)
	ph := pairPhone(t, h)
	body, ctype := multipartBody("hello")
	path := "/v1/upload?pane_id=w1:p1"
	mk := func() *http.Request {
		r, _ := http.NewRequest("POST", h.hs.URL+path, bytes.NewReader(body))
		r.Header.Set("Content-Type", ctype)
		return r
	}
	now := time.Now().Unix()

	r := mk()
	ph.sign(t, r, body, now, nonce())
	if code, out := do(t, r); code != 200 || out["path"] == nil {
		t.Fatalf("valid upload: %d %v", code, out)
	}

	cases := []struct {
		name string
		req  func() *http.Request
		want string
	}{
		{"wrong key", func() *http.Request {
			r := mk()
			phone{key: newKey(t), id: ph.id}.sign(t, r, body, now, nonce())
			return r
		}, "auth_bad_sig"},
		{"stale ts", func() *http.Request { r := mk(); ph.sign(t, r, body, now-61, nonce()); return r }, "auth_stale"},
		{"future ts", func() *http.Request { r := mk(); ph.sign(t, r, body, now+61, nonce()); return r }, "auth_stale"},
		{"body tamper", func() *http.Request {
			r := mk()
			ph.sign(t, r, append([]byte{}, body[:len(body)-1]...), now, nonce())
			return r
		}, "auth_bad_sig"},
		{"path tamper", func() *http.Request {
			r := mk()
			ph.sign(t, r, body, now, nonce())
			r.URL.RawQuery = "pane_id=w1:p2"
			return r
		}, "auth_bad_sig"},
		{"host tamper", func() *http.Request {
			r := mk()
			r.Host = "100.64.0.1:8787"
			ph.sign(t, r, body, now, nonce())
			r.Host = "" // sent with the real (allowed) Host
			return r
		}, "auth_bad_sig"},
		{"unknown device", func() *http.Request {
			r := mk()
			phone{key: ph.key, id: "dnope"}.sign(t, r, body, now, nonce())
			return r
		}, "device_unknown"},
		{"malformed", func() *http.Request {
			r := mk()
			r.Header.Set("Authorization", "Whistle v1 device="+ph.id)
			return r
		}, "auth_bad_sig"},
	}
	for _, c := range cases {
		code, out := do(t, c.req())
		if code != 401 || errOf(out) != c.want {
			t.Errorf("%s: %d %v, want 401 %s", c.name, code, out, c.want)
		}
	}

	// Replay: the identical request a second time.
	r = mk()
	ph.sign(t, r, body, now, nonce())
	auth := r.Header.Get("Authorization")
	if code, _ := do(t, r); code != 200 {
		t.Fatal(code)
	}
	r = mk()
	r.Header.Set("Authorization", auth)
	if code, out := do(t, r); code != 401 || errOf(out) != "auth_stale" {
		t.Fatalf("replay: %d %v", code, out)
	}
}

func TestSignedWebSocketWelcomeAndRevoke(t *testing.T) {
	h := newHarness(t, devicesOnly)
	ph := pairPhone(t, h)
	other := pairPhone(t, h)

	n := nonce()
	r := mustReq("GET", h.hs.URL+"/v1/ws")
	ph.sign(t, r, nil, time.Now().Unix(), n)
	c := h.dialHdr(http.Header{"Authorization": {r.Header.Get("Authorization")}})
	w := c.hello("", 0)
	if !devauth.Verify(&h.srv.cfg.Identity.PublicKey, devauth.WelcomeMessage(h.st.ServerID(), n), w.s("sig")) {
		t.Fatalf("welcome sig does not verify: %v", w)
	}
	r2 := mustReq("GET", h.hs.URL+"/v1/ws")
	other.sign(t, r2, nil, time.Now().Unix(), nonce())
	c2 := h.dialHdr(http.Header{"Authorization": {r2.Header.Get("Authorization")}})
	c2.hello("", 0)

	if err := h.srv.RevokeDevice(ph.id); err != nil {
		t.Fatal(err)
	}
	deadline := time.After(5 * time.Second)
	for {
		select {
		case <-c.in:
			continue
		case err := <-c.err:
			if websocket.CloseStatus(err) != closeRevoked {
				t.Fatalf("close: %v", err)
			}
		case <-deadline:
			t.Fatal("revoked socket still open")
		}
		break
	}
	// The other device stays connected.
	if m := c2.cmd("x", "chat.history", map[string]any{"session_key": "claude:s1"}); m["error"] != nil {
		t.Fatalf("other device: %v", m)
	}
	code, out := do(t, ph.req(t, h, "GET", "/v1/health", nil))
	if code != 200 || out["head_seq"] != nil {
		t.Fatalf("revoked health: %d %v", code, out)
	}
	code, out = do(t, ph.req(t, h, "GET", "/v1/ws", nil))
	if code != 401 || errOf(out) != "device_revoked" {
		t.Fatalf("revoked ws: %d %v", code, out)
	}
}

func TestLegacyModeAcceptsBoth(t *testing.T) {
	h := newHarness(t, nil) // AllowAll = legacy allowlist that admits the test client
	ph := pairPhone(t, h)
	c := h.dial()
	if w := c.hello("", 0); w.s("sig") != "" {
		t.Fatal("legacy connection must not get a welcome sig")
	}
	code, out := do(t, ph.req(t, h, "GET", "/v1/health", nil))
	if code != 200 || out["head_seq"] == nil {
		t.Fatalf("signed in legacy mode: %d %v", code, out)
	}
	// A bad signature is refused even though the legacy allowlist would pass.
	r := mustReq("GET", h.hs.URL+"/v1/ws")
	phone{key: newKey(t), id: ph.id}.sign(t, r, nil, time.Now().Unix(), nonce())
	if code, _ := do(t, r); code != 401 {
		t.Fatalf("bad sig in legacy mode: %d", code)
	}
}

func TestControlSocket(t *testing.T) {
	h := newHarness(t, devicesOnly)
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	path := filepath.Join(t.TempDir(), "b.ctl")
	done := make(chan error, 1)
	go func() { done <- h.srv.ServeControl(ctx, path) }()
	var resp ControlResponse
	var err error
	for i := 0; i < 50; i++ {
		if resp, err = CallControl(path, ControlRequest{Op: "pair"}); err == nil {
			break
		}
		time.Sleep(20 * time.Millisecond)
	}
	if err != nil || resp.Pairing == nil || len(resp.Pairing.Token) != 26 {
		t.Fatalf("pair: %v %v", resp, err)
	}
	k := newKey(t)
	code, out := postPair(t, h, resp.Pairing.Token, k)
	if code != 200 {
		t.Fatal(code, out)
	}
	id := out["device_id"].(string)
	resp, err = CallControl(path, ControlRequest{Op: "devices"})
	if err != nil || len(resp.Devices) != 1 || resp.Devices[0].ID != id || resp.Devices[0].LastSeen != 0 {
		t.Fatalf("devices: %+v %v", resp, err)
	}
	if _, err = CallControl(path, ControlRequest{Op: "unpair", ID: "dnope"}); err == nil {
		t.Fatal("unpair unknown must fail")
	}
	if _, err = CallControl(path, ControlRequest{Op: "unpair", ID: id}); err != nil {
		t.Fatal(err)
	}
	d, _ := h.st.Device(id)
	if d.RevokedAt == 0 {
		t.Fatal("not revoked")
	}
	cancel()
	if err := <-done; err != nil && !errors.Is(err, context.Canceled) {
		t.Fatal(err)
	}
}

func TestDeviceLastSeen(t *testing.T) {
	h := newHarness(t, devicesOnly)
	ph := pairPhone(t, h)
	do(t, ph.req(t, h, "GET", "/v1/health", nil))
	d, _ := h.st.Device(ph.id)
	if d.LastSeen == 0 {
		t.Fatal(fmt.Sprint("last_seen not set: ", d))
	}
}
