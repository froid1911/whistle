package server

import (
	"bytes"
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"mime/multipart"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/coder/websocket"
)

type denyAll struct{}

func (denyAll) Authorize(context.Context, string) (string, error) { return "", errors.New("nope") }

func TestAuthRejects(t *testing.T) {
	h := newHarness(t, func(_ *Config, d *Deps) { d.Auth = denyAll{} })
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	_, resp, err := websocket.Dial(ctx, "ws"+strings.TrimPrefix(h.hs.URL, "http")+"/v1/ws", nil)
	if err == nil || resp == nil || resp.StatusCode != http.StatusForbidden {
		t.Fatalf("want 403, got %v %v", resp, err)
	}
	r, err := http.Get(h.hs.URL + "/v1/upload?pane_id=w1:p1")
	if err != nil || r.StatusCode != http.StatusForbidden {
		t.Fatalf("upload: %v %v", r, err)
	}
	r.Body.Close()
	// Health stays reachable but tells an unauthorized caller nothing.
	r, err = http.Get(h.hs.URL + "/v1/health")
	if err != nil || r.StatusCode != http.StatusOK {
		t.Fatalf("health: %v %v", r, err)
	}
	var b map[string]any
	json.NewDecoder(r.Body).Decode(&b)
	r.Body.Close()
	if len(b) != 1 || b["ok"] != true {
		t.Fatalf("unauthorized health leaks: %v", b)
	}
}

func TestTailscaleAuthAllowlist(t *testing.T) {
	a := TailscaleAuth{Allowed: []string{"you@github"}}
	a.whois = func(_ context.Context, addr string) (string, error) {
		if addr == "100.64.0.9:1234" {
			return "you@github", nil
		}
		return "mallory@github", nil
	}
	if l, err := a.Authorize(context.Background(), "100.64.0.9:1234"); err != nil || l != "you@github" {
		t.Fatal(l, err)
	}
	if _, err := a.Authorize(context.Background(), "100.64.0.7:1234"); err == nil {
		t.Fatal("must reject")
	}
}

func TestHealth(t *testing.T) {
	h := newHarness(t, nil)
	appendN(t, h.st, 4)
	r, err := http.Get(h.hs.URL + "/v1/health")
	if err != nil {
		t.Fatal(err)
	}
	defer r.Body.Close()
	var b map[string]any
	json.NewDecoder(r.Body).Decode(&b)
	if b["ok"] != true || b["herdr_online"] != true || b["head_seq"] != float64(4) {
		t.Fatalf("%v", b)
	}
}

func upload(t *testing.T, url, name string, data []byte) *http.Response {
	t.Helper()
	var buf bytes.Buffer
	mw := multipart.NewWriter(&buf)
	fw, _ := mw.CreateFormFile("file", name)
	fw.Write(data)
	mw.Close()
	r, err := http.Post(url, mw.FormDataContentType(), &buf)
	if err != nil {
		t.Fatal(err)
	}
	return r
}

func TestUploadSavesFile(t *testing.T) {
	h := newHarness(t, func(c *Config, _ *Deps) { c.MaxUpload = 16 })
	r := upload(t, h.hs.URL+"/v1/upload?pane_id=w9:p1", "../../evil name;.txt", []byte("hello"))
	var b map[string]string
	json.NewDecoder(r.Body).Decode(&b)
	r.Body.Close()
	if r.StatusCode != 200 {
		t.Fatalf("status %d", r.StatusCode)
	}
	p := b["path"]
	dir := filepath.Join(h.cfg.UploadDir, "w9_p1")
	if filepath.Dir(p) != dir || !strings.HasSuffix(p, "-evil_name_.txt") {
		t.Fatalf("path %q", p)
	}
	if got, _ := os.ReadFile(p); string(got) != "hello" {
		t.Fatalf("content %q", got)
	}
	r = upload(t, h.hs.URL+"/v1/upload?pane_id=w9:p1", "big.bin", bytes.Repeat([]byte("x"), 17))
	r.Body.Close()
	if r.StatusCode != http.StatusRequestEntityTooLarge {
		t.Fatalf("status %d", r.StatusCode)
	}
	if ents, _ := os.ReadDir(dir); len(ents) != 1 {
		t.Fatalf("partial upload left behind: %v", ents)
	}
	r = upload(t, h.hs.URL+"/v1/upload", "a.txt", []byte("x"))
	r.Body.Close()
	if r.StatusCode != http.StatusBadRequest {
		t.Fatalf("missing pane_id: %d", r.StatusCode)
	}
}

