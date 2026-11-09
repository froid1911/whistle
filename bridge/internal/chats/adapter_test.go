package chats

import (
	"context"
	"encoding/json"
	"testing"

	"github.com/froid1911/whistle/bridge/internal/herdr"
	"github.com/froid1911/whistle/bridge/internal/testutil/fakeherdr"
)

func TestHerdrAdapterReadNestedResult(t *testing.T) {
	f := fakeherdr.New(t)
	f.Handle("agent.read", func(json.RawMessage) (any, *herdr.APIError) {
		// Shape verified against herdr 0.9.5.
		return map[string]any{"type": "pane_read", "read": map[string]any{
			"pane_id": "w1:p1", "source": "recent_unwrapped", "format": "text", "text": "hello screen",
		}}, nil
	})
	got, err := HerdrAdapter{C: herdr.NewRedialer(f.SocketPath())}.Read(context.Background(), "w1:p1", "recent_unwrapped", 40)
	if err != nil || got != "hello screen" {
		t.Fatalf("got %q, %v", got, err)
	}
}
