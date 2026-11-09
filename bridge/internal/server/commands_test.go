package server

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"sync"
	"testing"
	"time"

	"github.com/froid1911/whistle/bridge/internal/chats"
	"github.com/froid1911/whistle/bridge/internal/herdr"
	"github.com/froid1911/whistle/bridge/internal/state"
	"github.com/froid1911/whistle/bridge/internal/store"
)

func TestCmdPassthroughForcesFocusFalse(t *testing.T) {
	h := newHarness(t, nil)
	h.fh.Handle("tab.create", func(json.RawMessage) (any, *herdr.APIError) {
		return map[string]any{"type": "tab_created", "tab": map[string]any{"tab_id": "w1:t2"}}, nil
	})
	c := h.dial()
	c.hello("", 0)
	r := c.cmd("c1", "tab.create", map[string]any{"workspace_id": "w1", "focus": true})
	if errCode(r) != "" {
		t.Fatalf("%v", r)
	}
	if tab := r["result"].(map[string]any)["tab"].(map[string]any); tab["tab_id"] != "w1:t2" {
		t.Fatalf("%v", r)
	}
	calls := h.fh.Calls("tab.create")
	var p map[string]any
	json.Unmarshal(calls[0], &p)
	if p["focus"] != false || p["workspace_id"] != "w1" {
		t.Fatalf("herdr got %s", calls[0])
	}
	// focus is also forced when the client omitted it.
	h.fh.Handle("pane.split", func(json.RawMessage) (any, *herdr.APIError) { return map[string]any{}, nil })
	c.cmd("c2", "pane.split", map[string]any{"direction": "right"})
	json.Unmarshal(h.fh.Calls("pane.split")[0], &p)
	if p["focus"] != false {
		t.Fatalf("pane.split focus %v", p)
	}
}

func TestCmdNotAllowed(t *testing.T) {
	h := newHarness(t, nil)
	c := h.dial()
	c.hello("", 0)
	if r := c.cmd("c1", "server.stop", map[string]any{}); errCode(r) != "method_not_allowed" {
		t.Fatalf("%v", r)
	}
	if n := len(h.fh.Calls("server.stop")); n != 0 {
		t.Fatal("forwarded")
	}
}

func TestCmdPassesHerdrErrorCode(t *testing.T) {
	h := newHarness(t, nil)
	h.fh.Handle("agent.prompt", func(json.RawMessage) (any, *herdr.APIError) {
		return nil, &herdr.APIError{Code: "agent_blocked", Message: "blocked"}
	})
	c := h.dial()
	c.hello("", 0)
	if r := c.cmd("c1", "agent.prompt", map[string]any{"target": "w1:p1", "text": "hi"}); errCode(r) != "agent_blocked" {
		t.Fatalf("%v", r)
	}
}

func TestChatHistory(t *testing.T) {
	h := newHarness(t, nil)
	var ms []store.Message
	for i := 0; i < 3; i++ {
		ms = append(ms, store.Message{ID: fmt.Sprintf("m%d", i), TS: "2026-01-01T00:00:00Z", Role: "assistant", Text: fmt.Sprint(i)})
	}
	es, _ := h.st.AppendMessages("claude:s1", ms)
	c := h.dial()
	c.hello("", 0)
	r := c.cmd("c1", "chat.history", map[string]any{"session_key": "claude:s1", "limit": 2})
	res := r["result"].(map[string]any)
	got := res["messages"].([]any)
	if len(got) != 2 || res["more"] != true {
		t.Fatalf("%v", r)
	}
	m1 := got[1].(map[string]any)
	if m1["id"] != "m2" || int64(m1["seq"].(float64)) != es[2].Seq {
		t.Fatalf("newest-last with seq: %v", got)
	}
	r = c.cmd("c2", "chat.history", map[string]any{"session_key": "claude:s1", "before_seq": es[1].Seq})
	res = r["result"].(map[string]any)
	if got := res["messages"].([]any); len(got) != 1 || res["more"] != false {
		t.Fatalf("%v", r)
	}
	r = c.cmd("c3", "chat.history", map[string]any{"session_key": "nope"})
	if got, ok := r["result"].(map[string]any)["messages"].([]any); !ok || len(got) != 0 {
		t.Fatalf("empty history must be []: %v", r)
	}
	if r := c.cmd("c4", "chat.history", map[string]any{}); errCode(r) != "bad_request" {
		t.Fatalf("%v", r)
	}
}

