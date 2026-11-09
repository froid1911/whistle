package herdr

import (
	"context"
)

// Caller is the request side of a herdr connection.
type Caller interface {
	Call(ctx context.Context, method string, params any, out any) error
}

// Done is closed when the connection's read loop has ended.
func (c *Client) Done() <-chan struct{} { return c.done }

// Redialer is a Caller that dials a fresh herdr connection for every call.
// herdr (verified 0.9.5) serves exactly one request per non-subscription
// connection and then closes it, so a connection is never reused. A call is
// never retried: a request that may have reached herdr is not sent twice.
type Redialer struct {
	socket string
}

func NewRedialer(socketPath string) *Redialer { return &Redialer{socket: socketPath} }

func (r *Redialer) Call(ctx context.Context, method string, params any, out any) error {
	c, err := Dial(ctx, r.socket)
	if err != nil {
		return err
	}
	defer c.Close()
	return c.Call(ctx, method, params, out)
}

// Close is a no-op kept so callers can treat Redialer like a connection.
func (r *Redialer) Close() error { return nil }

// GetSnapshot calls session.snapshot via c and unwraps the .snapshot field.
func GetSnapshot(ctx context.Context, c Caller) (Snapshot, error) {
	var out struct {
		Snapshot Snapshot `json:"snapshot"`
	}
	err := c.Call(ctx, "session.snapshot", map[string]any{}, &out)
	return out.Snapshot, err
}
