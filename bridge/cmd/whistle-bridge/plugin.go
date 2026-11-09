// Plugin mode: `whistle-bridge plugin [run|status|stop|pair|devices|unpair|
// open|popup]`, launched by herdr as the whistle.bridge plugin (see
// bridge/plugin/herdr-plugin.toml).
//
// herdr runs [[startup]] commands for every enabled plugin in every session's
// server, as a child of that server, with HERDR_SESSION / HERDR_SOCKET_PATH /
// HERDR_PLUGIN_CONFIG_DIR / HERDR_PLUGIN_STATE_DIR set. Startup hooks are not
// supervised (no restart on crash) and are not stopped with the server, so:
//   - the session picks the listen address from sessions.conf in the plugin
//     config dir; sessions without an entry exit 0 quietly;
//   - a per-session lock file keeps one bridge per session; a new `run`
//     takes over from the holder (SIGTERM, wait), so startup after a server
//     restart/handoff and the "restart" action both leave exactly one bridge;
//   - a supervisor loop restarts the bridge after errors and panics;
//   - the bridge exits by itself once the herdr server has been gone a while.
package main

import (
	"bufio"
	"context"
	"errors"
	"flag"
	"fmt"
	"io"
	"log/slog"
	"net"
	"net/http"
	"os"
	"os/signal"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"syscall"
	"time"

	"github.com/froid1911/whistle/bridge/internal/devauth"
	"github.com/froid1911/whistle/bridge/internal/popup"
	"github.com/froid1911/whistle/bridge/internal/server"
)

const (
	pluginID        = "whistle.bridge"
	sessionsConf    = "sessions.conf"
	defaultSession  = "default"
	takeoverTimeout = 20 * time.Second
	serverGrace     = 90 * time.Second
	serverPoll      = 15 * time.Second
	logMaxBytes     = 10 << 20
)

// builtinSessions is used when sessions.conf is missing, and written there as
// the starting point.
var builtinSessions = map[string]string{
	defaultSession: "tailscale:8787",
	"whistle-dev":  "tailscale:8788",
}

const sessionsConfHeader = `# whistle.bridge: which herdr session gets a bridge, and where it listens.
# One "<session> <addr>" per line; "default" is the unnamed default session.
# addr "off" disables a session. Sessions not listed get no bridge.
# Host "tailscale" = this machine's Tailscale IPv4.
`

// pluginEnv is what herdr tells a plugin command about its session.
type pluginEnv struct {
	session   string // "" = default session
	socket    string
	configDir string
	stateDir  string
}

// name is the session's file/config name ("default" for the default session).
func (e pluginEnv) name() string {
	if e.session == "" {
		return defaultSession
	}
	return e.session
}

// pluginEnvFrom reads herdr's plugin environment. The session comes from
// HERDR_SESSION, else from the socket path, else it is the default session.
// sessionFlag (from -session, for manual runs) overrides all of it.
func pluginEnvFrom(getenv func(string) string, home, sessionFlag string) pluginEnv {
	e := pluginEnv{
		session:   getenv("HERDR_SESSION"),
		socket:    getenv("HERDR_SOCKET_PATH"),
		configDir: getenv("HERDR_PLUGIN_CONFIG_DIR"),
		stateDir:  getenv("HERDR_PLUGIN_STATE_DIR"),
	}
	if e.session == "" && e.socket != "" {
		e.session = sessionFromSocket(e.socket, home)
	}
	if sessionFlag != "" {
		e.session, e.socket = sessionFlag, ""
		if sessionFlag == defaultSession {
			e.session = ""
		}
	}
	if e.session == defaultSession {
		e.session = ""
	}
	if e.socket == "" {
		e.socket = filepath.Join(home, ".config/herdr/herdr.sock")
		if e.session != "" {
			e.socket = filepath.Join(home, ".config/herdr/sessions", e.session, "herdr.sock")
		}
	}
	if e.configDir == "" {
		e.configDir = filepath.Join(home, ".config/herdr/plugins/config", pluginID)
	}
	if e.stateDir == "" {
		e.stateDir = filepath.Join(home, ".local/state/herdr/plugins", pluginID)
	}
	return e
}