func TestAgentScreen(t *testing.T) {
	h := newHarness(t, nil)
	h.fh.Handle("agent.read", func(p json.RawMessage) (any, *herdr.APIError) {
		return map[string]any{"text": "hello screen\n"}, nil
	})
	c := h.dial()
	c.hello("", 0)
	r := c.cmd("c1", "agent.screen", map[string]any{"pane_id": "w1:p1"})
	if txt := r["result"].(map[string]any)["text"]; txt != chats.Clean("claude", "hello screen\n") {
		t.Fatalf("%v", r)
	}
	var p map[string]any
	json.Unmarshal(h.fh.Calls("agent.read")[0], &p)
	if p["target"] != "w1:p1" || p["source"] != "recent_unwrapped" {
		t.Fatalf("%v", p)
	}
}

type fakeGit struct{ paths []string }

func (g *fakeGit) Status(_ context.Context, path string) (GitStatus, error) {
	g.paths = append(g.paths, path)
	return GitStatus{Branch: "main", Files: []GitFile{{Path: "x", Status: "M"}}}, nil
}
func (g *fakeGit) Diff(_ context.Context, path, file string) (string, bool, error) {
	return "diff", false, nil
}
func (g *fakeGit) Commit(_ context.Context, path, message string, all bool) (string, error) {
	if message == "fail" {
		return "", errors.New("nothing to commit")
	}
	return "abc123", nil
}

func TestGitNotAvailableWithoutDep(t *testing.T) {
	h := newHarness(t, nil)
	c := h.dial()
	c.hello("", 0)
	if r := c.cmd("c1", "git.status", map[string]any{"path": "/repo/a"}); errCode(r) != "not_available" {
		t.Fatalf("%v", r)
	}
}

func TestGitPathChecked(t *testing.T) {
	g := &fakeGit{}
	h := newHarness(t, func(_ *Config, d *Deps) { d.Git = g })
	h.fh.Handle("worktree.list", func(json.RawMessage) (any, *herdr.APIError) {
		return map[string]any{"type": "worktree_list", "worktrees": []any{map[string]any{"path": "/wt/feature"}}}, nil
	})
	c := h.dial()
	c.hello("", 0)
	if r := c.cmd("c1", "git.status", map[string]any{"path": "/repo/a"}); errCode(r) != "" || r["result"].(map[string]any)["branch"] != "main" {
		t.Fatalf("%v", r)
	}
	if r := c.cmd("c2", "git.diff", map[string]any{"path": "/wt/feature/"}); errCode(r) != "" || r["result"].(map[string]any)["diff"] != "diff" {
		t.Fatalf("%v", r)
	}
	if r := c.cmd("c3", "git.commit", map[string]any{"path": "/etc", "message": "x"}); errCode(r) != "forbidden_path" {
		t.Fatalf("%v", r)
	}
	if r := c.cmd("c4", "git.commit", map[string]any{"path": "/repo/b", "message": "m", "all": true}); r["result"].(map[string]any)["commit"] != "abc123" {
		t.Fatalf("%v", r)
	}
	if r := c.cmd("c5", "git.commit", map[string]any{"path": "/repo/b", "message": "fail"}); errCode(r) != "git_error" {
		t.Fatalf("%v", r)
	}
}

func TestLiveOnlyForWatched(t *testing.T) {
	h := newHarness(t, func(_ *Config, d *Deps) {
		d.Live = chats.NewLive(chats.HerdrAdapter{C: d.Herdr}, 20*time.Millisecond)
	})
	var n int
	h.fh.Handle("agent.read", func(p json.RawMessage) (any, *herdr.APIError) {
		var q struct{ Target string }
		json.Unmarshal(p, &q)
		n++
		return map[string]any{"text": fmt.Sprintf("%s says %d\n", q.Target, n)}, nil
	})
	c := h.dial()
	c.hello("", 0)
	c.recvT("synced")
	c.send(map[string]any{"t": "watch", "pane_id": "w1:p1"})
	for i := 0; i < 3; i++ {
		m := c.recvT("live")
		if m.s("pane_id") != "w1:p1" || m.s("status") != "working" || m.s("text") == "" {
			t.Fatalf("%v", m)
		}
	}
	// Turn completes: status change streams, then live.clear, then no more live.
	h.st.Append("status", map[string]any{"kind": "status", "pane_id": "w1:p1", "from": "working", "to": "idle", "at": "2026-01-01T00:00:00Z"})
	for {
		m := c.recv()
		if m.s("t") == "live" {
			continue
		}
		if m.s("t") != "change" || m.s("kind") != "status" {
			t.Fatalf("%v", m)
		}
		break
	}
	if m := c.recv(); m.s("t") != "live.clear" || m.s("pane_id") != "w1:p1" {
		t.Fatalf("%v", m)
	}
	if m, err := c.recvWithin(150 * time.Millisecond); err == nil {
		t.Fatalf("live after clear: %v", m)
	}
	c.send(map[string]any{"t": "unwatch", "pane_id": "w1:p1"})
	c.send(map[string]any{"t": "watch", "pane_id": "w1:p2"})
	h.st.Append("status", map[string]any{"kind": "status", "pane_id": "w1:p2", "from": "idle", "to": "working", "at": "2026-01-01T00:00:00Z"})
	c.recvT("change")
	deadline := time.Now().Add(time.Second)
	for time.Now().Before(deadline) {
		m := c.recv()
		if m.s("t") == "live" {
			if m.s("pane_id") != "w1:p2" {
				t.Fatalf("live for unwatched pane: %v", m)
			}
			return
		}
	}
	t.Fatal("no live for w1:p2")
}

