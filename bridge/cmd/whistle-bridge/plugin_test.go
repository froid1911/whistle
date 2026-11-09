package main

import (
	"context"
	"errors"
	"io"
	"log/slog"
	"os"
	"path/filepath"
	"strings"
	"sync/atomic"
	"testing"

	"github.com/froid1911/whistle/bridge/internal/server"
	"github.com/froid1911/whistle/bridge/internal/store"
	"time"
)

func envMap(m map[string]string) func(string) string { return func(k string) string { return m[k] } }

func TestPluginEnvSession(t *testing.T) {
	home := "/h"
	cases := []struct {
		name        string
		env         map[string]string
		flag        string
		wantSession string
		wantSocket  string
	}{
		{"herdr named session", map[string]string{"HERDR_SESSION": "whistle-dev", "HERDR_SOCKET_PATH": "/h/.config/herdr/sessions/whistle-dev/herdr.sock"}, "",
			"whistle-dev", "/h/.config/herdr/sessions/whistle-dev/herdr.sock"},
		{"default session socket only", map[string]string{"HERDR_SOCKET_PATH": "/h/.config/herdr/herdr.sock"}, "",
			"", "/h/.config/herdr/herdr.sock"},
		{"named session from socket", map[string]string{"HERDR_SOCKET_PATH": "/h/.config/herdr/sessions/x/herdr.sock"}, "",
			"x", "/h/.config/herdr/sessions/x/herdr.sock"},
		{"no env", map[string]string{}, "", "", "/h/.config/herdr/herdr.sock"},
		{"flag overrides env", map[string]string{"HERDR_SESSION": "a", "HERDR_SOCKET_PATH": "/h/.config/herdr/sessions/a/herdr.sock"}, "whistle-dev",
			"whistle-dev", "/h/.config/herdr/sessions/whistle-dev/herdr.sock"},
		{"flag default", map[string]string{"HERDR_SESSION": "a"}, "default", "", "/h/.config/herdr/herdr.sock"},
	}
	for _, c := range cases {
		e := pluginEnvFrom(envMap(c.env), home, c.flag)
		if e.session != c.wantSession || e.socket != c.wantSocket {
			t.Errorf("%s: got session=%q socket=%q", c.name, e.session, e.socket)
		}
	}
	e := pluginEnvFrom(envMap(map[string]string{}), home, "")
	if e.name() != "default" || e.configDir != "/h/.config/herdr/plugins/config/whistle.bridge" ||
		e.stateDir != "/h/.local/state/herdr/plugins/whistle.bridge" {
		t.Errorf("defaults: %+v", e)
	}
	e = pluginEnvFrom(envMap(map[string]string{"HERDR_PLUGIN_CONFIG_DIR": "/c", "HERDR_PLUGIN_STATE_DIR": "/s"}), home, "")
	if e.configDir != "/c" || e.stateDir != "/s" {
		t.Errorf("herdr dirs: %+v", e)
	}
}

// The plugin must keep using the DBs the systemd units used, or the app sees
// a new server_id and wipes its cache.
func TestPluginKeepsDBPaths(t *testing.T) {
	for session, db := range map[string]string{"": "/h/.local/share/whistle-bridge/default.db", "whistle-dev": "/h/.local/share/whistle-bridge/whistle-dev.db"} {
		e := pluginEnvFrom(envMap(map[string]string{"HERDR_SESSION": session}), "/h", "")
		fl := []string{"-addr", "tailscale:1", "-socket", e.socket}
		if e.session != "" {
			fl = append(fl, "-session", e.session)
		}
		o, err := parseFlags(fl, "/h")
		if err != nil || o.db != db || o.session != session {
			t.Errorf("session %q: db=%q session=%q err=%v", session, o.db, o.session, err)
		}
	}
}

