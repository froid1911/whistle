// Package chats turns live agent panes into chat streams: it tails each live
// agent session's transcript into the store, orders turn-completion status
// entries after the turn's final reply, and cleans TUI screens for live text
// and transcript-less agents.
package chats

import (
	"regexp"
	"strings"
)

const boxChars = "─│╭╮╰╯┌┐└┘═║━┃├┤┬┴┼╔╗╚╝"

var (
	// Spinner/status line: a spinner glyph and a short text ending in an ellipsis,
	// e.g. "✻ Working… (12s)", "⠹ Thinking..." or "⠋ Running bash…". Only the
	// newest one is kept (it is the agent's current status).
	spinnerRe = regexp.MustCompile(`^[✻✶✳✢✽✺·⠀-⣿◐◓◑◒]\s+.{1,60}?(…|\.\.\.)(\s*\(.{0,60}\))?$`)
	// omp tool footer: "└── Wall: 0.38s · Timeout: 300s ────" (a corner, a rule,
	// a label, and a trailing rule; tree output like "└── src" has no trailing rule).
	toolFooterRe = regexp.MustCompile(`^[└╰┗╚├┌╭][─━═]+\s.*\S\s*[─━═]{3,}$|^[└╰┗╚][─━═]+\s*(Wall|Timeout|Took|Duration|Exit code):`)
	// A lone prompt echo: "$", "$ …" or "$ ...".
	promptEchoRe = regexp.MustCompile(`^\$\s*(…|\.\.\.)?$`)
	// omp's status line: ".: 1h > ● DeepSeek V4.1 Flash (Strata, local) > 📁 …".
	ompStatusRe = regexp.MustCompile(`^\.:\s|\s>\s●\s`)
	// Footer / key hints.
	footerRe = regexp.MustCompile(`(?i)^\? for shortcuts|esc to (interrupt|cancel)|ctrl\+c to exit|shift\+tab to cycle|^⏵⏵`)
	// The input prompt line inside the input box.
	inputRe = regexp.MustCompile(`^>(\s|$)`)
)

// isRule reports whether l consists only of box-drawing characters and spaces.
func isRule(l string) bool {
	t := strings.TrimSpace(l)
	if t == "" {
		return false
	}
	for _, r := range t {
		if r != ' ' && !strings.ContainsRune(boxChars, r) {
			return false
		}
	}
	return true
}

// unborder strips a vertical border on either side of a boxed line.
func unborder(l string) string {
	t := strings.TrimLeft(l, " ")
	if r := []rune(t); len(r) > 0 && (r[0] == '│' || r[0] == '║' || r[0] == '┃') {
		l = string(r[1:])
		l = strings.TrimPrefix(l, " ")
	}
	l = strings.TrimRight(l, " \t")
	if r := []rune(l); len(r) > 0 && (r[len(r)-1] == '│' || r[len(r)-1] == '║' || r[len(r)-1] == '┃') {
		l = strings.TrimRight(string(r[:len(r)-1]), " \t")
	}
	return l
}

// isChrome reports omp chrome lines: tool footers, lone prompt echoes, the status line.
func isChrome(t string) bool {
	return toolFooterRe.MatchString(t) || promptEchoRe.MatchString(t) || ompStatusRe.MatchString(t)
}

// Clean removes TUI chrome from an agent screen: box borders and rules, the
// older spinner/status lines (the newest is kept), omp tool footers, prompt
// echoes and status line, the input prompt box and everything below it (footer
// hints), and leading/trailing blank lines. The agent's prose and tool lines
// (including approval questions and their numbered options) are kept.
func Clean(agent, screen string) string {
	lines := strings.Split(strings.ReplaceAll(screen, "\r\n", "\n"), "\n")
	content := make([]string, len(lines))
	for i, l := range lines {
		content[i] = unborder(l)
	}
	// The input box: the last prompt line directly below a rule. Cut from that
	// rule to the end (the box bottom and footer hints live below it).
	end := len(lines)
	for i := len(lines) - 1; i > 0; i-- {
		if inputRe.MatchString(content[i]) && isRule(lines[i-1]) {
			end = i - 1
			break
		}
	}
	lastSpinner := -1
	for i := 0; i < end; i++ {
		t := strings.TrimSpace(content[i])
		if spinnerRe.MatchString(t) && !footerRe.MatchString(t) {
			lastSpinner = i
		}
	}
	var out []string
	for i := 0; i < end; i++ {
		if isRule(lines[i]) {
			continue
		}
		c := content[i]
		t := strings.TrimSpace(c)
		if footerRe.MatchString(t) || isChrome(t) || (spinnerRe.MatchString(t) && i != lastSpinner) {
			continue
		}
		if t == "" {
			if len(out) == 0 || out[len(out)-1] == "" {
				continue
			}
			c = ""
		}
		out = append(out, c)
	}
	for len(out) > 0 && out[len(out)-1] == "" {
		out = out[:len(out)-1]
	}
	return strings.Join(out, "\n")
}
