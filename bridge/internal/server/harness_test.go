package server

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/coder/websocket"

	"github.com/froid1911/whistle/bridge/internal/herdr"
	"github.com/froid1911/whistle/bridge/internal/state"
	"github.com/froid1911/whistle/bridge/internal/store"
	"github.com/froid1911/whistle/bridge/internal/testutil/fakeherdr"
)

type fakeState struct {
	mu     sync.Mutex
	st     state.State
	online bool
}

func (f *fakeState) Current() state.State {
	f.mu.Lock()
	defer f.mu.Unlock()
	return f.st
}
func (f *fakeState) Online() bool      { f.mu.Lock(); defer f.mu.Unlock(); return f.online }
func (f *fakeState) Version() string   { return "0.9.5" }
func (f *fakeState) set(s state.State) { f.mu.Lock(); f.st = s; f.mu.Unlock() }
func (f *fakeState) setOnline(v bool)  { f.mu.Lock(); f.online = v; f.mu.Unlock() }

type harness struct {
	t     *testing.T
	st    *store.Store
	fh    *fakeherdr.Fake
	state *fakeState
	srv   *Server
	hs    *httptest.Server
	cfg   Config
	deps  Deps
}

func testState() state.State {
	return state.State{
		Workspaces: []state.Workspace{{ID: "w1", Number: 1, Label: "~", ActiveTabID: "w1:t1"}},
		Tabs:       []state.Tab{{ID: "w1:t1", WorkspaceID: "w1", Number: 1, Label: "t"}},
		Panes: []state.Pane{
			{ID: "w1:p1", TabID: "w1:t1", WorkspaceID: "w1", Cwd: "/repo/a", Kind: "agent", Agent: "claude", AgentStatus: "working", SessionKey: "claude:s1"},
			{ID: "w1:p2", TabID: "w1:t1", WorkspaceID: "w1", Cwd: "/repo/b", Kind: "agent", Agent: "claude", AgentStatus: "working", SessionKey: "claude:s2"},
		},
		Layouts: []state.Layout{},
	}
}

// newHarness builds a server; mod may adjust cfg/deps before New.
func newHarness(t *testing.T, mod func(*Config, *Deps)) *harness {
	t.Helper()
	st, err := store.Open(filepath.Join(t.TempDir(), "b.db"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { st.Close() })
	fh := fakeherdr.New(t)
	hc := herdr.NewRedialer(fh.SocketPath())
	fs := &fakeState{st: testState(), online: true}
	h := &harness{t: t, st: st, fh: fh, state: fs}
	h.cfg = Config{UploadDir: t.TempDir(), MaxUpload: 1 << 20, Log: slog.New(slog.NewTextHandler(io.Discard, nil))}
	h.deps = Deps{Store: st, Watcher: fs, Herdr: hc, Auth: AllowAll{}}
	if mod != nil {
		mod(&h.cfg, &h.deps)
	}
	h.hs = httptest.NewUnstartedServer(nil)
	h.cfg.Addr = h.hs.Listener.Addr().String() // the Host the server accepts
	h.srv = New(h.cfg, h.deps)
	h.hs.Config.Handler = h.srv.Handler()
	h.hs.Start()
	t.Cleanup(h.hs.Close)
	return h
}

type client struct {
	t   *testing.T
	ws  *websocket.Conn
	in  chan msg
	err chan error
}

func (h *harness) dial() *client {
	h.t.Helper()
	return h.dialHdr(nil)
}

func (h *harness) dialHdr(hdr http.Header) *client {
	h.t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	ws, _, err := websocket.Dial(ctx, "ws"+strings.TrimPrefix(h.hs.URL, "http")+"/v1/ws", &websocket.DialOptions{HTTPHeader: hdr})
	if err != nil {
		h.t.Fatal(err)
	}
	ws.SetReadLimit(16 << 20)
	h.t.Cleanup(func() { ws.CloseNow() })
	c := &client{t: h.t, ws: ws, in: make(chan msg, 1024), err: make(chan error, 1)}
	// Read in the background: a Read whose ctx expires closes the conn.
	go func() {
		for {
			_, b, err := ws.Read(context.Background())
			if err != nil {
				c.err <- err
				return
			}
			var m msg
			if err := json.Unmarshal(b, &m); err != nil {
				c.err <- err
				return
			}
			c.in <- m
		}
	}()
	return c
}

func (c *client) send(v any) {
	c.t.Helper()
	b, _ := json.Marshal(v)
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	if err := c.ws.Write(ctx, websocket.MessageText, b); err != nil {
		c.t.Fatal(err)
	}
}

type msg map[string]any

func (m msg) s(k string) string { v, _ := m[k].(string); return v }
func (m msg) n(k string) int64  { v, _ := m[k].(float64); return int64(v) }

var errTimeout = errors.New("timeout")

func (c *client) recvWithin(d time.Duration) (msg, error) {
	select {
	case m := <-c.in:
		return m, nil
	case err := <-c.err:
		return nil, err
	case <-time.After(d):
		return nil, errTimeout
	}
}

func (c *client) recv() msg {
	c.t.Helper()
	m, err := c.recvWithin(5 * time.Second)
	if err != nil {
		c.t.Fatal(err)
	}
	return m
}

// recvT skips messages of other types (e.g. live) until one of type typ.
func (c *client) recvT(typ string) msg {
	c.t.Helper()
	deadline := time.Now().Add(5 * time.Second)
	for time.Now().Before(deadline) {
		m := c.recv()
		if m.s("t") == typ {
			return m
		}
	}
	c.t.Fatalf("no %s", typ)
	return nil
}

func (c *client) hello(serverID string, since int64) msg {
	c.t.Helper()
	c.send(map[string]any{"t": "hello", "server_id": serverID, "since": since, "client": "test/1"})
	w := c.recv()
	if w.s("t") != "welcome" {
		c.t.Fatalf("want welcome, got %v", w)
	}
	// The ephemeral herdr status always follows welcome directly.
	if m := c.recv(); m.s("t") != "herdr" {
		c.t.Fatalf("want herdr after welcome, got %v", m)
	}
	return w
}

func (c *client) cmd(id, method string, params any) msg {
	c.t.Helper()
	c.send(map[string]any{"t": "cmd", "id": id, "method": method, "params": params})
	for {
		m := c.recvT("res")
		if m.s("id") == id {
			return m
		}
	}
}

func errCode(m msg) string {
	e, _ := m["error"].(map[string]any)
	if e == nil {
		return ""
	}
	s, _ := e["code"].(string)
	return s
}

func appendN(t *testing.T, st *store.Store, n int) {
	t.Helper()
	for i := 0; i < n; i++ {
		if _, err := st.Append("remove", map[string]any{"kind": "remove", "entity": "pane", "id": "x"}); err != nil {
			t.Fatal(err)
		}
	}
}
