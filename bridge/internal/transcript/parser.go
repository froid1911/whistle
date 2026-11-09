// Package transcript parses agent transcript files (Claude Code, omp JSONL),
// locates them from a herdr agent session and tails them.
package transcript

import (
	"encoding/json"
	"strings"
	"unicode/utf8"

	"github.com/froid1911/whistle/bridge/internal/store"
)

const maxField = 16384

// Parser converts one transcript line into zero or more messages.
type Parser interface {
	ParseLine(sessionKey string, line []byte) ([]store.Message, error)
}

// capText truncates s to maxField bytes on a rune boundary.
func capText(s string) (string, bool) {
	if len(s) <= maxField {
		return s, false
	}
	s = s[:maxField]
	for len(s) > 0 && !utf8.ValidString(s) {
		s = s[:len(s)-1]
	}
	return s, true
}

// capInput limits serialized input. Wire input is always a JSON object
// (docs/reference/wire-protocol.md), so an oversized input is replaced by
// {"_truncated": "<prefix of its serialization>"}, whole value <= maxField.
func capInput(raw json.RawMessage) (json.RawMessage, bool) {
	if len(raw) == 0 || string(raw) == "null" {
		return nil, false
	}
	if len(raw) <= maxField {
		return raw, false
	}
	s, _ := capText(string(raw))
	for {
		b, _ := json.Marshal(map[string]string{"_truncated": s})
		if len(b) <= maxField {
			return b, true
		}
		s = s[:len(s)-len(s)/8-1]
		for len(s) > 0 && !utf8.ValidString(s) {
			s = s[:len(s)-1]
		}
	}
}

func blockID(base string, i, n int) string {
	if n > 1 {
		return base + "#" + itoa(i)
	}
	return base
}

func itoa(i int) string {
	b, _ := json.Marshal(i)
	return string(b)
}

// resultText joins text blocks; content may be a string or a block list.
func resultText(raw json.RawMessage) string {
	var s string
	if json.Unmarshal(raw, &s) == nil {
		return s
	}
	var blocks []struct {
		Type string `json:"type"`
		Text string `json:"text"`
	}
	if json.Unmarshal(raw, &blocks) != nil {
		return ""
	}
	parts := make([]string, 0, len(blocks))
	for _, b := range blocks {
		switch b.Type {
		case "text":
			parts = append(parts, b.Text)
		case "image":
			parts = append(parts, "[image]")
		}
	}
	return strings.Join(parts, "\n")
}

func mk(id, key, ts, role, text string) store.Message {
	t, tr := capText(text)
	return store.Message{ID: id, SessionKey: key, TS: ts, Role: role, Text: t, Truncated: tr}
}
