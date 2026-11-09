package server

import (
	"bytes"
	"context"
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/froid1911/whistle/bridge/internal/transcript"
)

// TestTruncatedToolInputFixture renders a real oversized tool_use (parsed by
// the transcript parser, stored, and framed exactly as on the wire) into
// testdata/truncated_tool_call.json. The Android tests decode the same file
// (copied to android/app/src/test/resources/). Regenerate with
// WHISTLE_UPDATE_FIXTURES=1 go test ./internal/server -run Fixture.
func TestTruncatedToolInputFixture(t *testing.T) {
	h := newHarness(t, nil)
	big := strings.Repeat("abcdefgh", 4096) // 32 KiB > 16 KiB cap
	line := `{"type":"assistant","uuid":"a1","timestamp":"2026-11-08T10:00:00Z","message":{"content":[{"type":"tool_use","id":"toolu_big","name":"Write","input":{"file_path":"/tmp/x","content":"` + big + `"}}]}}`
	msgs, err := transcript.Claude{}.ParseLine("claude:s1", []byte(line))
	if err != nil || len(msgs) != 1 || !msgs[0].Truncated {
		t.Fatalf("%v %+v", err, msgs)
	}
	es, err := h.st.AppendMessages("claude:s1", msgs)
	if err != nil || len(es) != 1 {
		t.Fatalf("%v %d", err, len(es))
	}
	res, err := h.srv.dispatch(context.Background(), "chat.history", json.RawMessage(`{"session_key":"claude:s1"}`))
	if err != nil {
		t.Fatal(err)
	}
	hist, _ := json.Marshal(map[string]any{"t": "res", "id": "c1", "result": res})
	fx, _ := json.MarshalIndent(map[string]json.RawMessage{
		"change":  changeFrame(es[0]),
		"history": hist,
	}, "", " ")
	fx = append(fx, '\n')
	// Contract: input is an object.
	var chk struct {
		Message struct {
			Input map[string]string `json:"input"`
		} `json:"message"`
	}
	if err := json.Unmarshal(changeFrame(es[0]), &chk); err != nil || chk.Message.Input["_truncated"] == "" {
		t.Fatalf("change frame input not an object: %v", err)
	}
	path := filepath.Join("testdata", "truncated_tool_call.json")
	if os.Getenv("WHISTLE_UPDATE_FIXTURES") != "" {
		os.MkdirAll("testdata", 0o755)
		if err := os.WriteFile(path, fx, 0o644); err != nil {
			t.Fatal(err)
		}
	}
	want, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(want, fx) {
		t.Fatal("fixture out of date; regenerate with WHISTLE_UPDATE_FIXTURES=1 and copy to android/app/src/test/resources/")
	}
}
