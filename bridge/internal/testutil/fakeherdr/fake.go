// Package fakeherdr is an in-process fake herdr server for tests.
package fakeherdr

import (
	"bufio"
	"encoding/json"
	"net"
	"os"
	"path/filepath"
	"sync"
	"testing"

	"github.com/froid1911/whistle/bridge/internal/herdr"
)

type HandlerFunc func(params json.RawMessage) (any, *herdr.APIError)

type Fake struct {
	t    testing.TB
	path string

	mu       sync.Mutex
	ln       net.Listener
	conns    map[*fconn]struct{}
	handlers map[string]HandlerFunc
	calls    map[string][]json.RawMessage
	snapshot herdr.Snapshot
	stopped  bool
	wg       sync.WaitGroup
}

type fconn struct {
	c   net.Conn
	wmu sync.Mutex
	sub bool
}

func (fc *fconn) write(v any) {
	b, _ := json.Marshal(v)
	fc.wmu.Lock()
	fc.c.Write(append(b, '\n'))
	fc.wmu.Unlock()
}

// New starts a fake on a temp unix socket; it stops on test cleanup.
func New(t testing.TB) *Fake {
	t.Helper()
	dir, err := os.MkdirTemp("", "fh")
	if err != nil {
		t.Fatal(err)
	}
	f := &Fake{
		t: t, path: filepath.Join(dir, "herdr.sock"),
		conns: map[*fconn]struct{}{}, handlers: map[string]HandlerFunc{},
		calls: map[string][]json.RawMessage{},
	}
	f.listen()
	t.Cleanup(func() { f.Stop(); os.RemoveAll(dir) })
	return f
}

func (f *Fake) listen() {
	os.Remove(f.path)
	ln, err := net.Listen("unix", f.path)
	if err != nil {
		f.t.Fatal(err)
	}
	f.mu.Lock()
	f.ln = ln
	f.stopped = false
	f.mu.Unlock()
	f.wg.Add(1)
	go f.accept(ln)
}

func (f *Fake) accept(ln net.Listener) {
	defer f.wg.Done()
	for {
		c, err := ln.Accept()
		if err != nil {
			return
		}
		fc := &fconn{c: c}
		f.mu.Lock()
		if f.stopped {
			f.mu.Unlock()
			c.Close()
			continue
		}
		f.conns[fc] = struct{}{}
		f.mu.Unlock()
		f.wg.Add(1)
		go f.serve(fc)
	}
}

func (f *Fake) serve(fc *fconn) {
	defer f.wg.Done()
	defer func() {
		fc.c.Close()
		f.mu.Lock()
		delete(f.conns, fc)
		f.mu.Unlock()
	}()
	sc := bufio.NewScanner(fc.c)
	sc.Buffer(make([]byte, 0, 64<<10), 16<<20)
	for sc.Scan() {
		var req struct {
			ID     string          `json:"id"`
			Method string          `json:"method"`
			Params json.RawMessage `json:"params"`
		}
		if json.Unmarshal(sc.Bytes(), &req) != nil {
			continue
		}
		f.mu.Lock()
		f.calls[req.Method] = append(f.calls[req.Method], req.Params)
		h := f.handlers[req.Method]
		snap := f.snapshot
		f.mu.Unlock()

		switch {
		case h != nil:
			res, apiErr := h(req.Params)
			if apiErr != nil {
				fc.write(map[string]any{"id": req.ID, "error": apiErr})
			} else {
				fc.write(map[string]any{"id": req.ID, "result": res})
			}
		case req.Method == "events.subscribe":
			f.mu.Lock()
			fc.sub = true
			f.mu.Unlock()
			fc.write(map[string]any{"id": req.ID, "result": map[string]string{"type": "subscription_started"}})
		case req.Method == "session.snapshot":
			fc.write(map[string]any{"id": req.ID, "result": map[string]any{"type": "session_snapshot", "snapshot": snap}})
		default:
			fc.write(map[string]any{"id": req.ID, "error": &herdr.APIError{Code: "unknown_method", Message: req.Method}})
		}
		// Like real herdr (0.9.5): a non-subscription connection serves
		// exactly one request, then the server closes it.
		if req.Method != "events.subscribe" {
			return
		}
	}
}

func (f *Fake) SocketPath() string { return f.path }

func (f *Fake) SetSnapshot(s herdr.Snapshot) {
	f.mu.Lock()
	f.snapshot = s
	f.mu.Unlock()
}

// Handle overrides a method (including session.snapshot / events.subscribe).
func (f *Fake) Handle(method string, fn func(params json.RawMessage) (any, *herdr.APIError)) {
	f.mu.Lock()
	f.handlers[method] = fn
	f.mu.Unlock()
}

// Calls returns the params of every request received for method.
func (f *Fake) Calls(method string) []json.RawMessage {
	f.mu.Lock()
	defer f.mu.Unlock()
	return append([]json.RawMessage(nil), f.calls[method]...)
}

func (f *Fake) broadcast(v any) {
	f.mu.Lock()
	var subs []*fconn
	for c := range f.conns {
		if c.sub {
			subs = append(subs, c)
		}
	}
	f.mu.Unlock()
	for _, c := range subs {
		c.write(v)
	}
}

// Emit sends {"event":..,"data":..} to all subscribers.
func (f *Fake) Emit(event string, data any) {
	f.broadcast(map[string]any{"event": event, "data": data})
}

// EmitEventsLost sends an events_lost error line to all subscribers.
func (f *Fake) EmitEventsLost() {
	f.broadcast(map[string]any{"error": &herdr.APIError{Code: "events_lost", Message: "events lost"}})
}

func (f *Fake) closeAll() {
	f.mu.Lock()
	f.stopped = true
	ln := f.ln
	var cs []*fconn
	for c := range f.conns {
		cs = append(cs, c)
	}
	f.mu.Unlock()
	ln.Close()
	for _, c := range cs {
		c.c.Close()
	}
	f.wg.Wait()
}

// SendRaw writes a raw line to every connected client (for malformed-input tests).
func (f *Fake) SendRaw(line string) {
	f.mu.Lock()
	var cs []*fconn
	for c := range f.conns {
		cs = append(cs, c)
	}
	f.mu.Unlock()
	for _, c := range cs {
		c.wmu.Lock()
		c.c.Write([]byte(line + "\n"))
		c.wmu.Unlock()
	}
}

// Restart closes all connections and re-listens on the same path.
func (f *Fake) Restart() {
	f.closeAll()
	f.listen()
}

// Stop closes the listener and all connections.
func (f *Fake) Stop() { f.closeAll() }
