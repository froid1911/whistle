package popup

import (
	"errors"
	"strings"
	"sync"
	"testing"
	"time"

	tea "github.com/charmbracelet/bubbletea"

	"github.com/froid1911/whistle/bridge/internal/server"
	"github.com/froid1911/whistle/bridge/internal/store"
)

type fakeCtl struct {
	mu        sync.Mutex
	state     string
	dev       *store.Device
	cancelled []string
	unpaired  []string
	devs      []server.ControlDevice
	down      bool
	exp       time.Time
}

func (f *fakeCtl) Pair() (server.PairingToken, error) {
	if f.down {
		return server.PairingToken{}, errors.New("dial unix: no such file")
	}
	return server.PairingToken{Token: "ABCDEFGHIJKLMNOPQRSTUVWXYZ", Code: "ABCD-EFGH-IJKL-MNOP-QRST-UVWX-YZ",
		URI:  "whistle://pair?v=1&u=100.64.0.10:8787&t=ABCDEFGHIJKLMNOPQRSTUVWXYZ&f=MkR9tVZeCyn4s6dBt2aCjTQQfhPzyXnLrICFTJuIP-g",
		Addr: "100.64.0.10:8787", Name: "myserver", Expires: f.exp}, nil
}
func (f *fakeCtl) PairStatus(string) (string, *store.Device, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	return f.state, f.dev, nil
}
func (f *fakeCtl) Cancel(t string) error { f.cancelled = append(f.cancelled, t); return nil }
func (f *fakeCtl) Devices() ([]server.ControlDevice, error) {
	if f.down {
		return nil, errors.New("down")
	}
	return f.devs, nil
}
func (f *fakeCtl) Unpair(id string) error { f.unpaired = append(f.unpaired, id); return nil }

func key(s string) tea.KeyMsg {
	if s == "esc" {
		return tea.KeyMsg{Type: tea.KeyEsc}
	}
	return tea.KeyMsg{Type: tea.KeyRunes, Runes: []rune(s)}
}

func upd(t *testing.T, m Model, msg tea.Msg) (Model, tea.Cmd) {
	t.Helper()
	mm, cmd := m.Update(msg)
	return mm.(Model), cmd
}

func isQuit(cmd tea.Cmd) bool {
	if cmd == nil {
		return false
	}
	_, ok := cmd().(tea.QuitMsg)
	return ok
}

func setup(t *testing.T) (*fakeCtl, Model, *time.Time) {
	now := time.Unix(1762689600, 0)
	f := &fakeCtl{state: server.PairPending, exp: now.Add(5 * time.Minute)}
	m := New(f, "default", ModePair)
	m.now = func() time.Time { return now }
	m, _ = upd(t, m, tea.WindowSizeMsg{Width: 120, Height: 40})
	m, _ = upd(t, m, m.requestPair()())
	return f, m, &now
}

func TestPairPopupPairs(t *testing.T) {
	f, m, now := setup(t)
	v := m.View()
	for _, want := range []string{"Waiting for phone", "5:00", "ABCD-EFGH-IJKL-MNOP-QRST-UVWX-YZ", "myserver", "100.64.0.10:8787", "Esc to cancel", "▀"} {
		if !strings.Contains(v, want) {
			t.Fatalf("view lacks %q:\n%s", want, v)
		}
	}
	*now = now.Add(61 * time.Second)
	if v := m.View(); !strings.Contains(v, "3:59") {
		t.Fatal("countdown")
	}
	f.state, f.dev = server.PairPaired, &store.Device{Name: "Pixel 8a"}
	m, cmd := upd(t, m, m.pollStatus()())
	if !strings.Contains(m.View(), "✓ Paired: Pixel 8a") || cmd == nil {
		t.Fatalf("paired view:\n%s", m.View())
	}
	m, cmd = upd(t, m, closeMsg{})
	if !isQuit(cmd) || len(f.cancelled) != 0 {
		t.Fatal("auto close must quit without cancelling", f.cancelled)
	}
}

