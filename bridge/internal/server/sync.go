package server

import (
	"encoding/json"
	"errors"
	"strconv"
	"time"

	"github.com/coder/websocket"

	"github.com/froid1911/whistle/bridge/internal/store"
)

const replayBatch = 500

// herdrPoll is how often a connection checks the watcher's Online() to push
// an ephemeral {"t":"herdr"} frame when it changes (wire-protocol "Ephemeral").
const herdrPoll = 250 * time.Millisecond

// errLogGap means entries the client needs were pruned while it was being
// served; the connection is closed so the client reconnects and resyncs.
var errLogGap = errors.New("change log pruned past client position")

// needResync decides how a hello is answered (wire-protocol "Connect / resume").
//
// A cursor at or below the store's head at bridge start (OpenHead) is always
// resynced: while the bridge was down herdr kept changing, and the restarted
// watcher only knows the state it persisted, which may be newer or older than
// the log (persisting and logging are not atomic). Resyncing those clients to
// Current() and diffing everything after the start from that same state is
// the only way no remove/status of the downtime can be lost.
//
// A client resynced by THIS process must not be resynced again on every
// reconnect: before such a resync, ensureEpoch logs one "epoch" entry, so
// its cursor (the new head) is above OpenHead and later hellos resume.
func (s *Server) needResync(serverID string, since, head int64) (bool, error) {
	if serverID != s.deps.Store.ServerID() || since <= 0 || since > head || since <= s.deps.Store.OpenHead() {
		return true, nil
	}
	if since == head {
		return false, nil
	}
	oldest, err := s.deps.Store.Oldest()
	if err != nil {
		return false, err
	}
	// oldest == 0: the log was fully pruned although head > since.
	return oldest == 0 || since+1 < oldest, nil
}

// ensureEpoch logs a single {"kind":"epoch"} entry (clients ignore unknown
// kinds) once per process when the head is still at OpenHead, and returns the
// current head. Resyncing to a head > OpenHead lets that client resume later.
func (s *Server) ensureEpoch() (int64, error) {
	s.emu.Lock()
	defer s.emu.Unlock()
	head, err := s.deps.Store.Head()
	if err != nil || head > s.deps.Store.OpenHead() {
		return head, err
	}
	e, err := s.deps.Store.Append("epoch", struct {
		Kind string `json:"kind"`
	}{"epoch"})
	return e.Seq, err
}

// changeFrame renders an entry as {"t":"change","seq":N,<payload fields>}.
func changeFrame(e store.Entry) []byte {
	b := make([]byte, 0, len(e.Payload)+40)
	b = append(b, `{"t":"change","seq":`...)
	b = strconv.AppendInt(b, e.Seq, 10)
	p := e.Payload
	if len(p) > 2 && p[0] == '{' {
		b = append(b, ',')
		b = append(b, p[1:]...)
	} else {
		b = append(b, '}')
	}
	return b
}

// syncLoop answers hello and then streams the change log forever.
//
// Gap-freedom: subscribe BEFORE reading head/replaying, so every entry
// committed after the replay's snapshot is also in the subscription; entries
// already sent (seq <= last) are skipped. A subscription the store closed for
// being slow is replaced by a new one followed by a catch-up replay from last.
func (c *conn) syncLoop(h inMsg) {
	s := c.s
	ch, cancel := s.subscribe()
	defer func() { cancel() }()

	err := func() error {
		head, err := s.deps.Store.Head()
		if err != nil {
			return err
		}
		resync, err := s.needResync(h.ServerID, h.Since, head)
		if err != nil {
			return err
		}
		if resync && head <= s.deps.Store.OpenHead() {
			if head, err = s.ensureEpoch(); err != nil {
				return err
			}
		}
		welcome := map[string]any{
			"t": "welcome", "protocol": 1, "server_id": s.deps.Store.ServerID(),
			"head_seq": head, "resync": resync, "herdr_version": s.deps.Watcher.Version(),
		}
		if sig := s.welcomeSig(c.nonce); sig != "" {
			welcome["sig"] = sig
		}
		if err := c.send(welcome); err != nil {
			return err
		}
		online := s.deps.Watcher.Online()
		if err := c.sendHerdr(online); err != nil {
			return err
		}
		last := h.Since
		if resync {
			// The watcher's state is never older than the log (changes are
			// derived from it), so it covers every entry <= head.
			last = head
			if err := c.send(map[string]any{"t": "state", "seq": head, "state": s.deps.Watcher.Current()}); err != nil {
				return err
			}
		} else if last, err = c.replay(last); err != nil {
			return err
		}
		if err := c.send(map[string]any{"t": "synced", "seq": last}); err != nil {
			return err
		}
		tick := time.NewTicker(herdrPoll)
		defer tick.Stop()
		for {
			select {
			case <-c.ctx.Done():
				return nil
			case <-tick.C:
				c.activatePending()
				// Polled here (not in its own goroutine) so it is only ever sent after welcome.
				if now := s.deps.Watcher.Online(); now != online {
					online = now
					if err := c.sendHerdr(online); err != nil {
						return err
					}
				}
			case e, ok := <-ch:
				if !ok {
					// Store cut us off for being slow: resubscribe, then catch up.
					cancel()
					ch, cancel = s.subscribe()
					if last, err = c.replay(last); err != nil {
						return err
					}
					continue
				}
				if e.Seq <= last {
					continue
				}
				if e.Seq != last+1 {
					// A hole between last and e: fill it from the log.
					if last, err = c.replay(last); err != nil {
						return err
					}
					if e.Seq <= last {
						continue
					}
				}
				if err := c.sendEntry(e); err != nil {
					return err
				}
				last = e.Seq
			}
		}
	}()
	if errors.Is(err, errLogGap) {
		s.log.Warn("client fell behind pruned log; closing for resync")
		c.ws.Close(websocket.StatusTryAgainLater, "resync")
	} else if err != nil && c.ctx.Err() == nil {
		s.log.Error("sync stream", "err", err)
	}
	c.cancel()
	c.ws.CloseNow()
}

// replay sends every logged entry after last; returns the new last seq.
func (c *conn) replay(last int64) (int64, error) {
	for {
		es, err := c.s.deps.Store.Since(last, replayBatch)
		if err != nil {
			return last, err
		}
		if len(es) == 0 {
			return last, nil
		}
		if es[0].Seq != last+1 {
			// seqs are contiguous unless pruned; verify before skipping ahead.
			oldest, err := c.s.deps.Store.Oldest()
			if err != nil {
				return last, err
			}
			if last+1 < oldest {
				return last, errLogGap
			}
		}
		for _, e := range es {
			if err := c.sendEntry(e); err != nil {
				return last, err
			}
			last = e.Seq
		}
	}
}

func (c *conn) sendHerdr(online bool) error {
	return c.send(map[string]any{"t": "herdr", "online": online})
}

func (c *conn) sendEntry(e store.Entry) error {
	if err := c.sendRaw(changeFrame(e)); err != nil {
		return err
	}
	if e.Kind == "status" {
		var p struct {
			PaneID string `json:"pane_id"`
			From   string `json:"from"`
			To     string `json:"to"`
		}
		if json.Unmarshal(e.Payload, &p) == nil && p.PaneID != "" {
			c.noteStatus(p.PaneID, p.From, p.To)
		}
	}
	return nil
}