// sessionFromSocket maps ~/.config/herdr/sessions/<name>/herdr.sock to name;
// anything else is the default session.
func sessionFromSocket(socket, home string) string {
	dir := filepath.Join(home, ".config/herdr/sessions")
	rel, err := filepath.Rel(dir, filepath.Dir(socket))
	if err != nil || rel == "." || strings.HasPrefix(rel, "..") || strings.Contains(rel, string(filepath.Separator)) {
		return ""
	}
	return rel
}

// parseSessionsConf reads "<session> <addr>" lines.
func parseSessionsConf(r io.Reader) (map[string]string, error) {
	m := map[string]string{}
	sc := bufio.NewScanner(r)
	for n := 1; sc.Scan(); n++ {
		line := strings.TrimSpace(sc.Text())
		if i := strings.IndexByte(line, '#'); i >= 0 {
			line = strings.TrimSpace(line[:i])
		}
		if line == "" {
			continue
		}
		f := strings.Fields(line)
		if len(f) != 2 {
			return nil, fmt.Errorf("%s line %d: want \"<session> <addr>\", got %q", sessionsConf, n, line)
		}
		if f[1] != "off" {
			if _, _, err := net.SplitHostPort(f[1]); err != nil {
				return nil, fmt.Errorf("%s line %d: %w", sessionsConf, n, err)
			}
		}
		m[f[0]] = f[1]
	}
	return m, sc.Err()
}

// loadSessionsConf reads dir/sessions.conf, creating it from builtinSessions
// when missing.
func loadSessionsConf(dir string) (map[string]string, error) {
	p := filepath.Join(dir, sessionsConf)
	f, err := os.Open(p)
	if errors.Is(err, os.ErrNotExist) {
		var b strings.Builder
		b.WriteString(sessionsConfHeader)
		for _, s := range []string{defaultSession, "whistle-dev"} {
			fmt.Fprintf(&b, "%-12s %s\n", s, builtinSessions[s])
		}
		if os.MkdirAll(dir, 0o700) == nil {
			_ = os.WriteFile(p, []byte(b.String()), 0o600)
		}
		m := map[string]string{}
		for k, v := range builtinSessions {
			m[k] = v
		}
		return m, nil
	}
	if err != nil {
		return nil, err
	}
	defer f.Close()
	return parseSessionsConf(f)
}

// ---- auth.conf ----

const authConf = "auth.conf"

const authConfDefault = `# whistle.bridge: who may connect (all sessions).
#   auth devices                   paired devices only (default)
#   auth devices+tailscale-users   legacy: also the Tailscale logins listed with
#                                  "allow" (one or more lines, comma-separated)
#   accept_v1 true|false           DEPRECATED, INSECURE: also accept the
#                                  older "Whistle v1" request signatures (no
#                                  server challenge, replayable within 60 s).
#                                  Default false; only for apps older than
#                                  Whistle 0.5.
# Pair a phone: "Whistle: pair a phone" in herdr's command list.
auth devices
`

// authConfig is auth.conf: the auth mode and the legacy allowlist.
type authConfig struct {
	mode     string
	allow    []string
	acceptV1 bool
}

func parseAuthConf(r io.Reader) (authConfig, error) {
	c := authConfig{mode: authDevices}
	sc := bufio.NewScanner(r)
	for n := 1; sc.Scan(); n++ {
		line := strings.TrimSpace(sc.Text())
		if i := strings.IndexByte(line, '#'); i >= 0 {
			line = strings.TrimSpace(line[:i])
		}
		if line == "" {
			continue
		}
		k, v, _ := strings.Cut(line, " ")
		v = strings.TrimSpace(v)
		switch k {
		case "auth":
			if v != authDevices && v != authDevicesLegacy {
				return c, fmt.Errorf("%s line %d: auth must be %q or %q", authConf, n, authDevices, authDevicesLegacy)
			}
			c.mode = v
		case "accept_v1":
			switch v {
			case "true":
				c.acceptV1 = true
			case "false":
				c.acceptV1 = false
			default:
				return c, fmt.Errorf("%s line %d: accept_v1 must be true or false", authConf, n)
			}
		case "allow":
			for _, a := range strings.Split(v, ",") {
				if a = strings.TrimSpace(a); a != "" {
					c.allow = append(c.allow, a)
				}
			}
		default:
			return c, fmt.Errorf("%s line %d: unknown key %q", authConf, n, k)
		}
	}
	return c, sc.Err()
}

