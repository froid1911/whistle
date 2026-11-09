package chats

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"log/slog"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/froid1911/whistle/bridge/internal/state"
	"github.com/froid1911/whistle/bridge/internal/store"
	"github.com/froid1911/whistle/bridge/internal/transcript"
)

type env struct {
	t    *testing.T
	home string
	st   *store.Store
	r    *fakeReader
	m    *Manager
	prev state.State
}

func newEnv(t *testing.T) *env {
	t.Helper()
	st, err := store.Open(filepath.Join(t.TempDir(), "bridge.db"))
	if err != nil {
		t.Fatal(err)
	}
	e := &env{t: t, home: t.TempDir(), st: st, r: &fakeReader{}}
	e.m = e.newManager()
	t.Cleanup(func() { e.m.Close(); st.Close() })
	return e
}

func (e *env) newManager() *Manager {
	m := NewManager(e.st, e.r, e.home, slog.New(slog.NewTextHandler(io.Discard, nil)))
	m.RetryMin = 10 * time.Millisecond
	m.RetryMax = 50 * time.Millisecond
	return m
}

func (e *env) claudePath(id string) string {
	return filepath.Join(e.home, ".claude", "projects", "-home-dev-demo", id+".jsonl")
}

func appendLine(t *testing.T, path, line string) {
	t.Helper()
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		t.Fatal(err)
	}
	f, err := os.OpenFile(path, os.O_APPEND|os.O_CREATE|os.O_WRONLY, 0o644)
	if err != nil {
		t.Fatal(err)
	}
	defer f.Close()
	if _, err := f.WriteString(line + "\n"); err != nil {
		t.Fatal(err)
	}
}

func userLine(id, text string) string {
	return fmt.Sprintf(`{"type":"user","uuid":%q,"timestamp":"2026-01-01T00:00:01.000Z","message":{"role":"user","content":%q}}`, id, text)
}

func asstLine(id, text string) string {
	return fmt.Sprintf(`{"type":"assistant","uuid":%q,"timestamp":"2026-01-01T00:00:02.000Z","message":{"id":"m-%s","role":"assistant","content":[{"type":"text","text":%q}]}}`, id, id, text)
}

func toolUseLine(id string) string {
	return fmt.Sprintf(`{"type":"assistant","uuid":%q,"timestamp":"2026-01-01T00:00:02.000Z","message":{"id":"m-%s","role":"assistant","content":[{"type":"tool_use","id":"tu-%s","name":"Bash","input":{"command":"ls"}}]}}`, id, id, id)
}

func agentPane(id, agent, key, status string) state.Pane {
	return state.Pane{ID: id, TabID: "t1", WorkspaceID: "w1", Kind: "agent", Agent: agent, AgentStatus: status, SessionKey: key}
}

func stateOf(panes ...state.Pane) state.State {
	return state.State{Workspaces: []state.Workspace{}, Tabs: []state.Tab{}, Panes: panes, Layouts: []state.Layout{}}
}

func (e *env) apply(s state.State) {
	e.m.OnState(context.Background(), s, state.Diff(e.prev, s))
	e.prev = s
}

// log returns the change log as short tags: "message:<id>", "status:<from>-><to>",
// "upsert:<entity>:<id>", "remove:<entity>:<id>".
func (e *env) log() []string {
	es, err := e.st.Since(0, 100000)
	if err != nil {
		e.t.Fatal(err)
	}
	var out []string
	for _, en := range es {
		var p struct {
			Kind   string `json:"kind"`
			Entity string `json:"entity"`
			ID     string `json:"id"`
			From   string `json:"from"`
			To     string `json:"to"`
			Data   struct {
				ID string `json:"id"`
			} `json:"data"`
			Message store.Message `json:"message"`
		}
		if err := json.Unmarshal(en.Payload, &p); err != nil {
			e.t.Fatal(err)
		}
		if p.Kind != en.Kind {
			e.t.Fatalf("payload kind %q != entry kind %q", p.Kind, en.Kind)
		}
		switch en.Kind {
		case "message":
			out = append(out, "message:"+p.Message.ID)
		case "status":
			out = append(out, "status:"+p.From+"->"+p.To)
		case "upsert":
			out = append(out, "upsert:"+p.Entity+":"+p.Data.ID)
		case "remove":
			out = append(out, "remove:"+p.Entity+":"+p.ID)
		}
	}
	return out
}

func index(log []string, tag string) int {
	for i, s := range log {
		if s == tag {
			return i
		}
	}
	return -1
}

func (e *env) waitTag(tag string) {
	e.t.Helper()
	waitFor(e.t, func() bool { return index(e.log(), tag) >= 0 })
}

