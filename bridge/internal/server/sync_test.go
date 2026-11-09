package server

import (
	"path/filepath"
	"sync"
	"testing"
	"time"

	"github.com/froid1911/whistle/bridge/internal/store"
)

func TestHelloFreshGetsState(t *testing.T) {
	h := newHarness(t, nil)
	appendN(t, h.st, 3)
	c := h.dial()
	w := c.hello("", 0)
	if w["resync"] != true || w.n("head_seq") != 3 || w.s("server_id") != h.st.ServerID() || w.n("protocol") != 1 || w.s("herdr_version") != "0.9.5" {
		t.Fatalf("welcome %v", w)
	}
	s := c.recv()
	if s.s("t") != "state" || s.n("seq") != 3 {
		t.Fatalf("state %v", s)
	}
	panes := s["state"].(map[string]any)["panes"].([]any)
	if len(panes) != 2 {
		t.Fatalf("panes %v", panes)
	}
	if y := c.recv(); y.s("t") != "synced" || y.n("seq") != 3 {
		t.Fatalf("synced %v", y)
	}
	// Live changes follow.
	appendN(t, h.st, 1)
	if ch := c.recv(); ch.s("t") != "change" || ch.n("seq") != 4 || ch.s("kind") != "remove" || ch.s("entity") != "pane" {
		t.Fatalf("change %v", ch)
	}
}

func TestHelloResumeGetsOnlyNewer(t *testing.T) {
	h := newHarness(t, nil)
	appendN(t, h.st, 5)
	c := h.dial()
	if w := c.hello(h.st.ServerID(), 2); w["resync"] != false || w.n("head_seq") != 5 {
		t.Fatalf("welcome %v", w)
	}
	for want := int64(3); want <= 5; want++ {
		if m := c.recv(); m.s("t") != "change" || m.n("seq") != want {
			t.Fatalf("want change %d got %v", want, m)
		}
	}
	if y := c.recv(); y.s("t") != "synced" || y.n("seq") != 5 {
		t.Fatalf("synced %v", y)
	}
}

func TestHelloUnknownServerIDResyncs(t *testing.T) {
	h := newHarness(t, nil)
	appendN(t, h.st, 5)
	c := h.dial()
	if w := c.hello("someone-else", 2); w["resync"] != true {
		t.Fatalf("welcome %v", w)
	}
	if s := c.recv(); s.s("t") != "state" || s.n("seq") != 5 {
		t.Fatalf("state %v", s)
	}
}

func TestHelloFromFutureResyncs(t *testing.T) {
	h := newHarness(t, nil)
	appendN(t, h.st, 2)
	c := h.dial()
	if w := c.hello(h.st.ServerID(), 9); w["resync"] != true {
		t.Fatalf("welcome %v", w)
	}
}

func TestResyncWhenLogFullyPruned(t *testing.T) {
	h := newHarness(t, nil)
	appendN(t, h.st, 3)
	if err := h.st.Prune(0); err != nil {
		t.Fatal(err)
	}
	c := h.dial()
	if w := c.hello(h.st.ServerID(), 1); w["resync"] != true || w.n("head_seq") != 3 {
		t.Fatalf("since<head on empty log must resync: %v", w)
	}
	c2 := h.dial()
	if w := c2.hello(h.st.ServerID(), 3); w["resync"] != false {
		t.Fatalf("since==head needs no resync: %v", w)
	}
	if y := c2.recv(); y.s("t") != "synced" || y.n("seq") != 3 {
		t.Fatalf("synced %v", y)
	}
}

func TestResyncWhenOlderThanRetained(t *testing.T) {
	h := newHarness(t, nil)
	appendN(t, h.st, 10)
	h.st.Prune(3) // keeps 8..10
	c := h.dial()
	if w := c.hello(h.st.ServerID(), 6); w["resync"] != true {
		t.Fatalf("welcome %v", w)
	}
	c2 := h.dial()
	if w := c2.hello(h.st.ServerID(), 7); w["resync"] != false {
		t.Fatalf("welcome %v", w)
	}
	if m := c2.recv(); m.n("seq") != 8 {
		t.Fatalf("%v", m)
	}
}

// flakySubscribe wraps Store.Subscribe so every subscription is closed after
// `after` forwarded entries, like a slow subscriber being cut off.
func flakySubscribe(st *store.Store, after int) (func() (<-chan store.Entry, func()), func() int) {
	var mu sync.Mutex
	subs := 0
	sub := func() (<-chan store.Entry, func()) {
		ch, cancel := st.Subscribe()
		mu.Lock()
		subs++
		mu.Unlock()
		out := make(chan store.Entry, 8)
		go func() {
			defer close(out)
			n := 0
			for e := range ch {
				if n == after {
					cancel()
					return
				}
				out <- e
				n++
			}
		}()
		return out, cancel
	}
	return sub, func() int { mu.Lock(); defer mu.Unlock(); return subs }
}

