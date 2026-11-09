// Command whistle-bridge connects a herdr session to the whistle Android app.
package main

import (
	"context"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"log/slog"
	"net"
	"os"
	"os/exec"
	"os/signal"
	"path/filepath"
	"strings"
	"syscall"
	"time"

	"github.com/froid1911/whistle/bridge/internal/chats"
	"github.com/froid1911/whistle/bridge/internal/devauth"
	"github.com/froid1911/whistle/bridge/internal/gitops"
	"github.com/froid1911/whistle/bridge/internal/herdr"
	"github.com/froid1911/whistle/bridge/internal/server"
	"github.com/froid1911/whistle/bridge/internal/state"
	"github.com/froid1911/whistle/bridge/internal/store"
	"github.com/froid1911/whistle/bridge/internal/term"
)

const (
	keepLog       = 50_000
	pruneEvery    = time.Hour
	livePoll      = 250 * time.Millisecond
	defaultListen = "tailscale:8787"
)

// Auth modes (spec: device pairing, "Migration / compatibility").
const (
	authDevices       = "devices"                 // paired devices only
	authDevicesLegacy = "devices+tailscale-users" // also the Tailscale allowlist
)

type options struct {
	addr, socket, session, db, herdrBin string
	auth, key, name                     string
	allow                               []string
	insecure                            bool
	acceptV1                            bool
}

// ctlPath is the control socket next to the database.
func ctlPath(db string) string { return strings.TrimSuffix(db, ".db") + ".ctl" }

func defaultHerdrBin() string {
	if p := "/home/linuxbrew/.linuxbrew/bin/herdr"; fileExists(p) {
		return p
	}
	if p, err := exec.LookPath("herdr"); err == nil {
		return p
	}
	if m, _ := filepath.Glob("/home/linuxbrew/.linuxbrew/Cellar/herdr/*/bin/herdr"); len(m) > 0 {
		return m[len(m)-1]
	}
	return "herdr"
}

func fileExists(p string) bool { _, err := os.Stat(p); return err == nil }

func parseFlags(args []string, home string) (options, error) {
	var o options
	var allow string
	fs := flag.NewFlagSet("whistle-bridge", flag.ContinueOnError)
	fs.StringVar(&o.addr, "addr", defaultListen, `listen address; host "tailscale" = this machine's Tailscale IPv4`)
	fs.StringVar(&o.socket, "socket", filepath.Join(home, ".config/herdr/herdr.sock"), "herdr socket path")
	fs.StringVar(&o.session, "session", "", "herdr session name (sets -socket to that session's socket)")
	fs.StringVar(&o.db, "db", "", "bridge database (default ~/.local/share/whistle-bridge/<session|default>.db)")
	fs.StringVar(&o.auth, "auth", authDevices, `"devices" (paired devices only) or "devices+tailscale-users" (also -allow)`)
	fs.StringVar(&allow, "allow", "", `comma-separated Tailscale logins allowed without pairing (only with -auth devices+tailscale-users)`)
	fs.StringVar(&o.key, "key", "", "bridge identity key (default bridge-key.pem next to -db)")
	fs.StringVar(&o.name, "name", "", "machine name shown when pairing (default: hostname)")
	fs.StringVar(&o.herdrBin, "herdr", "", "herdr binary (default: linuxbrew herdr or PATH)")
	fs.BoolVar(&o.acceptV1, "accept-v1", false, `DEPRECATED, insecure: also accept "Whistle v1" signatures (no server challenge, replayable); only for apps older than 0.5`)
	fs.BoolVar(&o.insecure, "insecure-allow-all", false, "skip Tailscale auth (tests only; loopback addresses only)")
	if err := fs.Parse(args); err != nil {
		return o, err
	}
	explicit := map[string]bool{}
	fs.Visit(func(f *flag.Flag) { explicit[f.Name] = true })
	if o.session != "" && !explicit["socket"] {
		o.socket = filepath.Join(home, ".config/herdr/sessions", o.session, "herdr.sock")
	}
	if o.db == "" {
		name := o.session
		if name == "" {
			name = "default"
		}
		o.db = filepath.Join(home, ".local/share/whistle-bridge", name+".db")
	}
	if o.key == "" {
		o.key = filepath.Join(filepath.Dir(o.db), "bridge-key.pem")
	}
	if o.name == "" {
		o.name, _ = os.Hostname()
	}
	if o.auth != authDevices && o.auth != authDevicesLegacy {
		return o, fmt.Errorf("-auth: want %q or %q, got %q", authDevices, authDevicesLegacy, o.auth)
	}
	if o.herdrBin == "" {
		o.herdrBin = defaultHerdrBin()
	}
	for _, a := range strings.Split(allow, ",") {
		if a = strings.TrimSpace(a); a != "" {
			o.allow = append(o.allow, a)
		}
	}
	return o, nil
}

func tailscaleIPv4() (string, error) {
	out, err := exec.Command("tailscale", "ip", "-4").Output()
	if err != nil {
		return "", fmt.Errorf("tailscale ip -4: %w", err)
	}
	ip := strings.TrimSpace(strings.SplitN(string(out), "\n", 2)[0])
	if net.ParseIP(ip) == nil {
		return "", fmt.Errorf("tailscale ip -4: unexpected output %q", out)
	}
	return ip, nil
}

func resolveAddr(addr string, tsIP func() (string, error)) (string, error) {
	host, port, err := net.SplitHostPort(addr)
	if err != nil {
		return "", err
	}
	if host == "tailscale" {
		if host, err = tsIP(); err != nil {
			return "", err
		}
	}
	return net.JoinHostPort(host, port), nil
}

