//go:build live

// Package integration holds live tests against the dev bridge on myserver
// (herdr session whistle-dev, port 8788). Run from a machine on the same
// tailnet as an allowed user:
//
//	make test-live   # or: go test -tags live -count=1 -v ./internal/integration/
//
// WHISTLE_BRIDGE_URL overrides the target (default ws://100.64.0.10:8788/v1/ws).
// WHISTLE_SKIP_CLAUDE=1 skips the (paid) Claude pong sub-test.
// Safety: the test refuses any URL whose port is not 8788 and any session
// without a workspace labelled "whistle-dev", before mutating anything.
package integration

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"fmt"
	neturl "net/url"
	"os"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/coder/websocket"
)

const (
	defaultURL = "ws://100.64.0.10:8788/v1/ws"
	claudeDir  = "~/.cache/whistle-it-claude" // cwd of the test's claude agent on myserver
	devPort    = "8788"
	devLabel   = "whistle-dev" // label of the dev session's workspace
)

type msg struct {
	at  time.Time
	raw map[string]any
}

func (m msg) str(k string) string { s, _ := m.raw[k].(string); return s }

func (m msg) num(k string) int64 { f, _ := m.raw[k].(float64); return int64(f) }

func (m msg) obj(k string) map[string]any { o, _ := m.raw[k].(map[string]any); return o }

type client struct {
	t   *testing.T
	ws  *websocket.Conn
	ctx context.Context

	mu   sync.Mutex
	msgs []msg
	cond chan struct{} // closed and replaced on every new message
	err  error
	nid  int
}

func dial(t *testing.T, ctx context.Context) *client {
	t.Helper()
	url := os.Getenv("WHISTLE_BRIDGE_URL")
	if url == "" {
		url = defaultURL
	}
	if err := devOnly(url); err != nil {
		t.Fatal(err)
	}
	ws, _, err := websocket.Dial(ctx, url, nil)
	if err != nil {
		t.Fatalf("dial %s: %v", url, err)
	}
	ws.SetReadLimit(32 << 20)
	c := &client{t: t, ws: ws, ctx: ctx, cond: make(chan struct{})}
	go c.read()
	t.Cleanup(func() { ws.Close(websocket.StatusNormalClosure, "") })
	return c
}

func (c *client) read() {
	for {
		_, b, err := c.ws.Read(c.ctx)
		now := time.Now()
		c.mu.Lock()
		if err != nil {
			c.err = err
		} else {
			var m map[string]any
			if json.Unmarshal(b, &m) == nil {
				c.msgs = append(c.msgs, msg{at: now, raw: m})
			}
		}
		close(c.cond)
		c.cond = make(chan struct{})
		c.mu.Unlock()
		if err != nil {
			return
		}
	}
}

func (c *client) send(v any) {
	c.t.Helper()
	b, _ := json.Marshal(v)
	if err := c.ws.Write(c.ctx, websocket.MessageText, b); err != nil {
		c.t.Fatalf("write: %v", err)
	}
}

// mark returns the index of the next message to arrive.
func (c *client) mark() int {
	c.mu.Lock()
	defer c.mu.Unlock()
	return len(c.msgs)
}

// waitFor returns the first message at index >= from matching pred, failing
// the test on timeout.
func (c *client) waitFor(from int, d time.Duration, what string, pred func(msg) bool) (msg, int) {
	c.t.Helper()
	m, i, ok := c.tryWait(from, d, what, pred)
	if !ok {
		c.t.Fatalf("timeout (%v) waiting for %s", d, what)
	}
	return m, i
}

// tryWait is waitFor without the timeout failure.
func (c *client) tryWait(from int, d time.Duration, what string, pred func(msg) bool) (msg, int, bool) {
	c.t.Helper()
	deadline := time.After(d)
	for {
		c.mu.Lock()
		for i := from; i < len(c.msgs); i++ {
			if pred(c.msgs[i]) {
				m := c.msgs[i]
				c.mu.Unlock()
				return m, i, true
			}
		}
		from = len(c.msgs)
		cond, err := c.cond, c.err
		c.mu.Unlock()
		if err != nil {
			c.t.Fatalf("waiting for %s: connection error: %v", what, err)
		}
		select {
		case <-cond:
		case <-deadline:
			return msg{}, -1, false
		}
	}
}

