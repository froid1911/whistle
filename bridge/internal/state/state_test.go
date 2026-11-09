package state

import (
	"testing"

	"github.com/froid1911/whistle/bridge/internal/herdr"
)

func snap(status string) herdr.Snapshot {
	return herdr.Snapshot{
		Protocol:   22,
		Workspaces: []herdr.Workspace{{WorkspaceID: "w1", Number: 1, Label: "ws", ActiveTabID: "w1:t1"}},
		Tabs:       []herdr.Tab{{TabID: "w1:t1", WorkspaceID: "w1", Number: 1, Label: "tab"}},
		Panes: []herdr.Pane{
			{PaneID: "w1:p1", TabID: "w1:t1", WorkspaceID: "w1", Cwd: "/x", Label: "lbl", TerminalTitleStripped: "title",
				Agent: "claude", AgentStatus: status, AgentSession: &herdr.AgentSession{Agent: "claude", Kind: "id", Value: "abc"}},
			{PaneID: "w1:p2", TabID: "w1:t1", WorkspaceID: "w1", Cwd: "/x", Label: "sh"},
		},
		Layouts: []herdr.Layout{{TabID: "w1:t1", WorkspaceID: "w1", Panes: []herdr.LayoutPane{
			{PaneID: "w1:p1", Rect: herdr.Rect{X: 0, Y: 0, Width: 10, Height: 5}}}}},
	}
}

func TestFromSnapshotMapsSessionKey(t *testing.T) {
	st := FromSnapshot(snap("idle"))
	a, sh := st.Panes[0], st.Panes[1]
	if a.SessionKey != "claude:abc" || a.Kind != "agent" || a.Label != "title" {
		t.Fatalf("agent pane: %+v", a)
	}
	if sh.Kind != "shell" || sh.SessionKey != "" || sh.Label != "sh" {
		t.Fatalf("shell pane: %+v", sh)
	}
	if a.HasTranscript {
		t.Fatal("has_transcript must default false")
	}
	st.MarkTranscripts(func(k string) bool { return k == "claude:abc" })
	if !st.Panes[0].HasTranscript || st.Panes[1].HasTranscript {
		t.Fatal("MarkTranscripts")
	}
	if l := st.Layouts[0].Panes[0]; l.W != 10 || l.H != 5 {
		t.Fatalf("layout %+v", l)
	}
}

func TestDiffIgnoresRevisionAndFocus(t *testing.T) {
	a := snap("idle")
	b := snap("idle")
	b.Panes[0].Revision = 99
	b.Panes[0].Focused = true
	b.Panes[0].Scroll = &herdr.Scroll{ViewportRows: 3}
	b.FocusedPane = "w1:p1"
	if d := Diff(FromSnapshot(a), FromSnapshot(b)); len(d) != 0 {
		t.Fatalf("expected empty diff, got %+v", d)
	}
}

func TestDiffStatusChange(t *testing.T) {
	d := Diff(FromSnapshot(snap("idle")), FromSnapshot(snap("working")))
	if len(d) != 2 {
		t.Fatalf("want 2 changes, got %+v", d)
	}
	if d[0].Kind != "upsert" || d[0].Entity != "pane" || d[0].ID != "w1:p1" {
		t.Fatalf("d0 %+v", d[0])
	}
	if d[1].Kind != "status" || d[1].From != "idle" || d[1].To != "working" ||
		d[1].PaneID != "w1:p1" || d[1].SessionKey != "claude:abc" {
		t.Fatalf("d1 %+v", d[1])
	}
}

func TestDiffRemove(t *testing.T) {
	b := snap("idle")
	b.Panes = b.Panes[:1]
	d := Diff(FromSnapshot(snap("idle")), FromSnapshot(b))
	if len(d) != 1 || d[0].Kind != "remove" || d[0].Entity != "pane" || d[0].ID != "w1:p2" {
		t.Fatalf("%+v", d)
	}
}
