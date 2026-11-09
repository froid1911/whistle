package devauth

import (
	"crypto/rand"
	"crypto/subtle"
	"encoding/base32"
	"errors"
	"io"
	"strings"
	"sync"
	"time"

	"github.com/skip2/go-qrcode"
)

const (
	// TokenTTL is how long a pairing token is valid.
	TokenTTL = 5 * time.Minute
	// MaxFailures failed redemptions per FailureWindow turn on rate limiting.
	MaxFailures   = 5
	FailureWindow = time.Minute
	maxLiveTokens = 8
)

var (
	ErrPairingInvalid = errors.New("pairing_invalid")
	ErrRateLimited    = errors.New("rate_limited")
)

var b32 = base32.StdEncoding.WithPadding(base32.NoPadding)

// Tokens holds one-time pairing tokens in memory only.
type Tokens struct {
	Now func() time.Time

	mu       sync.Mutex
	live     map[string]time.Time // token -> expiry
	failures *Window              // failed redemptions per source IP
}

func NewTokens() *Tokens {
	return &Tokens{Now: time.Now, live: map[string]time.Time{}, failures: NewWindow(FailureWindow)}
}

func (t *Tokens) prune(now time.Time) {
	for k, exp := range t.live {
		if !now.Before(exp) {
			delete(t.live, k)
		}
	}
}

// Issue creates a 128-bit token (26 base32 chars) valid for TokenTTL.
func (t *Tokens) Issue() (string, time.Time, error) {
	b := make([]byte, 16)
	if _, err := rand.Read(b); err != nil {
		return "", time.Time{}, err
	}
	tok := b32.EncodeToString(b)
	t.mu.Lock()
	defer t.mu.Unlock()
	now := t.Now()
	t.prune(now)
	for len(t.live) >= maxLiveTokens { // drop the oldest
		var oldest string
		for k, exp := range t.live {
			if oldest == "" || exp.Before(t.live[oldest]) {
				oldest = k
			}
		}
		delete(t.live, oldest)
	}
	exp := now.Add(TokenTTL)
	t.live[tok] = exp
	return tok, exp, nil
}

// Redeem burns a valid token and returns it (normalized). A matching token
// always succeeds. A miss counts against source (the client IP): once it
// has MaxFailures misses within FailureWindow its misses get
// ErrRateLimited instead of ErrPairingInvalid. Other sources are unaffected,
// so nobody can lock the owner out of pairing.
func (t *Tokens) Redeem(token, source string) (string, error) {
	token = NormalizeCode(token)
	t.mu.Lock()
	defer t.mu.Unlock()
	now := t.Now()
	t.prune(now)
	match := ""
	for k := range t.live { // compare against all: no early exit on match
		if subtle.ConstantTimeCompare([]byte(k), []byte(token)) == 1 {
			match = k
		}
	}
	if match == "" {
		if t.failures.Count(source, now) >= MaxFailures {
			return "", ErrRateLimited
		}
		t.failures.Add(source, now)
		return "", ErrPairingInvalid
	}
	delete(t.live, match)
	return match, nil
}

// Live reports whether token is issued, unused and unexpired.
func (t *Tokens) Live(token string) bool {
	t.mu.Lock()
	defer t.mu.Unlock()
	t.prune(t.Now())
	_, ok := t.live[NormalizeCode(token)]
	return ok
}

// Cancel burns token (unused codes the owner dismissed).
func (t *Tokens) Cancel(token string) {
	t.mu.Lock()
	defer t.mu.Unlock()
	delete(t.live, NormalizeCode(token))
}

// Window counts events per key within a sliding window (rate limits).
type Window struct {
	span time.Duration

	mu   sync.Mutex
	ev   map[string][]time.Time
	last time.Time
}

func NewWindow(span time.Duration) *Window { return &Window{span: span, ev: map[string][]time.Time{}} }

func (w *Window) trim(key string, now time.Time) []time.Time {
	ts := w.ev[key]
	i := 0
	for i < len(ts) && now.Sub(ts[i]) >= w.span {
		i++
	}
	ts = ts[i:]
	if len(ts) == 0 {
		delete(w.ev, key)
	} else {
		w.ev[key] = ts
	}
	return ts
}

func (w *Window) gc(now time.Time) {
	if now.Sub(w.last) < w.span {
		return
	}
	w.last = now
	for k := range w.ev {
		w.trim(k, now)
	}
}

// Count is the number of events for key within the window.
func (w *Window) Count(key string, now time.Time) int {
	w.mu.Lock()
	defer w.mu.Unlock()
	w.gc(now)
	return len(w.trim(key, now))
}