type fakeTerm struct {
	mu     sync.Mutex
	opens  []string
	sizes  []string
	inputs []string
}

type fakeSess struct {
	f      *fakeTerm
	frames chan TermFrame
	once   sync.Once
}

func (f *fakeTerm) Open(_ context.Context, paneID, mode string, cols, rows int) (TermSession, error) {
	f.mu.Lock()
	f.opens = append(f.opens, paneID+"/"+mode)
	f.sizes = append(f.sizes, fmt.Sprintf("%dx%d", cols, rows))
	f.mu.Unlock()
	s := &fakeSess{f: f, frames: make(chan TermFrame, 4)}
	s.frames <- TermFrame{Seq: 1, Width: 80, Height: 24, Full: true, Bytes: []byte("\x1b[1mhi")}
	return s, nil
}
func (s *fakeSess) Frames() <-chan TermFrame { return s.frames }
func (s *fakeSess) Input(text string, b []byte) error {
	s.f.mu.Lock()
	s.f.inputs = append(s.f.inputs, text+string(b))
	s.f.mu.Unlock()
	return nil
}
func (s *fakeSess) Resize(int, int) error { return nil }
func (s *fakeSess) Close() error          { s.once.Do(func() { close(s.frames) }); return nil }

func TestTermOpenStreamsFramesAndUpgrades(t *testing.T) {
	ft := &fakeTerm{}
	h := newHarness(t, func(_ *Config, d *Deps) { d.Term = ft })
	c := h.dial()
	c.hello("", 0)
	c.send(map[string]any{"t": "term.open", "sid": "s1", "pane_id": "w1:p1", "mode": "observe"})
	f := c.recvT("term.frame")
	if f.s("sid") != "s1" || f.n("width") != 80 || f["full"] != true || f.s("bytes") != base64.StdEncoding.EncodeToString([]byte("\x1b[1mhi")) {
		t.Fatalf("%v", f)
	}
	c.send(map[string]any{"t": "term.input", "sid": "s1", "text": "ls\r"})
	c.recvT("term.frame") // first frame of the control session
	ft.mu.Lock()
	opens, inputs := append([]string(nil), ft.opens...), append([]string(nil), ft.inputs...)
	ft.mu.Unlock()
	if len(opens) != 2 || opens[1] != "w1:p1/control" || len(inputs) != 1 || inputs[0] != "ls\r" {
		t.Fatalf("opens %v inputs %v", opens, inputs)
	}
	c.send(map[string]any{"t": "term.open", "sid": "s2", "pane_id": "w1:p2", "mode": "observe"})
	c.recvT("term.frame")
	c.send(map[string]any{"t": "term.open", "sid": "s3", "pane_id": "w1:p2", "mode": "observe"})
	if m := c.recvT("term.closed"); m.s("sid") != "s3" {
		t.Fatalf("third session must be refused: %v", m)
	}
}

// A fit-to-phone observe session carries cols/rows; the control session the
// bridge restarts on input must not, because control+cols/rows resizes the
// real desktop pane permanently.
func TestTermUpgradeDropsFitSize(t *testing.T) {
	ft := &fakeTerm{}
	h := newHarness(t, func(_ *Config, d *Deps) { d.Term = ft })
	c := h.dial()
	c.hello("", 0)
	c.send(map[string]any{"t": "term.open", "sid": "s1", "pane_id": "w1:p1", "mode": "observe", "cols": 60, "rows": 20})
	c.recvT("term.frame")
	c.send(map[string]any{"t": "term.input", "sid": "s1", "text": "x"})
	c.recvT("term.frame")
	ft.mu.Lock()
	opens, sizes := append([]string(nil), ft.opens...), append([]string(nil), ft.sizes...)
	ft.mu.Unlock()
	if len(opens) != 2 || opens[1] != "w1:p1/control" {
		t.Fatalf("opens %v", opens)
	}
	if sizes[0] != "60x20" || sizes[1] != "0x0" {
		t.Fatalf("sizes %v: observe keeps fit size, control must have none", sizes)
	}
}

