package server

import (
	"context"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"io"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"
	"unicode"

	"github.com/coder/websocket"

	"github.com/froid1911/whistle/bridge/internal/devauth"
	"github.com/froid1911/whistle/bridge/internal/store"
)

// Device auth error codes (HTTP 401 unless noted).
const (
	codeDeviceUnknown  = "device_unknown"
	codeDeviceRevoked  = "device_revoked"
	codeAuthStale      = "auth_stale"
	codeAuthBadSig     = "auth_bad_sig"
	codeAuthV2Required = "auth_v2_required" // v1 header while accept_v1 is off
	codePairingInvalid = "pairing_invalid"  // 403
	codeKeyInUse       = "key_in_use"       // 409
	codeRateLimited    = "rate_limited"     // 429
	codeBusy           = "busy"             // 429
)

// closeRevoked is the WebSocket close code sent when a device is unpaired.
const closeRevoked websocket.StatusCode = 4001

const (
	touchEvery = time.Minute
	// Signed request bodies are spooled to disk and hashed before the handler
	// sees them; at most this many at once per device and in total.
	maxSpoolPerDevice = 2
	maxSpoolTotal     = 6
	// bodyReadTimeout bounds reading one request body (uploads over mobile).
	bodyReadTimeout = 5 * time.Minute
	// GET /v1/challenge per source IP and minute.
	challengesPerMinute = 60
	// How long a finished pairing is remembered for the pair popup.
	pairedMemory = 10 * time.Minute
)

type authKey struct{}

// authInfo is who a request was authorized as.
type authInfo struct {
	device string // paired device id ("" for legacy)
	nonce  string // the request's nonce (signs the welcome)
	login  string // legacy Tailscale login
}

func withAuth(ctx context.Context, a authInfo) context.Context {
	return context.WithValue(ctx, authKey{}, a)
}

func authFrom(ctx context.Context) (authInfo, bool) {
	a, ok := ctx.Value(authKey{}).(authInfo)
	return a, ok
}

func writeErr(w http.ResponseWriter, status int, code, msg string) {
	writeJSON(w, status, map[string]any{"error": map[string]string{"code": code, "message": msg}})
}

type pairedEntry struct {
	dev store.Device
	at  time.Time
}

// devices is the server's device-auth state.
type devices struct {
	tokens     *devauth.Tokens
	nonces     *devauth.Nonces
	challenges *devauth.Challenges
	chalRate   *devauth.Window // GET /v1/challenge per source IP

	mu         sync.Mutex
	conns      map[string]map[*conn]struct{} // device id -> open WebSockets
	revoked    map[string]bool               // revoked during this run (registerConn race)
	touched    map[string]time.Time
	paired     map[string]pairedEntry // pairing token -> device paired with it
	spooling   map[string]int         // device id -> bodies being spooled
	spoolTotal int
}

func newDevices() *devices {
	return &devices{
		tokens: devauth.NewTokens(), nonces: devauth.NewNonces(),
		challenges: devauth.NewChallenges(), chalRate: devauth.NewWindow(time.Minute),
		conns: map[string]map[*conn]struct{}{}, revoked: map[string]bool{},
		touched: map[string]time.Time{}, paired: map[string]pairedEntry{}, spooling: map[string]int{},
	}
}

func (s *Server) now() time.Time {
	if s.cfg.Now != nil {
		return s.cfg.Now()
	}
	return time.Now()
}

// remoteIP is the host part of r.RemoteAddr (rate-limit key).
func remoteIP(r *http.Request) string {
	if h, _, err := net.SplitHostPort(r.RemoteAddr); err == nil {
		return h
	}
	return r.RemoteAddr
}

// serveChallenge: GET /v1/challenge?device=<id> → a fresh single-use server
// nonce for v2 auth, valid 30 s, usable only by that device. Unauthenticated
// (behind the Origin/Host guards), rate-limited per source IP.
func (s *Server) serveChallenge(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		http.Error(w, "GET only", http.StatusMethodNotAllowed)
		return
	}
	dev := r.URL.Query().Get("device")
	if dev == "" || len(dev) > 64 {
		writeErr(w, http.StatusBadRequest, "bad_request", "device required")
		return
	}
	if !s.dev.chalRate.Allow(remoteIP(r), challengesPerMinute, s.now()) {
		writeErr(w, http.StatusTooManyRequests, codeRateLimited, "too many challenges; slow down")
		return
	}
	ch, _, err := s.dev.challenges.Issue(dev)
	if err != nil {
		writeErr(w, http.StatusServiceUnavailable, codeBusy, err.Error())
		return
	}
	w.Header().Set("Cache-Control", "no-store")
	writeJSON(w, http.StatusOK, map[string]any{"challenge": ch, "expires_in": int(devauth.ChallengeTTL / time.Second)})
}