// cmd sends a command and waits for its res; it returns result or error.
func (c *client) cmd(method string, params map[string]any, d time.Duration) (map[string]any, map[string]any) {
	c.t.Helper()
	c.mu.Lock()
	c.nid++
	id := fmt.Sprintf("c%d", c.nid)
	c.mu.Unlock()
	from := c.mark()
	c.send(map[string]any{"t": "cmd", "id": id, "method": method, "params": params})
	m, _ := c.waitFor(from, d, "res "+method, func(m msg) bool { return m.str("t") == "res" && m.str("id") == id })
	return m.obj("result"), m.obj("error")
}

func (c *client) mustCmd(method string, params map[string]any) map[string]any {
	c.t.Helper()
	res, e := c.cmd(method, params, 30*time.Second)
	if e != nil {
		c.t.Fatalf("%s: %v", method, e)
	}
	return res
}

// dump logs a one-line summary of every message from index from on.
func (c *client) dump(from int) {
	c.mu.Lock()
	defer c.mu.Unlock()
	for i, m := range c.msgs[from:] {
		line := fmt.Sprintf("%s t=%s", m.at.Format("15:04:05.000"), m.str("t"))
		switch m.str("t") {
		case "change":
			line += fmt.Sprintf(" seq=%d kind=%s", m.num("seq"), m.str("kind"))
			switch m.str("kind") {
			case "upsert":
				d := m.obj("data")
				line += fmt.Sprintf(" %s id=%v agent=%v status=%v sk=%v", m.str("entity"), d["id"], d["agent"], d["agent_status"], d["session_key"])
			case "remove":
				line += fmt.Sprintf(" %s id=%s", m.str("entity"), m.str("id"))
			case "status":
				line += fmt.Sprintf(" pane=%s %s->%s sk=%s", m.str("pane_id"), m.str("from"), m.str("to"), m.str("session_key"))
			case "message":
				mm := m.obj("message")
				txt, _ := mm["text"].(string)
				if len(txt) > 60 {
					txt = txt[:60]
				}
				line += fmt.Sprintf(" sk=%s role=%v %q", m.str("session_key"), mm["role"], txt)
			}
		case "res":
			line += " id=" + m.str("id")
		}
		c.t.Logf("#%d %s", from+i, line)
	}
}

func isChange(kind, entity string) func(msg) bool {
	return func(m msg) bool {
		return m.str("t") == "change" && m.str("kind") == kind && (entity == "" || m.str("entity") == entity)
	}
}

func dataField(m msg, k string) string { s, _ := m.obj("data")[k].(string); return s }

// hello performs the handshake and returns the full state.
func (c *client) hello() map[string]any {
	c.t.Helper()
	from := c.mark()
	c.send(map[string]any{"t": "hello", "server_id": "", "since": 0, "client": "integration/live"})
	w, _ := c.waitFor(from, 10*time.Second, "welcome", func(m msg) bool { return m.str("t") == "welcome" })
	c.t.Logf("welcome: protocol=%v server_id=%s head_seq=%d resync=%v herdr=%s",
		w.raw["protocol"], w.str("server_id"), w.num("head_seq"), w.raw["resync"], w.str("herdr_version"))
	s, _ := c.waitFor(from, 10*time.Second, "state", func(m msg) bool { return m.str("t") == "state" })
	c.waitFor(from, 10*time.Second, "synced", func(m msg) bool { return m.str("t") == "synced" })
	return s.obj("state")
}

// devOnly refuses any target but the dev bridge (port 8788): this test
// creates tabs, types into panes and starts a paid agent, which must never
// happen in the owner's real session (prod bridge, port 8787).
func devOnly(raw string) error {
	u, err := neturl.Parse(raw)
	if err != nil {
		return fmt.Errorf("WHISTLE_BRIDGE_URL %q: %v", raw, err)
	}
	if u.Port() != devPort {
		return fmt.Errorf("refusing to run against %q: port %q is not the dev bridge port %s", raw, u.Port(), devPort)
	}
	return nil
}

