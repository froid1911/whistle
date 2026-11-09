package state

import (
	"context"
	"encoding/json"
	"io"
	"log/slog"
	"sync"
	"testing"
	"time"

	"github.com/froid1911/whistle/bridge/internal/herdr"
	"github.com/froid1911/whistle/bridge/internal/testutil/fakeherdr"
)

// C2: a watcher seeded with the state persisted before a restart diffs the
// first snapshot against it, so a pane that vanished meanwhile gets a remove.
func TestSeededWatcherEmitsRemoveForVanishedPane(t *testing.T) {
	f := fakeherdr.New(t)
	before := snap("working")
	before.Panes = append(before.Panes, herdr.Pane{PaneID: "w1:p9", TabID: "w1:t1", WorkspaceID: "w1", Cwd: "/x", Label: "ghost"})
	f.SetSnapshot(snap("idle")) // p9 closed while the bridge was down
	w := NewWatcher(f.SocketPath(), slog.New(slog.NewTextHandler(io.Discard, nil)))
	w.Seed(FromSnapshot(before))
	var mu sync.Mutex
	var persisted []State
	w.Persist = func(s State) { mu.Lock(); persisted = append(persisted, s); mu.Unlock() }
	s := newSink()
	ctx, cancel := context.WithCancel(context.Background())
	exited := make(chan struct{})
	go func() { w.Run(ctx, s.on); close(exited) }()
	t.Cleanup(func() { cancel(); <-exited })
	got := s.wait(t, 2*time.Second, func([]Change) bool { return true })
	var removed, status bool
	for _, c := range got {
		if c.Kind == "remove" && c.Entity == "pane" && c.ID == "w1:p9" {
			removed = true
		}
		if c.Kind == "upsert" && c.Entity == "workspace" {
			t.Fatalf("unchanged workspace re-upserted: %+v", got)
		}
		if c.Kind == "status" && c.From == "working" && c.To == "idle" {
			status = true
		}
	}
	if !removed || !status {
		t.Fatalf("want remove w1:p9 and status working->idle, got %+v", got)
	}
	mu.Lock()
	defer mu.Unlock()
	if len(persisted) == 0 || len(persisted[len(persisted)-1].Panes) != 2 {
		t.Fatalf("persisted %+v", persisted)
	}
}

// M1: a herdr that accepts but never answers must not leave the watcher
// online with frozen state.
func TestRefreshTimeoutMarksOffline(t *testing.T) {
	old := refreshTimeout
	refreshTimeout = 200 * time.Millisecond
	t.Cleanup(func() { refreshTimeout = old })
	f := fakeherdr.New(t)
	f.SetSnapshot(snap("idle"))
	w, s, _ := start(t, f, nil)
	s.wait(t, 2*time.Second, func([]Change) bool { return true })
	if !w.Online() {
		t.Fatal("not online")
	}
	hang := make(chan struct{})
	var once sync.Once
	release := func() { once.Do(func() { close(hang) }) }
	t.Cleanup(release) // runs before the fake's/watcher's cleanups
	f.Handle("session.snapshot", func(json.RawMessage) (any, *herdr.APIError) {
		<-hang
		return map[string]any{"snapshot": snap("idle")}, nil
	})
	f.Emit("pane_updated", map[string]any{"type": "pane_updated"})
	deadline := time.Now().Add(3 * time.Second)
	for w.Online() {
		if time.Now().After(deadline) {
			t.Fatal("still online while herdr hangs")
		}
		time.Sleep(20 * time.Millisecond)
	}
	release()
	f.Handle("session.snapshot", func(json.RawMessage) (any, *herdr.APIError) {
		return map[string]any{"type": "session_snapshot", "snapshot": snap("working")}, nil
	})
	s.wait(t, 5*time.Second, hasPaneStatus("working"))
	if !w.Online() {
		t.Fatal("did not reconnect")
	}
}