// loadAuthConf reads dir/auth.conf, creating it ("auth devices") when missing.
func loadAuthConf(dir string) (authConfig, error) {
	p := filepath.Join(dir, authConf)
	b, err := os.ReadFile(p)
	if errors.Is(err, os.ErrNotExist) {
		if os.MkdirAll(dir, 0o700) == nil {
			_ = os.WriteFile(p, []byte(authConfDefault), 0o600)
		}
		return authConfig{mode: authDevices}, nil
	}
	if err != nil {
		return authConfig{}, err
	}
	return parseAuthConf(strings.NewReader(string(b)))
}

// sessionAddr is the listen address for a session, ok=false if it gets none.
func sessionAddr(conf map[string]string, name string) (string, bool) {
	a, ok := conf[name]
	if !ok || a == "off" {
		return "", false
	}
	return a, true
}

// ---- single-instance lock ----

var errLocked = errors.New("another bridge holds the session lock")

func lockPath(home, name string) string {
	return filepath.Join(home, ".local/share/whistle-bridge", name+".lock")
}

// tryLock takes an exclusive flock on path and records our pid in it.
func tryLock(path string) (*os.File, error) {
	if err := os.MkdirAll(filepath.Dir(path), 0o700); err != nil {
		return nil, err
	}
	f, err := os.OpenFile(path, os.O_RDWR|os.O_CREATE, 0o600)
	if err != nil {
		return nil, err
	}
	if err := syscall.Flock(int(f.Fd()), syscall.LOCK_EX|syscall.LOCK_NB); err != nil {
		f.Close()
		if errors.Is(err, syscall.EWOULDBLOCK) {
			return nil, errLocked
		}
		return nil, err
	}
	_ = f.Truncate(0)
	_, _ = f.WriteAt([]byte(strconv.Itoa(os.Getpid())+"\n"), 0)
	return f, nil
}

// holderPID is the pid recorded in the lock file, 0 if none.
func holderPID(path string) int {
	b, err := os.ReadFile(path)
	if err != nil {
		return 0
	}
	pid, _ := strconv.Atoi(strings.TrimSpace(string(b)))
	return pid
}

// isBridge guards against signalling a recycled pid.
func isBridge(pid int) bool {
	b, err := os.ReadFile(fmt.Sprintf("/proc/%d/cmdline", pid))
	if err != nil {
		return !fileExists("/proc/self/cmdline") // no procfs: trust the lock
	}
	return strings.Contains(string(b), "whistle-bridge")
}

// acquireLock takes the session lock. With takeover it asks the current
// holder to exit (SIGTERM) and waits for it; otherwise it fails fast.
func acquireLock(ctx context.Context, path string, takeover bool, timeout time.Duration, signal func(pid int) error) (*os.File, error) {
	f, err := tryLock(path)
	if err == nil || !errors.Is(err, errLocked) || !takeover {
		return f, err
	}
	// Signal every holder seen while waiting: with two concurrent takeovers
	// the loser of the race keeps waiting and then replaces the winner, so
	// the latest start wins and only one bridge ever holds the lock.
	signalled := map[int]bool{}
	deadline := time.Now().Add(timeout)
	for {
		if pid := holderPID(path); pid > 0 && !signalled[pid] {
			signalled[pid] = true
			_ = signal(pid)
		}
		select {
		case <-ctx.Done():
			return nil, ctx.Err()
		case <-time.After(100 * time.Millisecond):
		}
		f, err = tryLock(path)
		if err == nil || !errors.Is(err, errLocked) {
			return f, err
		}
		if time.Now().After(deadline) {
			return nil, err
		}
	}
}

func termBridge(pid int) error {
	if pid == os.Getpid() || !isBridge(pid) {
		return fmt.Errorf("pid %d is not a whistle-bridge", pid)
	}
	return syscall.Kill(pid, syscall.SIGTERM)
}

