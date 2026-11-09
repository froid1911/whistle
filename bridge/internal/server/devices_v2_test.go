package server

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"os"
	"path/filepath"
	"sync"
	"testing"
	"time"

	"github.com/froid1911/whistle/bridge/internal/devauth"
)

// challenge fetches a v2 server challenge for device id.
func challenge(t *testing.T, h *harness, id string) string {
	t.Helper()
	code, out := do(t, mustReq("GET", h.hs.URL+"/v1/challenge?device="+id))
	ch, _ := out["challenge"].(string)
	if code != 200 || ch == "" || out["expires_in"] != float64(30) {
		t.Fatalf("challenge: %d %v", code, out)
	}
	return ch
}

// signV2 sets a v2 Authorization header; bodyHash is what the header
// declares (and the signature covers).
func (p phone) signV2(t *testing.T, req *http.Request, bodyHash, ch string, ts int64) {
	t.Helper()
	n := nonce()
	msg := devauth.CanonicalV2(req.Method, req.URL.RequestURI(), req.Host, ts, n, bodyHash, ch, p.id)
	sig, err := devauth.Sign(p.key, msg)
	if err != nil {
		t.Fatal(err)
	}
	req.Header.Set("Authorization", devauth.Header{Version: 2, Device: p.id, TS: ts, Nonce: n,
		Challenge: ch, Body: bodyHash, Sig: sig}.String())
}

type clock struct {
	mu  sync.Mutex
	now time.Time
}

func (c *clock) Now() time.Time      { c.mu.Lock(); defer c.mu.Unlock(); return c.now }
func (c *clock) add(d time.Duration) { c.mu.Lock(); c.now = c.now.Add(d); c.mu.Unlock() }
func newClock() *clock               { return &clock{now: time.Now()} }
func withClock(c *clock) func(*Config, *Deps) {
	return func(cf *Config, d *Deps) { devicesOnly(cf, d); cf.Now = c.Now }
}

func TestV2ChallengeAuth(t *testing.T) {
	clk := newClock()
	h := newHarness(t, withClock(clk))
	ph := pairPhone(t, h)
	other := pairPhone(t, h)
	empty := devauth.BodyHash(nil)

	r := mustReq("GET", h.hs.URL+"/v1/health")
	ph.signV2(t, r, empty, challenge(t, h, ph.id), clk.Now().Unix())
	auth := r.Header.Get("Authorization")
	if code, out := do(t, r); code != 200 || out["head_seq"] == nil {
		t.Fatalf("v2 health: %d %v", code, out)
	}

	// Replay of the identical request: the challenge is used up.
	r = mustReq("GET", h.hs.URL+"/v1/health")
	r.Header.Set("Authorization", auth)
	if code, out := do(t, r); code != 200 || out["head_seq"] != nil {
		t.Fatalf("replay must not authenticate (health answers minimal): %d %v", code, out)
	}
	// Reusing the challenge with a fresh nonce and signature: refused too.
	ch := challenge(t, h, ph.id)
	r = mustReq("GET", h.hs.URL+"/v1/health")
	ph.signV2(t, r, empty, ch, clk.Now().Unix())
	if code, out := do(t, r); code != 200 || out["head_seq"] == nil {
		t.Fatalf("first use: %d %v", code, out)
	}
	r = mustReq("GET", h.hs.URL+"/v1/ws")
	ph.signV2(t, r, empty, ch, clk.Now().Unix())
	if code, out := do(t, r); code != 401 || errOf(out) != "auth_stale" {
		t.Fatalf("challenge reuse: %d %v", code, out)
	}

	// A challenge issued for another device.
	r = mustReq("GET", h.hs.URL+"/v1/upload?pane_id=x")
	ph.signV2(t, r, empty, challenge(t, h, other.id), clk.Now().Unix())
	if code, out := do(t, r); code != 401 || errOf(out) != "auth_stale" {
		t.Fatalf("other device's challenge: %d %v", code, out)
	}

	// Expired.
	ch = challenge(t, h, ph.id)
	clk.add(devauth.ChallengeTTL + time.Second)
	r = mustReq("POST", h.hs.URL+"/v1/upload?pane_id=x")
	ph.signV2(t, r, empty, ch, clk.Now().Unix())
	if code, out := do(t, r); code != 401 || errOf(out) != "auth_stale" {
		t.Fatalf("expired challenge: %d %v", code, out)
	}

	// Tampered device id in the header (signed by ph for its own id).
	r = mustReq("GET", h.hs.URL+"/v1/ws")
	ph.signV2(t, r, empty, challenge(t, h, other.id), clk.Now().Unix())
	hd, _ := devauth.ParseHeader(r.Header.Get("Authorization"))
	hd.Device = other.id
	r.Header.Set("Authorization", hd.String())
	if code, out := do(t, r); code != 401 || errOf(out) != "auth_bad_sig" {
		t.Fatalf("device swap: %d %v", code, out)
	}
}

