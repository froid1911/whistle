package herdr

import (
	"encoding/json"
	"errors"
)

// APIError is an error returned by herdr in a response envelope.
type APIError struct {
	Code    string `json:"code"`
	Message string `json:"message"`
}

func (e *APIError) Error() string { return e.Code + ": " + e.Message }

// IsCode reports whether err is (or wraps) an APIError with the given code.
func IsCode(err error, code string) bool {
	var ae *APIError
	return errors.As(err, &ae) && ae.Code == code
}

type Subscription struct {
	Type   string `json:"type"`
	PaneID string `json:"pane_id,omitempty"`
}

type Event struct {
	Event string          `json:"event"`
	Data  json.RawMessage `json:"data"`
}

type Snapshot struct {
	Protocol         int         `json:"protocol"`
	Version          string      `json:"version"`
	FocusedWorkspace string      `json:"focused_workspace_id,omitempty"`
	FocusedTab       string      `json:"focused_tab_id,omitempty"`
	FocusedPane      string      `json:"focused_pane_id,omitempty"`
	Workspaces       []Workspace `json:"workspaces"`
	Tabs             []Tab       `json:"tabs"`
	Panes            []Pane      `json:"panes"`
	Agents           []Agent     `json:"agents"`
	Layouts          []Layout    `json:"layouts"`
}

type Workspace struct {
	WorkspaceID string `json:"workspace_id"`
	Number      int    `json:"number"`
	Label       string `json:"label"`
	ActiveTabID string `json:"active_tab_id,omitempty"`
	AgentStatus string `json:"agent_status,omitempty"`
	Focused     bool   `json:"focused"`
	PaneCount   int    `json:"pane_count"`
	TabCount    int    `json:"tab_count"`
}

type Tab struct {
	TabID       string `json:"tab_id"`
	WorkspaceID string `json:"workspace_id"`
	Number      int    `json:"number"`
	Label       string `json:"label"`
	AgentStatus string `json:"agent_status,omitempty"`
	Focused     bool   `json:"focused"`
	PaneCount   int    `json:"pane_count"`
}

type Scroll struct {
	OffsetFromBottom    int `json:"offset_from_bottom"`
	MaxOffsetFromBottom int `json:"max_offset_from_bottom"`
	ViewportRows        int `json:"viewport_rows"`
}

type AgentSession struct {
	Agent  string `json:"agent"`
	Kind   string `json:"kind"` // "id" | "path"
	Source string `json:"source"`
	Value  string `json:"value"`
}

type Pane struct {
	PaneID                string        `json:"pane_id"`
	TabID                 string        `json:"tab_id"`
	WorkspaceID           string        `json:"workspace_id"`
	TerminalID            string        `json:"terminal_id,omitempty"`
	Cwd                   string        `json:"cwd,omitempty"`
	ForegroundCwd         string        `json:"foreground_cwd,omitempty"`
	Label                 string        `json:"label,omitempty"`
	TerminalTitle         string        `json:"terminal_title,omitempty"`
	TerminalTitleStripped string        `json:"terminal_title_stripped,omitempty"`
	Focused               bool          `json:"focused"`
	Revision              int64         `json:"revision"`
	Scroll                *Scroll       `json:"scroll,omitempty"`
	Agent                 string        `json:"agent,omitempty"`
	AgentStatus           string        `json:"agent_status,omitempty"`
	AgentSession          *AgentSession `json:"agent_session,omitempty"`
}

// Agent is a pane's agent view: pane fields plus sequence counters.
type Agent struct {
	Pane
	CompletionSeq          int64 `json:"completion_seq"`
	StateChangeSeq         int64 `json:"state_change_seq"`
	ScreenDetectionSkipped bool  `json:"screen_detection_skipped,omitempty"`
}

type Rect struct {
	X      int `json:"x"`
	Y      int `json:"y"`
	Width  int `json:"width"`
	Height int `json:"height"`
}

type LayoutPane struct {
	PaneID  string `json:"pane_id"`
	Focused bool   `json:"focused"`
	Rect    Rect   `json:"rect"`
}

type Layout struct {
	TabID         string            `json:"tab_id"`
	WorkspaceID   string            `json:"workspace_id"`
	Area          Rect              `json:"area"`
	FocusedPaneID string            `json:"focused_pane_id,omitempty"`
	Panes         []LayoutPane      `json:"panes"`
	Splits        []json.RawMessage `json:"splits"`
	Zoomed        bool              `json:"zoomed"`
}