func TestSessionsConf(t *testing.T) {
	dir := t.TempDir()
	conf, err := loadSessionsConf(dir)
	if err != nil {
		t.Fatal(err)
	}
	if a, ok := sessionAddr(conf, "default"); !ok || a != "tailscale:8787" {
		t.Errorf("default: %q %v", a, ok)
	}
	if a, ok := sessionAddr(conf, "whistle-dev"); !ok || a != "tailscale:8788" {
		t.Errorf("dev: %q %v", a, ok)
	}
	if _, ok := sessionAddr(conf, "other"); ok {
		t.Error("unlisted session must get no bridge")
	}
	// The written file round-trips to the same map.
	f, err := os.Open(filepath.Join(dir, sessionsConf))
	if err != nil {
		t.Fatal(err)
	}
	defer f.Close()
	again, err := parseSessionsConf(f)
	if err != nil || len(again) != 2 || again["default"] != "tailscale:8787" || again["whistle-dev"] != "tailscale:8788" {
		t.Fatalf("round trip: %v %v", again, err)
	}

	m, err := parseSessionsConf(strings.NewReader("# c\n\nwhistle-dev off  # disabled\nlab 127.0.0.1:9000\n"))
	if err != nil {
		t.Fatal(err)
	}
	if _, ok := sessionAddr(m, "whistle-dev"); ok {
		t.Error("off must disable")
	}
	if a, ok := sessionAddr(m, "lab"); !ok || a != "127.0.0.1:9000" {
		t.Error("lab", a, ok)
	}
	if _, ok := sessionAddr(m, "default"); ok {
		t.Error("an explicit file replaces the builtins")
	}
	for _, bad := range []string{"a\n", "a b c\n", "a nope\n"} {
		if _, err := parseSessionsConf(strings.NewReader(bad)); err == nil {
			t.Errorf("%q: want error", bad)
		}
	}
}

func TestLockSingleInstance(t *testing.T) {
	p := filepath.Join(t.TempDir(), "s.lock")
	a, err := tryLock(p)
	if err != nil {
		t.Fatal(err)
	}
	if holderPID(p) != os.Getpid() {
		t.Error("lock must record the holder pid")
	}
	if _, err := tryLock(p); !errors.Is(err, errLocked) {
		t.Fatalf("second lock: %v", err)
	}
	// No takeover: fail fast.
	if _, err := acquireLock(context.Background(), p, false, time.Second, nil); !errors.Is(err, errLocked) {
		t.Fatal(err)
	}
	// Takeover with a holder that never leaves: times out, never runs two.
	var signalled atomic.Int32
	sig := func(int) error { signalled.Add(1); return nil }
	if _, err := acquireLock(context.Background(), p, true, 300*time.Millisecond, sig); !errors.Is(err, errLocked) {
		t.Fatal(err)
	}
	if signalled.Load() != 1 {
		t.Errorf("holder signalled %d times", signalled.Load())
	}
	// Takeover with a holder that exits on the signal.
	sig = func(int) error { go func() { time.Sleep(50 * time.Millisecond); a.Close() }(); return nil }
	b, err := acquireLock(context.Background(), p, true, 5*time.Second, sig)
	if err != nil {
		t.Fatal(err)
	}
	b.Close()
}

func TestChannelBinary(t *testing.T) {
	dir := t.TempDir()
	exe := filepath.Join(dir, "whistle-bridge")
	if channelBinary(exe, "whistle-dev") != "" {
		t.Error("no channel binary present")
	}
	dev := filepath.Join(dir, "whistle-bridge-whistle-dev")
	if err := os.WriteFile(dev, nil, 0o755); err != nil {
		t.Fatal(err)
	}
	if got := channelBinary(exe, "whistle-dev"); got != dev {
		t.Errorf("got %q", got)
	}
	if channelBinary(dev, "whistle-dev") != "" {
		t.Error("channel binary must not re-exec itself")
	}
	if channelBinary(exe, "default") != "" {
		t.Error("other session")
	}
}

func TestSuperviseRestartsAfterPanicAndError(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	var n atomic.Int32
	done := make(chan struct{})
	go func() {
		supervise(ctx, slog.New(slog.NewTextHandler(io.Discard, nil)), func(ctx context.Context) error {
			switch n.Add(1) {
			case 1:
				panic("boom")
			case 2:
				return errors.New("listen failed")
			}
			<-ctx.Done()
			return nil
		}, time.Millisecond, 10*time.Millisecond)
		close(done)
	}()
	deadline := time.After(5 * time.Second)
	for n.Load() < 3 {
		select {
		case <-deadline:
			t.Fatal("not restarted", n.Load())
		case <-time.After(5 * time.Millisecond):
		}
	}
	cancel()
	<-done
	if n.Load() != 3 {
		t.Errorf("runs=%d after cancel", n.Load())
	}
}

