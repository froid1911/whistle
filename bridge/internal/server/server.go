// Package server is the bridge's HTTP/WebSocket front end: it authorizes
// every request by Tailscale identity, streams the change log to the app
// (docs/reference/wire-protocol.md) and executes app commands.
package server

import (
	"context"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"encoding/json"
	"errors"
	"log/slog"
	"net"
	"net/http"
	"sync"
	"time"

	"github.com/froid1911/whistle/bridge/internal/herdr"
	"github.com/froid1911/whistle/bridge/internal/state"
	"github.com/froid1911/whistle/bridge/internal/store"
)

// StateSource is the live herdr state (implemented by *state.Watcher).
type StateSource interface {
	Current() state.State
	Online() bool
	Version() string
}

// LiveWatcher polls in-progress screen text (implemented by *chats.Live).
type LiveWatcher interface {
	Watch(paneID, agent string, status func() string, send func(text string)) (stop func())
}

type Config struct {
	Addr      string // listen address for Run
	MaxUpload int64  // bytes; 0 = 25 MiB
	UploadDir string
	// PingInterval is the WebSocket keepalive period; 0 = 15 s.
	PingInterval time.Duration
	Log          *slog.Logger
	// Identity is the bridge's P-256 key (signs welcome frames, pinned by
	// paired apps). nil = an ephemeral key (tests).
	Identity *ecdsa.PrivateKey
	// MachineName is shown in pairing QR codes.
	MachineName string
	// Now overrides the clock (tests).
	Now func() time.Time
	// RejectV1 refuses "Whistle v1" signatures (no server challenge); set it
	// once every paired app speaks v2 (auth.conf: accept_v1 false).
	RejectV1 bool
}

// Deps are the server's collaborators. Live, Git and Term may be nil:
// features depending on them then answer not_available.
type Deps struct {
	Store   *store.Store
	Watcher StateSource
	Live    LiveWatcher
	Herdr   herdr.Caller
	// Auth is the legacy (Tailscale allowlist) authorizer, accepted in
	// addition to paired devices; nil = paired devices only.
	Auth Authorizer
	Git  GitOps
	Term TermFactory
}

type Server struct {
	cfg  Config
	deps Deps
	log  *slog.Logger

	hmu   sync.Mutex
	hosts map[string]bool // allowed Host header values (bound ip:port)

	emu sync.Mutex // serializes ensureEpoch

	dev *devices

	// subscribe is Store.Subscribe; tests replace it to force slow-subscriber closes.
	subscribe func() (<-chan store.Entry, func())
}

func New(cfg Config, deps Deps) *Server {
	if cfg.MaxUpload <= 0 {
		cfg.MaxUpload = 25 << 20
	}
	if cfg.PingInterval <= 0 {
		cfg.PingInterval = 15 * time.Second
	}
	if cfg.Log == nil {
		cfg.Log = slog.Default()
	}
	if cfg.Identity == nil {
		k, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
		if err != nil {
			panic(err)
		}
		cfg.Identity = k
	}
	s := &Server{cfg: cfg, deps: deps, log: cfg.Log, subscribe: deps.Store.Subscribe, dev: newDevices()}
	if cfg.Now != nil {
		s.dev.tokens.Now, s.dev.nonces.Now, s.dev.challenges.Now = cfg.Now, cfg.Now, cfg.Now
	}
	s.addHost(cfg.Addr)
	return s
}

// Handler serves /v1/ws, /v1/upload, /v1/health, /v1/pair and
// /v1/challenge, all behind authorize.
func (s *Server) Handler() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("/v1/ws", s.serveWS)
	mux.HandleFunc("/v1/upload", s.serveUpload)
	mux.HandleFunc("/v1/health", s.serveHealth)
	// /v1/pair is answered by authorize itself (token-authorized); a signed
	// request to it lands here.
	mux.HandleFunc("/v1/pair", s.servePair)
	return s.authorize(mux)
}

// Run listens on cfg.Addr until ctx is done.
func (s *Server) Run(ctx context.Context) error {
	ln, err := net.Listen("tcp", s.cfg.Addr)
	if err != nil {
		return err
	}
	s.addHost(ln.Addr().String()) // e.g. when Addr used port 0
	// No server-wide ReadTimeout/WriteTimeout: they would also cut hijacked
	// WebSockets. Signed bodies get a per-request read deadline (spoolBody).
	hs := &http.Server{Handler: s.Handler(), ReadHeaderTimeout: 10 * time.Second,
		IdleTimeout: 2 * time.Minute, MaxHeaderBytes: 64 << 10}
	go func() {
		<-ctx.Done()
		sctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		hs.Shutdown(sctx)
		hs.Close() // hijacked WebSockets are not covered by Shutdown
	}()
	s.log.Info("listening", "addr", ln.Addr().String())
	if err := hs.Serve(ln); !errors.Is(err, http.ErrServerClosed) {
		return err
	}
	return nil
}

// serveHealthMinimal is /v1/health for unauthenticated callers: liveness only.
func (s *Server) serveHealthMinimal(w http.ResponseWriter) {
	writeJSON(w, http.StatusOK, map[string]any{"ok": true})
}

func (s *Server) serveHealth(w http.ResponseWriter, r *http.Request) {
	head, err := s.deps.Store.Head()
	if err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"ok": true, "herdr_online": s.deps.Watcher.Online(), "head_seq": head})
}

func writeJSON(w http.ResponseWriter, code int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(code)
	json.NewEncoder(w).Encode(v)
}
