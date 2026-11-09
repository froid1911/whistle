package herdr_test

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/froid1911/whistle/bridge/internal/herdr"
	"github.com/froid1911/whistle/bridge/internal/testutil/fakeherdr"
)

func ctx(t *testing.T) context.Context {
	c, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	t.Cleanup(cancel)
	return c
}

func TestCallRoundTrip(t *testing.T) {
	f := fakeherdr.New(t)
	f.Handle("ping", func(json.RawMessage) (any, *herdr.APIError) {
		return map[string]string{"type": "pong"}, nil
	})
	c, err := herdr.Dial(ctx(t), f.SocketPath())
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close()
	var out struct{ Type string }
	if err := c.Call(ctx(t), "ping", map[string]any{}, &out); err != nil {
		t.Fatal(err)
	}
	if out.Type != "pong" {
		t.Fatalf("got %q", out.Type)
	}
}

func TestCallAPIError(t *testing.T) {
	f := fakeherdr.New(t)
	f.Handle("x", func(json.RawMessage) (any, *herdr.APIError) {
		return nil, &herdr.APIError{Code: "not_found", Message: "nope"}
	})
	c, _ := herdr.Dial(ctx(t), f.SocketPath())
	defer c.Close()
	err := c.Call(ctx(t), "x", nil, nil)
	if !herdr.IsCode(err, "not_found") || herdr.IsCode(err, "other") {
		t.Fatalf("err=%v", err)
	}
}

// herdr serves one request per connection, so concurrent callers go through
// a Redialer (one connection per call).
func TestConcurrentCalls(t *testing.T) {
	f := fakeherdr.New(t)
	f.Handle("echo", func(p json.RawMessage) (any, *herdr.APIError) {
		var in struct{ N int }
		json.Unmarshal(p, &in)
		return map[string]int{"n": in.N}, nil
	})
	c := herdr.NewRedialer(f.SocketPath())
	var wg sync.WaitGroup
	errs := make(chan error, 50)
	for i := 0; i < 50; i++ {
		wg.Add(1)
		go func(i int) {
			defer wg.Done()
			var out struct{ N int }
			if err := c.Call(ctx(t), "echo", map[string]int{"n": i}, &out); err != nil {
				errs <- err
			} else if out.N != i {
				errs <- fmt.Errorf("want %d got %d", i, out.N)
			}
		}(i)
	}
	wg.Wait()
	close(errs)
	for err := range errs {
		t.Error(err)
	}
}

func TestSnapshotUnwrap(t *testing.T) {
	f := fakeherdr.New(t)
	f.SetSnapshot(herdr.Snapshot{Protocol: 22, Panes: []herdr.Pane{
		{PaneID: "w1:p1", AgentSession: &herdr.AgentSession{Agent: "claude", Kind: "id", Value: "abc"}},
		{PaneID: "w1:p2"},
	}})
	c, _ := herdr.Dial(ctx(t), f.SocketPath())
	defer c.Close()
	s, err := c.Snapshot(ctx(t))
	if err != nil {
		t.Fatal(err)
	}
	if s.Protocol != 22 || len(s.Panes) != 2 || s.Panes[0].AgentSession.Value != "abc" {
		t.Fatalf("%+v", s)
	}
}

func TestSubscribeReceivesEvents(t *testing.T) {
	f := fakeherdr.New(t)
	evs, errs, err := herdr.Subscribe(ctx(t), f.SocketPath(), []herdr.Subscription{{Type: "pane.updated"}})
	if err != nil {
		t.Fatal(err)
	}
	f.Emit("pane_updated", map[string]string{"type": "pane_updated"})
	select {
	case e := <-evs:
		if e.Event != "pane_updated" {
			t.Fatalf("%+v", e)
		}
	case err := <-errs:
		t.Fatal(err)
	case <-time.After(3 * time.Second):
		t.Fatal("timeout")
	}
	f.Restart()
	select {
	case _, ok := <-evs:
		if ok {
			t.Fatal("expected close")
		}
	case <-time.After(3 * time.Second):
		t.Fatal("channel not closed")
	}
}

func TestSubscribeSendsPaneIDs(t *testing.T) {
	f := fakeherdr.New(t)
	_, _, err := herdr.Subscribe(ctx(t), f.SocketPath(), []herdr.Subscription{
		{Type: "pane.agent_status_changed", PaneID: "w1:p1"}})
	if err != nil {
		t.Fatal(err)
	}
	calls := f.Calls("events.subscribe")
	if len(calls) != 1 {
		t.Fatalf("calls=%d", len(calls))
	}
	var p struct {
		Subscriptions []herdr.Subscription
	}
	json.Unmarshal(calls[0], &p)
	if len(p.Subscriptions) != 1 || p.Subscriptions[0].PaneID != "w1:p1" {
		t.Fatalf("%s", calls[0])
	}
}

func TestSubscribeDeliversEventsLost(t *testing.T) {
	f := fakeherdr.New(t)
	_, errs, err := herdr.Subscribe(ctx(t), f.SocketPath(), []herdr.Subscription{{Type: "pane.updated"}})
	if err != nil {
		t.Fatal(err)
	}
	// more than the channel buffer, none dropped
	for i := 0; i < 40; i++ {
		f.EmitEventsLost()
	}
	for i := 0; i < 40; i++ {
		select {
		case err := <-errs:
			if !herdr.IsCode(err, "events_lost") {
				t.Fatalf("err=%v", err)
			}
		case <-time.After(3 * time.Second):
			t.Fatalf("lost error %d", i)
		}
	}
}

func TestMalformedLineFailsPendingCalls(t *testing.T) {
	f := fakeherdr.New(t)
	release := make(chan struct{})
	defer close(release)
	f.Handle("slow", func(json.RawMessage) (any, *herdr.APIError) {
		<-release
		return nil, nil
	})
	c, _ := herdr.Dial(ctx(t), f.SocketPath())
	defer c.Close()
	res := make(chan error, 1)
	go func() { res <- c.Call(ctx(t), "slow", nil, nil) }()
	for len(f.Calls("slow")) == 0 {
		time.Sleep(time.Millisecond)
	}
	f.SendRaw("garbage{")
	select {
	case err := <-res:
		if !errors.Is(err, herdr.ErrClosed) || !strings.Contains(err.Error(), "malformed") {
			t.Fatalf("err=%v", err)
		}
	case <-time.After(3 * time.Second):
		t.Fatal("call hung")
	}
	if err := c.Call(ctx(t), "slow", nil, nil); !errors.Is(err, herdr.ErrClosed) {
		t.Fatalf("later call err=%v", err)
	}
}

func TestIDLessResponseFailsPendingCalls(t *testing.T) {
	f := fakeherdr.New(t)
	release := make(chan struct{})
	defer close(release)
	f.Handle("slow", func(json.RawMessage) (any, *herdr.APIError) { <-release; return nil, nil })
	c, _ := herdr.Dial(ctx(t), f.SocketPath())
	defer c.Close()
	res := make(chan error, 1)
	go func() { res <- c.Call(ctx(t), "slow", nil, nil) }()
	for len(f.Calls("slow")) == 0 {
		time.Sleep(time.Millisecond)
	}
	f.SendRaw(`{"error":{"code":"invalid_request","message":"x"}}`)
	select {
	case err := <-res:
		if !errors.Is(err, herdr.ErrClosed) || !strings.Contains(err.Error(), "without id") {
			t.Fatalf("err=%v", err)
		}
	case <-time.After(3 * time.Second):
		t.Fatal("call hung")
	}
}