func TestWatchServer(t *testing.T) {
	var alive atomic.Bool
	alive.Store(true)
	done := make(chan struct{})
	go func() {
		watchServer(context.Background(), alive.Load, time.Millisecond, 30*time.Millisecond)
		close(done)
	}()
	select {
	case <-done:
		t.Fatal("returned while server alive")
	case <-time.After(80 * time.Millisecond):
	}
	alive.Store(false)
	select {
	case <-done:
	case <-time.After(5 * time.Second):
		t.Fatal("did not notice the server is gone")
	}
}

func TestRotatingFile(t *testing.T) {
	p := filepath.Join(t.TempDir(), "x.log")
	r, err := openRotating(p, 10)
	if err != nil {
		t.Fatal(err)
	}
	r.Write([]byte("123456"))
	r.Write([]byte("abcdef"))
	r.f.Close()
	old, _ := os.ReadFile(p + ".1")
	cur, _ := os.ReadFile(p)
	if string(old) != "123456" || string(cur) != "abcdef" {
		t.Errorf("old=%q cur=%q", old, cur)
	}
}

func TestParseAuthConf(t *testing.T) {
	c, err := parseAuthConf(strings.NewReader("# x\nauth devices+tailscale-users\nallow you@github, b@github\nallow c@github\n"))
	if err != nil || c.mode != authDevicesLegacy || len(c.allow) != 3 || c.allow[1] != "b@github" {
		t.Fatal(c, err)
	}
	if c, err := parseAuthConf(strings.NewReader(authConfDefault)); err != nil || c.mode != authDevices || len(c.allow) != 0 || !c.acceptV1 {
		t.Fatal(c, err)
	}
	if c, err := parseAuthConf(strings.NewReader("accept_v1 false\n")); err != nil || c.acceptV1 {
		t.Fatal(c, err)
	}
	for _, bad := range []string{"auth tailscale\n", "allowed x\n", "accept_v1 no\n"} {
		if _, err := parseAuthConf(strings.NewReader(bad)); err == nil {
			t.Errorf("accepted %q", bad)
		}
	}
	dir := t.TempDir()
	if c, err := loadAuthConf(dir); err != nil || c.mode != authDevices {
		t.Fatal(c, err)
	}
	if b, _ := os.ReadFile(filepath.Join(dir, authConf)); string(b) != authConfDefault {
		t.Fatal("default auth.conf not written")
	}
}

func TestPluginPairDevicesUnpair(t *testing.T) {
	home := t.TempDir()
	env := pluginEnvFrom(func(string) string { return "" }, home, "")
	var b strings.Builder
	if pluginPair(env, home, &b) != 1 || !strings.Contains(b.String(), "not reachable") {
		t.Fatal(b.String())
	}
	st, err := store.Open(filepath.Join(home, "b.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer st.Close()
	srv := server.New(server.Config{Addr: "100.64.0.1:8787", MachineName: "myserver",
		Log: slog.New(slog.NewTextHandler(io.Discard, nil))}, server.Deps{Store: st})
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	go srv.ServeControl(ctx, sessionCtl(env, home))
	for i := 0; i < 100; i++ {
		b.Reset()
		if pluginPair(env, home, &b) == 0 {
			break
		}
		time.Sleep(10 * time.Millisecond)
	}
	out := b.String()
	for _, want := range []string{"Pair Whistle with myserver (100.64.0.1:8787)", "█", "whistle://pair?v=1&u=100.64.0.1:8787&t=", "code ", "single use"} {
		if !strings.Contains(out, want) {
			t.Fatalf("missing %q in:\n%s", want, out)
		}
	}
	d, _ := st.AddDevice("Pixel 8a", "k", time.Now())
	b.Reset()
	if pluginDevices(env, home, &b) != 0 || !strings.Contains(b.String(), d.ID) || !strings.Contains(b.String(), "active") {
		t.Fatal(b.String())
	}
	b.Reset()
	if pluginUnpair(env, home, d.ID, &b) != 0 {
		t.Fatal(b.String())
	}
	b.Reset()
	if pluginDevices(env, home, &b); !strings.Contains(b.String(), "removed") {
		t.Fatal(b.String())
	}
	if pluginUnpair(env, home, "dnope", &b) != 1 {
		t.Fatal("unknown id")
	}
}
