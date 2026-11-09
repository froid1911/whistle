package transcript

import (
	"encoding/json"
	"strings"

	"github.com/froid1911/whistle/bridge/internal/store"
)

// Claude parses Claude Code transcript lines.
type Claude struct{}

type claudeLine struct {
	Type        string `json:"type"`
	UUID        string `json:"uuid"`
	Timestamp   string `json:"timestamp"`
	IsSidechain bool   `json:"isSidechain"`
	IsMeta      bool   `json:"isMeta"`
	Message     struct {
		Content json.RawMessage `json:"content"`
	} `json:"message"`
}

type claudeBlock struct {
	Type      string          `json:"type"`
	Text      string          `json:"text"`
	Thinking  string          `json:"thinking"`
	ID        string          `json:"id"`
	Name      string          `json:"name"`
	Input     json.RawMessage `json:"input"`
	ToolUseID string          `json:"tool_use_id"`
	Content   json.RawMessage `json:"content"`
	IsError   bool            `json:"is_error"`
}

func (Claude) ParseLine(key string, line []byte) ([]store.Message, error) {
	var l claudeLine
	if err := json.Unmarshal(line, &l); err != nil {
		return nil, err
	}
	if l.IsSidechain || l.IsMeta || (l.Type != "user" && l.Type != "assistant") {
		return nil, nil
	}
	var str string
	if json.Unmarshal(l.Message.Content, &str) == nil {
		t := strings.TrimSpace(str)
		if t == "" {
			return nil, nil
		}
		if l.Type == "assistant" {
			return []store.Message{mk(l.UUID, key, l.Timestamp, "assistant", str)}, nil
		}
		if strings.HasPrefix(t, "<") {
			return nil, nil
		}
		return []store.Message{mk(l.UUID, key, l.Timestamp, "user", str)}, nil
	}
	var blocks []claudeBlock
	if err := json.Unmarshal(l.Message.Content, &blocks); err != nil {
		return nil, nil
	}
	if l.Type == "user" && !hasToolResult(blocks) {
		// A typed turn sent as blocks (e.g. text + pasted image).
		var parts []string
		for _, b := range blocks {
			switch b.Type {
			case "text":
				if b.Text != "" {
					parts = append(parts, b.Text)
				}
			case "image":
				parts = append(parts, "[image]")
			}
		}
		t := strings.Join(parts, "\n")
		if strings.TrimSpace(t) == "" || strings.HasPrefix(strings.TrimSpace(t), "<") {
			return nil, nil
		}
		return []store.Message{mk(l.UUID, key, l.Timestamp, "user", t)}, nil
	}
	var out []store.Message
	for i, b := range blocks {
		id := blockID(l.UUID, i, len(blocks))
		switch {
		case l.Type == "assistant" && b.Type == "text" && b.Text != "":
			out = append(out, mk(id, key, l.Timestamp, "assistant", b.Text))
		case l.Type == "assistant" && b.Type == "thinking" && b.Thinking != "":
			out = append(out, mk(id, key, l.Timestamp, "thinking", b.Thinking))
		case l.Type == "assistant" && b.Type == "tool_use":
			m := mk(id, key, l.Timestamp, "tool_call", "")
			m.ToolName, m.ToolCallID = b.Name, b.ID
			m.Input, m.Truncated = capInput(b.Input)
			out = append(out, m)
		case l.Type == "user" && b.Type == "tool_result":
			m := mk(id, key, l.Timestamp, "tool_result", resultText(b.Content))
			m.ToolCallID, m.IsError = b.ToolUseID, b.IsError
			out = append(out, m)
		}
	}
	return out, nil
}

func hasToolResult(blocks []claudeBlock) bool {
	for _, b := range blocks {
		if b.Type == "tool_result" {
			return true
		}
	}
	return false
}
