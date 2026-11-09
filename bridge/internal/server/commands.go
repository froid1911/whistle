package server

import (
	"context"
	"encoding/json"
	"errors"
	"net"
	"path/filepath"
	"time"

	"github.com/froid1911/whistle/bridge/internal/chats"
	"github.com/froid1911/whistle/bridge/internal/herdr"
	"github.com/froid1911/whistle/bridge/internal/store"
)

// passthrough are the herdr methods the app may call directly.
var passthrough = map[string]bool{
	"workspace.create": true, "workspace.rename": true, "workspace.close": true,
	"tab.create": true, "tab.rename": true, "tab.close": true,
	"pane.split": true, "pane.close": true, "pane.rename": true, "pane.send_text": true, "pane.send_keys": true,
	"agent.prompt": true, "agent.send_keys": true, "agent.start": true, "agent.rename": true,
	"worktree.list": true,
}

// focusMethods take a focus param; the bridge always sends focus:false so
// the desktop's focus is never stolen.
var focusMethods = map[string]bool{"workspace.create": true, "tab.create": true, "pane.split": true}

const cmdTimeout = 5 * time.Minute // agent.prompt may wait for a turn

// cmdError is a wire error {code, message}.
type cmdError struct{ Code, Message string }

func (e *cmdError) Error() string { return e.Code + ": " + e.Message }

func errf(code, msg string) error { return &cmdError{code, msg} }

// GitOps is implemented by package gitops (Task 8).
type GitOps interface {
	Status(ctx context.Context, path string) (GitStatus, error)
	Diff(ctx context.Context, path, file string) (diff string, truncated bool, err error)
	Commit(ctx context.Context, path, message string, all bool) (sha string, err error)
}

type GitStatus struct {
	Branch string    `json:"branch"`
	Files  []GitFile `json:"files"`
}

type GitFile struct {
	Path   string `json:"path"`
	Status string `json:"status"` // "M" | "A" | "D" | "R" | "?"
	Staged bool   `json:"staged"`
}

func (c *conn) handleCmd(m inMsg) {
	ctx, cancel := context.WithTimeout(c.ctx, cmdTimeout)
	defer cancel()
	res, err := c.s.dispatch(ctx, m.Method, m.Params)
	if err != nil {
		var ce *cmdError
		var ae *herdr.APIError
		switch {
		case errors.As(err, &ce):
		case errors.As(err, &ae):
			ce = &cmdError{ae.Code, ae.Message}
		case errors.Is(err, context.DeadlineExceeded):
			ce = &cmdError{"timeout", err.Error()}
		case errors.Is(err, herdr.ErrClosed) || isDialErr(err):
			ce = &cmdError{"herdr_unavailable", err.Error()}
		default:
			ce = &cmdError{"internal", err.Error()}
		}
		c.send(map[string]any{"t": "res", "id": m.ID, "error": map[string]string{"code": ce.Code, "message": ce.Message}})
		return
	}
	c.send(map[string]any{"t": "res", "id": m.ID, "result": res})
}

// isDialErr reports a socket-level failure reaching herdr (e.g. herdr down).
func isDialErr(err error) bool {
	var op *net.OpError
	return errors.As(err, &op)
}

func decode(params json.RawMessage, v any) error {
	if len(params) == 0 || string(params) == "null" {
		params = []byte("{}")
	}
	if err := json.Unmarshal(params, v); err != nil {
		return errf("bad_request", err.Error())
	}
	return nil
}

func (s *Server) dispatch(ctx context.Context, method string, params json.RawMessage) (any, error) {
	switch method {
	case "chat.history":
		return s.chatHistory(params)
	case "agent.screen":
		return s.agentScreen(ctx, params)
	case "git.status", "git.diff", "git.commit":
		return s.git(ctx, method, params)
	}
	if !passthrough[method] {
		return nil, errf("method_not_allowed", method)
	}
	var p map[string]any
	if err := decode(params, &p); err != nil {
		return nil, err
	}
	if p == nil {
		p = map[string]any{}
	}
	if _, has := p["focus"]; has || focusMethods[method] {
		p["focus"] = false
	}
	var out json.RawMessage
	if err := s.deps.Herdr.Call(ctx, method, p, &out); err != nil {
		return nil, err
	}
	if len(out) == 0 {
		out = json.RawMessage("{}")
	}
	return out, nil
}