func TestNoGapBetweenReplayAndLive(t *testing.T) {
	h := newHarness(t, nil)
	sub, count := flakySubscribe(h.st, 40)
	h.srv.subscribe = sub
	appendN(t, h.st, 300)
	const total = 700
	c := h.dial()
	done := make(chan struct{})
	t.Cleanup(func() { <-done }) // runs before the store closes
	go func() {
		defer close(done)
		for i := 0; i < total-300; i++ {
			if _, err := h.st.Append("remove", map[string]any{"kind": "remove", "entity": "pane", "id": "y"}); err != nil {
				return
			}
			if i%50 == 0 {
				time.Sleep(time.Millisecond)
			}
		}
	}()
	c.hello(h.st.ServerID(), 1)
	last := int64(1)
	synced := false
	for last < total {
		m := c.recv()
		switch m.s("t") {
		case "change":
			if m.n("seq") != last+1 {
				t.Fatalf("got seq %d after %d", m.n("seq"), last)
			}
			last = m.n("seq")
		case "synced":
			synced = true
		default:
			t.Fatalf("unexpected %v", m)
		}
	}
	<-done
	if !synced {
		t.Fatal("no synced")
	}
	if count() < 3 {
		t.Fatalf("forced slow-subscriber closes not exercised: %d subscriptions", count())
	}
	if m, err := c.recvWithin(200 * time.Millisecond); err == nil {
		t.Fatalf("extra message %v", m)
	}
}

func TestHerdrOnlineMessage(t *testing.T) {
	h := newHarness(t, nil)
	h.state.setOnline(false)
	c := h.dial()
	c.send(map[string]any{"t": "hello", "server_id": "", "since": 0, "client": "test/1"})
	if w := c.recv(); w.s("t") != "welcome" {
		t.Fatalf("want welcome, got %v", w)
	}
	if m := c.recv(); m.s("t") != "herdr" || m["online"] != false {
		t.Fatalf("want herdr offline right after welcome, got %v", m)
	}
	c.recvT("synced")
	// A change of Online() is pushed within 1 s, and only once.
	h.state.setOnline(true)
	start := time.Now()
	m := c.recvT("herdr")
	if m["online"] != true || time.Since(start) > time.Second {
		t.Fatalf("herdr %v after %v", m, time.Since(start))
	}
	h.state.setOnline(false)
	if m := c.recvT("herdr"); m["online"] != false {
		t.Fatalf("herdr %v", m)
	}
	if m, err := c.recvWithin(700 * time.Millisecond); err != errTimeout {
		t.Fatalf("unexpected frame %v (%v)", m, err)
	}
}

// C2: a client whose cursor predates the bridge's start cannot trust the
// pre-restart log to be complete (state diffs of the downtime are unknown),
// so it must be resynced; clients that synced after the start resume.
func TestResumeAcrossBridgeRestartResyncs(t *testing.T) {
	path := filepath.Join(t.TempDir(), "r.db")
	st, err := store.Open(path)
	if err != nil {
		t.Fatal(err)
	}
	appendN(t, st, 5)
	id := st.ServerID()
	st.Close()
	st2, err := store.Open(path) // "restart"
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { st2.Close() })
	h := newHarness(t, func(_ *Config, d *Deps) { d.Store = st2 })
	c := h.dial()
	if w := c.hello(id, 5); w["resync"] != true {
		t.Fatalf("resume at pre-restart head: %v", w)
	}
	if s := c.recv(); s.s("t") != "state" {
		t.Fatalf("state %v", s)
	}
	appendN(t, st2, 2) // post-restart changes
	c2 := h.dial()
	if w := c2.hello(id, 6); w["resync"] != false {
		t.Fatalf("resume after restart: %v", w)
	}
}

// A client resynced by a fresh process (head == OpenHead) must resume, not
// resync again, when it reconnects with since == head.
func TestResyncOnFreshProcessThenResumes(t *testing.T) {
	path := filepath.Join(t.TempDir(), "r.db")
	st, err := store.Open(path)
	if err != nil {
		t.Fatal(err)
	}
	appendN(t, st, 5)
	id := st.ServerID()
	st.Close()
	st2, err := store.Open(path) // "restart", no new entries yet
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { st2.Close() })
	h := newHarness(t, func(_ *Config, d *Deps) { d.Store = st2 })
	c := h.dial()
	w := c.hello(id, 5)
	if w["resync"] != true {
		t.Fatalf("first hello: %v", w)
	}
	head := int64(w["head_seq"].(float64))
	if head <= 5 {
		t.Fatalf("head %d not above open head", head)
	}
	if s := c.recv(); s.s("t") != "state" {
		t.Fatalf("state %v", s)
	}
	for i := 0; i < 2; i++ { // second and third reconnect
		c2 := h.dial()
		if w := c2.hello(id, head); w["resync"] != false || int64(w["head_seq"].(float64)) != head {
			t.Fatalf("reconnect %d: %v", i, w)
		}
	}
}