func TestTermNotAvailable(t *testing.T) {
	h := newHarness(t, nil)
	c := h.dial()
	c.hello("", 0)
	c.send(map[string]any{"t": "term.open", "sid": "s1", "pane_id": "w1:p1", "mode": "observe"})
	if m := c.recvT("term.closed"); m.s("sid") != "s1" || m.s("reason") != "not_available" {
		t.Fatalf("%v", m)
	}
}

func statusOf(t *testing.T, req *http.Request) int {
	t.Helper()
	r, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	r.Body.Close()
	return r.StatusCode
}

func TestRejectsOriginHeader(t *testing.T) {
	h := newHarness(t, nil)
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	hdr := http.Header{"Origin": {"http://" + h.hs.Listener.Addr().String()}} // even a same-host origin
	_, resp, err := websocket.Dial(ctx, "ws"+strings.TrimPrefix(h.hs.URL, "http")+"/v1/ws", &websocket.DialOptions{HTTPHeader: hdr})
	if err == nil || resp == nil || resp.StatusCode != http.StatusForbidden {
		t.Fatalf("ws with Origin: want 403, got %v %v", resp, err)
	}
	var buf bytes.Buffer
	mw := multipart.NewWriter(&buf)
	fw, _ := mw.CreateFormFile("file", "a.txt")
	fw.Write([]byte("x"))
	mw.Close()
	req, _ := http.NewRequest("POST", h.hs.URL+"/v1/upload?pane_id=w1:p1", &buf)
	req.Header.Set("Content-Type", mw.FormDataContentType())
	req.Header.Set("Origin", "https://evil.example")
	if c := statusOf(t, req); c != http.StatusForbidden {
		t.Fatalf("upload with Origin: %d", c)
	}
	if ents, _ := os.ReadDir(h.cfg.UploadDir); len(ents) != 0 {
		t.Fatal("upload written despite Origin")
	}
	req, _ = http.NewRequest("GET", h.hs.URL+"/v1/health", nil)
	req.Header.Set("Origin", "https://evil.example")
	if c := statusOf(t, req); c != http.StatusForbidden {
		t.Fatalf("health with Origin: %d", c)
	}
	// Without Origin the same requests are fine.
	req, _ = http.NewRequest("GET", h.hs.URL+"/v1/health", nil)
	if c := statusOf(t, req); c != http.StatusOK {
		t.Fatalf("health: %d", c)
	}
}

func TestRejectsForeignHost(t *testing.T) {
	h := newHarness(t, nil)
	for _, p := range []string{"/v1/health", "/v1/ws", "/v1/upload?pane_id=w1:p1"} {
		req, _ := http.NewRequest("GET", h.hs.URL+p, nil)
		req.Host = "evil.example"
		if c := statusOf(t, req); c != http.StatusForbidden {
			t.Fatalf("%s Host evil.example: %d", p, c)
		}
	}
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	_, resp, err := websocket.Dial(ctx, "ws"+strings.TrimPrefix(h.hs.URL, "http")+"/v1/ws",
		&websocket.DialOptions{Host: "evil.example:" + strings.Split(h.hs.Listener.Addr().String(), ":")[1]})
	if err == nil || resp == nil || resp.StatusCode != http.StatusForbidden {
		t.Fatalf("ws foreign host: want 403, got %v %v", resp, err)
	}
}

func TestAllowedHostsFailClosed(t *testing.T) {
	s := &Server{}
	s.addHost("")
	if s.hostAllowed("") || s.hostAllowed("x:1") {
		t.Fatal("empty config must allow nothing")
	}
	s.addHost("100.64.0.1:8787")
	if !s.hostAllowed("100.64.0.1:8787") || s.hostAllowed("100.64.0.1:8788") || s.hostAllowed("100.64.0.1") {
		t.Fatal("exact ip:port only")
	}
}
