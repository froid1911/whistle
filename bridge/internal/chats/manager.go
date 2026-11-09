package chats

import (
	"bytes"
	"context"
	"io"
	"log/slog"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"sync"
	"time"

	"github.com/froid1911/whistle/bridge/internal/herdr"
	"github.com/froid1911/whistle/bridge/internal/state"
	"github.com/froid1911/whistle/bridge/internal/store"
	"github.com/froid1911/whistle/bridge/internal/transcript"
)

// ScreenLines is how many recent_unwrapped lines a final screen capture reads.
const ScreenLines = 120

// HerdrReader reads pane screen text.
type HerdrReader interface {
	Read(ctx context.Context, paneID, source string, lines int) (string, error)
}

// HerdrAdapter implements HerdrReader over herdr's agent.read.
type HerdrAdapter struct{ C herdr.Caller }

func (a HerdrAdapter) Read(ctx context.Context, paneID, source string, lines int) (string, error) {
	// herdr 0.9.5 answers {"type":"pane_read","read":{...,"text":"..."}};
	// a top-level text is accepted too.
	var res struct {
		Text string `json:"text"`
		Read *struct {
			Text string `json:"text"`
		} `json:"read"`
	}
	err := a.C.Call(ctx, "agent.read", map[string]any{
		"target": paneID, "source": source, "lines": lines, "format": "text", "strip_ansi": true,
	}, &res)
	if res.Read != nil {
		return res.Read.Text, err
	}
	return res.Text, err
}

// Manager keeps one transcript tailer per live agent session and appends
// state changes to the store, ordering turn-completion status entries after
// the turn's final reply.
//
// Tailers run for every live agent session regardless of status edges, so
// replies are captured even when the watcher coalesces a whole turn or the
// pane disappears mid-turn; reconciliation only delays the status entry.
//
// Ordering: workspace/tab/layout changes are appended immediately. Each pane
// has its own ordered lane for its upserts, removes, status entries and
// session stops, so a pane's reconciliation delays only that pane's later
// changes. OnState never blocks on reconciliation or tailer shutdown.
type Manager struct {
	// ReconcileTimeout bounds how long a completion status waits for the
	// final reply (default 3 s).
	ReconcileTimeout time.Duration
	// RetryMin/RetryMax bound the backoff while a session's transcript file
	// does not exist yet or a batch failed to persist (defaults 500 ms / 5 s).
	RetryMin, RetryMax time.Duration
	// PollStep is the reconcile re-check interval (default 25 ms).
	PollStep time.Duration

	st   *store.Store
	hc   HerdrReader
	home string
	log  *slog.Logger

	// ctx is the Manager's lifetime; all queued work runs under it.
	ctx    context.Context
	cancel context.CancelFunc

	// tail is transcript.Tail; appendMessages is st.AppendMessages.
	// Replaceable in tests.
	tail           func(ctx context.Context, path string, offset int64, p transcript.Parser, sessionKey string, emit func([]store.Message, int64)) error
	appendMessages func(sessionKey string, msgs []store.Message) ([]store.Entry, error)

	mu      sync.Mutex
	tailers map[string]*tailer
	live    map[string]bool   // session keys of live agent panes (latest state)
	keyPane map[string]string // session key -> pane holding it (latest state)
	lanes   map[string]*lane  // pane id -> lane with pending work
	pending int               // queued + running lane tasks
	idle    *sync.Cond        // signalled when pending drops to 0
}

// lane runs one pane's tasks in order on a goroutine that exits when empty.
type lane struct {
	tasks []func()
}

func NewManager(st *store.Store, hc HerdrReader, home string, log *slog.Logger) *Manager {
	if log == nil {
		log = slog.Default()
	}
	ctx, cancel := context.WithCancel(context.Background())
	m := &Manager{
		ReconcileTimeout: 3 * time.Second,
		RetryMin:         500 * time.Millisecond,
		RetryMax:         5 * time.Second,
		PollStep:         25 * time.Millisecond,
		st:               st, hc: hc, home: home, log: log,
		ctx: ctx, cancel: cancel,
		tail:           transcript.Tail,
		appendMessages: st.AppendMessages,
		tailers:        map[string]*tailer{},
		live:           map[string]bool{},
		keyPane:        map[string]string{},
		lanes:          map[string]*lane{},
	}
	m.idle = sync.NewCond(&m.mu)
	return m
}

// Close cancels pending work, waits for the lanes and stops all tailers.
// Cursors are already persisted.
func (m *Manager) Close() {
	m.cancel()
	m.barrier()
	m.mu.Lock()
	keys := make([]string, 0, len(m.tailers))
	for k := range m.tailers {
		keys = append(keys, k)
	}
	m.mu.Unlock()
	for _, k := range keys {
		m.stopTailer(k, false)
	}
}