// Add records an event for key.
func (w *Window) Add(key string, now time.Time) {
	w.mu.Lock()
	defer w.mu.Unlock()
	w.gc(now)
	w.ev[key] = append(w.trim(key, now), now)
}

// Allow records an event for key unless max events already happened
// within the window.
func (w *Window) Allow(key string, max int, now time.Time) bool {
	w.mu.Lock()
	defer w.mu.Unlock()
	w.gc(now)
	ts := w.trim(key, now)
	if len(ts) >= max {
		return false
	}
	w.ev[key] = append(ts, now)
	return true
}

// ChallengeBytes is the size of a server challenge (v2 auth).
const ChallengeBytes = 16

// ChallengeTTL is how long a server challenge can be used.
const ChallengeTTL = 30 * time.Second

const maxChallenges = 4096

// ErrTooManyChallenges: the outstanding-challenge table is full.
var ErrTooManyChallenges = errors.New("too many outstanding challenges")

// Challenges are single-use server nonces bound to a device id (v2 auth):
// a signature over one can be used once, by that device, within ChallengeTTL.
type Challenges struct {
	Now func() time.Time

	mu   sync.Mutex
	live map[string]challenge
}

type challenge struct {
	device string
	exp    time.Time
}

func NewChallenges() *Challenges { return &Challenges{Now: time.Now, live: map[string]challenge{}} }

// Issue creates a challenge for device.
func (c *Challenges) Issue(device string) (string, time.Time, error) {
	b := make([]byte, ChallengeBytes)
	if _, err := rand.Read(b); err != nil {
		return "", time.Time{}, err
	}
	ch := b64u.EncodeToString(b)
	c.mu.Lock()
	defer c.mu.Unlock()
	now := c.Now()
	if len(c.live) >= maxChallenges {
		for k, v := range c.live {
			if !now.Before(v.exp) {
				delete(c.live, k)
			}
		}
		if len(c.live) >= maxChallenges {
			return "", time.Time{}, ErrTooManyChallenges
		}
	}
	exp := now.Add(ChallengeTTL)
	c.live[ch] = challenge{device: device, exp: exp}
	return ch, exp, nil
}

// Use consumes ch for device: false when unknown, used, expired or issued
// for another device. Any presented challenge is burned.
func (c *Challenges) Use(device, ch string) bool {
	c.mu.Lock()
	defer c.mu.Unlock()
	v, ok := c.live[ch]
	if !ok {
		return false
	}
	delete(c.live, ch)
	return c.Now().Before(v.exp) && subtle.ConstantTimeCompare([]byte(v.device), []byte(device)) == 1
}

// Nonces remembers (device, nonce) pairs for NonceWindowSeconds.
type Nonces struct {
	Now func() time.Time

	mu   sync.Mutex
	seen map[string]time.Time
	last time.Time
}

func NewNonces() *Nonces { return &Nonces{Now: time.Now, seen: map[string]time.Time{}} }

// Use records the nonce; false if it was already seen within the window.
func (n *Nonces) Use(device, nonce string) bool {
	n.mu.Lock()
	defer n.mu.Unlock()
	now := n.Now()
	win := NonceWindowSeconds * time.Second
	if now.Sub(n.last) > win/4 {
		for k, at := range n.seen {
			if now.Sub(at) > win {
				delete(n.seen, k)
			}
		}
		n.last = now
	}
	k := device + "\x00" + nonce
	if at, ok := n.seen[k]; ok && now.Sub(at) <= win {
		return false
	}
	n.seen[k] = now
	return true
}

// RenderQR writes text as a QR code with Unicode half blocks: light modules
// are printed (full/half blocks), dark ones are blank, so on the usual light-
// on-dark terminal the code has the normal polarity. Includes the quiet zone.
func RenderQR(w io.Writer, text string) error {
	q, err := qrcode.New(text, qrcode.Medium)
	if err != nil {
		return err
	}
	bm := q.Bitmap() // true = dark
	var b strings.Builder
	for y := 0; y < len(bm); y += 2 {
		for x := range bm[y] {
			top := !bm[y][x]
			bot := y+1 < len(bm) && !bm[y+1][x]
			switch {
			case top && bot:
				b.WriteRune('█')
			case top:
				b.WriteRune('▀')
			case bot:
				b.WriteRune('▄')
			default:
				b.WriteByte(' ')
			}
		}
		b.WriteByte('\n')
	}
	_, err = io.WriteString(w, b.String())
	return err
}