func TestReconcileWaitsForFinalReply(t *testing.T) {
	e := newEnv(t)
	p := e.claudePath("s1")
	appendLine(t, p, userLine("u1", "fix it"))
	appendLine(t, p, toolUseLine("a0"))
	e.apply(stateOf(agentPane("p1", "claude", "claude:s1", "working")))
	e.waitTag("message:a0")

	go func() {
		time.Sleep(500 * time.Millisecond)
		appendLine(t, p, asstLine("a1", "Fixed."))
	}()
	start := time.Now()
	e.apply(stateOf(agentPane("p1", "claude", "claude:s1", "idle")))
	if d := time.Since(start); d > 100*time.Millisecond {
		t.Fatalf("OnState blocked %v", d)
	}
	e.waitTag("status:working->idle")
	if d := time.Since(start); d < 400*time.Millisecond || d > 2*time.Second {
		t.Fatalf("status after %v", d)
	}
	l := e.log()
	if ia, is := index(l, "message:a1"), index(l, "status:working->idle"); ia < 0 || ia > is {
		t.Fatalf("want message before status: %v", l)
	}
}

func TestReconcileTimeout(t *testing.T) {
	e := newEnv(t)
	e.m.ReconcileTimeout = 300 * time.Millisecond
	p := e.claudePath("s1")
	appendLine(t, p, userLine("u1", "fix it"))
	e.apply(stateOf(agentPane("p1", "claude", "claude:s1", "working")))
	e.waitTag("message:u1")
	start := time.Now()
	e.apply(stateOf(agentPane("p1", "claude", "claude:s1", "done")))
	e.waitTag("status:working->done")
	if d := time.Since(start); d < 300*time.Millisecond {
		t.Fatalf("status after %v, want >= timeout", d)
	}
	for _, s := range e.log() {
		if strings.HasPrefix(s, "message:screen") {
			t.Fatalf("unexpected screen message for transcript agent: %v", e.log())
		}
	}
}

func TestReconcileDefaultTimeoutIs3s(t *testing.T) {
	e := newEnv(t)
	if e.m.ReconcileTimeout != 3*time.Second {
		t.Fatal(e.m.ReconcileTimeout)
	}
}

func TestOtherStatusAppendedImmediately(t *testing.T) {
	e := newEnv(t)
	e.m.ReconcileTimeout = 10 * time.Second
	appendLine(t, e.claudePath("s1"), userLine("u1", "hi"))
	e.apply(stateOf(agentPane("p1", "claude", "claude:s1", "idle")))
	start := time.Now()
	e.apply(stateOf(agentPane("p1", "claude", "claude:s1", "working")))
	e.apply(stateOf(agentPane("p1", "claude", "claude:s1", "blocked")))
	e.waitTag("status:working->blocked")
	if d := time.Since(start); d > time.Second {
		t.Fatalf("took %v", d)
	}
}

func TestNoTranscriptAppendsScreen(t *testing.T) {
	e := newEnv(t)
	e.r.set(fixture(t, "claude_working.txt"))
	e.apply(stateOf(agentPane("p1", "codex", "", "working")))
	e.apply(stateOf(agentPane("p1", "codex", "", "idle")))
	e.waitTag("status:working->idle")
	es, _ := e.st.Since(0, 1000)
	var screen *store.Message
	var screenIdx, statusIdx int
	for i, en := range es {
		var p struct {
			SessionKey string        `json:"session_key"`
			Message    store.Message `json:"message"`
		}
		json.Unmarshal(en.Payload, &p)
		if en.Kind == "message" && p.Message.Role == "screen" {
			m := p.Message
			screen, screenIdx = &m, i
			if p.SessionKey != "pane:p1" {
				t.Errorf("screen session_key %q", p.SessionKey)
			}
		}
		if en.Kind == "status" {
			statusIdx = i
		}
	}
	if screen == nil || screenIdx > statusIdx {
		t.Fatalf("want screen message before status: %v", e.log())
	}
	if screen.Text != Clean("codex", fixture(t, "claude_working.txt")) || screen.TS == "" {
		t.Fatalf("screen = %+v", screen)
	}
	if n := e.r.calls.Load(); n != 1 {
		t.Fatalf("reads = %d", n)
	}
}

func TestSessionSwitch(t *testing.T) {
	e := newEnv(t)
	pa, pb := e.claudePath("sa"), e.claudePath("sb")
	appendLine(t, pa, userLine("ua1", "a"))
	appendLine(t, pb, userLine("ub1", "b"))
	e.apply(stateOf(agentPane("p1", "claude", "claude:sa", "idle")))
	e.waitTag("message:ua1")
	e.apply(stateOf(agentPane("p1", "claude", "claude:sb", "idle")))
	e.waitTag("message:ub1")
	e.m.barrier()
	if keys := e.m.tailerKeys(); len(keys) != 1 || keys[0] != "claude:sb" {
		t.Fatalf("tailers = %v", keys)
	}
	appendLine(t, pa, userLine("ua2", "a2"))
	appendLine(t, pb, userLine("ub2", "b2"))
	e.waitTag("message:ub2")
	time.Sleep(100 * time.Millisecond)
	if index(e.log(), "message:ua2") >= 0 {
		t.Fatal("old session still tailed")
	}
}