// ---- supervision ----

// supervise calls runOnce until ctx ends, turning panics into errors and
// backing off between failures. A run that lasted longer than maxBackoff
// resets the backoff.
func supervise(ctx context.Context, log *slog.Logger, runOnce func(context.Context) error, minBackoff, maxBackoff time.Duration) {
	backoff := minBackoff
	for ctx.Err() == nil {
		start := time.Now()
		err := safeRun(ctx, runOnce)
		if ctx.Err() != nil {
			return
		}
		if time.Since(start) > maxBackoff {
			backoff = minBackoff
		}
		log.Error("bridge stopped; restarting", "err", err, "in", backoff)
		select {
		case <-ctx.Done():
			return
		case <-time.After(backoff):
		}
		if backoff *= 2; backoff > maxBackoff {
			backoff = maxBackoff
		}
	}
}

func safeRun(ctx context.Context, f func(context.Context) error) (err error) {
	defer func() {
		if r := recover(); r != nil {
			err = fmt.Errorf("panic: %v", r)
		}
	}()
	if err = f(ctx); err == nil {
		err = errors.New("exited")
	}
	return err
}

// watchServer returns once the herdr socket has refused connections for
// longer than grace: the server this plugin belongs to is gone.
func watchServer(ctx context.Context, alive func() bool, poll, grace time.Duration) {
	last := time.Now()
	t := time.NewTicker(poll)
	defer t.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-t.C:
		}
		if alive() {
			last = time.Now()
		} else if time.Since(last) > grace {
			return
		}
	}
}

func socketAlive(path string) func() bool {
	return func() bool {
		c, err := net.DialTimeout("unix", path, 3*time.Second)
		if err != nil {
			return false
		}
		c.Close()
		return true
	}
}

// ---- logging ----

// rotatingFile appends to path and moves it to path.1 past max bytes.
type rotatingFile struct {
	mu   sync.Mutex
	path string
	max  int64
	f    *os.File
	size int64
}

func openRotating(path string, max int64) (*rotatingFile, error) {
	r := &rotatingFile{path: path, max: max}
	return r, r.open()
}

func (r *rotatingFile) open() error {
	if err := os.MkdirAll(filepath.Dir(r.path), 0o700); err != nil {
		return err
	}
	f, err := os.OpenFile(r.path, os.O_WRONLY|os.O_CREATE|os.O_APPEND, 0o600)
	if err != nil {
		return err
	}
	if err := f.Chmod(0o600); err != nil { // a file from an older version may be wider
		f.Close()
		return err
	}
	st, err := f.Stat()
	if err != nil {
		f.Close()
		return err
	}
	r.f, r.size = f, st.Size()
	return nil
}

func (r *rotatingFile) Write(p []byte) (int, error) {
	r.mu.Lock()
	defer r.mu.Unlock()
	if r.size+int64(len(p)) > r.max && r.size > 0 {
		r.f.Close()
		_ = os.Rename(r.path, r.path+".1")
		if err := r.open(); err != nil {
			return 0, err
		}
	}
	n, err := r.f.Write(p)
	r.size += int64(n)
	return n, err
}

// ---- entry points ----