// devWorkspace returns the id of the workspace labelled devLabel. Without
// it the target is not the dev session, and the test stops before mutating.
func devWorkspace(t *testing.T, st map[string]any) string {
	ws, _ := st["workspaces"].([]any)
	for _, w := range ws {
		m, _ := w.(map[string]any)
		if m["label"] == devLabel {
			id, _ := m["id"].(string)
			return id
		}
	}
	t.Fatalf("no workspace labelled %q: not the dev session, refusing to mutate", devLabel)
	return ""
}

// newTab creates a tab and returns (tab id, its pane id, upsert latency).
func (c *client) newTab(ws, label string) (string, string, time.Duration) {
	c.t.Helper()
	from := c.mark()
	start := time.Now()
	res := c.mustCmd("tab.create", map[string]any{"workspace_id": ws, "label": label, "cwd": "/home/you"})
	tab, _ := res["tab"].(map[string]any)
	tabID, _ := tab["tab_id"].(string)
	if tabID == "" {
		c.t.Fatalf("tab.create result without tab.tab_id: %v", res)
	}
	owner := c.t
	owner.Cleanup(func() {
		prev := c.t
		c.t = owner
		defer func() { c.t = prev }()
		c.closeTab(tabID)
	})
	up, _ := c.waitFor(from, 5*time.Second, "upsert tab "+tabID, func(m msg) bool {
		return isChange("upsert", "tab")(m) && dataField(m, "id") == tabID
	})
	lat := up.at.Sub(start)
	p, _ := c.waitFor(from, 5*time.Second, "upsert pane in "+tabID, func(m msg) bool {
		return isChange("upsert", "pane")(m) && dataField(m, "tab_id") == tabID
	})
	return tabID, dataField(p, "id"), lat
}

func (c *client) closeTab(tabID string) {
	from := c.mark()
	if _, e := c.cmd("tab.close", map[string]any{"tab_id": tabID}, 15*time.Second); e != nil {
		c.t.Errorf("tab.close %s: %v", tabID, e)
		return
	}
	c.waitFor(from, 5*time.Second, "remove tab "+tabID, func(m msg) bool {
		return isChange("remove", "tab")(m) && m.str("id") == tabID
	})
}

func frameText(m msg) string {
	b, _ := base64.StdEncoding.DecodeString(m.str("bytes"))
	return string(b)
}