// verifyDevice checks a Whistle signature. On failure code != "". On
// success a request body has been spooled to a temp file, verified against
// the signed hash, and set as r.Body; done removes it (always non-nil).
func (s *Server) verifyDevice(w http.ResponseWriter, r *http.Request) (info authInfo, status int, code string, done func()) {
	done = func() {}
	h, err := devauth.ParseHeader(r.Header.Get("Authorization"))
	if err != nil {
		return info, http.StatusUnauthorized, codeAuthBadSig, done
	}
	info = authInfo{device: h.Device, nonce: h.Nonce}
	if h.Version == 1 && s.cfg.RejectV1 {
		return info, http.StatusUnauthorized, codeAuthV2Required, done
	}
	d, err := s.deps.Store.Device(h.Device)
	if errors.Is(err, store.ErrNoDevice) {
		return info, http.StatusUnauthorized, codeDeviceUnknown, done
	}
	if err != nil {
		return info, http.StatusInternalServerError, "internal", done
	}
	if d.RevokedAt != 0 {
		return info, http.StatusUnauthorized, codeDeviceRevoked, done
	}
	now := s.now()
	if skew := now.Unix() - h.TS; skew > devauth.MaxSkewSeconds || skew < -devauth.MaxSkewSeconds {
		return info, http.StatusUnauthorized, codeAuthStale, done
	}
	pub, err := devauth.ParsePublicKey(d.PublicKey)
	if err != nil {
		return info, http.StatusInternalServerError, "internal", done
	}
	target := r.RequestURI // the request target exactly as sent
	if target == "" {
		target = r.URL.RequestURI()
	}
	hasBody := r.Body != nil && r.Body != http.NoBody && r.ContentLength != 0

	if h.Version == 2 {
		// The signature covers the declared body hash: verify it before
		// reading a byte of the body, then check the body against it.
		msg := devauth.CanonicalV2(r.Method, target, r.Host, h.TS, h.Nonce, h.Body, h.Challenge, h.Device)
		if !devauth.Verify(pub, msg, h.Sig) {
			return info, http.StatusUnauthorized, codeAuthBadSig, done
		}
		if !s.dev.challenges.Use(h.Device, h.Challenge) {
			return info, http.StatusUnauthorized, codeAuthStale, done
		}
		if !s.dev.nonces.Use(h.Device, h.Nonce) {
			return info, http.StatusUnauthorized, codeAuthStale, done
		}
		if hasBody {
			sum, st, c, cleanup := s.spoolBody(w, r, h.Device)
			if c != "" {
				return info, st, c, done
			}
			if subtle.ConstantTimeCompare([]byte(sum), []byte(h.Body)) != 1 {
				cleanup()
				return info, http.StatusUnauthorized, codeAuthBadSig, done
			}
			done = cleanup
		} else if h.Body != devauth.BodyHash(nil) {
			return info, http.StatusUnauthorized, codeAuthBadSig, done
		}
		s.touch(h.Device, now)
		return info, 0, "", done
	}

	// v1: the body hash is not in the header, so the body (bounded, spooled
	// to disk) must be read before the signature can be checked.
	sum := devauth.BodyHash(nil)
	if hasBody {
		var st int
		var c string
		var cleanup func()
		if sum, st, c, cleanup = s.spoolBody(w, r, h.Device); c != "" {
			return info, st, c, done
		}
		done = cleanup
	}
	msg := devauth.CanonicalHashed(r.Method, target, r.Host, h.TS, h.Nonce, sum)
	if !devauth.Verify(pub, msg, h.Sig) {
		done()
		return info, http.StatusUnauthorized, codeAuthBadSig, func() {}
	}
	if !s.dev.nonces.Use(h.Device, h.Nonce) {
		done()
		return info, http.StatusUnauthorized, codeAuthStale, func() {}
	}
	s.touch(h.Device, now)
	return info, 0, "", done
}