// enqueue adds task to pane's lane; caller holds m.mu.
func (m *Manager) enqueue(pane string, task func()) {
	m.pending++
	if l, ok := m.lanes[pane]; ok {
		l.tasks = append(l.tasks, task)
		return
	}
	l := &lane{tasks: []func(){task}}
	m.lanes[pane] = l
	go m.runLane(pane, l)
}

func (m *Manager) runLane(pane string, l *lane) {
	m.mu.Lock()
	for len(l.tasks) > 0 {
		task := l.tasks[0]
		l.tasks = l.tasks[1:]
		m.mu.Unlock()
		if m.ctx.Err() == nil {
			task()
		}
		m.mu.Lock()
		m.pending--
	}
	delete(m.lanes, pane)
	if m.pending == 0 {
		m.idle.Broadcast()
	}
	m.mu.Unlock()
}

// barrier waits until every queued lane task has run.
func (m *Manager) barrier() {
	m.mu.Lock()
	for m.pending > 0 {
		m.idle.Wait()
	}
	m.mu.Unlock()
}

func needsReconcile(c state.Change) bool {
	return (c.From == "working" || c.From == "blocked") && (c.To == "idle" || c.To == "done")
}

// OnState handles one watcher update without blocking: it starts tailers for
// new sessions, appends workspace/tab/layout changes, and queues each pane's
// changes on that pane's lane. A session that left is drained to EOF and
// stopped on its pane's lane before that pane's changes of this update.
// ctx is unused for queued work, which runs under the Manager's lifetime.
func (m *Manager) OnState(_ context.Context, s state.State, changes []state.Change) {
	at := time.Now().UTC().Format(time.RFC3339)
	agents := map[string]string{}
	live := map[string]bool{}
	keyPane := map[string]string{}
	for _, p := range s.Panes {
		agents[p.ID] = p.Agent
		if p.Kind == "agent" && p.SessionKey != "" {
			live[p.SessionKey] = true
			keyPane[p.SessionKey] = p.ID
		}
	}

	var immediate []state.Change
	defer func() {
		for _, c := range immediate {
			m.appendChange(c, at)
		}
	}()
	m.mu.Lock()
	defer m.mu.Unlock()
	if m.ctx.Err() != nil {
		return
	}
	// Sessions that left (pane removed, turned shell, switched session).
	var gone []string
	for k := range m.tailers {
		if !live[k] {
			gone = append(gone, k)
		}
	}
	sort.Strings(gone)
	for _, k := range gone {
		pane := m.keyPane[k]
		k := k
		m.enqueue(pane, func() { m.retire(k) })
	}
	m.live, m.keyPane = live, keyPane
	for k := range live {
		m.ensureTailerLocked(k)
	}

	for _, c := range changes {
		c := c
		if c.Entity != "pane" {
			immediate = append(immediate, c)
			continue
		}
		pane := c.ID
		if c.Kind == "status" && needsReconcile(c) {
			agent := agents[c.PaneID]
			m.enqueue(pane, func() {
				m.reconcile(c, agent)
				m.appendChange(c, at)
			})
			continue
		}
		m.enqueue(pane, func() { m.appendChange(c, at) })
	}
}

// retire drains a session that left to EOF and stops its tailer, unless the
// session has become live again (e.g. moved to another pane) meanwhile.
func (m *Manager) retire(key string) {
	m.mu.Lock()
	if m.live[key] {
		m.mu.Unlock()
		return
	}
	m.mu.Unlock()
	m.stopTailer(key, true)
}

func (m *Manager) appendChange(c state.Change, at string) {
	var err error
	switch c.Kind {
	case "upsert":
		_, err = m.st.Append("upsert", struct {
			Kind   string `json:"kind"`
			Entity string `json:"entity"`
			Data   any    `json:"data"`
		}{"upsert", c.Entity, c.Data})
	case "remove":
		_, err = m.st.Append("remove", struct {
			Kind   string `json:"kind"`
			Entity string `json:"entity"`
			ID     string `json:"id"`
		}{"remove", c.Entity, c.ID})
	case "status":
		_, err = m.st.Append("status", struct {
			Kind       string `json:"kind"`
			PaneID     string `json:"pane_id"`
			SessionKey string `json:"session_key,omitempty"`
			From       string `json:"from"`
			To         string `json:"to"`
			At         string `json:"at"`
		}{"status", c.PaneID, c.SessionKey, c.From, c.To, at})
	}
	if err != nil {
		m.log.Error("append change", "kind", c.Kind, "id", c.ID, "err", err)
	}
}

