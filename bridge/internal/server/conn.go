package server

import (
	"context"
	"encoding/json"
	"net/http"
	"sync"
	"time"

	"github.com/coder/websocket"
)

const (
	helloTimeout = 10 * time.Second
	writeTimeout = 10 * time.Second
	maxWatches   = 4
	maxCmds      = 8 // concurrent commands per connection
	readLimit    = 4 << 20
)

// conn is one app connection.
type conn struct {
	s      *Server
	ws     *websocket.Conn
	ctx    context.Context
	cancel context.CancelFunc
	cmdSem chan struct{}
	wg     sync.WaitGroup

	wmu sync.Mutex // one frame at a time, in call order

	// mu guards the per-pane live state. It is held while sending live and
	// live.clear frames so a stale live frame can never follow live.clear.
	mu         sync.Mutex
	watches    map[string]*watch
	pending    map[string]bool   // watched panes not (yet) in state; activated by activatePending
	lastStatus map[string]string // pane -> "to" of the latest streamed status change

	tmu   sync.Mutex
	terms map[string]*termSess

	device string // paired device id, "" for legacy
	nonce  string // upgrade request nonce, signs the welcome
}

type watch struct{ stop func() }

type inMsg struct {
	T string `json:"t"`
	// hello
	ServerID string `json:"server_id"`
	Since    int64  `json:"since"`
	// cmd
	ID     string          `json:"id"`
	Method string          `json:"method"`
	Params json.RawMessage `json:"params"`
	// watch / term
	PaneID string `json:"pane_id"`
	SID    string `json:"sid"`
	Mode   string `json:"mode"`
	Cols   int    `json:"cols"`
	Rows   int    `json:"rows"`
	Text   string `json:"text"`
	Bytes  []byte `json:"bytes"` // base64 in JSON
}

func (s *Server) serveWS(w http.ResponseWriter, r *http.Request) {
	ws, err := websocket.Accept(w, r, nil)
	if err != nil {
		return
	}
	ws.SetReadLimit(readLimit)
	ctx, cancel := context.WithCancel(context.Background())
	c := &conn{
		s: s, ws: ws, ctx: ctx, cancel: cancel,
		cmdSem:     make(chan struct{}, maxCmds),
		watches:    map[string]*watch{},
		pending:    map[string]bool{},
		lastStatus: map[string]string{},
		terms:      map[string]*termSess{},
	}
	if a, ok := authFrom(r.Context()); ok {
		c.device, c.nonce = a.device, a.nonce
	}
	if !s.registerConn(c) { // revoked while the upgrade was in flight
		ws.Close(closeRevoked, codeDeviceRevoked)
		cancel()
		return
	}
	defer s.unregisterConn(c)
	c.run(r.RemoteAddr)
}

func (c *conn) run(remote string) {
	defer c.shutdown()
	hctx, hcancel := context.WithTimeout(c.ctx, helloTimeout)
	h, err := c.read(hctx)
	hcancel()
	if err != nil || h.T != "hello" {
		c.ws.Close(websocket.StatusPolicyViolation, "hello expected")
		return
	}
	c.s.log.Info("client connected", "remote", remote, "device", c.device, "since", h.Since)
	c.goRun(func() { c.syncLoop(h) })
	c.goRun(c.pingLoop)
	for {
		m, err := c.read(c.ctx)
		if err != nil {
			return
		}
		switch m.T {
		case "cmd":
			select {
			case c.cmdSem <- struct{}{}:
			default:
				// Never block the read loop (it would starve pongs and every
				// other client frame): refuse instead.
				c.send(map[string]any{"t": "res", "id": m.ID, "error": map[string]string{
					"code": "busy", "message": "too many commands in flight"}})
				continue
			}
			c.goRun(func() {
				defer func() { <-c.cmdSem }()
				c.handleCmd(m)
			})
		case "watch":
			c.watch(m.PaneID)
		case "unwatch":
			c.unwatch(m.PaneID)
		case "term.open", "term.input", "term.resize", "term.close":
			c.handleTerm(m)
		default:
			c.s.log.Debug("ignored client message", "t", m.T)
		}
	}
}

func (c *conn) goRun(f func()) {
	c.wg.Add(1)
	go func() {
		defer c.wg.Done()
		f()
	}()
}

func (c *conn) shutdown() {
	c.cancel()
	c.mu.Lock()
	ws := c.watches
	c.watches = map[string]*watch{}
	c.mu.Unlock()
	for _, w := range ws {
		if w.stop != nil {
			w.stop()
		}
	}
	c.closeAllTerms()
	c.ws.CloseNow()
	c.wg.Wait()
}