func pluginMain(args []string, log *slog.Logger) int {
	cmd := "run"
	if len(args) > 0 && !strings.HasPrefix(args[0], "-") {
		cmd, args = args[0], args[1:]
	}
	fs := flag.NewFlagSet("whistle-bridge plugin", flag.ContinueOnError)
	session := fs.String("session", "", `herdr session (default: from herdr's plugin env; "default" = default session)`)
	if err := fs.Parse(args); err != nil {
		return 2
	}
	home, err := os.UserHomeDir()
	if err != nil {
		log.Error("home", "err", err)
		return 1
	}
	if *session == "" { // set by `plugin open` for the popup pane
		*session = os.Getenv(popupSessionEnv)
	}
	env := pluginEnvFrom(os.Getenv, home, *session)
	switch cmd {
	case "open": // herdr action: open the popup pane
		if fs.NArg() != 1 || (fs.Arg(0) != "pair" && fs.Arg(0) != "devices") {
			fmt.Fprintln(os.Stderr, "usage: whistle-bridge plugin open pair|devices")
			return 2
		}
		return pluginOpen(env, fs.Arg(0), os.Stderr)
	case "popup": // the popup pane itself
		if fs.NArg() != 1 || (fs.Arg(0) != "pair" && fs.Arg(0) != "devices") {
			fmt.Fprintln(os.Stderr, "usage: whistle-bridge plugin popup pair|devices")
			return 2
		}
		return pluginPopup(env, home, fs.Arg(0))
	case "run":
		return pluginRun(env, home, log)
	case "status":
		return pluginStatus(env, home, os.Stdout)
	case "stop":
		return pluginStop(env, home, log)
	case "pair":
		if os.Getenv("HERDR_PLUGIN_ACTION_ID") != "" && !isTerminal(os.Stdout) {
			// Run as a herdr action (e.g. an old keybinding): stdout would
			// land in the plugin log, and the code must not. Open the popup.
			return pluginOpen(env, "pair", os.Stderr)
		}
		return pluginPair(env, home, os.Stdout)
	case "devices":
		return pluginDevices(env, home, os.Stdout)
	case "unpair":
		if fs.NArg() > 1 { // flags after the id: "unpair ID -session X"
			id := fs.Arg(0)
			if err := fs.Parse(fs.Args()[1:]); err != nil || fs.NArg() != 0 {
				fmt.Fprintln(os.Stderr, "usage: whistle-bridge plugin unpair <device-id> [-session NAME]")
				return 2
			}
			env = pluginEnvFrom(os.Getenv, home, *session)
			return pluginUnpair(env, home, id, os.Stdout)
		}
		if fs.NArg() != 1 {
			fmt.Fprintln(os.Stderr, "usage: whistle-bridge plugin unpair <device-id> [-session NAME]")
			return 2
		}
		return pluginUnpair(env, home, fs.Arg(0), os.Stdout)
	}
	fmt.Fprintf(os.Stderr, "usage: whistle-bridge plugin [run|status|stop|pair|devices|unpair ID|open pair|devices|popup pair|devices] [-session NAME]\n")
	return 2
}

// channelBinary is bin/whistle-bridge-<session> next to the running binary, if
// present and not already us: lets dev deploys ship their own binary without
// touching the one prod runs.
func channelBinary(exe, name string) string {
	want := filepath.Join(filepath.Dir(exe), "whistle-bridge-"+name)
	if filepath.Base(exe) == filepath.Base(want) || !fileExists(want) {
		return ""
	}
	return want
}

func pluginRun(env pluginEnv, home string, log *slog.Logger) int {
	conf, err := loadSessionsConf(env.configDir)
	if err != nil {
		log.Error("plugin config", "err", err)
		return 1
	}
	addr, ok := sessionAddr(conf, env.name())
	if !ok {
		return 0 // no bridge for this session
	}
	if exe, err := os.Executable(); err == nil {
		if bin := channelBinary(exe, env.name()); bin != "" {
			err := syscall.Exec(bin, append([]string{bin}, os.Args[1:]...), os.Environ())
			log.Error("exec channel binary", "bin", bin, "err", err)
		}
	}

	if lf, err := openRotating(filepath.Join(env.stateDir, env.name()+".log"), logMaxBytes); err == nil {
		log = slog.New(slog.NewTextHandler(io.MultiWriter(os.Stderr, lf), nil))
	} else {
		log.Warn("log file", "err", err)
	}
	log = log.With("session", env.name())

	ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
	defer stop()

	lock, err := acquireLock(ctx, lockPath(home, env.name()), true, takeoverTimeout, termBridge)
	if err != nil {
		log.Error("single-instance lock", "err", err)
		return 1
	}
	defer lock.Close()

	ac, err := loadAuthConf(env.configDir)
	if err != nil {
		log.Error("plugin auth config", "err", err)
		return 1
	}
	fl := []string{"-addr", addr, "-socket", env.socket, "-auth", ac.mode,
		"-allow", strings.Join(ac.allow, ","), "-key", filepath.Join(env.stateDir, "bridge-key.pem"),
		"-accept-v1=" + strconv.FormatBool(ac.acceptV1)}
	if env.session != "" {
		fl = append(fl, "-session", env.session)
	}
	o, err := parseFlags(fl, home)
	if err != nil {
		log.Error("options", "err", err)
		return 1
	}
	log.Info("plugin mode", "pid", os.Getpid(), "addr", addr, "socket", o.socket, "db", o.db)

	go func() {
		watchServer(ctx, socketAlive(o.socket), serverPoll, serverGrace)
		if ctx.Err() == nil {
			log.Info("herdr server gone; exiting", "grace", serverGrace)
			stop()
		}
	}()
	supervise(ctx, log, func(ctx context.Context) error { return serve(ctx, o, home, log) }, 2*time.Second, time.Minute)
	log.Info("plugin mode exiting")
	return 0
}