// M2: a watch for a pane not yet in state stays pending and activates when
// the pane appears; pending watches count toward the cap.
func TestWatchPendingActivatesWhenPaneAppears(t *testing.T) {
	h := newHarness(t, func(_ *Config, d *Deps) {
		d.Live = chats.NewLive(chats.HerdrAdapter{C: d.Herdr}, 20*time.Millisecond)
	})
	h.fh.Handle("agent.read", func(p json.RawMessage) (any, *herdr.APIError) {
		var q struct{ Target string }
		json.Unmarshal(p, &q)
		return map[string]any{"text": q.Target + " says hi\n"}, nil
	})
	c := h.dial()
	c.hello("", 0)
	c.recvT("synced")
	c.send(map[string]any{"t": "watch", "pane_id": "w1:p3"})
	if m, err := c.recvWithin(200 * time.Millisecond); err == nil {
		t.Fatalf("frame before pane exists: %v", m)
	}
	st := testState()
	st.Panes = append(st.Panes, state.Pane{ID: "w1:p3", TabID: "w1:t1", WorkspaceID: "w1", Cwd: "/repo/c", Kind: "agent", Agent: "claude", AgentStatus: "working", SessionKey: "claude:s3"})
	h.state.set(st)
	if m := c.recvT("live"); m.s("pane_id") != "w1:p3" {
		t.Fatalf("%v", m)
	}
	// Cap: p3 active + 3 pending unknown panes = 4; a 5th watch is refused.
	for _, p := range []string{"w1:p7", "w1:p8", "w1:p9"} {
		c.send(map[string]any{"t": "watch", "pane_id": p})
	}
	c.send(map[string]any{"t": "watch", "pane_id": "w1:p1"})
	deadline := time.Now().Add(500 * time.Millisecond)
	for time.Now().Before(deadline) {
		m, err := c.recvWithin(100 * time.Millisecond)
		if err == nil && m.s("t") == "live" && m.s("pane_id") == "w1:p1" {
			t.Fatalf("watch over cap accepted: %v", m)
		}
	}
}

// T6: with maxCmds commands in flight the read loop must not block (it would
// starve pongs and every other frame); extra commands get error "busy".
func TestCmdBusyWhenSaturated(t *testing.T) {
	h := newHarness(t, nil)
	hang := make(chan struct{})
	var once sync.Once
	release := func() { once.Do(func() { close(hang) }) }
	t.Cleanup(release)
	h.fh.Handle("agent.prompt", func(json.RawMessage) (any, *herdr.APIError) {
		<-hang
		return map[string]any{}, nil
	})
	c := h.dial()
	c.hello("", 0)
	c.recvT("synced")
	for i := 0; i < maxCmds; i++ {
		c.send(map[string]any{"t": "cmd", "id": fmt.Sprintf("h%d", i), "method": "agent.prompt", "params": map[string]any{}})
	}
	time.Sleep(100 * time.Millisecond)
	c.send(map[string]any{"t": "cmd", "id": "x", "method": "chat.history", "params": map[string]any{"session_key": "k"}})
	deadline := time.After(2 * time.Second)
	for {
		select {
		case m := <-c.in:
			if m.s("t") == "res" && m.s("id") == "x" {
				if errCode(m) != "busy" {
					t.Fatalf("want busy, got %v", m)
				}
				release()
				return
			}
		case <-deadline:
			t.Fatal("read loop blocked: no reply to 9th command")
		}
	}
}
