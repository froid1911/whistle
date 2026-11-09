package state

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"log/slog"
	"sync"
	"testing"
	"time"

	"github.com/froid1911/whistle/bridge/internal/testutil/fakeherdr"
)

type sink struct {
	mu  sync.Mutex
	all [][]Change
	ch  chan []Change
}

func newSink() *sink { return &sink{ch: make(chan []Change, 100)} }
func (s *sink) on(_ State, c []Change) {
	s.mu.Lock()
	s.all = append(s.all, c)
	s.mu.Unlock()
	s.ch <- c
}

func (s *sink) wait(t *testing.T, d time.Duration, pred func([]Change) bool) []Change {
	t.Helper()
	to := time.After(d)
	for {
		select {
		case c := <-s.ch:
			if pred(c) {
				return c
			}
		case <-to:
			t.Fatal("timeout waiting for change")
		}
	}
}

func start(t *testing.T, f *fakeherdr.Fake, decorate func(*State)) (*Watcher, *sink, chan error) {
	t.Helper()
	w := NewWatcher(f.SocketPath(), slog.New(slog.NewTextHandler(io.Discard, nil)))
	w.Decorate = decorate
	s := newSink()
	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan error, 1)
	exited := make(chan struct{})
	go func() { done <- w.Run(ctx, s.on); close(exited) }()
	t.Cleanup(func() { cancel(); <-exited })
	return w, s, done
}

func hasPaneStatus(to string) func([]Change) bool {
	return func(c []Change) bool {
		for _, x := range c {
			if x.Kind == "status" && x.To == to {
				return true
			}
		}
		return false
	}
}

func TestWatcherDebouncesBursts(t *testing.T) {
	f := fakeherdr.New(t)
	f.SetSnapshot(snap("idle"))
	_, s, _ := start(t, f, nil)
	s.wait(t, 2*time.Second, func([]Change) bool { return true })
	time.Sleep(200 * time.Millisecond)
	base := len(f.Calls("session.snapshot"))
	for i := 0; i < 200; i++ {
		f.Emit("pane_updated", map[string]any{"type": "pane_updated"})
		time.Sleep(250 * time.Microsecond)
	}
	time.Sleep(400 * time.Millisecond)
	if n := len(f.Calls("session.snapshot")) - base; n > 3 || n < 1 {
		t.Fatalf("snapshots during burst: %d", n)
	}
}

func TestEventsLostTriggersResnapshot(t *testing.T) {
	f := fakeherdr.New(t)
	f.SetSnapshot(snap("idle"))
	_, s, _ := start(t, f, nil)
	s.wait(t, 2*time.Second, func([]Change) bool { return true })
	time.Sleep(200 * time.Millisecond)
	f.SetSnapshot(snap("working"))
	f.EmitEventsLost()
	s.wait(t, 2*time.Second, hasPaneStatus("working"))
}

func TestWatcherRecoversFromServerRestart(t *testing.T) {
	f := fakeherdr.New(t)
	f.SetSnapshot(snap("idle"))
	w, s, _ := start(t, f, nil)
	s.wait(t, 2*time.Second, func([]Change) bool { return true })
	if !w.Online() {
		t.Fatal("should be online")
	}
	f.Stop()
	deadline := time.Now().Add(2 * time.Second)
	for w.Online() && time.Now().Before(deadline) {
		time.Sleep(10 * time.Millisecond)
	}
	if w.Online() {
		t.Fatal("should be offline during outage")
	}
	f.SetSnapshot(snap("working"))
	f.Restart()
	s.wait(t, 2*time.Second, hasPaneStatus("working"))
	if !w.Online() {
		t.Fatal("should be online after restart")
	}
}

func TestProtocolMismatch(t *testing.T) {
	f := fakeherdr.New(t)
	sn := snap("idle")
	sn.Protocol = 21
	f.SetSnapshot(sn)
	_, _, done := start(t, f, nil)
	select {
	case err := <-done:
		if !errors.Is(err, ErrProtocolMismatch) {
			t.Fatalf("got %v", err)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("Run did not return")
	}
}

func TestDecorateAppliedBeforeDiff(t *testing.T) {
	f := fakeherdr.New(t)
	f.SetSnapshot(snap("idle"))
	_, s, _ := start(t, f, func(st *State) {
		for i := range st.Panes {
			if st.Panes[i].ID == "w1:p1" {
				st.Panes[i].HasTranscript = true
			}
		}
	})
	first := s.wait(t, 2*time.Second, func([]Change) bool { return true })
	found := false
	for _, c := range first {
		if c.Kind == "upsert" && c.Entity == "pane" && c.ID == "w1:p1" {
			b, _ := json.Marshal(c.Data)
			var m map[string]any
			json.Unmarshal(b, &m)
			if m["has_transcript"] != true {
				t.Fatalf("has_transcript not set: %s", b)
			}
			found = true
		}
	}
	if !found {
		t.Fatal("no pane upsert")
	}
	f.SetSnapshot(snap("idle"))
	f.EmitEventsLost()
	select {
	case c := <-s.ch:
		t.Fatalf("unexpected change: %+v", c)
	case <-time.After(500 * time.Millisecond):
	}
}

// Clean EOF on the subscription socket (no error line) must make the watcher
// reconnect, not hang. Repeated because Subscribe closes errs/events in an
// order the select may observe either way.
func TestWatcherReconnectsOnCleanClose(t *testing.T) {
	f := fakeherdr.New(t)
	f.SetSnapshot(snap("idle"))
	_, s, _ := start(t, f, nil)
	s.wait(t, 2*time.Second, func([]Change) bool { return true })
	statuses := []string{"working", "idle"}
	for i := 0; i < 12; i++ {
		time.Sleep(150 * time.Millisecond) // let the subscription establish
		to := statuses[i%2]
		f.SetSnapshot(snap(to))
		f.Restart()
		s.wait(t, 3*time.Second, hasPaneStatus(to))
	}
}

func TestWatcherReportsVersion(t *testing.T) {
	f := fakeherdr.New(t)
	sn := snap("idle")
	sn.Version = "0.9.5"
	f.SetSnapshot(sn)
	w, s, _ := start(t, f, nil)
	s.wait(t, 2*time.Second, func([]Change) bool { return true })
	if w.Version() != "0.9.5" {
		t.Fatalf("version %q", w.Version())
	}
}