func pluginStatus(env pluginEnv, home string, w io.Writer) int {
	pid := 0
	if f, err := tryLock(lockPath(home, env.name())); err == nil {
		f.Close()
	} else {
		pid = holderPID(lockPath(home, env.name()))
	}
	fmt.Fprintf(w, "session=%s pid=%d", env.name(), pid)
	conf, err := loadSessionsConf(env.configDir)
	if err != nil {
		fmt.Fprintf(w, " config_error=%q\n", err)
		return 1
	}
	a, ok := sessionAddr(conf, env.name())
	if !ok {
		fmt.Fprintln(w, " addr=off")
		return 0
	}
	addr, err := resolveAddr(a, tailscaleIPv4)
	if err != nil {
		fmt.Fprintf(w, " addr=%s error=%q\n", a, err)
		return 1
	}
	fmt.Fprintf(w, " addr=%s", addr)
	c := http.Client{Timeout: 3 * time.Second}
	resp, err := c.Get("http://" + addr + "/v1/health")
	if err != nil {
		fmt.Fprintf(w, " health_error=%q\n", err)
		return 1
	}
	defer resp.Body.Close()
	b, _ := io.ReadAll(io.LimitReader(resp.Body, 4096))
	fmt.Fprintf(w, " health=%s\n", strings.TrimSpace(string(b)))
	if resp.StatusCode != http.StatusOK {
		return 1
	}
	return 0
}

func pluginStop(env pluginEnv, home string, log *slog.Logger) int {
	ctx, cancel := context.WithTimeout(context.Background(), takeoverTimeout+time.Second)
	defer cancel()
	f, err := acquireLock(ctx, lockPath(home, env.name()), true, takeoverTimeout, termBridge)
	if err != nil {
		log.Error("stop", "session", env.name(), "err", err)
		return 1
	}
	f.Close()
	fmt.Printf("session=%s stopped\n", env.name())
	return 0
}

// ---- popups (herdr plugin panes) ----

// popupSessionEnv carries the session name into the popup pane.
const popupSessionEnv = "HERDR_BRIDGE_SESSION"

func isTerminal(f *os.File) bool {
	st, err := f.Stat()
	return err == nil && st.Mode()&os.ModeCharDevice != 0
}

// herdrBinary is the herdr CLI to call from an action: HERDR_BIN_PATH when
// it is still there (an upgrade leaves it dangling), else the usual places.
func herdrBinary() string {
	if p := os.Getenv("HERDR_BIN_PATH"); p != "" {
		if st, err := os.Stat(p); err == nil && st.Mode().IsRegular() && st.Mode().Perm()&0o111 != 0 {
			return p
		}
	}
	return defaultHerdrBin()
}

// popupArgs is the herdr command line that opens popup entrypoint which.
func popupArgs(env pluginEnv, which string) []string {
	args := []string{}
	if env.session != "" {
		args = append(args, "--session", env.session)
	}
	return append(args, "plugin", "pane", "open", "--plugin", pluginID, "--entrypoint", which,
		"--placement", "popup", "--focus", "--env", popupSessionEnv+"="+env.name())
}