// freezeTail makes the live tailer stop delivering (as if it had not yet
// noticed new writes), so only a flush on stop can capture them.
func (e *env) freezeTail() *atomic.Bool {
	var frozen atomic.Bool
	e.m.tail = func(ctx context.Context, path string, off int64, p transcript.Parser, key string, emit func([]store.Message, int64)) error {
		return transcript.Tail(ctx, path, off, p, key, func(ms []store.Message, o int64) {
			if !frozen.Load() {
				emit(ms, o)
			}
		})
	}
	return &frozen
}

func TestPaneRemovedWhileWorkingFlushesReply(t *testing.T) {
	e := newEnv(t)
	frozen := e.freezeTail()
	p := e.claudePath("s1")
	appendLine(t, p, userLine("u1", "fix it"))
	e.apply(stateOf(agentPane("p1", "claude", "claude:s1", "working")))
	e.waitTag("message:u1")
	frozen.Store(true)
	appendLine(t, p, asstLine("a1", "Fixed."))
	time.Sleep(50 * time.Millisecond)
	e.apply(stateOf()) // pane vanished while working: remove only, no status
	e.m.barrier()
	l := e.log()
	if ia, ir := index(l, "message:a1"), index(l, "remove:pane:p1"); ia < 0 || ir < 0 || ia > ir {
		t.Fatalf("want reply flushed before remove: %v", l)
	}
	if len(e.m.tailerKeys()) != 0 {
		t.Fatal("tailer not stopped")
	}
}

func TestPaneTurnsShellWhileWorkingFlushesReply(t *testing.T) {
	e := newEnv(t)
	frozen := e.freezeTail()
	p := e.claudePath("s1")
	appendLine(t, p, userLine("u1", "fix it"))
	e.apply(stateOf(agentPane("p1", "claude", "claude:s1", "working")))
	e.waitTag("message:u1")
	frozen.Store(true)
	appendLine(t, p, asstLine("a1", "Fixed."))
	time.Sleep(50 * time.Millisecond)
	sh := state.Pane{ID: "p1", TabID: "t1", WorkspaceID: "w1", Kind: "shell"}
	e.apply(stateOf(sh))
	e.m.barrier()
	l := e.log()
	last := -1
	for i, tag := range l {
		if tag == "upsert:pane:p1" {
			last = i
		}
	}
	if ia := index(l, "message:a1"); ia < 0 || ia > last {
		t.Fatalf("want reply flushed before the shell upsert: %v", l)
	}
}

func TestCoalescedTurnStillCaptured(t *testing.T) {
	// idle -> working -> idle within one snapshot window: no status edge at all.
	e := newEnv(t)
	p := e.claudePath("s1")
	e.apply(stateOf(agentPane("p1", "claude", "claude:s1", "idle")))
	appendLine(t, p, userLine("u1", "quick"))
	appendLine(t, p, asstLine("a1", "Done."))
	e.waitTag("message:a1")
}

func TestTranscriptAppearsLater(t *testing.T) {
	e := newEnv(t)
	key := "claude:fresh"
	if e.m.HasTranscript(key) {
		t.Fatal("HasTranscript before file exists")
	}
	e.apply(stateOf(agentPane("p1", "claude", key, "working")))
	time.Sleep(80 * time.Millisecond) // a few retries while the directory is missing
	if e.m.HasTranscript(key) {
		t.Fatal("HasTranscript before file exists")
	}
	appendLine(t, e.claudePath("fresh"), userLine("u1", "hello"))
	e.waitTag("message:u1")
	waitFor(t, func() bool { return e.m.HasTranscript(key) })
}

func TestCursorResume(t *testing.T) {
	e := newEnv(t)
	p := e.claudePath("s1")
	appendLine(t, p, userLine("u1", "a"))
	appendLine(t, p, asstLine("a1", "b"))
	e.apply(stateOf(agentPane("p1", "claude", "claude:s1", "idle")))
	e.waitTag("message:a1")
	fi, _ := os.Stat(p)
	waitFor(t, func() bool { _, off, ok := e.st.Cursor("claude:s1"); return ok && off == fi.Size() })
	e.m.Close()

	appendLine(t, p, userLine("u2", "c"))
	e.m = e.newManager()
	e.prev = state.State{}
	e.apply(stateOf(agentPane("p1", "claude", "claude:s1", "idle")))
	e.waitTag("message:u2")
	e.m.barrier()
	if off := e.m.startOffset("claude:s1"); off != fi.Size() {
		t.Fatalf("resumed at %d, want %d", off, fi.Size())
	}
	fi2, _ := os.Stat(p)
	waitFor(t, func() bool { _, off, _ := e.st.Cursor("claude:s1"); return off == fi2.Size() })
}

