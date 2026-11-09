package state

import (
	"testing"

	"github.com/froid1911/whistle/bridge/internal/herdr"
)

func TestNormalizeLabel(t *testing.T) {
	for _, c := range []struct{ in, want string }{
		{"π ⠧ 🐳 PARK: Tasks need refactored", "π 🐳 PARK: Tasks need refactored"},
		{"π ⠙ 🐳 PARK: Tasks need refactored", "π 🐳 PARK: Tasks need refactored"},
		{"π > 🐳 PARK: Tasks need refactored", "π 🐳 PARK: Tasks need refactored"},
		{"1 · todo-api › π ⠴ 🐳 PARK: Tasks need refactored", "1 · todo-api › π 🐳 PARK: Tasks need refactored"},
		{"✳ Using ConnectBot", "Using ConnectBot"},
		{"✻ Using ConnectBot", "Using ConnectBot"},
		{"· Using ConnectBot", "Using ConnectBot"},
		{"1 · Using ConnectBot", "1 · Using ConnectBot"},
		{"1 · ✶ Using ConnectBot", "1 · Using ConnectBot"},
		{"1 · · Using ConnectBot", "1 · Using ConnectBot"},
		{"✢Using  ConnectBot ", "Using ConnectBot"},
		{"◐ build", "build"},
		{"⣾ build", "build"},
		{"| build", "build"},
		{"\\ build", "build"},
		{"* build", "build"},
		{"you@myserver: ~", "you@myserver: ~"},
		{"1 · htop", "1 · htop"},
		{"a/b-c", "a/b-c"},
		{"⠋", ""},
		{"  ✳  ", ""},
		{"", ""},
	} {
		if got := NormalizeLabel(c.in); got != c.want {
			t.Errorf("NormalizeLabel(%q) = %q, want %q", c.in, got, c.want)
		}
	}
}

func TestPaneLabelFallbacks(t *testing.T) {
	if got := paneLabel("⠧", "claude", "w1:p1"); got != "claude" {
		t.Fatalf("spinner-only title: %q", got)
	}
	if got := paneLabel("✳ ", "", "w1:p1"); got != "w1:p1" {
		t.Fatalf("spinner-only title, no label: %q", got)
	}
	if got := paneLabel("", "⠧", "w1:p1"); got != "w1:p1" {
		t.Fatalf("spinner-only label: %q", got)
	}
}

func spinSnap(frame string) herdr.Snapshot {
	s := snap("working")
	s.Panes[0].TerminalTitleStripped = "π " + frame + " 🐳 PARK: Tasks need refactored"
	s.Tabs[0].Label = "1 · todo-api › π " + frame + " 🐳 PARK: Tasks need refactored"
	s.Workspaces[0].Label = frame + " todo-api"
	return s
}

func TestDiffIgnoresSpinnerFrames(t *testing.T) {
	prev := FromSnapshot(spinSnap("⠧"))
	for _, f := range []string{"⠙", "⠴", ">", "✳", "✻", "·", "|", "◐"} {
		next := FromSnapshot(spinSnap(f))
		if ch := Diff(prev, next); len(ch) != 0 {
			t.Fatalf("frame %q: %d changes: %+v", f, len(ch), ch)
		}
		prev = next
	}
	if l := prev.Panes[0].Label; l != "π 🐳 PARK: Tasks need refactored" {
		t.Fatalf("label %q", l)
	}
}
