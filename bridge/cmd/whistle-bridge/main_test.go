package main

import (
	"errors"
	"log/slog"
	"path/filepath"
	"testing"

	"github.com/froid1911/whistle/bridge/internal/state"
	"github.com/froid1911/whistle/bridge/internal/store"
)

func TestResolveAddrTailscale(t *testing.T) {
	ip := func() (string, error) { return "100.101.102.103", nil }
	got, err := resolveAddr("tailscale:8787", ip)
	if err != nil || got != "100.101.102.103:8787" {
		t.Fatal(got, err)
	}
	if got, _ := resolveAddr("127.0.0.1:9", ip); got != "127.0.0.1:9" {
		t.Fatal(got)
	}
	if _, err := resolveAddr("tailscale:8787", func() (string, error) { return "", errors.New("down") }); err == nil {
		t.Fatal("want error")
	}
}

func TestInsecureRequiresLoopback(t *testing.T) {
	for addr, ok := range map[string]bool{
		"127.0.0.1:8788": true, "[::1]:8788": true, "localhost:8788": true,
		"100.64.0.1:8788": false, ":8788": false, "0.0.0.0:8788": false,
	} {
		if err := checkListen(addr, true); (err == nil) != ok {
			t.Errorf("%s insecure: err=%v", addr, err)
		}
	}
	if err := checkListen(":8787", false); err == nil {
		t.Error("wildcard bind must be refused")
	}
	if err := checkListen("100.64.0.1:8787", false); err != nil {
		t.Error(err)
	}
}

func TestDefaultPaths(t *testing.T) {
	o, err := parseFlags([]string{"-session", "dev"}, "/home/u")
	if err != nil {
		t.Fatal(err)
	}
	if o.socket != filepath.FromSlash("/home/u/.config/herdr/sessions/dev/herdr.sock") ||
		o.db != "/home/u/.local/share/whistle-bridge/dev.db" || o.addr != "tailscale:8787" {
		t.Fatalf("%+v", o)
	}
	if len(o.allow) != 0 || o.auth != authDevices || o.key != "/home/u/.local/share/whistle-bridge/bridge-key.pem" {
		t.Fatal("no built-in allowlist; devices mode by default", o.allow, o.auth, o.key)
	}
	o, _ = parseFlags([]string{"-socket", "/x.sock", "-session", "dev", "-allow", "a@github, b@github"}, "/home/u")
	if o.socket != "/x.sock" || len(o.allow) != 2 || o.allow[1] != "b@github" {
		t.Fatalf("%+v", o)
	}
	o, _ = parseFlags(nil, "/home/u")
	if o.socket != "/home/u/.config/herdr/herdr.sock" || o.db != "/home/u/.local/share/whistle-bridge/default.db" {
		t.Fatalf("%+v", o)
	}
}

func TestSeedWatcherRestoresPersistedState(t *testing.T) {
	path := filepath.Join(t.TempDir(), "s.db")
	st, err := store.Open(path)
	if err != nil {
		t.Fatal(err)
	}
	w := state.NewWatcher("/nonexistent", nil)
	seedWatcher(w, st, slog.Default())
	if len(w.Current().Panes) != 0 {
		t.Fatal("fresh db seeded panes")
	}
	w.Persist(state.State{Panes: []state.Pane{{ID: "w1:p9", Kind: "shell"}}})
	st.Close()
	st2, err := store.Open(path)
	if err != nil {
		t.Fatal(err)
	}
	defer st2.Close()
	w2 := state.NewWatcher("/nonexistent", nil)
	seedWatcher(w2, st2, slog.Default())
	if p := w2.Current().Panes; len(p) != 1 || p[0].ID != "w1:p9" {
		t.Fatalf("seeded %+v", p)
	}
}