func TestHasTranscriptConcurrent(t *testing.T) {
	e := newEnv(t)
	e.m.ReconcileTimeout = 20 * time.Millisecond
	appendLine(t, e.claudePath("s1"), userLine("u1", "a"))
	var wg sync.WaitGroup
	for i := 0; i < 8; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for j := 0; j < 50; j++ {
				e.m.HasTranscript("claude:s1")
				e.m.HasTranscript("claude:nope")
			}
		}()
	}
	for j := 0; j < 10; j++ {
		st := "idle"
		if j%2 == 1 {
			st = "working"
		}
		e.apply(stateOf(agentPane("p1", "claude", "claude:s1", st)))
	}
	wg.Wait()
	e.m.barrier()
	if !e.m.HasTranscript("claude:s1") || e.m.HasTranscript("claude:nope") {
		t.Fatal("wrong HasTranscript")
	}
}

func TestOtherPaneNotBlockedByReconcile(t *testing.T) {
	e := newEnv(t)
	e.m.ReconcileTimeout = 10 * time.Second
	appendLine(t, e.claudePath("sa"), userLine("ua", "a")) // reply never arrives
	appendLine(t, e.claudePath("sb"), userLine("ub", "b"))
	e.apply(stateOf(agentPane("pA", "claude", "claude:sa", "working"), agentPane("pB", "claude", "claude:sb", "working")))
	e.waitTag("message:ua")
	e.waitTag("message:ub")

	start := time.Now()
	e.apply(stateOf(agentPane("pA", "claude", "claude:sa", "idle"), agentPane("pB", "claude", "claude:sb", "working")))
	e.apply(stateOf(agentPane("pA", "claude", "claude:sa", "idle"), agentPane("pB", "claude", "claude:sb", "blocked")))
	if d := time.Since(start); d > 100*time.Millisecond {
		t.Fatalf("OnState blocked %v", d)
	}
	e.waitTag("status:working->blocked")
	if d := time.Since(start); d > 500*time.Millisecond {
		t.Fatalf("pane B status delayed %v by pane A reconcile", d)
	}
	if index(e.log(), "status:working->idle") >= 0 {
		t.Fatal("pane A status appended before its reconcile finished")
	}
}

func TestAppendFailureRetriesBatch(t *testing.T) {
	e := newEnv(t)
	var failOnce atomic.Bool
	failOnce.Store(true)
	e.m.appendMessages = func(key string, msgs []store.Message) ([]store.Entry, error) {
		for _, m := range msgs {
			if m.ID == "a1" && failOnce.CompareAndSwap(true, false) {
				return nil, fmt.Errorf("disk full")
			}
		}
		return e.st.AppendMessages(key, msgs)
	}
	p := e.claudePath("s1")
	appendLine(t, p, userLine("u1", "fix it"))
	e.apply(stateOf(agentPane("p1", "claude", "claude:s1", "working")))
	e.waitTag("message:u1")
	appendLine(t, p, asstLine("a1", "Fixed."))
	waitFor(t, func() bool { return !failOnce.Load() }) // the failure happened
	e.apply(stateOf(agentPane("p1", "claude", "claude:s1", "idle")))
	e.waitTag("message:a1")
	e.waitTag("status:working->idle")
	l := e.log()
	if ia, is := index(l, "message:a1"), index(l, "status:working->idle"); ia > is {
		t.Fatalf("want message before status: %v", l)
	}
	fi, _ := os.Stat(p)
	waitFor(t, func() bool { _, off, _ := e.st.Cursor("claude:s1"); return off == fi.Size() })
}

func TestCancelledCallerCtxStillReconciles(t *testing.T) {
	e := newEnv(t)
	p := e.claudePath("s1")
	appendLine(t, p, userLine("u1", "fix it"))
	e.apply(stateOf(agentPane("p1", "claude", "claude:s1", "working")))
	e.waitTag("message:u1")
	go func() {
		time.Sleep(200 * time.Millisecond)
		appendLine(t, p, asstLine("a1", "Fixed."))
	}()
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	s := stateOf(agentPane("p1", "claude", "claude:s1", "idle"))
	e.m.OnState(ctx, s, state.Diff(e.prev, s))
	e.waitTag("status:working->idle")
	l := e.log()
	if ia, is := index(l, "message:a1"), index(l, "status:working->idle"); ia < 0 || ia > is {
		t.Fatalf("want message before status: %v", l)
	}
}
