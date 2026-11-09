package chats

import (
	"context"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

type fakeReader struct {
	mu    sync.Mutex
	text  string
	calls atomic.Int32
}

func (f *fakeReader) set(s string) { f.mu.Lock(); f.text = s; f.mu.Unlock() }

func (f *fakeReader) Read(ctx context.Context, paneID, source string, lines int) (string, error) {
	f.calls.Add(1)
	f.mu.Lock()
	defer f.mu.Unlock()
	return f.text, nil
}

func TestLiveSendsOnlyChanges(t *testing.T) {
	r := &fakeReader{text: "⏺ hello\n"}
	l := NewLive(r, 5*time.Millisecond)
	var mu sync.Mutex
	var sent []string
	stop := l.Watch("p1", "claude", func() string { return "working" }, func(s string) {
		mu.Lock()
		sent = append(sent, s)
		mu.Unlock()
	})
	defer stop()
	waitFor(t, func() bool { return r.calls.Load() >= 5 })
	r.set("⏺ hello\n⏺ world\n")
	waitFor(t, func() bool { mu.Lock(); defer mu.Unlock(); return len(sent) >= 2 })
	n := r.calls.Load()
	waitFor(t, func() bool { return r.calls.Load() >= n+5 })
	stop()
	mu.Lock()
	defer mu.Unlock()
	if len(sent) != 2 || sent[0] != "⏺ hello" || sent[1] != "⏺ hello\n⏺ world" {
		t.Fatalf("sent = %q", sent)
	}
}

func TestLiveStopsWhenIdle(t *testing.T) {
	r := &fakeReader{text: "⏺ hello"}
	l := NewLive(r, 5*time.Millisecond)
	var status atomic.Value
	status.Store("working")
	var sends atomic.Int32
	stop := l.Watch("p1", "claude", func() string { return status.Load().(string) }, func(string) { sends.Add(1) })
	defer stop()
	waitFor(t, func() bool { return sends.Load() == 1 })
	status.Store("idle")
	time.Sleep(20 * time.Millisecond)
	n := r.calls.Load()
	time.Sleep(50 * time.Millisecond)
	if got := r.calls.Load(); got != n {
		t.Fatalf("polled while idle: %d -> %d", n, got)
	}
	// A new turn sends again even if the text is unchanged.
	status.Store("blocked")
	waitFor(t, func() bool { return sends.Load() == 2 })
	stop()
	n = r.calls.Load()
	time.Sleep(30 * time.Millisecond)
	if r.calls.Load() != n {
		t.Fatal("polled after stop")
	}
}

func waitFor(t *testing.T, cond func() bool) {
	t.Helper()
	deadline := time.Now().Add(5 * time.Second)
	for !cond() {
		if time.Now().After(deadline) {
			t.Fatal("timeout waiting for condition")
		}
		time.Sleep(2 * time.Millisecond)
	}
}
