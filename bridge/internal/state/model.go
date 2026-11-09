// Package state maps herdr snapshots to the bridge wire State, diffs states
// and watches herdr for changes.
package state

import "github.com/froid1911/whistle/bridge/internal/herdr"

type Workspace struct {
	ID          string `json:"id"`
	Number      int    `json:"number"`
	Label       string `json:"label"`
	AgentStatus string `json:"agent_status,omitempty"`
	ActiveTabID string `json:"active_tab_id"`
}

type Tab struct {
	ID          string `json:"id"`
	WorkspaceID string `json:"workspace_id"`
	Number      int    `json:"number"`
	Label       string `json:"label"`
	AgentStatus string `json:"agent_status,omitempty"`
}

type Pane struct {
	ID            string `json:"id"`
	TabID         string `json:"tab_id"`
	WorkspaceID   string `json:"workspace_id"`
	Label         string `json:"label"`
	Cwd           string `json:"cwd"`
	Kind          string `json:"kind"` // "agent" | "shell"
	Agent         string `json:"agent,omitempty"`
	AgentStatus   string `json:"agent_status,omitempty"`
	SessionKey    string `json:"session_key,omitempty"`
	HasTranscript bool   `json:"has_transcript"`
}

type LayoutPane struct {
	PaneID string `json:"pane_id"`
	X      int    `json:"x"`
	Y      int    `json:"y"`
	W      int    `json:"w"`
	H      int    `json:"h"`
}

type Layout struct {
	TabID string       `json:"tab_id"`
	Panes []LayoutPane `json:"panes"`
}

type State struct {
	Workspaces []Workspace `json:"workspaces"`
	Tabs       []Tab       `json:"tabs"`
	Panes      []Pane      `json:"panes"`
	Layouts    []Layout    `json:"layouts"`
}

// FromSnapshot maps a herdr snapshot to the wire State. HasTranscript is left
// false; see MarkTranscripts.
func FromSnapshot(s herdr.Snapshot) State {
	st := State{
		Workspaces: []Workspace{},
		Tabs:       []Tab{},
		Panes:      []Pane{},
		Layouts:    []Layout{},
	}
	for _, w := range s.Workspaces {
		st.Workspaces = append(st.Workspaces, Workspace{
			ID: w.WorkspaceID, Number: w.Number, Label: entityLabel(w.Label),
			AgentStatus: w.AgentStatus, ActiveTabID: w.ActiveTabID,
		})
	}
	for _, t := range s.Tabs {
		st.Tabs = append(st.Tabs, Tab{
			ID: t.TabID, WorkspaceID: t.WorkspaceID, Number: t.Number,
			Label: entityLabel(t.Label), AgentStatus: t.AgentStatus,
		})
	}
	for _, p := range s.Panes {
		wp := Pane{
			ID: p.PaneID, TabID: p.TabID, WorkspaceID: p.WorkspaceID,
			Label: paneLabel(p.TerminalTitleStripped, p.Label, p.PaneID), Cwd: p.Cwd, Kind: "shell",
			AgentStatus: p.AgentStatus,
		}
		if p.Agent != "" {
			wp.Kind = "agent"
			wp.Agent = p.Agent
			if p.AgentSession != nil && p.AgentSession.Value != "" {
				wp.SessionKey = p.Agent + ":" + p.AgentSession.Value
			}
		}
		st.Panes = append(st.Panes, wp)
	}
	for _, l := range s.Layouts {
		wl := Layout{TabID: l.TabID, Panes: []LayoutPane{}}
		for _, lp := range l.Panes {
			wl.Panes = append(wl.Panes, LayoutPane{
				PaneID: lp.PaneID, X: lp.Rect.X, Y: lp.Rect.Y, W: lp.Rect.Width, H: lp.Rect.Height,
			})
		}
		st.Layouts = append(st.Layouts, wl)
	}
	return st
}

// MarkTranscripts sets HasTranscript on agent panes whose session key has one.
func (s *State) MarkTranscripts(has func(sessionKey string) bool) {
	for i := range s.Panes {
		p := &s.Panes[i]
		p.HasTranscript = p.SessionKey != "" && has(p.SessionKey)
	}
}