// reconcile waits until the session's tailer is at EOF and the turn's final
// assistant reply is stored, up to ReconcileTimeout. Agents without a
// transcript get one cleaned screen capture instead.
func (m *Manager) reconcile(c state.Change, agent string) {
	var t *tailer
	if c.SessionKey != "" {
		m.mu.Lock()
		t = m.tailers[c.SessionKey]
		m.mu.Unlock()
	}
	if t != nil {
		deadline := time.Now().Add(m.ReconcileTimeout)
		for !t.settled() {
			if !time.Now().Before(deadline) {
				break
			}
			select {
			case <-m.ctx.Done():
				return
			case <-time.After(m.PollStep):
			}
		}
		if t.isFound() {
			return
		}
	}
	m.appendScreen(c, agent)
}

func (m *Manager) appendScreen(c state.Change, agent string) {
	rctx, cancel := context.WithTimeout(m.ctx, 5*time.Second)
	defer cancel()
	txt, err := m.hc.Read(rctx, c.PaneID, "recent_unwrapped", ScreenLines)
	if err != nil {
		m.log.Warn("screen capture", "pane", c.PaneID, "err", err)
		return
	}
	key := c.SessionKey
	if key == "" {
		key = "pane:" + c.PaneID
	}
	now := time.Now().UTC()
	msg := store.Message{
		ID:   "screen:" + c.PaneID + ":" + now.Format(time.RFC3339Nano),
		TS:   now.Format(time.RFC3339),
		Role: "screen",
		Text: Clean(agent, txt),
	}
	if _, err := m.appendMessages(key, []store.Message{msg}); err != nil {
		m.log.Error("append screen", "pane", c.PaneID, "err", err)
	}
}

// HasTranscript reports whether sessionKey currently has a transcript file.
// Safe for concurrent use; cheap (a few stats) — suitable as the watcher's
// Decorate hook via State.MarkTranscripts.
func (m *Manager) HasTranscript(sessionKey string) bool {
	m.mu.Lock()
	t := m.tailers[sessionKey]
	m.mu.Unlock()
	if t != nil && t.isFound() {
		return true
	}
	sess, ok := sessionOf(sessionKey)
	if !ok {
		return false
	}
	_, _, found := transcript.Locate(m.home, sess)
	return found
}

// sessionOf rebuilds the herdr agent session from a "<agent>:<value>" key:
// absolute paths are kind "path" (omp), anything else kind "id" (Claude).
func sessionOf(key string) (herdr.AgentSession, bool) {
	i := strings.IndexByte(key, ':')
	if i <= 0 || i == len(key)-1 {
		return herdr.AgentSession{}, false
	}
	s := herdr.AgentSession{Agent: key[:i], Kind: "id", Value: key[i+1:]}
	if filepath.IsAbs(s.Value) {
		s.Kind = "path"
	}
	return s, true
}

func (m *Manager) tailerKeys() []string {
	m.mu.Lock()
	defer m.mu.Unlock()
	out := make([]string, 0, len(m.tailers))
	for k := range m.tailers {
		out = append(out, k)
	}
	sort.Strings(out)
	return out
}

func (m *Manager) startOffset(key string) int64 {
	m.mu.Lock()
	t := m.tailers[key]
	m.mu.Unlock()
	if t == nil {
		return -1
	}
	t.mu.Lock()
	defer t.mu.Unlock()
	return t.startOff
}

// tailer follows one session's transcript into the store.
type tailer struct {
	m      *Manager
	key    string
	cancel context.CancelFunc
	done   chan struct{}

	mu       sync.Mutex
	found    bool
	path     string
	parser   transcript.Parser
	offset   int64 // persisted cursor: just past the last consumed line
	startOff int64
	replied  bool // a final assistant reply follows the last user message
}

// ensureTailerLocked starts a tailer for key if none runs; caller holds m.mu.
func (m *Manager) ensureTailerLocked(key string) {
	if _, ok := m.tailers[key]; ok || m.ctx.Err() != nil {
		return
	}
	ctx, cancel := context.WithCancel(m.ctx)
	t := &tailer{m: m, key: key, cancel: cancel, done: make(chan struct{})}
	if p, off, ok := m.st.Cursor(key); ok {
		t.path, t.offset = p, off
	}
	if hist, _, err := m.st.History(key, 0, 50); err == nil {
		t.observe(hist)
	}
	m.tailers[key] = t
	go t.run(ctx)
}