func TestPairPopupEscCancels(t *testing.T) {
	f, m, _ := setup(t)
	_, cmd := upd(t, m, key("esc"))
	if !isQuit(cmd) || len(f.cancelled) != 1 || f.cancelled[0] != "ABCDEFGHIJKLMNOPQRSTUVWXYZ" {
		t.Fatal("esc must burn the token and quit", f.cancelled)
	}
}

func TestPairPopupExpires(t *testing.T) {
	f, m, now := setup(t)
	*now = now.Add(5*time.Minute + time.Second)
	f.state = server.PairGone
	m, _ = upd(t, m, m.pollStatus()())
	if !strings.Contains(m.View(), "Code expired — press r for a new one") {
		t.Fatalf("expired view:\n%s", m.View())
	}
	m, cmd := upd(t, m, key("r"))
	if m.ps != pairLoading || cmd == nil {
		t.Fatal("r must request a new code")
	}
	// Gone before expiry (being redeemed right now): keep waiting.
	f2, m2, _ := setup(t)
	f2.state = server.PairGone
	m2, _ = upd(t, m2, m2.pollStatus()())
	if m2.ps != pairWaiting {
		t.Fatal("gone before expiry is still waiting")
	}
}

func TestPairPopupSmallWindowFallsBackToText(t *testing.T) {
	_, m, _ := setup(t)
	m, _ = upd(t, m, tea.WindowSizeMsg{Width: 50, Height: 14})
	v := m.View()
	if strings.Contains(v, "▀") || !strings.Contains(v, "Enlarge the window") || !strings.Contains(v, "ABCD-EFGH") {
		t.Fatalf("fallback:\n%s", v)
	}
}

func TestBridgeDown(t *testing.T) {
	f := &fakeCtl{down: true}
	m := New(f, "default", ModePair)
	m, _ = upd(t, m, m.requestPair()())
	if !strings.Contains(m.View(), "not reachable") {
		t.Fatal(m.View())
	}
}

func TestDevicesPopup(t *testing.T) {
	now := time.Unix(1762689600, 0)
	f := &fakeCtl{devs: []server.ControlDevice{
		{Device: store.Device{ID: "d0123456789ab", Name: "Pixel 8a", CreatedAt: now.Unix() - 86400, LastSeen: now.Unix() - 120}, Connections: 1},
		{Device: store.Device{ID: "dffffffffffff", Name: "Tablet", CreatedAt: now.Unix() - 86400}},
		{Device: store.Device{ID: "deeeeeeeeeeee", Name: "Old", RevokedAt: 5}},
	}}
	m := New(f, "default", ModeDevices)
	m.now = func() time.Time { return now }
	m, _ = upd(t, m, m.loadDevices()())
	v := m.View()
	for _, want := range []string{"Pixel 8a", "d012345", "2 min ago", "never", "●", "1 unpaired device(s) not shown"} {
		if !strings.Contains(v, want) {
			t.Fatalf("view lacks %q:\n%s", want, v)
		}
	}
	if strings.Contains(v, "Old") {
		t.Fatal("revoked device listed")
	}
	m, _ = upd(t, m, key("j"))
	m, _ = upd(t, m, key("u"))
	if !strings.Contains(m.View(), "Unpair Tablet? y/n") {
		t.Fatal(m.View())
	}
	m, cmd := upd(t, m, key("y"))
	m, _ = upd(t, m, cmd())
	if len(f.unpaired) != 1 || f.unpaired[0] != "dffffffffffff" || !strings.Contains(m.View(), "Unpaired Tablet") {
		t.Fatal(f.unpaired, m.View())
	}
	// p opens pairing in the same popup; Esc comes back to the list.
	f.exp = now.Add(time.Minute)
	m, cmd = upd(t, m, key("p"))
	m, _ = upd(t, m, cmd())
	if m.mode != ModePair || !strings.Contains(m.View(), "Waiting for phone") {
		t.Fatal(m.View())
	}
	m, cmd = upd(t, m, key("esc"))
	if m.mode != ModeDevices || isQuit(cmd) || len(f.cancelled) != 1 {
		t.Fatal("esc in pairing from the list returns to the list", f.cancelled)
	}
	_, cmd = upd(t, m, key("esc"))
	if !isQuit(cmd) {
		t.Fatal("esc closes the list")
	}
}