// pluginOpen replaces this process with `herdr plugin pane open …`: the
// popup shows in the client that invoked the action.
func pluginOpen(env pluginEnv, which string, stderr io.Writer) int {
	bin := herdrBinary()
	argv := append([]string{bin}, popupArgs(env, which)...)
	err := syscall.Exec(bin, argv, os.Environ())
	fmt.Fprintf(stderr, "whistle-bridge: could not run %s: %v\n", bin, err)
	return 1
}

// pluginPopup runs the interactive pair/devices UI in the popup pane.
func pluginPopup(env pluginEnv, home, which string) int {
	start := popup.ModePair
	if which == "devices" {
		start = popup.ModeDevices
	}
	if err := popup.Run(popup.SocketControl{Path: sessionCtl(env, home)}, env.name(), start); err != nil {
		fmt.Fprintln(os.Stderr, "whistle-bridge popup:", err)
		return 1
	}
	return 0
}

// ---- pairing CLI (talks to the running bridge's control socket) ----

func sessionCtl(env pluginEnv, home string) string {
	return ctlPath(filepath.Join(home, ".local/share/whistle-bridge", env.name()+".db"))
}

func controlErr(w io.Writer, env pluginEnv, err error) int {
	fmt.Fprintf(w, "Whistle bridge (session %s) is not reachable: %v\n", env.name(), err)
	fmt.Fprintln(w, "Start it with: herdr plugin action invoke whistle.bridge.restart")
	return 1
}

func pluginPair(env pluginEnv, home string, w io.Writer) int {
	resp, err := server.CallControl(sessionCtl(env, home), server.ControlRequest{Op: "pair"})
	if err != nil {
		return controlErr(w, env, err)
	}
	p := resp.Pairing
	fmt.Fprintf(w, "Pair Whistle with %s (%s)\n\n", p.Name, p.Addr)
	if err := devauth.RenderQR(w, p.URI); err != nil {
		fmt.Fprintf(w, "(QR failed: %v)\n", err)
	}
	fmt.Fprintf(w, "\nIn Whistle: Machines -> Pair a machine -> scan this code.\n")
	fmt.Fprintf(w, "Manual entry:  address %s   code %s\n", p.Addr, p.Code)
	fmt.Fprintf(w, "Or paste:      %s\n", p.URI)
	fmt.Fprintf(w, "Bridge key:    %s\n", p.Fingerprint)
	fmt.Fprintf(w, "Valid until %s (%s), single use.\n", p.Expires.Format("15:04:05"), devauth.TokenTTL)
	return 0
}

func pluginDevices(env pluginEnv, home string, w io.Writer) int {
	resp, err := server.CallControl(sessionCtl(env, home), server.ControlRequest{Op: "devices"})
	if err != nil {
		return controlErr(w, env, err)
	}
	if len(resp.Devices) == 0 {
		fmt.Fprintf(w, "No paired devices (session %s). Pair one with Ctrl+Alt+W in herdr (or: herdr plugin action invoke whistle.bridge.pair).\n", env.name())
		return 0
	}
	ts := func(u int64) string {
		if u == 0 {
			return "never"
		}
		return time.Unix(u, 0).Format("2006-01-02 15:04")
	}
	fmt.Fprintf(w, "%-14s %-24s %-16s %-16s %s\n", "ID", "NAME", "PAIRED", "LAST SEEN", "STATE")
	for _, d := range resp.Devices {
		state := "active"
		if d.Connections > 0 {
			state = fmt.Sprintf("connected (%d)", d.Connections)
		}
		if d.RevokedAt != 0 {
			state = "removed " + ts(d.RevokedAt)
		}
		fmt.Fprintf(w, "%-14s %-24s %-16s %-16s %s\n", d.ID, d.Name, ts(d.CreatedAt), ts(d.LastSeen), state)
	}
	return 0
}

func pluginUnpair(env pluginEnv, home, id string, w io.Writer) int {
	if _, err := server.CallControl(sessionCtl(env, home), server.ControlRequest{Op: "unpair", ID: id}); err != nil {
		if strings.HasPrefix(err.Error(), "no device") {
			fmt.Fprintln(w, err)
			return 1
		}
		return controlErr(w, env, err)
	}
	fmt.Fprintf(w, "Device %s removed from session %s; its connections were closed.\n", id, env.name())
	return 0
}