func (m *Manager) stopTailer(key string, flush bool) {
	m.mu.Lock()
	t := m.tailers[key]
	delete(m.tailers, key)
	m.mu.Unlock()
	if t == nil {
		return
	}
	t.cancel()
	<-t.done
	if flush {
		t.flush()
	}
}

// observe updates the reply state; caller holds t.mu or owns t exclusively.
// Tool calls/results after a reply mean the turn is still going, so only an
// assistant text message after the last user message counts as the reply.
func (t *tailer) observe(msgs []store.Message) {
	for _, mm := range msgs {
		switch mm.Role {
		case "user", "tool_call", "tool_result":
			t.replied = false
		case "assistant":
			t.replied = true
		}
	}
}

func (t *tailer) isFound() bool {
	t.mu.Lock()
	defer t.mu.Unlock()
	return t.found
}

// settled: transcript found, tailer at EOF, and the final reply is stored.
func (t *tailer) settled() bool {
	t.mu.Lock()
	found, path, off, replied := t.found, t.path, t.offset, t.replied
	t.mu.Unlock()
	if !found || !replied {
		return false
	}
	fi, err := os.Stat(path)
	return err == nil && fi.Size() <= off
}

func (t *tailer) run(ctx context.Context) {
	defer close(t.done)
	m := t.m
	sess, ok := sessionOf(t.key)
	if !ok {
		return
	}
	delay := m.RetryMin
	for {
		if path, p, found := transcript.Locate(m.home, sess); found {
			t.mu.Lock()
			if path != t.path {
				t.path, t.offset = path, 0
			}
			t.found, t.parser, t.startOff = true, p, t.offset
			off := t.offset
			t.mu.Unlock()
			// Each run gets its own ctx: when a batch fails to persist, the
			// run is cancelled (Tail's internal offset is already past the
			// batch) and restarted from the last persisted offset. Re-reading
			// is safe: AppendMessages skips identical messages.
			runCtx, runCancel := context.WithCancel(ctx)
			failed := false
			err := m.tail(runCtx, path, off, p, t.key, func(msgs []store.Message, newOff int64) {
				if failed {
					return
				}
				if !t.emit(path, msgs, newOff) {
					failed = true
					runCancel()
				}
			})
			runCancel()
			if ctx.Err() != nil {
				return
			}
			if failed {
				m.log.Warn("transcript batch not persisted; retrying", "session", t.key, "offset", off)
			} else {
				m.log.Warn("transcript tail", "session", t.key, "err", err)
				t.mu.Lock()
				t.found = false
				t.mu.Unlock()
				delay = m.RetryMin
			}
		}
		select {
		case <-ctx.Done():
			return
		case <-time.After(delay):
		}
		if delay *= 2; delay > m.RetryMax {
			delay = m.RetryMax
		}
	}
}

// emit persists a batch, then its cursor (same order), then updates state.
// It reports false when the batch was not stored; offset and cursor then
// stay at the last persisted batch so the caller can re-read it.
func (t *tailer) emit(path string, msgs []store.Message, newOff int64) bool {
	if len(msgs) > 0 {
		if _, err := t.m.appendMessages(t.key, msgs); err != nil {
			t.m.log.Error("append messages", "session", t.key, "err", err)
			return false
		}
	}
	if err := t.m.st.SetCursor(t.key, path, newOff); err != nil {
		t.m.log.Error("set cursor", "session", t.key, "err", err)
	}
	t.mu.Lock()
	t.path, t.offset = path, newOff
	t.observe(msgs)
	t.mu.Unlock()
	return true
}

// flush drains complete lines from the cursor to EOF (tail goroutine stopped).
func (t *tailer) flush() {
	t.mu.Lock()
	found, path, off, p := t.found, t.path, t.offset, t.parser
	t.mu.Unlock()
	if !found || p == nil {
		return
	}
	f, err := os.Open(path)
	if err != nil {
		return
	}
	defer f.Close()
	fi, err := f.Stat()
	if err != nil {
		return
	}
	if fi.Size() < off {
		off = 0
	}
	if _, err := f.Seek(off, io.SeekStart); err != nil {
		return
	}
	data, err := io.ReadAll(f)
	if err != nil {
		return
	}
	last := bytes.LastIndexByte(data, '\n')
	if last < 0 {
		return
	}
	var msgs []store.Message
	for _, line := range bytes.Split(data[:last], []byte{'\n'}) {
		if len(bytes.TrimSpace(line)) == 0 {
			continue
		}
		if ms, err := p.ParseLine(t.key, line); err == nil {
			msgs = append(msgs, ms...)
		}
	}
	t.emit(path, msgs, off+int64(last+1))
}