// spoolBody copies r.Body (at most MaxUpload + multipart overhead) to a
// private temp file while hashing it, and makes the file r.Body. Few bodies
// are spooled at once per device. cleanup removes the file.
func (s *Server) spoolBody(w http.ResponseWriter, r *http.Request, device string) (sum string, status int, code string, cleanup func()) {
	cleanup = func() {}
	s.dev.mu.Lock()
	if s.dev.spooling[device] >= maxSpoolPerDevice || s.dev.spoolTotal >= maxSpoolTotal {
		s.dev.mu.Unlock()
		return "", http.StatusTooManyRequests, codeBusy, cleanup
	}
	s.dev.spooling[device]++
	s.dev.spoolTotal++
	s.dev.mu.Unlock()
	defer func() {
		s.dev.mu.Lock()
		if s.dev.spooling[device]--; s.dev.spooling[device] <= 0 {
			delete(s.dev.spooling, device)
		}
		s.dev.spoolTotal--
		s.dev.mu.Unlock()
	}()

	// A deadline for this body only: a server-wide ReadTimeout would also
	// cut hijacked WebSockets.
	rc := http.NewResponseController(w)
	_ = rc.SetReadDeadline(time.Now().Add(bodyReadTimeout))
	defer rc.SetReadDeadline(time.Time{})

	dir := filepath.Join(s.cfg.UploadDir, ".incoming")
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return "", http.StatusInternalServerError, "internal", cleanup
	}
	f, err := os.CreateTemp(dir, "body-*")
	if err != nil {
		return "", http.StatusInternalServerError, "internal", cleanup
	}
	name := f.Name()
	cleanup = func() { f.Close(); os.Remove(name) }
	hs := sha256.New()
	_, err = io.Copy(io.MultiWriter(f, hs), http.MaxBytesReader(w, r.Body, s.cfg.MaxUpload+64<<10))
	if err == nil {
		_, err = f.Seek(0, io.SeekStart)
	}
	if err != nil {
		cleanup()
		var mbe *http.MaxBytesError
		if errors.As(err, &mbe) {
			return "", http.StatusRequestEntityTooLarge, "too_large", func() {}
		}
		return "", http.StatusBadRequest, "bad_request", func() {}
	}
	r.Body = f
	return hex.EncodeToString(hs.Sum(nil)), 0, "", cleanup
}

func (s *Server) touch(id string, now time.Time) {
	s.dev.mu.Lock()
	last := s.dev.touched[id]
	due := now.Sub(last) >= touchEvery
	if due {
		s.dev.touched[id] = now
	}
	s.dev.mu.Unlock()
	if due {
		if err := s.deps.Store.TouchDevice(id, now); err != nil {
			s.log.Warn("touch device", "device", id, "err", err)
		}
	}
}

// registerConn tracks a device's WebSocket so unpairing can close it. It
// fails for a device revoked after its request was verified: the check runs
// under the lock RevokeDevice takes to collect connections, so a connection
// is either refused here or closed by RevokeDevice.
func (s *Server) registerConn(c *conn) bool {
	if c.device == "" {
		return true
	}
	s.dev.mu.Lock()
	defer s.dev.mu.Unlock()
	if s.dev.revoked[c.device] {
		return false
	}
	m := s.dev.conns[c.device]
	if m == nil {
		m = map[*conn]struct{}{}
		s.dev.conns[c.device] = m
	}
	m[c] = struct{}{}
	return true
}

func (s *Server) unregisterConn(c *conn) {
	if c.device == "" {
		return
	}
	s.dev.mu.Lock()
	defer s.dev.mu.Unlock()
	delete(s.dev.conns[c.device], c)
	if len(s.dev.conns[c.device]) == 0 {
		delete(s.dev.conns, c.device)
	}
}

// connections is the number of open WebSockets per device.
func (s *Server) connections() map[string]int {
	s.dev.mu.Lock()
	defer s.dev.mu.Unlock()
	m := map[string]int{}
	for id, cs := range s.dev.conns {
		m[id] = len(cs)
	}
	return m
}

// RevokeDevice unpairs a device and closes its open WebSockets at once.
func (s *Server) RevokeDevice(id string) error {
	if err := s.deps.Store.RevokeDevice(id, s.now()); err != nil {
		return err
	}
	s.dev.mu.Lock()
	s.dev.revoked[id] = true
	var cs []*conn
	for c := range s.dev.conns[id] {
		cs = append(cs, c)
	}
	s.dev.mu.Unlock()
	for _, c := range cs {
		go c.ws.Close(closeRevoked, codeDeviceRevoked)
	}
	s.log.Info("device revoked", "device", id, "closed_conns", len(cs))
	return nil
}

// PairingToken is what the pair popup shows.
type PairingToken struct {
	Token       string    `json:"token"`
	Code        string    `json:"code"`
	URI         string    `json:"uri"`
	Addr        string    `json:"addr"`
	Name        string    `json:"name"`
	Fingerprint string    `json:"fingerprint"`
	Expires     time.Time `json:"expires"`
}

// IssuePairingToken creates a one-time pairing token (memory only).
func (s *Server) IssuePairingToken() (PairingToken, error) {
	tok, exp, err := s.dev.tokens.Issue()
	if err != nil {
		return PairingToken{}, err
	}
	fp := devauth.Fingerprint(devauth.SPKI(&s.cfg.Identity.PublicKey))
	s.log.Info("pairing token issued", "expires", exp)
	return PairingToken{
		Token: tok, Code: devauth.GroupCode(tok), Addr: s.cfg.Addr, Name: s.cfg.MachineName,
		Fingerprint: fp, Expires: exp, URI: devauth.PairURI(s.cfg.Addr, tok, fp),
	}, nil
}

