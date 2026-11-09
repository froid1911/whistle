package transcript

import (
	"encoding/json"

	"github.com/froid1911/whistle/bridge/internal/store"
)

// Omp parses omp transcript lines.
type Omp struct{}

type ompLine struct {
	Type      string `json:"type"`
	ID        string `json:"id"`
	Timestamp string `json:"timestamp"`
	Message   struct {
		Role       string          `json:"role"`
		Content    json.RawMessage `json:"content"`
		ToolCallID string          `json:"toolCallId"`
		ToolName   string          `json:"toolName"`
		IsError    bool            `json:"isError"`
	} `json:"message"`
}

type ompBlock struct {
	Type      string          `json:"type"`
	Text      string          `json:"text"`
	Thinking  string          `json:"thinking"`
	ID        string          `json:"id"`
	Name      string          `json:"name"`
	Arguments json.RawMessage `json:"arguments"`
}

func (Omp) ParseLine(key string, line []byte) ([]store.Message, error) {
	var l ompLine
	if err := json.Unmarshal(line, &l); err != nil {
		return nil, err
	}
	if l.Type != "message" {
		return nil, nil
	}
	r := l.Message.Role
	if r == "toolResult" {
		m := mk(l.ID, key, l.Timestamp, "tool_result", resultText(l.Message.Content))
		m.ToolCallID, m.ToolName, m.IsError = l.Message.ToolCallID, l.Message.ToolName, l.Message.IsError
		return []store.Message{m}, nil
	}
	if r != "user" && r != "assistant" {
		return nil, nil
	}
	var str string
	if json.Unmarshal(l.Message.Content, &str) == nil {
		if str == "" {
			return nil, nil
		}
		return []store.Message{mk(l.ID, key, l.Timestamp, r, str)}, nil
	}
	var blocks []ompBlock
	if json.Unmarshal(l.Message.Content, &blocks) != nil {
		return nil, nil
	}
	var out []store.Message
	for i, b := range blocks {
		id := blockID(l.ID, i, len(blocks))
		switch {
		case b.Type == "text" && b.Text != "":
			out = append(out, mk(id, key, l.Timestamp, r, b.Text))
		case r == "assistant" && b.Type == "thinking" && b.Thinking != "":
			out = append(out, mk(id, key, l.Timestamp, "thinking", b.Thinking))
		case r == "assistant" && b.Type == "toolCall":
			m := mk(id, key, l.Timestamp, "tool_call", "")
			m.ToolName, m.ToolCallID = b.Name, b.ID
			m.Input, m.Truncated = capInput(b.Arguments)
			out = append(out, m)
		}
	}
	return out, nil
}