func TestLiveBridge(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 4*time.Minute)
	defer cancel()
	c := dial(t, ctx)

	st := c.hello()
	panes, _ := st["panes"].([]any)
	if len(panes) < 1 {
		t.Fatalf("state has %d panes, want >= 1", len(panes))
	}
	t.Logf("state: %d workspaces, %d tabs, %d panes", len(st["workspaces"].([]any)), len(st["tabs"].([]any)), len(panes))
	ws := devWorkspace(t, st)

	t.Run("tab_term_echo", func(t *testing.T) {
		defer func(p *testing.T) { c.t = p }(c.t)
		c.t = t
		tabID, paneID, upsertLat := c.newTab(ws, "it-echo")
		t.Logf("tab.create -> upsert tab: %v (tab %s, pane %s)", upsertLat.Round(time.Millisecond), tabID, paneID)
		if upsertLat >= 500*time.Millisecond {
			t.Errorf("upsert tab latency %v, want < 500ms", upsertLat)
		}

		from := c.mark()
		c.send(map[string]any{"t": "term.open", "sid": "s1", "pane_id": paneID, "mode": "observe"})
		first, _ := c.waitFor(from, 10*time.Second, "first term.frame", func(m msg) bool {
			return (m.str("t") == "term.frame" || m.str("t") == "term.closed") && m.str("sid") == "s1"
		})
		if first.str("t") == "term.closed" {
			t.Fatalf("term.closed: %s", first.str("reason"))
		}
		t.Logf("first frame: %dx%d full=%v seq=%d", first.num("width"), first.num("height"), first.raw["full"], first.num("seq"))

		from = c.mark()
		sent := time.Now()
		c.mustCmd("pane.send_text", map[string]any{"pane_id": paneID, "text": "echo whistle-ok\n"})
		fr, _ := c.waitFor(from, 10*time.Second, "frame containing whistle-ok", func(m msg) bool {
			return m.str("t") == "term.frame" && m.str("sid") == "s1" && strings.Contains(frameText(m), "whistle-ok")
		})
		t.Logf("pane.send_text -> term.frame with whistle-ok: %v (frame %dx%d full=%v); tab.create -> upsert tab was %v",
			fr.at.Sub(sent).Round(time.Millisecond), fr.num("width"), fr.num("height"), fr.raw["full"], upsertLat.Round(time.Millisecond))

		// A client-initiated term.close is silent (no term.closed is sent).
		c.send(map[string]any{"t": "term.close", "sid": "s1"})

		// Fit-to-phone observe: frames come at the requested size (the real
		// pane is not resized in observe mode).
		from = c.mark()
		c.send(map[string]any{"t": "term.open", "sid": "s2", "pane_id": paneID, "mode": "observe", "cols": 60, "rows": 20})
		fit, _ := c.waitFor(from, 10*time.Second, "s2 first frame", func(m msg) bool {
			return (m.str("t") == "term.frame" || m.str("t") == "term.closed") && m.str("sid") == "s2"
		})
		if fit.str("t") == "term.closed" {
			t.Fatalf("s2 term.closed: %s", fit.str("reason"))
		}
		t.Logf("observe cols=60 rows=20 -> frame %dx%d", fit.num("width"), fit.num("height"))
		if fit.num("width") != 60 || fit.num("height") != 20 {
			t.Errorf("fit frame %dx%d, want 60x20", fit.num("width"), fit.num("height"))
		}
		c.send(map[string]any{"t": "term.close", "sid": "s2"})
	})

	t.Run("claude_pong", func(t *testing.T) {
		if os.Getenv("WHISTLE_SKIP_CLAUDE") != "" {
			t.Skip("WHISTLE_SKIP_CLAUDE set")
		}
		defer func(p *testing.T) { c.t = p }(c.t)
		c.t = t
		_, paneID, _ := c.newTab(ws, "it-claude")

		// A dedicated cwd, so a Claude folder-trust prompt (if any) only
		// ever trusts this scratch directory.
		c.mustCmd("pane.send_text", map[string]any{"pane_id": paneID, "text": "mkdir -p " + claudeDir + " && cd " + claudeDir + "\n"})
		time.Sleep(time.Second)

		from := c.mark()
		res, e := c.cmd("agent.start", map[string]any{
			"name": "itpong", "kind": "claude", "pane_id": paneID, "timeout_ms": 60000,
		}, 90*time.Second)
		if e != nil {
			// agent_not_ready = blocked during startup (e.g. trust dialog); handled below.
			t.Logf("agent.start: %v", e)
		} else {
			t.Logf("agent.start: argv=%v", res["argv"])
		}

		// Wait until the pane is an idle claude agent, answering a folder-trust
		// dialog at most twice. Its default choice is "No, exit", so select
		// "Yes, I trust this folder" with Down before Enter.
		isIdleClaude := func(m msg) bool {
			return isChange("upsert", "pane")(m) && dataField(m, "id") == paneID &&
				dataField(m, "agent") == "claude" && dataField(m, "agent_status") == "idle"
		}
		screen := func() string {
			scr, e := c.cmd("agent.screen", map[string]any{"pane_id": paneID, "lines": 40}, 10*time.Second)
			if e != nil {
				return fmt.Sprintf("<agent.screen error %v>", e)
			}
			text, _ := scr["text"].(string)
			return text
		}
		var up msg
		ok, answered := false, 0
		for deadline := time.Now().Add(90 * time.Second); !ok && time.Now().Before(deadline); {
			if up, _, ok = c.tryWait(from, 3*time.Second, "claude pane idle", isIdleClaude); ok {
				break
			}
			if text := screen(); strings.Contains(text, "trust this folder") && answered < 2 {
				answered++
				t.Logf("trust dialog seen, answering Yes (%d):\n%s", answered, text)
				c.mustCmd("pane.send_keys", map[string]any{"pane_id": paneID, "keys": []string{"down"}})
				time.Sleep(300 * time.Millisecond)
				c.mustCmd("pane.send_keys", map[string]any{"pane_id": paneID, "keys": []string{"enter"}})
			}
		}
		if !ok {
			c.mu.Lock()
			var last map[string]any
			for _, m := range c.msgs[from:] {
				if isChange("upsert", "pane")(m) && dataField(m, "id") == paneID {
					last = m.obj("data")
				}
			}
			c.mu.Unlock()
			t.Fatalf("claude never became idle; last pane upsert %v; screen:\n%s", last, screen())
		}
		sk := dataField(up, "session_key")
		t.Logf("claude pane %s idle, session_key=%q", paneID, sk)

		from = c.mark()
		prompted := time.Now()
		c.mustCmd("agent.prompt", map[string]any{"target": paneID, "text": "Reply with exactly: pong"})

		isPong := func(m msg) bool {
			if !isChange("message", "")(m) {
				return false
			}
			mm := m.obj("message")
			role, _ := mm["role"].(string)
			text, _ := mm["text"].(string)
			return role == "assistant" && strings.Contains(strings.ToLower(text), "pong")
		}
		isWorking := func(m msg) bool {
			return isChange("status", "")(m) && m.str("pane_id") == paneID && m.str("to") == "working"
		}
		_, wi := c.waitFor(from, 60*time.Second, "status -> working", isWorking)
		// herdr reports a finished turn in an unfocused pane as "done" (not
		// "idle") until someone looks at it; both end the turn.
		idle, ii, ok := c.tryWait(wi, 120*time.Second, "status -> idle|done", func(m msg) bool {
			to := m.str("to")
			return isChange("status", "")(m) && m.str("pane_id") == paneID && (to == "idle" || to == "done")
		})
		if !ok {
			c.dump(from)
			t.Fatalf("no status -> idle|done for %s; screen:\n%s", paneID, screen())
		}
		t.Logf("prompt -> status %s->%s: %v (status seq %d, session_key=%q)", idle.str("from"), idle.str("to"),
			idle.at.Sub(prompted).Round(time.Millisecond), idle.num("seq"), idle.str("session_key"))

		c.mu.Lock()
		pi := -1
		var pong msg
		for i := from; i < len(c.msgs); i++ {
			if isPong(c.msgs[i]) {
				pi, pong = i, c.msgs[i]
				break
			}
		}
		c.mu.Unlock()
		if pi < 0 {
			// Not yet: the bridge emitted status before the reply — an ordering bug.
			m, _ := c.waitFor(ii, 20*time.Second, "assistant pong message", isPong)
			t.Fatalf("ordering: turn-end status (seq %d) arrived BEFORE assistant pong message (seq %d)", idle.num("seq"), m.num("seq"))
		}
		if pong.num("seq") >= idle.num("seq") || pi >= ii {
			t.Fatalf("ordering: pong msg seq %d idx %d, turn-end status seq %d idx %d", pong.num("seq"), pi, idle.num("seq"), ii)
		}
		pm := pong.obj("message")
		t.Logf("assistant message (change seq %d, session_key=%q) %v before turn-end status: %q",
			pong.num("seq"), pong.str("session_key"), idle.at.Sub(pong.at).Round(time.Millisecond), pm["text"])
		if sk != "" && pong.str("session_key") != sk {
			t.Errorf("pong session_key %q, pane session_key %q", pong.str("session_key"), sk)
		}

		hist := c.mustCmd("chat.history", map[string]any{"session_key": pong.str("session_key"), "limit": 10})
		ms, _ := hist["messages"].([]any)
		t.Logf("chat.history: %d messages", len(ms))
		scr := screen()
		t.Logf("agent.screen after turn: %d bytes", len(scr))
		if !strings.Contains(scr, "pong") {
			t.Errorf("agent.screen after turn lacks the reply:\n%s", scr)
		}
	})
}
