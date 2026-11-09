package server

import (
	"context"
)

const maxTerms = 2

// TermFactory opens herdr terminal streams (implemented by package term, Task 7).
type TermFactory interface {
	Open(ctx context.Context, paneID, mode string, cols, rows int) (TermSession, error)
}

// TermSession is one running terminal stream. Frames closes when it ends.
type TermSession interface {
	Frames() <-chan TermFrame
	Input(text string, b []byte) error
	Resize(cols, rows int) error
	Close() error
}

type TermFrame struct {
	Seq           int64
	Width, Height int
	Full          bool
	Bytes         []byte
}

type termSess struct {
	sid, pane, mode string
	cols, rows      int
	sess            TermSession
}

func (c *conn) termClosed(sid, reason string) {
	c.send(map[string]string{"t": "term.closed", "sid": sid, "reason": reason})
}

func (c *conn) handleTerm(m inMsg) {
	if m.SID == "" {
		return
	}
	switch m.T {
	case "term.open":
		if c.s.deps.Term == nil {
			c.termClosed(m.SID, "not_available")
			return
		}
		mode := m.Mode
		if mode != "control" {
			mode = "observe"
		}
		c.tmu.Lock()
		_, dup := c.terms[m.SID]
		full := len(c.terms) >= maxTerms
		c.tmu.Unlock()
		switch {
		case dup:
			c.termClosed(m.SID, "duplicate_sid")
		case full:
			c.termClosed(m.SID, "too_many_sessions")
		default:
			c.openTerm(&termSess{sid: m.SID, pane: m.PaneID, mode: mode, cols: m.Cols, rows: m.Rows})
		}
	case "term.input":
		ts := c.term(m.SID)
		if ts == nil {
			return
		}
		if ts.mode == "observe" {
			// Input upgrades the session to control by restarting it. The
			// control session gets no cols/rows: with a size, herdr resizes
			// the real desktop pane and the size persists after the session.
			// Frames then arrive at the pane's own size.
			c.dropTerm(ts)
			ts = &termSess{sid: ts.sid, pane: ts.pane, mode: "control"}
			if !c.openTerm(ts) {
				return
			}
		}
		if err := ts.sess.Input(m.Text, m.Bytes); err != nil {
			c.s.log.Warn("term input", "sid", m.SID, "err", err)
		}
	case "term.resize":
		if ts := c.term(m.SID); ts != nil {
			ts.sess.Resize(m.Cols, m.Rows)
		}
	case "term.close":
		if ts := c.term(m.SID); ts != nil {
			c.dropTerm(ts)
		}
	}
}

func (c *conn) term(sid string) *termSess {
	c.tmu.Lock()
	defer c.tmu.Unlock()
	return c.terms[sid]
}

// dropTerm forgets ts (its pump then ends silently) and closes it.
func (c *conn) dropTerm(ts *termSess) {
	c.tmu.Lock()
	if c.terms[ts.sid] == ts {
		delete(c.terms, ts.sid)
	}
	c.tmu.Unlock()
	ts.sess.Close()
}

func (c *conn) openTerm(ts *termSess) bool {
	sess, err := c.s.deps.Term.Open(c.ctx, ts.pane, ts.mode, ts.cols, ts.rows)
	if err != nil {
		c.termClosed(ts.sid, err.Error())
		return false
	}
	ts.sess = sess
	c.tmu.Lock()
	if c.ctx.Err() != nil {
		c.tmu.Unlock()
		sess.Close()
		return false
	}
	c.terms[ts.sid] = ts
	c.tmu.Unlock()
	c.goRun(func() { c.pumpTerm(ts) })
	return true
}

func (c *conn) pumpTerm(ts *termSess) {
	frames := ts.sess.Frames()
	for {
		select {
		case <-c.ctx.Done():
			return
		case f, ok := <-frames:
			if !ok {
				c.tmu.Lock()
				current := c.terms[ts.sid] == ts
				if current {
					delete(c.terms, ts.sid)
				}
				c.tmu.Unlock()
				if current {
					c.termClosed(ts.sid, "ended")
				}
				return
			}
			c.send(map[string]any{
				"t": "term.frame", "sid": ts.sid, "seq": f.Seq, "width": f.Width, "height": f.Height,
				"full": f.Full, "bytes": f.Bytes, // []byte marshals as base64
			})
		}
	}
}

func (c *conn) closeAllTerms() {
	c.tmu.Lock()
	ts := c.terms
	c.terms = map[string]*termSess{}
	c.tmu.Unlock()
	for _, t := range ts {
		t.sess.Close()
	}
}