// checkListen refuses wildcard binds, and non-loopback binds without auth.
func checkListen(addr string, insecure bool) error {
	host, _, err := net.SplitHostPort(addr)
	if err != nil {
		return err
	}
	ip := net.ParseIP(host)
	loopback := host == "localhost" || (ip != nil && ip.IsLoopback())
	if insecure && !loopback {
		return fmt.Errorf("-insecure-allow-all refuses non-loopback address %q", addr)
	}
	if host == "" || (ip != nil && ip.IsUnspecified()) {
		return fmt.Errorf("refusing to listen on all interfaces (%q); bind the Tailscale IP", addr)
	}
	return nil
}

func main() {
	log := slog.New(slog.NewTextHandler(os.Stderr, nil))
	if len(os.Args) > 1 && os.Args[1] == "plugin" {
		os.Exit(pluginMain(os.Args[2:], log))
	}
	if err := run(os.Args[1:], log); err != nil {
		log.Error("whistle-bridge exiting", "err", err)
		os.Exit(1)
	}
}

func run(args []string, log *slog.Logger) error {
	home, err := os.UserHomeDir()
	if err != nil {
		return err
	}
	o, err := parseFlags(args, home)
	if err != nil {
		return err
	}
	ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
	defer stop()
	return serve(ctx, o, home, log)
}

// serve runs the bridge until ctx ends or a component fails.
func serve(parent context.Context, o options, home string, log *slog.Logger) error {
	addr, err := resolveAddr(o.addr, tailscaleIPv4)
	if err != nil {
		return err
	}
	if err := checkListen(addr, o.insecure); err != nil {
		return err
	}
	var auth server.Authorizer // nil = paired devices only
	switch {
	case o.insecure:
		log.Warn("INSECURE: authorization disabled (loopback only)")
		auth = server.AllowAll{}
	case o.auth == authDevicesLegacy && len(o.allow) > 0:
		auth = server.TailscaleAuth{Allowed: o.allow}
	case o.auth == authDevicesLegacy:
		log.Warn("auth devices+tailscale-users without an allowlist: paired devices only")
	}
	identity, err := devauth.LoadOrCreateKey(o.key)
	if err != nil {
		return fmt.Errorf("bridge identity key: %w", err)
	}

	if err := os.MkdirAll(filepath.Dir(o.db), 0o700); err != nil {
		return err
	}
	st, err := store.Open(o.db)
	if err != nil {
		return fmt.Errorf("open db: %w", err)
	}
	defer st.Close()

	ctx, stop := context.WithCancel(parent)
	defer stop()

	hc := herdr.NewRedialer(o.socket)
	defer hc.Close()
	reader := chats.HerdrAdapter{C: hc}
	mgr := chats.NewManager(st, reader, home, log)
	defer mgr.Close()
	watcher := state.NewWatcher(o.socket, log)
	watcher.Decorate = func(s *state.State) { s.MarkTranscripts(mgr.HasTranscript) }
	seedWatcher(watcher, st, log)

	srv := server.New(server.Config{
		Addr:        addr,
		UploadDir:   filepath.Join(home, ".cache/whistle-bridge/uploads"),
		Log:         log,
		Identity:    identity,
		MachineName: o.name,
		RejectV1:    !o.acceptV1,
	}, server.Deps{
		Store: st, Watcher: watcher, Live: chats.NewLive(reader, livePoll),
		Herdr: hc, Auth: auth,
		Term: term.Factory{HerdrBin: o.herdrBin, Session: o.session},
		Git:  gitops.Git{},
	})
	log.Info("starting", "addr", addr, "socket", o.socket, "db", o.db, "herdr", o.herdrBin,
		"auth", o.auth, "allow", o.allow, "legacy", auth != nil, "accept_v1", o.acceptV1,
		"bridge_key", devauth.Fingerprint(devauth.SPKI(&identity.PublicKey)))

	protoErr := make(chan error, 1)
	go func() {
		err := watcher.Run(ctx, func(s state.State, ch []state.Change) { mgr.OnState(ctx, s, ch) })
		if errors.Is(err, state.ErrProtocolMismatch) {
			protoErr <- err
		}
	}()
	srvErr := make(chan error, 1)
	go func() { srvErr <- srv.Run(ctx) }()
	go pruneLoop(ctx, st, log)
	go func() {
		if err := srv.ServeControl(ctx, ctlPath(o.db)); err != nil {
			log.Error("control socket", "err", err) // pairing actions unavailable; serving goes on
		}
	}()

	var result error
	select {
	case <-ctx.Done():
		log.Info("shutting down")
	case result = <-protoErr:
	case result = <-srvErr:
		srvErr <- nil
	}
	stop()
	select { // let connections finish before the store closes
	case <-srvErr:
	case <-time.After(10 * time.Second):
	}
	return result
}

func pruneLoop(ctx context.Context, st *store.Store, log *slog.Logger) {
	t := time.NewTicker(pruneEvery)
	defer t.Stop()
	for {
		if err := st.Prune(keepLog); err != nil {
			log.Error("prune", "err", err)
		}
		select {
		case <-ctx.Done():
			return
		case <-t.C:
		}
	}
}

// seedWatcher restores the state persisted by the previous run (so the first
// diff emits removes for what vanished meanwhile) and persists every changed
// state from now on.
func seedWatcher(w *state.Watcher, st *store.Store, log *slog.Logger) {
	if b, ok := st.LoadState(); ok {
		var s state.State
		if err := json.Unmarshal(b, &s); err != nil {
			log.Warn("ignoring corrupt persisted state", "err", err)
		} else {
			w.Seed(s)
		}
	}
	w.Persist = func(s state.State) {
		b, err := json.Marshal(s)
		if err == nil {
			err = st.SaveState(b)
		}
		if err != nil {
			log.Error("persist state", "err", err)
		}
	}
}