func TestV2UploadBodyVerifiedAfterSignature(t *testing.T) {
	h := newHarness(t, devicesOnly)
	ph := pairPhone(t, h)
	body, ctype := multipartBody("hello v2")
	mk := func(b []byte) *http.Request {
		r, _ := http.NewRequest("POST", h.hs.URL+"/v1/upload?pane_id=w1:p1", bytes.NewReader(b))
		r.Header.Set("Content-Type", ctype)
		return r
	}
	r := mk(body)
	ph.signV2(t, r, devauth.BodyHash(body), challenge(t, h, ph.id), time.Now().Unix())
	code, out := do(t, r)
	if code != 200 || out["path"] == nil {
		t.Fatalf("v2 upload: %d %v", code, out)
	}
	if b, _ := os.ReadFile(out["path"].(string)); string(b) != "hello v2" {
		t.Fatalf("saved %q", b)
	}
	// The body differs from the signed hash.
	other, _ := multipartBody("evil")
	r = mk(other)
	ph.signV2(t, r, devauth.BodyHash(body), challenge(t, h, ph.id), time.Now().Unix())
	if code, out := do(t, r); code != 401 || errOf(out) != "auth_bad_sig" {
		t.Fatalf("body mismatch: %d %v", code, out)
	}
	// No temp body is left behind.
	if es, _ := os.ReadDir(filepath.Join(h.cfg.UploadDir, ".incoming")); len(es) != 0 {
		t.Fatalf("spool not cleaned: %v", es)
	}
	// Too many bodies in flight for this device -> 429 busy.
	h.srv.dev.mu.Lock()
	h.srv.dev.spooling[ph.id] = maxSpoolPerDevice
	h.srv.dev.mu.Unlock()
	r = mk(body)
	ph.signV2(t, r, devauth.BodyHash(body), challenge(t, h, ph.id), time.Now().Unix())
	if code, out := do(t, r); code != 429 || errOf(out) != "busy" {
		t.Fatalf("spool cap: %d %v", code, out)
	}
}

func TestRejectV1(t *testing.T) {
	h := newHarness(t, func(c *Config, d *Deps) { devicesOnly(c, d); c.RejectV1 = true })
	ph := pairPhone(t, h)
	if code, out := do(t, ph.req(t, h, "GET", "/v1/health", nil)); code != 200 || out["head_seq"] != nil {
		t.Fatalf("v1 health must be minimal: %d %v", code, out)
	}
	if code, out := do(t, ph.req(t, h, "GET", "/v1/ws", nil)); code != 401 || errOf(out) != "auth_v2_required" {
		t.Fatalf("v1 refused: %d %v", code, out)
	}
	r := mustReq("GET", h.hs.URL+"/v1/health")
	ph.signV2(t, r, devauth.BodyHash(nil), challenge(t, h, ph.id), time.Now().Unix())
	if code, out := do(t, r); code != 200 || out["head_seq"] == nil {
		t.Fatalf("v2: %d %v", code, out)
	}
}

func TestChallengeRateLimitAndGuards(t *testing.T) {
	h := newHarness(t, devicesOnly)
	for i := 0; i < challengesPerMinute; i++ {
		challenge(t, h, "dx")
	}
	if code, out := do(t, mustReq("GET", h.hs.URL+"/v1/challenge?device=dx")); code != 429 || errOf(out) != "rate_limited" {
		t.Fatalf("rate limit: %d %v", code, out)
	}
	r := mustReq("GET", h.hs.URL+"/v1/challenge?device=dx")
	r.Header.Set("Origin", "http://evil")
	if code, _ := do(t, r); code != 403 {
		t.Fatalf("origin guard: %d", code)
	}
	h2 := newHarness(t, devicesOnly)
	if code, _ := do(t, mustReq("GET", h2.hs.URL+"/v1/challenge")); code != 400 {
		t.Fatalf("device required: %d", code)
	}
}