func (s *Server) chatHistory(params json.RawMessage) (any, error) {
	var p struct {
		SessionKey string `json:"session_key"`
		BeforeSeq  int64  `json:"before_seq"`
		Limit      int    `json:"limit"`
	}
	if err := decode(params, &p); err != nil {
		return nil, err
	}
	if p.SessionKey == "" {
		return nil, errf("bad_request", "session_key required")
	}
	switch {
	case p.Limit <= 0:
		p.Limit = 100
	case p.Limit > 200:
		p.Limit = 200
	}
	msgs, more, err := s.deps.Store.History(p.SessionKey, p.BeforeSeq, p.Limit)
	if err != nil {
		return nil, err
	}
	if msgs == nil {
		msgs = []store.Message{}
	}
	return map[string]any{"messages": msgs, "more": more}, nil
}

func (s *Server) paneAgent(paneID string) (string, bool) {
	for _, p := range s.deps.Watcher.Current().Panes {
		if p.ID == paneID {
			return p.Agent, true
		}
	}
	return "", false
}

func (s *Server) agentScreen(ctx context.Context, params json.RawMessage) (any, error) {
	var p struct {
		PaneID string `json:"pane_id"`
		Lines  int    `json:"lines"`
	}
	if err := decode(params, &p); err != nil {
		return nil, err
	}
	if p.PaneID == "" {
		return nil, errf("bad_request", "pane_id required")
	}
	if p.Lines <= 0 || p.Lines > 1000 {
		p.Lines = chats.ScreenLines
	}
	agent, _ := s.paneAgent(p.PaneID)
	txt, err := chats.HerdrAdapter{C: s.deps.Herdr}.Read(ctx, p.PaneID, "recent_unwrapped", p.Lines)
	if err != nil {
		return nil, err
	}
	return map[string]string{"text": chats.Clean(agent, txt)}, nil
}

func (s *Server) git(ctx context.Context, method string, params json.RawMessage) (any, error) {
	if s.deps.Git == nil {
		return nil, errf("not_available", "git support not available")
	}
	var p struct {
		Path    string `json:"path"`
		File    string `json:"file"`
		Message string `json:"message"`
		All     bool   `json:"all"`
	}
	if err := decode(params, &p); err != nil {
		return nil, err
	}
	if p.Path == "" || !filepath.IsAbs(p.Path) {
		return nil, errf("bad_request", "absolute path required")
	}
	p.Path = filepath.Clean(p.Path)
	if err := s.checkPath(ctx, p.Path); err != nil {
		return nil, err
	}
	gitErr := func(err error) error {
		if errors.Is(err, context.DeadlineExceeded) || errors.Is(err, context.Canceled) {
			return err
		}
		return errf("git_error", err.Error())
	}
	switch method {
	case "git.status":
		st, err := s.deps.Git.Status(ctx, p.Path)
		if err != nil {
			return nil, gitErr(err)
		}
		if st.Files == nil {
			st.Files = []GitFile{}
		}
		return st, nil
	case "git.diff":
		d, trunc, err := s.deps.Git.Diff(ctx, p.Path, p.File)
		if err != nil {
			return nil, gitErr(err)
		}
		return map[string]any{"diff": d, "truncated": trunc}, nil
	default: // git.commit
		if p.Message == "" {
			return nil, errf("bad_request", "message required")
		}
		sha, err := s.deps.Git.Commit(ctx, p.Path, p.Message, p.All)
		if err != nil {
			return nil, gitErr(err)
		}
		return map[string]string{"commit": sha}, nil
	}
}

// checkPath allows only current pane cwds and herdr worktree paths.
func (s *Server) checkPath(ctx context.Context, path string) error {
	st := s.deps.Watcher.Current()
	for _, p := range st.Panes {
		if p.Cwd != "" && filepath.Clean(p.Cwd) == path {
			return nil
		}
	}
	for _, w := range st.Workspaces {
		var out struct {
			Worktrees []struct {
				Path string `json:"path"`
			} `json:"worktrees"`
		}
		if err := s.deps.Herdr.Call(ctx, "worktree.list", map[string]any{"workspace_id": w.ID}, &out); err != nil {
			var ae *herdr.APIError
			if errors.As(err, &ae) {
				continue // a workspace outside any repo
			}
			return err
		}
		for _, wt := range out.Worktrees {
			if wt.Path != "" && filepath.Clean(wt.Path) == path {
				return nil
			}
		}
	}
	return errf("forbidden_path", path)
}
