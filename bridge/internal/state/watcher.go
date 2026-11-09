package state

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"sort"
	"strings"
	"sync"
	"time"

	"github.com/froid1911/whistle/bridge/internal/herdr"
)

var errResub = errors.New("agent pane set changed")

// ErrProtocolMismatch is returned by Run when herdr's protocol is not 22.
var ErrProtocolMismatch = errors.New("state: herdr protocol mismatch (want 22)")

const (
	wantProtocol = 22
	debounce     = 100 * time.Millisecond
	minBackoff   = 250 * time.Millisecond
	maxBackoff   = 5 * time.Second
)

var globalTypes = []string{
	"workspace.created", "workspace.updated", "workspace.metadata_updated", "workspace.renamed",
	"workspace.moved", "workspace.reordered", "workspace.closed", "workspace.focused",
	"worktree.created", "worktree.opened", "worktree.removed",
	"tab.created", "tab.closed", "tab.focused", "tab.renamed", "tab.moved",
	"pane.created", "pane.closed", "pane.updated", "pane.focused", "pane.moved",
	"pane.exited", "pane.agent_detected", "layout.updated",
}

type Watcher struct {
	socket string
	log    *slog.Logger

	// Decorate, when non-nil, is applied to every freshly mapped State
	// (before Diff). Set it before Run.
	Decorate func(*State)
	// Persist, when non-nil, receives every mapped State that produced
	// changes (after onState), so a restarted bridge can Seed from it.
	Persist func(State)

	mu      sync.Mutex
	cur     State
	online  bool
	version string
}

func NewWatcher(socketPath string, log *slog.Logger) *Watcher {
	if log == nil {
		log = slog.Default()
	}
	return &Watcher{socket: socketPath, log: log, cur: FromSnapshot(herdr.Snapshot{})}
}

// refreshTimeout bounds one snapshot: a herdr that accepts but never answers
// must surface as offline (and be redialed), not as frozen online state.
var refreshTimeout = 5 * time.Second

// Seed sets the state the first snapshot is diffed against (the state last
// persisted before a restart), so entities that vanished while the bridge was
// down get a remove. Call before Run.
func (w *Watcher) Seed(s State) {
	w.mu.Lock()
	w.cur = s
	w.mu.Unlock()
}

func (w *Watcher) Online() bool {
	w.mu.Lock()
	defer w.mu.Unlock()
	return w.online
}

// Version is herdr's version from the latest snapshot ("" before the first).
func (w *Watcher) Version() string {
	w.mu.Lock()
	defer w.mu.Unlock()
	return w.version
}

func (w *Watcher) Current() State {
	w.mu.Lock()
	defer w.mu.Unlock()
	return w.cur
}

func (w *Watcher) setOnline(v bool) {
	w.mu.Lock()
	w.online = v
	w.mu.Unlock()
}

// refresh takes a snapshot, diffs it against the current state and reports.
func (w *Watcher) refresh(ctx context.Context, c herdr.Caller, onState func(State, []Change)) (State, error) {
	rctx, cancel := context.WithTimeout(ctx, refreshTimeout)
	snap, err := herdr.GetSnapshot(rctx, c)
	cancel()
	if err != nil {
		return State{}, err
	}
	if snap.Protocol != wantProtocol {
		return State{}, fmt.Errorf("%w: got %d", ErrProtocolMismatch, snap.Protocol)
	}
	ns := FromSnapshot(snap)
	if w.Decorate != nil {
		w.Decorate(&ns)
	}
	w.mu.Lock()
	changes := Diff(w.cur, ns)
	w.cur = ns
	w.online = true
	w.version = snap.Version
	w.mu.Unlock()
	if len(changes) > 0 {
		onState(ns, changes)
		if w.Persist != nil {
			w.Persist(ns)
		}
	}
	return ns, nil
}

func agentPaneIDs(s State) []string {
	var ids []string
	for _, p := range s.Panes {
		if p.Kind == "agent" {
			ids = append(ids, p.ID)
		}
	}
	sort.Strings(ids)
	return ids
}

// Run watches herdr until ctx is done or the protocol mismatches.
func (w *Watcher) Run(ctx context.Context, onState func(State, []Change)) error {
	backoff := minBackoff
	for {
		connected, err := w.session(ctx, onState)
		if ctx.Err() != nil {
			return ctx.Err()
		}
		if errors.Is(err, ErrProtocolMismatch) {
			return err
		}
		if errors.Is(err, errResub) {
			continue
		}
		w.setOnline(false)
		if connected {
			backoff = minBackoff
		}
		if err != nil {
			w.log.Warn("herdr watch interrupted", "err", err, "retry_in", backoff)
		}
		select {
		case <-ctx.Done():
			return ctx.Err()
		case <-time.After(backoff):
		}
		if backoff *= 2; backoff > maxBackoff {
			backoff = maxBackoff
		}
	}
}

// session runs one connect/snapshot/subscribe cycle. It returns when the
// agent pane set changes (errResub), or on failure. connected reports whether
// the first snapshot succeeded.
func (w *Watcher) session(ctx context.Context, onState func(State, []Change)) (connected bool, err error) {
	// herdr serves one request per connection: every snapshot dials anew.
	c := herdr.NewRedialer(w.socket)
	st, err := w.refresh(ctx, c, onState)
	if err != nil {
		return false, err
	}
	connected = true

	subs := make([]herdr.Subscription, 0, len(globalTypes))
	for _, t := range globalTypes {
		subs = append(subs, herdr.Subscription{Type: t})
	}
	ids := agentPaneIDs(st)
	for _, id := range ids {
		subs = append(subs, herdr.Subscription{Type: "pane.agent_status_changed", PaneID: id})
	}
	sctx, cancel := context.WithCancel(ctx)
	defer cancel()
	events, errs, err := herdr.Subscribe(sctx, w.socket, subs)
	if err != nil {
		return connected, err
	}
	// Events between the snapshot and the subscription are covered by
	// re-snapshotting once the subscription is live.
	if st, err = w.refresh(ctx, c, onState); err != nil {
		return connected, err
	}
	if strings.Join(agentPaneIDs(st), ",") != strings.Join(ids, ",") {
		return connected, errResub
	}

	var timer *time.Timer
	var timerC <-chan time.Time
	defer func() {
		if timer != nil {
			timer.Stop()
		}
	}()
	resnap := func() (resub bool, err error) {
		st, err := w.refresh(ctx, c, onState)
		if err != nil {
			return false, err
		}
		return strings.Join(agentPaneIDs(st), ",") != strings.Join(ids, ","), nil
	}
	for {
		select {
		case <-ctx.Done():
			return connected, ctx.Err()
		case _, ok := <-events:
			if !ok {
				return connected, streamEnd(errs)
			}
			if timerC == nil {
				timer = time.NewTimer(debounce)
				timerC = timer.C
			}
		case e, ok := <-errs:
			if !ok {
				return connected, errors.New("herdr event stream closed")
			}
			if herdr.IsCode(e, "events_lost") {
				resub, err := resnap()
				if err != nil {
					return connected, err
				}
				if resub {
					return connected, errResub
				}
				continue
			}
			return connected, e
		case <-timerC:
			timerC = nil
			resub, err := resnap()
			if err != nil {
				return connected, err
			}
			if resub {
				return connected, errResub
			}
		}
	}
}

// streamEnd returns the stream's terminal error if one is already buffered,
// else a generic closed error. It never blocks.
func streamEnd(errs <-chan error) error {
	select {
	case e, ok := <-errs:
		if ok && e != nil {
			return e
		}
	default:
	}
	return errors.New("herdr event stream closed")
}