func TestRegisterConnAfterRevoke(t *testing.T) {
	h := newHarness(t, devicesOnly)
	ph := pairPhone(t, h)
	c := &conn{device: ph.id}
	if err := h.srv.RevokeDevice(ph.id); err != nil {
		t.Fatal(err)
	}
	if h.srv.registerConn(c) {
		t.Fatal("a connection of a just-revoked device was registered")
	}
	if !h.srv.registerConn(&conn{}) {
		t.Fatal("legacy connections always register")
	}
}

func TestPairDuplicateKeyAndName(t *testing.T) {
	h := newHarness(t, devicesOnly)
	p, _ := h.srv.IssuePairingToken()
	k := newKey(t)
	if code, _ := postPair(t, h, p.Token, k); code != 200 {
		t.Fatal(code)
	}
	p2, _ := h.srv.IssuePairingToken()
	if code, out := postPair(t, h, p2.Token, k); code != 409 || errOf(out) != "key_in_use" {
		t.Fatalf("duplicate key: %d %v", code, out)
	}
	if code, _ := postPair(t, h, p2.Token, newKey(t)); code != 200 {
		t.Fatal("token burned by the duplicate-key attempt:", code)
	}
	if got := cleanName(" Pixel‮8a​\x07 "); got != "Pixel8a" {
		t.Fatalf("cleanName %q", got)
	}
}

func TestControlPairStatusAndSocketPerms(t *testing.T) {
	h := newHarness(t, devicesOnly)
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	dir := filepath.Join(t.TempDir(), "run")
	os.MkdirAll(dir, 0o755)
	path := filepath.Join(dir, "b.ctl")
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
	if err != nil {
		t.Fatal(err)
	}
	if es, _ := os.ReadDir(dir); len(es) != 1 {
		t.Fatalf("leftovers next to the socket: %v", es)
	}
	if st, _ := os.Stat(dir); st.Mode().Perm() != 0o700 {
		t.Fatalf("dir mode %v", st.Mode())
	}
	if st, _ := os.Stat(path); st.Mode().Perm() != 0o600 || st.Mode()&os.ModeSocket == 0 {
		t.Fatalf("socket mode %v", st.Mode())
	}
	tok := resp.Pairing.Token
	status := func(tok string) ControlResponse {
		r, err := CallControl(path, ControlRequest{Op: "pair_status", Token: tok})
		if err != nil {
			t.Fatal(err)
		}
		return r
	}
	if r := status(tok); r.State != PairPending {
		t.Fatalf("pending: %+v", r)
	}
	if code, _ := postPair(t, h, tok, newKey(t)); code != 200 {
		t.Fatal(code)
	}
	if r := status(tok); r.State != PairPaired || r.Device == nil || r.Device.Name != "Pixel 8a" || r.Device.PublicKey != "" {
		t.Fatalf("paired: %+v", r)
	}
	resp, _ = CallControl(path, ControlRequest{Op: "pair"})
	if _, err := CallControl(path, ControlRequest{Op: "cancel", Token: resp.Pairing.Token}); err != nil {
		t.Fatal(err)
	}
	if r := status(resp.Pairing.Token); r.State != PairGone {
		t.Fatalf("cancelled: %+v", r)
	}
	if code, _ := postPair(t, h, resp.Pairing.Token, newKey(t)); code != 403 {
		t.Fatal("cancelled token still pairs:", code)
	}
	ds, _ := CallControl(path, ControlRequest{Op: "devices"})
	if len(ds.Devices) != 1 || ds.Devices[0].Connections != 0 {
		b, _ := json.Marshal(ds)
		t.Fatalf("devices %s", b)
	}
	cancel()
	<-done
	if _, err := os.Lstat(path); !os.IsNotExist(err) {
		t.Fatal(fmt.Sprint("socket left behind: ", err))
	}
}
