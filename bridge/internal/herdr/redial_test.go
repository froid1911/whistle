package herdr_test

import (
	"context"
	"testing"

	"github.com/froid1911/whistle/bridge/internal/herdr"
	"github.com/froid1911/whistle/bridge/internal/testutil/fakeherdr"
)

func TestRedialerReconnectsAfterRestart(t *testing.T) {
	f := fakeherdr.New(t)
	f.SetSnapshot(herdr.Snapshot{Protocol: 22})
	r := herdr.NewRedialer(f.SocketPath())
	defer r.Close()
	var c herdr.Caller = r
	ctx := context.Background()
	if err := c.Call(ctx, "session.snapshot", nil, nil); err != nil {
		t.Fatal(err)
	}
	f.Restart()
	// The cached connection died; the next call must dial a fresh one.
	var err error
	for i := 0; i < 3; i++ {
		if err = c.Call(ctx, "session.snapshot", nil, nil); err == nil {
			break
		}
	}
	if err != nil {
		t.Fatal(err)
	}
	if n := len(f.Calls("session.snapshot")); n < 2 {
		t.Fatalf("calls %d", n)
	}
}

func TestRedialerOfflineErrors(t *testing.T) {
	r := herdr.NewRedialer(t.TempDir() + "/nope.sock")
	if err := r.Call(context.Background(), "x", nil, nil); err == nil {
		t.Fatal("want error")
	}
}
