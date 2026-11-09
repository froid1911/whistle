package transcript

import (
	"bufio"
	"encoding/json"
	"os"
	"strings"
	"testing"

	"github.com/froid1911/whistle/bridge/internal/store"
)

func parseFile(t *testing.T, p Parser, path string) []store.Message {
	t.Helper()
	f, err := os.Open(path)
	if err != nil {
		t.Fatal(err)
	}
	defer f.Close()
	var out []store.Message
	sc := bufio.NewScanner(f)
	sc.Buffer(make([]byte, 1<<20), 1<<24)
	for sc.Scan() {
		m, err := p.ParseLine("k", sc.Bytes())
		if err != nil {
			t.Fatal(err)
		}
		out = append(out, m...)
	}
	return out
}

func roles(ms []store.Message) []string {
	var r []string
	for _, m := range ms {
		r = append(r, m.Role)
	}
	return r
}

func TestClaudeParsesTurn(t *testing.T) {
	ms := parseFile(t, Claude{}, "testdata/claude_basic.jsonl")
	if got := strings.Join(roles(ms), ","); got != "user,thinking,assistant,tool_call,tool_result" {
		t.Fatalf("roles %s", got)
	}
	if ms[3].ToolName != "Edit" || ms[3].ToolCallID != "toolu_1" || len(ms[3].Input) == 0 {
		t.Fatalf("call %+v", ms[3])
	}
	if ms[4].ToolCallID != "toolu_1" || ms[4].Text != "edited ok" || ms[4].ID != "u2" || ms[4].TS == "" {
		t.Fatalf("result %+v", ms[4])
	}
}

func TestClaudeSkipsMetaAndSidechain(t *testing.T) {
	for _, l := range []string{
		`{"type":"system","uuid":"x"}`,
		`{"type":"attachment","uuid":"x"}`,
		`{"type":"user","isMeta":true,"uuid":"x","message":{"content":"hi"}}`,
		`{"type":"user","isSidechain":true,"uuid":"x","message":{"content":"hi"}}`,
		`{"type":"user","uuid":"x","message":{"content":"<command-name>/clear</command-name>"}}`,
		`{"type":"assistant","uuid":"x","message":{"content":[{"type":"thinking","thinking":""}]}}`,
	} {
		ms, err := Claude{}.ParseLine("k", []byte(l))
		if err != nil || len(ms) != 0 {
			t.Errorf("%s -> %v %v", l, ms, err)
		}
	}
}

func TestClaudeToolResultBlocks(t *testing.T) {
	l := `{"type":"user","uuid":"x","message":{"content":[{"type":"tool_result","tool_use_id":"t","content":[{"type":"text","text":"a"},{"type":"image"},{"type":"text","text":"b"}],"is_error":true}]}}`
	ms, _ := Claude{}.ParseLine("k", []byte(l))
	if len(ms) != 1 || ms[0].Text != "a\n[image]\nb" || !ms[0].IsError {
		t.Fatalf("%+v", ms)
	}
}

func TestOmpParsesTurn(t *testing.T) {
	ms := parseFile(t, Omp{}, "testdata/omp_basic.jsonl")
	if got := strings.Join(roles(ms), ","); got != "user,thinking,assistant,tool_call,tool_result" {
		t.Fatalf("roles %s", got)
	}
	if ms[1].ID != "o2#0" || ms[3].ID != "o2#2" || ms[3].ToolName != "bash" || ms[3].ToolCallID != "call_1" {
		t.Fatalf("%+v", ms)
	}
	if ms[4].Text != "a.txt\n[image]" || !ms[4].IsError || ms[4].ToolCallID != "call_1" || ms[4].ID != "o3" {
		t.Fatalf("%+v", ms[4])
	}
}

func TestCapsLongToolResult(t *testing.T) {
	big := strings.Repeat("x", 100*1024)
	l := `{"type":"user","uuid":"x","message":{"content":[{"type":"tool_result","tool_use_id":"t","content":"` + big + `"}]}}`
	ms, _ := Claude{}.ParseLine("k", []byte(l))
	if len(ms) != 1 || len(ms[0].Text) > 16384 || !ms[0].Truncated {
		t.Fatalf("len=%d", len(ms[0].Text))
	}
}

func TestClaudeStringAssistantContent(t *testing.T) {
	ms, err := Claude{}.ParseLine("k", []byte(`{"type":"assistant","uuid":"x","timestamp":"t","message":{"content":"<b>hello</b>"}}`))
	if err != nil || len(ms) != 1 || ms[0].Role != "assistant" || ms[0].Text != "<b>hello</b>" {
		t.Fatalf("%+v %v", ms, err)
	}
}

// C1: an oversized tool input must stay a JSON object (the wire contract is
// input?:object) holding the truncated serialization under "_truncated".
func TestCapsLongToolInputAsObject(t *testing.T) {
	big := strings.Repeat("y", 100*1024)
	l := `{"type":"assistant","uuid":"x","message":{"content":[{"type":"tool_use","id":"t","name":"Write","input":{"content":"` + big + `"}}]}}`
	ms, err := Claude{}.ParseLine("k", []byte(l))
	if err != nil || len(ms) != 1 || !ms[0].Truncated {
		t.Fatalf("%v %+v", err, ms)
	}
	var obj map[string]string
	if err := json.Unmarshal(ms[0].Input, &obj); err != nil {
		t.Fatalf("input is not an object: %v (%.40s)", err, ms[0].Input)
	}
	tr := obj["_truncated"]
	if len(obj) != 1 || len(tr) == 0 || len(tr) > 16384 || !strings.HasPrefix(tr, `{"content":"yyy`) {
		t.Fatalf("bad _truncated len=%d keys=%d", len(tr), len(obj))
	}
	if len(ms[0].Input) > 16384+64 {
		t.Fatalf("serialized input too large: %d", len(ms[0].Input))
	}
}

// I3: a user turn made of text/image blocks (no tool_result) is a user message.
func TestClaudeUserTextAndImageBlocks(t *testing.T) {
	l := `{"type":"user","uuid":"u","timestamp":"t","message":{"content":[{"type":"text","text":"look at this"},{"type":"image","source":{}},{"type":"text","text":"why?"}]}}`
	ms, err := Claude{}.ParseLine("k", []byte(l))
	if err != nil || len(ms) != 1 || ms[0].Role != "user" || ms[0].Text != "look at this\n[image]\nwhy?" || ms[0].ID != "u" {
		t.Fatalf("%+v %v", ms, err)
	}
	meta := `{"type":"user","uuid":"m","message":{"content":[{"type":"text","text":"<system-reminder>x</system-reminder>"}]}}`
	if ms, _ := (Claude{}).ParseLine("k", []byte(meta)); len(ms) != 0 {
		t.Fatalf("meta block emitted: %+v", ms)
	}
}
