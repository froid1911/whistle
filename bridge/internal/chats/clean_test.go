package chats

import (
	"os"
	"strings"
	"testing"
)

func fixture(t *testing.T, name string) string {
	t.Helper()
	b, err := os.ReadFile("testdata/screens/" + name)
	if err != nil {
		t.Fatal(err)
	}
	return string(b)
}

func assertNoChrome(t *testing.T, out string) {
	t.Helper()
	for _, l := range strings.Split(out, "\n") {
		if strings.ContainsAny(l, "─│╭╮╰╯┌┐└┘═║") {
			t.Errorf("chrome line survived: %q", l)
		}
	}
	if strings.HasSuffix(out, "\n") || strings.HasSuffix(out, " ") {
		t.Errorf("trailing blank: %q", out)
	}
}

func TestCleanClaudeWorking(t *testing.T) {
	out := Clean("claude", fixture(t, "claude_working.txt"))
	assertNoChrome(t, out)
	for _, want := range []string{
		"⏺ The parser drops the last field when the line has no trailing newline.",
		"⏺ Bash(go test ./parser/...)",
		"parser_test.go:42: got 3, want 4",
		"> fix the failing test in parser_test.go",
	} {
		if !strings.Contains(out, want) {
			t.Errorf("missing %q in:\n%s", want, out)
		}
	}
	for _, bad := range []string{"? for shortcuts", "Working…", "esc to interrupt"} {
		if strings.Contains(out, bad) {
			t.Errorf("chrome %q survived:\n%s", bad, out)
		}
	}
	if strings.HasSuffix(strings.TrimRight(out, "\n"), ">") {
		t.Errorf("input prompt survived:\n%s", out)
	}
}

func TestCleanBlockedKeepsQuestion(t *testing.T) {
	out := Clean("claude", fixture(t, "claude_blocked.txt"))
	assertNoChrome(t, out)
	for _, want := range []string{
		"Do you want to proceed?\n❯ 1. Yes\n  2. No",
		"rm -rf build/",
		"⏺ I'll remove the stale build output.",
	} {
		if !strings.Contains(out, want) {
			t.Errorf("missing %q in:\n%s", want, out)
		}
	}
	if strings.Contains(out, "Esc to cancel") {
		t.Errorf("footer survived:\n%s", out)
	}
}

func TestCleanOmpWorking(t *testing.T) {
	out := Clean("omp", fixture(t, "omp_working.txt"))
	assertNoChrome(t, out)
	for _, want := range []string{"I'll add the flag to the CLI parser.", "▸ edit cmd/main.go", "You: add a --verbose flag"} {
		if !strings.Contains(out, want) {
			t.Errorf("missing %q in:\n%s", want, out)
		}
	}
	for _, bad := range []string{"Working…", "ctrl+c to exit", "12.4k tokens"} {
		if strings.Contains(out, bad) {
			t.Errorf("chrome %q survived:\n%s", bad, out)
		}
	}
}

func TestCleanOmpChrome(t *testing.T) {
	cases := []struct {
		name   string
		screen string
		want   string
	}{
		{
			name:   "tool footer dropped, tree kept",
			screen: "▸ bash ls\n└── src\n└── Wall: 0.38s · Timeout: 300s ────────────\nok",
			want:   "▸ bash ls\n└── src\nok",
		},
		{
			name:   "footer with rounded corner and only trailing rule",
			screen: "done\n╰─ Took 1.2s ─────",
			want:   "done",
		},
		{
			name:   "lone prompt echoes dropped, real commands kept",
			screen: "$ …\n$ go test ./...\n$\n$ ...\nPASS",
			want:   "$ go test ./...\nPASS",
		},
		{
			name:   "omp status line dropped",
			screen: "I'll check the parser.\n.: 1h > ● DeepSeek V4.1 Flash (Strata, local) > 📁 ~/src/park > main",
			want:   "I'll check the parser.",
		},
		{
			name:   "status line without the leading glyphs",
			screen: "text\n  12m > ● Claude Sonnet (api) > 📁 …",
			want:   "text",
		},
		{
			name:   "only the newest spinner survives",
			screen: "⠋ Running bash…\nreading files\n✻ Cogitating… (12s)\nmore\n⠙ Running bash…",
			want:   "reading files\nmore\n⠙ Running bash…",
		},
		{
			name:   "long line ending in an ellipsis is prose, not a spinner",
			screen: "· This sentence is much too long to be a spinner status line because it keeps going…",
			want:   "· This sentence is much too long to be a spinner status line because it keeps going…",
		},
		{
			name: "device capture",
			screen: "▸ bash grep -rn Reverse app/\n" +
				"└── Wall: 0.38s · Timeout: 300s ─────────────────────\n" +
				"$ …\n" +
				"⠹ Thinking…\n" +
				".: 1h > ● DeepSeek V4.1 Flash (Strata, local) > 📁 ~/projects/park",
			want: "▸ bash grep -rn Reverse app/\n⠹ Thinking…",
		},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			if got := Clean("omp", c.screen); got != c.want {
				t.Errorf("got:\n%s\nwant:\n%s", got, c.want)
			}
		})
	}
}