// Pairing states reported to the pair popup.
const (
	PairPending = "pending" // token live, waiting for the phone
	PairPaired  = "paired"  // a device paired with this token
	PairGone    = "gone"    // expired, cancelled, or being redeemed right now
)

// PairingStatus reports what became of a pairing token.
func (s *Server) PairingStatus(token string) (string, *store.Device) {
	token = devauth.NormalizeCode(token)
	s.dev.mu.Lock()
	now := s.now()
	for k, e := range s.dev.paired {
		if now.Sub(e.at) > pairedMemory {
			delete(s.dev.paired, k)
		}
	}
	e, ok := s.dev.paired[token]
	s.dev.mu.Unlock()
	if ok {
		d := e.dev
		d.PublicKey = ""
		return PairPaired, &d
	}
	if s.dev.tokens.Live(token) {
		return PairPending, nil
	}
	return PairGone, nil
}

// CancelPairing burns an unused token (the popup was dismissed).
func (s *Server) CancelPairing(token string) {
	s.dev.tokens.Cancel(token)
	s.log.Info("pairing token cancelled")
}

type pairReq struct {
	Token      string `json:"token"`
	DeviceName string `json:"device_name"`
	PublicKey  string `json:"public_key"`
}

// cleanName drops control and format characters (incl. bidi overrides).
func cleanName(n string) string {
	n = strings.Map(func(r rune) rune {
		if unicode.IsControl(r) || unicode.Is(unicode.Cf, r) {
			return -1
		}
		return r
	}, strings.TrimSpace(n))
	n = strings.TrimSpace(n)
	if r := []rune(n); len(r) > 64 {
		n = string(r[:64])
	}
	if n == "" {
		n = "phone"
	}
	return n
}

// servePair: POST /v1/pair {token, device_name, public_key}.
func (s *Server) servePair(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "POST only", http.StatusMethodNotAllowed)
		return
	}
	var req pairReq
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 16<<10)).Decode(&req); err != nil {
		writeErr(w, http.StatusBadRequest, "bad_request", "invalid JSON")
		return
	}
	if _, err := devauth.ParsePublicKey(req.PublicKey); err != nil {
		writeErr(w, http.StatusBadRequest, "bad_request", err.Error())
		return
	}
	if used, err := s.deps.Store.KeyInUse(req.PublicKey); err != nil || used {
		if err != nil {
			writeErr(w, http.StatusInternalServerError, "internal", err.Error())
			return
		}
		writeErr(w, http.StatusConflict, codeKeyInUse, "this key is already paired; generate a new one")
		return
	}
	tok, err := s.dev.tokens.Redeem(req.Token, remoteIP(r))
	switch {
	case errors.Is(err, devauth.ErrRateLimited):
		s.log.Warn("pairing rate limited", "remote", r.RemoteAddr)
		writeErr(w, http.StatusTooManyRequests, codeRateLimited, "too many failed pairing attempts; wait a minute")
		return
	case err != nil:
		s.log.Warn("pairing refused", "remote", r.RemoteAddr)
		writeErr(w, http.StatusForbidden, codePairingInvalid, "pairing code is wrong, expired or already used")
		return
	}
	d, err := s.deps.Store.AddDevice(cleanName(req.DeviceName), req.PublicKey, s.now())
	if errors.Is(err, store.ErrKeyInUse) {
		writeErr(w, http.StatusConflict, codeKeyInUse, "this key is already paired; generate a new one")
		return
	}
	if err != nil {
		writeErr(w, http.StatusInternalServerError, "internal", err.Error())
		return
	}
	s.dev.mu.Lock()
	s.dev.paired[tok] = pairedEntry{dev: d, at: s.now()}
	s.dev.mu.Unlock()
	s.log.Info("device paired", "device", d.ID, "name", d.Name, "remote", r.RemoteAddr)
	writeJSON(w, http.StatusOK, map[string]string{
		"device_id":         d.ID,
		"bridge_public_key": base64.StdEncoding.EncodeToString(devauth.SPKI(&s.cfg.Identity.PublicKey)),
		"server_id":         s.deps.Store.ServerID(),
	})
}

// welcomeSig signs the welcome for a device-authenticated connection.
func (s *Server) welcomeSig(nonce string) string {
	if nonce == "" {
		return ""
	}
	sig, err := devauth.Sign(s.cfg.Identity, devauth.WelcomeMessage(s.deps.Store.ServerID(), nonce))
	if err != nil {
		s.log.Error("sign welcome", "err", err)
		return ""
	}
	return sig
}
