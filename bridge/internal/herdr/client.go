// Package herdr is a client for herdr's newline-delimited JSON unix socket API.
package herdr

import (
	"bufio"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"strconv"
	"sync"
)

// ErrClosed is returned by calls on a closed or broken connection.
var ErrClosed = errors.New("herdr: connection closed")

const maxLine = 16 << 20

type response struct {
	ID     string          `json:"id"`
	Result json.RawMessage `json:"result"`
	Error  *APIError       `json:"error"`
}

type Client struct {
	conn net.Conn

	wmu sync.Mutex

	mu      sync.Mutex
	nextID  uint64
	pending map[string]chan response
	closed  bool
	termErr error // why the read loop ended; set before done closes
	done    chan struct{}
}

// Dial connects to the herdr socket at socketPath.
func Dial(ctx context.Context, socketPath string) (*Client, error) {
	var d net.Dialer
	conn, err := d.DialContext(ctx, "unix", socketPath)
	if err != nil {
		return nil, err
	}
	c := &Client{conn: conn, pending: map[string]chan response{}, done: make(chan struct{})}
	go c.readLoop()
	return c, nil
}

// readLoop dispatches responses to pending calls. A malformed line or a
// response without an id cannot be matched to a call, so the connection is
// treated as broken: it is closed and all pending and later calls fail with an
// error wrapping ErrClosed that describes the cause.
func (c *Client) readLoop() {
	defer close(c.done)
	sc := bufio.NewScanner(c.conn)
	sc.Buffer(make([]byte, 0, 64<<10), maxLine)
	var cause error
	for sc.Scan() {
		var r response
		if err := json.Unmarshal(sc.Bytes(), &r); err != nil {
			cause = fmt.Errorf("malformed line from herdr: %v", err)
			break
		}
		if r.ID == "" {
			cause = fmt.Errorf("response without id from herdr: %.200s", sc.Bytes())
			break
		}
		c.mu.Lock()
		ch := c.pending[r.ID]
		delete(c.pending, r.ID)
		c.mu.Unlock()
		if ch != nil {
			ch <- r // buffered
		}
	}
	if cause == nil {
		cause = sc.Err() // nil on clean EOF
	}
	c.conn.Close()
	c.mu.Lock()
	c.closed = true
	if cause != nil {
		c.termErr = fmt.Errorf("%w: %v", ErrClosed, cause)
	} else {
		c.termErr = ErrClosed
	}
	c.mu.Unlock()
}

func (c *Client) closedErr() error {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.termErr != nil {
		return c.termErr
	}
	return ErrClosed
}

// Call sends a request and decodes the result into out (may be nil).
func (c *Client) Call(ctx context.Context, method string, params any, out any) error {
	if params == nil {
		params = struct{}{}
	}
	c.mu.Lock()
	if c.closed {
		err := c.termErr
		c.mu.Unlock()
		if err == nil {
			err = ErrClosed
		}
		return err
	}
	c.nextID++
	id := "r" + strconv.FormatUint(c.nextID, 10)
	ch := make(chan response, 1)
	c.pending[id] = ch
	c.mu.Unlock()

	drop := func() {
		c.mu.Lock()
		delete(c.pending, id)
		c.mu.Unlock()
	}
	line, err := json.Marshal(map[string]any{"id": id, "method": method, "params": params})
	if err != nil {
		drop()
		return err
	}
	c.wmu.Lock()
	_, err = c.conn.Write(append(line, '\n'))
	c.wmu.Unlock()
	if err != nil {
		drop()
		return fmt.Errorf("%w: %v", ErrClosed, err)
	}
	select {
	case r := <-ch:
		if r.Error != nil {
			return r.Error
		}
		if out != nil && len(r.Result) > 0 {
			return json.Unmarshal(r.Result, out)
		}
		return nil
	case <-c.done:
		select { // a response may have landed just before close
		case r := <-ch:
			if r.Error != nil {
				return r.Error
			}
			if out != nil && len(r.Result) > 0 {
				return json.Unmarshal(r.Result, out)
			}
			return nil
		default:
		}
		return c.closedErr()
	case <-ctx.Done():
		drop()
		return ctx.Err()
	}
}

func (c *Client) Close() error {
	err := c.conn.Close()
	<-c.done
	return err
}

// Snapshot calls session.snapshot and unwraps the .snapshot field.
func (c *Client) Snapshot(ctx context.Context) (Snapshot, error) { return GetSnapshot(ctx, c) }

// Subscribe opens a dedicated connection, subscribes, and streams events.
// The event channel closes when the connection ends (or ctx is done). Stream
// errors such as events_lost (*APIError) are delivered reliably on the error
// channel (the reader blocks until the consumer takes them, or ctx is done),
// so consumers must drain errs. The connection stays open after events_lost.
// If the stream ends abnormally (read error, oversized line) that error is
// sent on errs before both channels close.
func Subscribe(ctx context.Context, socketPath string, subs []Subscription) (<-chan Event, <-chan error, error) {
	var d net.Dialer
	conn, err := d.DialContext(ctx, "unix", socketPath)
	if err != nil {
		return nil, nil, err
	}
	req, _ := json.Marshal(map[string]any{
		"id": "sub", "method": "events.subscribe",
		"params": map[string]any{"subscriptions": subs},
	})
	if _, err := conn.Write(append(req, '\n')); err != nil {
		conn.Close()
		return nil, nil, err
	}
	sc := bufio.NewScanner(conn)
	sc.Buffer(make([]byte, 0, 64<<10), maxLine)
	// Unblock the ack read if ctx ends.
	stop := context.AfterFunc(ctx, func() { conn.Close() })
	if !sc.Scan() {
		stop()
		conn.Close()
		if err := sc.Err(); err != nil {
			return nil, nil, err
		}
		return nil, nil, ErrClosed
	}
	var ack response
	if err := json.Unmarshal(sc.Bytes(), &ack); err != nil {
		stop()
		conn.Close()
		return nil, nil, fmt.Errorf("herdr: bad subscribe ack: %w", err)
	}
	if ack.Error != nil {
		stop()
		conn.Close()
		return nil, nil, ack.Error
	}
	events := make(chan Event, 256)
	errs := make(chan error, 16)
	go func() {
		defer close(events)
		defer close(errs)
		defer conn.Close()
		defer stop()
		for sc.Scan() {
			var m struct {
				Event string          `json:"event"`
				Data  json.RawMessage `json:"data"`
				Error *APIError       `json:"error"`
			}
			if json.Unmarshal(sc.Bytes(), &m) != nil {
				continue
			}
			switch {
			case m.Error != nil:
				select {
				case errs <- m.Error:
				case <-ctx.Done():
					return
				}
			case m.Event != "":
				select {
				case events <- Event{Event: m.Event, Data: m.Data}:
				case <-ctx.Done():
					return
				}
			}
		}
		if err := sc.Err(); err != nil && ctx.Err() == nil {
			select {
			case errs <- fmt.Errorf("herdr: event stream: %w", err):
			case <-ctx.Done():
			}
		}
	}()
	return events, errs, nil
}
