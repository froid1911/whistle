package state

import (
	"strings"
	"unicode"
)

// spinnerRunes are animation/status glyphs removed wherever they appear: the
// braille block (U+2800–U+28FF, checked separately), Claude's ✳✻✶✢✽ and the
// common quarter/circle spinners. They carry no meaning but change several
// times a second, so keeping them would log an upsert per animation frame.
var spinnerRunes = map[rune]bool{
	'✳': true, '✻': true, '✶': true, '✢': true, '✽': true, '✺': true, '✹': true, '✷': true,
	'◐': true, '◓': true, '◑': true, '◒': true,
	'◴': true, '◷': true, '◶': true, '◵': true,
	'◜': true, '◝': true, '◞': true, '◟': true,
	'◰': true, '◳': true, '◲': true, '◱': true,
}

// spinnerTokens are ASCII/punctuation spinner frames and idle markers removed
// only when they form a whole whitespace-separated token ("π > 🐳 x", "| x").
var spinnerTokens = map[string]bool{
	"|": true, "/": true, "-": true, `\`: true, "*": true, ">": true, "·": true, "•": true,
}

func isSpinnerRune(r rune) bool {
	return (r >= 0x2800 && r <= 0x28FF) || spinnerRunes[r]
}

// NormalizeLabel strips spinner/status glyphs from a terminal-title derived
// label, collapses whitespace and trims. A standalone "·" is kept only as
// herdr's tab separator ("1 · title"); emoji and other text are kept.
func NormalizeLabel(s string) string {
	s = strings.Map(func(r rune) rune {
		if isSpinnerRune(r) {
			return ' '
		}
		if unicode.IsSpace(r) || unicode.IsControl(r) {
			return ' '
		}
		return r
	}, s)
	var out []string
	sep := false // herdr's "N · title" separator is pending
	for _, f := range strings.Fields(s) {
		if f == "·" {
			// Kept only as herdr's tab separator right after a leading number;
			// anywhere else it is Claude's smallest spinner frame.
			sep = len(out) == 1 && isDigits(out[0])
			continue
		}
		if spinnerTokens[f] {
			continue
		}
		if sep {
			out = append(out, "·")
			sep = false
		}
		out = append(out, f)
	}
	return strings.Join(out, " ")
}

// paneLabel picks a pane's wire label: the normalized terminal title, else
// herdr's label, else the pane id.
func paneLabel(title, label, id string) string {
	if l := NormalizeLabel(title); l != "" {
		return l
	}
	if l := NormalizeLabel(label); l != "" {
		return l
	}
	return id
}

// entityLabel normalizes a workspace/tab label; one that is only glyphs keeps
// its raw form trimmed (better something than an empty name).
func entityLabel(label string) string {
	if l := NormalizeLabel(label); l != "" {
		return l
	}
	return strings.TrimSpace(label)
}

func isDigits(s string) bool {
	for _, r := range s {
		if r < '0' || r > '9' {
			return false
		}
	}
	return s != ""
}