func (c *conn) read(ctx context.Context) (inMsg, error) {
	var m inMsg
	_, b, err := c.ws.Read(ctx)
	if err != nil {
		return m, err
	}
	if err := json.Unmarshal(b, &m); err != nil {
		c.s.log.Debug("bad client frame", "err", err)
		m.T = ""
	}
	return m, nil
}

// sendRaw writes one frame; a failed write ends the connection.
func (c *conn) sendRaw(b []byte) error {
	c.wmu.Lock()
	defer c.wmu.Unlock()
	ctx, cancel := context.WithTimeout(c.ctx, writeTimeout)
	defer cancel()
	err := c.ws.Write(ctx, websocket.MessageText, b)
	if err != nil {
		c.cancel()
		c.ws.CloseNow()
	}
	return err
}

func (c *conn) send(v any) error {
	b, err := json.Marshal(v)
	if err != nil {
		return err
	}
	return c.sendRaw(b)
}

func (c *conn) pingLoop() {
	t := time.NewTicker(c.s.cfg.PingInterval)
	defer t.Stop()
	for {
		select {
		case <-c.ctx.Done():
			return
		case <-t.C:
		}
		ctx, cancel := context.WithTimeout(c.ctx, 2*c.s.cfg.PingInterval)
		err := c.ws.Ping(ctx)
		cancel()
		if err != nil {
			c.cancel()
			c.ws.CloseNow()
			return
		}
	}
}

// --- live (ephemeral in-progress screen text) ---

func active(status string) bool { return status == "working" || status == "blocked" }

func (c *conn) paneInfo(paneID string) (agent, status string, ok bool) {
	for _, p := range c.s.deps.Watcher.Current().Panes {
		if p.ID == paneID {
			return p.Agent, p.AgentStatus, true
		}
	}
	return "", "", false
}

func (c *conn) watch(paneID string) {
	if c.s.deps.Live == nil || paneID == "" {
		return
	}
	agent, _, ok := c.paneInfo(paneID)
	c.mu.Lock()
	_, dup := c.watches[paneID]
	if dup || c.pending[paneID] || len(c.watches)+len(c.pending) >= maxWatches || c.ctx.Err() != nil {
		c.mu.Unlock()
		return
	}
	if !ok {
		// Not in state yet (e.g. just created): keep it until it appears.
		c.pending[paneID] = true
		c.mu.Unlock()
		return
	}
	w := &watch{}
	c.watches[paneID] = w
	c.mu.Unlock()
	status := func() string {
		_, st, _ := c.paneInfo(paneID)
		return st
	}
	stop := c.s.deps.Live.Watch(paneID, agent, status, func(text string) {
		c.mu.Lock()
		defer c.mu.Unlock()
		if c.watches[paneID] != w {
			return // unwatched (or re-watched) meanwhile
		}
		if st, seen := c.lastStatus[paneID]; seen && !active(st) {
			return // turn already cleared on the stream; wait for its next status
		}
		c.send(map[string]any{"t": "live", "pane_id": paneID, "text": text, "status": status()})
	})
	c.mu.Lock()
	w.stop = stop
	stale := c.watches[paneID] != w
	c.mu.Unlock()
	if stale {
		stop()
	}
}

// activatePending starts watches whose pane has appeared in state.
func (c *conn) activatePending() {
	c.mu.Lock()
	if len(c.pending) == 0 {
		c.mu.Unlock()
		return
	}
	ids := make([]string, 0, len(c.pending))
	for id := range c.pending {
		ids = append(ids, id)
	}
	c.mu.Unlock()
	for _, id := range ids {
		if _, _, ok := c.paneInfo(id); !ok {
			continue
		}
		c.mu.Lock()
		still := c.pending[id]
		delete(c.pending, id)
		c.mu.Unlock()
		if still {
			c.watch(id)
		}
	}
}

func (c *conn) unwatch(paneID string) {
	c.mu.Lock()
	delete(c.pending, paneID)
	w := c.watches[paneID]
	delete(c.watches, paneID)
	c.mu.Unlock()
	if w != nil && w.stop != nil {
		w.stop()
	}
}

// noteStatus records a streamed status change (called after it was sent) and
// clears live text of a watched pane whose turn completed.
func (c *conn) noteStatus(paneID, from, to string) {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.lastStatus[paneID] = to
	if _, watched := c.watches[paneID]; watched && active(from) && !active(to) {
		c.send(map[string]string{"t": "live.clear", "pane_id": paneID})
	}
}
