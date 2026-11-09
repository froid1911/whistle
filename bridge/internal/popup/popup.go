// Package popup is the interactive UI that runs inside herdr plugin popup
// panes: "Whistle: pair a phone" (QR code, countdown, live pairing result)
// and "Whistle: devices" (list, unpair). It talks to the running bridge
// through its control socket only.
package popup

import (
	"fmt"
	"os"
	"os/signal"
	"strings"
	"syscall"
	"time"

	tea "github.com/charmbracelet/bubbletea"
	"github.com/charmbracelet/lipgloss"
	"github.com/skip2/go-qrcode"

	"github.com/froid1911/whistle/bridge/internal/server"
	"github.com/froid1911/whistle/bridge/internal/store"
)

// Control is the bridge control socket as the popup uses it.
type Control interface {
	Pair() (server.PairingToken, error)
	PairStatus(token string) (state string, dev *store.Device, err error)
	Cancel(token string) error
	Devices() ([]server.ControlDevice, error)
	Unpair(id string) error
}

// SocketControl implements Control over a control socket path.
type SocketControl struct{ Path string }

func (c SocketControl) Pair() (server.PairingToken, error) {
	r, err := server.CallControl(c.Path, server.ControlRequest{Op: "pair"})
	if err != nil {
		return server.PairingToken{}, err
	}
	if r.Pairing == nil {
		return server.PairingToken{}, fmt.Errorf("bridge sent no pairing code")
	}
	return *r.Pairing, nil
}

func (c SocketControl) PairStatus(token string) (string, *store.Device, error) {
	r, err := server.CallControl(c.Path, server.ControlRequest{Op: "pair_status", Token: token})
	return r.State, r.Device, err
}

func (c SocketControl) Cancel(token string) error {
	_, err := server.CallControl(c.Path, server.ControlRequest{Op: "cancel", Token: token})
	return err
}

func (c SocketControl) Devices() ([]server.ControlDevice, error) {
	r, err := server.CallControl(c.Path, server.ControlRequest{Op: "devices"})
	return r.Devices, err
}

func (c SocketControl) Unpair(id string) error {
	_, err := server.CallControl(c.Path, server.ControlRequest{Op: "unpair", ID: id})
	return err
}

// Mode picks the popup's first screen.
type Mode int

const (
	ModePair Mode = iota
	ModeDevices
)

// pairing sub-states
type pairState int

const (
	pairLoading pairState = iota
	pairWaiting
	pairDone
	pairExpired
	pairFailed
)

const autoClose = 3 * time.Second

// Model is the bubbletea model of both popups.
type Model struct {
	ctl     Control
	session string
	now     func() time.Time
	start   Mode
	mode    Mode
	w, h    int

	// pair screen
	ps      pairState
	tok     server.PairingToken
	paired  *store.Device
	doneAt  time.Time
	pairErr string

	// devices screen
	devs    []server.ControlDevice
	removed int
	sel     int
	confirm bool
	devErr  string
	flash   string

	quitting bool
}

// New builds the popup model.
func New(ctl Control, session string, start Mode) Model {
	return Model{ctl: ctl, session: session, now: time.Now, start: start, mode: start}
}

type (
	tickMsg time.Time
	pairMsg struct {
		tok server.PairingToken
		err error
	}
	statusMsg struct {
		token, state string
		dev          *store.Device
		err          error
	}
	devicesMsg struct {
		devs []server.ControlDevice
		err  error
	}
	unpairMsg struct {
		name string
		err  error
	}
	closeMsg struct{}
)

func tick() tea.Cmd {
	return tea.Tick(time.Second, func(t time.Time) tea.Msg { return tickMsg(t) })
}

func (m Model) requestPair() tea.Cmd {
	return func() tea.Msg {
		t, err := m.ctl.Pair()
		return pairMsg{t, err}
	}
}

func (m Model) pollStatus() tea.Cmd {
	tok := m.tok.Token
	return func() tea.Msg {
		st, d, err := m.ctl.PairStatus(tok)
		return statusMsg{token: tok, state: st, dev: d, err: err}
	}
}

func (m Model) loadDevices() tea.Cmd {
	return func() tea.Msg {
		ds, err := m.ctl.Devices()
		return devicesMsg{ds, err}
	}
}

func (m Model) Init() tea.Cmd {
	if m.mode == ModePair {
		return tea.Batch(m.requestPair(), tick())
	}
	return tea.Batch(m.loadDevices(), tick())
}

// cancelPair burns the token shown (if still unused).
func (m *Model) cancelPair() {
	if m.ps == pairWaiting && m.tok.Token != "" {
		_ = m.ctl.Cancel(m.tok.Token)
	}
	m.tok = server.PairingToken{}
}

func (m Model) enterPair() (Model, tea.Cmd) {
	m.mode, m.ps, m.tok, m.paired, m.pairErr = ModePair, pairLoading, server.PairingToken{}, nil, ""
	return m, m.requestPair()
}

// leavePair goes back to the list (when pairing was opened from it) or quits.
func (m Model) leavePair() (Model, tea.Cmd) {
	m.cancelPair()
	if m.start == ModeDevices {
		m.mode, m.confirm = ModeDevices, false
		return m, m.loadDevices()
	}
	m.quitting = true
	return m, tea.Quit
}

func (m Model) Update(msg tea.Msg) (tea.Model, tea.Cmd) {
	switch msg := msg.(type) {
	case tea.WindowSizeMsg:
		m.w, m.h = msg.Width, msg.Height
		return m, nil
	case tea.KeyMsg:
		if msg.Type == tea.KeyCtrlC {
			m.cancelPair()
			m.quitting = true
			return m, tea.Quit
		}
		if m.mode == ModePair {
			return m.pairKey(msg)
		}
		return m.devicesKey(msg)
	case tickMsg:
		var cmds []tea.Cmd
		cmds = append(cmds, tick())
		if m.mode == ModePair && m.ps == pairWaiting {
			// Also after expiry: one last look decides paired vs expired.
			cmds = append(cmds, m.pollStatus())
		}
		if m.mode == ModeDevices && !m.confirm {
			cmds = append(cmds, m.loadDevices())
		}
		return m, tea.Batch(cmds...)
	case pairMsg:
		if m.mode != ModePair || m.ps != pairLoading {
			if msg.err == nil {
				_ = m.ctl.Cancel(msg.tok.Token)
			}
			return m, nil
		}
		if msg.err != nil {
			m.ps, m.pairErr = pairFailed, msg.err.Error()
			return m, nil
		}
		m.ps, m.tok = pairWaiting, msg.tok
		return m, nil
	case statusMsg:
		if m.mode != ModePair || m.ps != pairWaiting || msg.token != m.tok.Token {
			return m, nil
		}
		switch {
		case msg.err != nil:
			m.ps, m.pairErr = pairFailed, msg.err.Error()
		case msg.state == server.PairPaired:
			m.ps, m.paired, m.doneAt = pairDone, msg.dev, m.now()
			return m, tea.Tick(autoClose, func(time.Time) tea.Msg { return closeMsg{} })
		case !m.now().Before(m.tok.Expires):
			m.ps = pairExpired
		}
		return m, nil
	case closeMsg:
		if m.mode == ModePair && m.ps == pairDone {
			m.tok = server.PairingToken{}
			if m.start == ModeDevices {
				m.mode = ModeDevices
				return m, m.loadDevices()
			}
			m.quitting = true
			return m, tea.Quit
		}
		return m, nil
	case devicesMsg:
		if msg.err != nil {
			m.devErr = msg.err.Error()
			return m, nil
		}
		m.devErr, m.devs, m.removed = "", nil, 0
		for _, d := range msg.devs {
			if d.RevokedAt != 0 {
				m.removed++
				continue
			}
			m.devs = append(m.devs, d)
		}
		if m.sel >= len(m.devs) {
			m.sel = max(0, len(m.devs)-1)
		}
		return m, nil
	case unpairMsg:
		if msg.err != nil {
			m.flash = "Unpair failed: " + msg.err.Error()
		} else {
			m.flash = "Unpaired " + msg.name + "; its connections were closed."
		}
		return m, m.loadDevices()
	}
	return m, nil
}

func (m Model) pairKey(k tea.KeyMsg) (tea.Model, tea.Cmd) {
	switch k.String() {
	case "esc", "q":
		return m.leavePair()
	case "r":
		if m.ps == pairExpired || m.ps == pairFailed {
			return m.enterPair()
		}
	case "enter":
		if m.ps == pairDone {
			return m.Update(closeMsg{})
		}
	}
	return m, nil
}

func (m Model) devicesKey(k tea.KeyMsg) (tea.Model, tea.Cmd) {
	if m.confirm {
		switch k.String() {
		case "y", "Y":
			m.confirm = false
			if m.sel < len(m.devs) {
				d := m.devs[m.sel]
				return m, func() tea.Msg { return unpairMsg{d.Name, m.ctl.Unpair(d.ID)} }
			}
		case "n", "N", "esc", "q":
			m.confirm = false
		}
		return m, nil
	}
	switch k.String() {
	case "esc", "q":
		m.quitting = true
		return m, tea.Quit
	case "up", "k":
		if m.sel > 0 {
			m.sel--
		}
	case "down", "j":
		if m.sel < len(m.devs)-1 {
			m.sel++
		}
	case "u", "delete", "d":
		if m.sel < len(m.devs) {
			m.confirm, m.flash = true, ""
		}
	case "p":
		m.flash = ""
		mm, cmd := m.enterPair()
		return mm, cmd
	}
	return m, nil
}

// ---- view ----

var (
	titleStyle = lipgloss.NewStyle().Bold(true)
	dimStyle   = lipgloss.NewStyle().Faint(true)
	okStyle    = lipgloss.NewStyle().Bold(true).Foreground(lipgloss.Color("10"))
	warnStyle  = lipgloss.NewStyle().Bold(true).Foreground(lipgloss.Color("11"))
	errStyle   = lipgloss.NewStyle().Foreground(lipgloss.Color("9"))
	codeStyle  = lipgloss.NewStyle().Bold(true)
	selStyle   = lipgloss.NewStyle().Reverse(true)
	// The QR is drawn black-on-white whatever the terminal theme.
	qrStyle = lipgloss.NewStyle().Foreground(lipgloss.Color("#ffffff")).Background(lipgloss.Color("#000000"))
)

func (m Model) View() string {
	if m.quitting {
		return ""
	}
	var body string
	if m.mode == ModePair {
		body = m.pairView()
	} else {
		body = m.devicesView()
	}
	if m.w == 0 || m.h == 0 {
		return body
	}
	return lipgloss.Place(m.w, m.h, lipgloss.Center, lipgloss.Center, body)
}

func mmss(d time.Duration) string {
	if d < 0 {
		d = 0
	}
	s := int(d.Round(time.Second) / time.Second)
	return fmt.Sprintf("%d:%02d", s/60, s%60)
}

func (m Model) notRunning(err string) string {
	return strings.Join([]string{
		errStyle.Render("Whistle bridge (session " + m.session + ") is not reachable."),
		dimStyle.Render(err),
		"",
		"Start it from herdr's command list: “Whistle bridge: restart”.",
		dimStyle.Render("Esc to close"),
	}, "\n")
}

func (m Model) pairView() string {
	switch m.ps {
	case pairLoading:
		return dimStyle.Render("Creating a pairing code…")
	case pairFailed:
		return m.notRunning(m.pairErr) + "\n" + dimStyle.Render("r to retry")
	case pairDone:
		name := "phone"
		if m.paired != nil {
			name = m.paired.Name
		}
		return okStyle.Render("✓ Paired: "+name) + "\n\n" + dimStyle.Render("Closing…")
	case pairExpired:
		return strings.Join([]string{
			titleStyle.Render("Pair a phone with Whistle"),
			"",
			warnStyle.Render("Code expired — press r for a new one"),
			"",
			dimStyle.Render("Esc to close"),
		}, "\n")
	}
	t := m.tok
	w, h := m.w, m.h
	if w == 0 || h == 0 {
		w, h = 200, 200
	}
	return pairScreen(t, w, h, mmss(t.Expires.Sub(m.now())))
}

// qrKind is how (and whether) the QR is drawn.
type qrKind int

const (
	qrHalf qrKind = iota // half blocks: 1 module wide x 2 tall per cell
	qrQuad               // quadrant blocks: 2x2 modules per cell
	qrNone               // too small: code and link only
)

// layout is what pairScreen draws for a given window size.
type layout struct {
	kind qrKind
	full bool // header, blank lines, link and hints; else one text line (two for quadrants)
}

const quadNote = "compact QR — hold phone a bit further away"

// qrSize returns the module count (incl. quiet zone) of the QR for text.
func qrSize(text string) int {
	q, err := qrcode.New(text, qrcode.Low)
	if err != nil {
		return 0
	}
	q.DisableBorder = true
	return len(q.Bitmap()) + 2*quietZone
}

// chooseLayout picks the richest rendering that fits w x h cells for a QR of
// n modules per side and a link of linkLen characters.
func chooseLayout(w, h, n, linkLen int) layout {
	if n == 0 {
		return layout{kind: qrNone}
	}
	linkLines := (linkLen + max(w-2, 1) - 1) / max(w-2, 1)
	fullText := 2 + 1 + 1 + 2 + linkLines + 2 // head, gaps, status+code, link, hint+esc
	if n <= w && (n+1)/2+fullText <= h {
		return layout{qrHalf, true}
	}
	if n <= w && (n+1)/2+1 <= h {
		return layout{qrHalf, false}
	}
	if (n+1)/2 <= w && (n+1)/2+fullText+1 <= h {
		return layout{qrQuad, true}
	}
	if (n+1)/2 <= w && (n+1)/2+2 <= h {
		return layout{qrQuad, false}
	}
	return layout{kind: qrNone}
}

func wrap(s string, w int) string {
	r := []rune(s)
	var out []string
	for len(r) > w && w > 0 {
		out = append(out, string(r[:w]))
		r = r[w:]
	}
	return strings.Join(append(out, string(r)), "\n")
}

func pairScreen(t server.PairingToken, w, h int, left string) string {
	n := qrSize(t.URI)
	lay := chooseLayout(w, h, n, len(t.URI))
	status := "Waiting for phone…  " + titleStyle.Render(left)
	code := "Code  " + codeStyle.Render(t.Code)
	if lay.kind == qrNone {
		return strings.Join([]string{
			titleStyle.Render("Pair a phone with Whistle"),
			t.Name + dimStyle.Render("  ·  ") + t.Addr,
			"",
			warnStyle.Render("Enlarge the window to see the QR code,"),
			warnStyle.Render("or enter the code in Whistle"),
			warnStyle.Render("(Machines → Pair → enter code):"),
			"",
			"Address  " + codeStyle.Render(t.Addr),
			code,
			"",
			"Link  " + wrap(t.URI, max(w-7, 20)),
			"",
			status,
			dimStyle.Render("Esc to cancel"),
		}, "\n")
	}
	qr := strings.Join(renderQR(t.URI, lay.kind == qrQuad, true), "\n")
	if !lay.full {
		txt := code + dimStyle.Render("  ·  ") + titleStyle.Render(left)
		if lay.kind == qrQuad {
			return qr + "\n" + txt + "\n" + dimStyle.Render(quadNote)
		}
		return qr + "\n" + txt
	}
	foot := []string{status, code, wrap(dimStyle.Render(t.URI), max(w-2, 20))}
	if lay.kind == qrQuad {
		foot = append(foot, dimStyle.Render(quadNote))
	}
	foot = append(foot, dimStyle.Render("In Whistle: Machines → Pair a machine → scan  ·  Esc to cancel"))
	return titleStyle.Render("Pair a phone with Whistle") + "\n" + t.Name + dimStyle.Render("  ·  ") + t.Addr +
		"\n\n" + qr + "\n\n" + strings.Join(foot, "\n")
}

const quietZone = 2

// quadrant glyphs indexed by mask UL=1 UR=2 LL=4 LR=8 (set = light module).
var quadGlyph = [16]rune{' ', '▘', '▝', '▀', '▖', '▌', '▞', '▛', '▗', '▚', '▐', '▜', '▄', '▙', '▟', '█'}

// renderQR draws text as a QR code (error correction L, quiet zone 2) in
// half blocks (1x2 modules per cell) or quadrant blocks (2x2). Glyphs are
// drawn where the module is light, so with a black background and white
// foreground (styled) the code is black-on-white on any theme. Cells beyond
// the symbol count as light.
func renderQR(text string, quad, styled bool) []string {
	q, err := qrcode.New(text, qrcode.Low)
	if err != nil {
		return nil
	}
	q.DisableBorder = true
	bm := q.Bitmap()
	n := len(bm) + 2*quietZone
	light := func(x, y int) bool {
		x, y = x-quietZone, y-quietZone
		return !(y >= 0 && y < len(bm) && x >= 0 && x < len(bm) && bm[y][x])
	}
	var lines []string
	emit := func(s string) {
		if styled {
			s = qrStyle.Render(s)
		}
		lines = append(lines, s)
	}
	if quad {
		for y := 0; y < n; y += 2 {
			var b strings.Builder
			for x := 0; x < n; x += 2 {
				m := 0
				for i, d := range [4][2]int{{0, 0}, {1, 0}, {0, 1}, {1, 1}} {
					if light(x+d[0], y+d[1]) {
						m |= 1 << i
					}
				}
				b.WriteRune(quadGlyph[m])
			}
			emit(b.String())
		}
		return lines
	}
	for y := 0; y < n; y += 2 {
		var b strings.Builder
		for x := 0; x < n; x++ {
			switch top, bot := light(x, y), light(x, y+1); {
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
		emit(b.String())
	}
	return lines
}

func ago(now time.Time, unix int64) string {
	if unix == 0 {
		return "never"
	}
	d := now.Sub(time.Unix(unix, 0))
	switch {
	case d < time.Minute:
		return "just now"
	case d < time.Hour:
		return fmt.Sprintf("%d min ago", int(d/time.Minute))
	case d < 48*time.Hour:
		return fmt.Sprintf("%d h ago", int(d/time.Hour))
	}
	return time.Unix(unix, 0).Format("2006-01-02")
}

func trunc(s string, n int) string {
	r := []rune(s)
	if len(r) <= n {
		return s
	}
	return string(r[:n-1]) + "…"
}

func (m Model) devicesView() string {
	lines := []string{titleStyle.Render("Whistle · paired devices") + dimStyle.Render("  (session "+m.session+")"), ""}
	if m.devErr != "" {
		return m.notRunning(m.devErr)
	}
	if len(m.devs) == 0 {
		lines = append(lines, "No paired phones yet.", "")
	}
	now := m.now()
	for i, d := range m.devs {
		mark := dimStyle.Render("○")
		if d.Connections > 0 {
			mark = okStyle.Render("●")
		}
		id := d.ID
		if len(id) > 7 {
			id = id[:7]
		}
		row := fmt.Sprintf("%-20s %-7s  paired %s  seen %-10s",
			trunc(d.Name, 20), id, time.Unix(d.CreatedAt, 0).Format("2006-01-02"), ago(now, d.LastSeen))
		if i == m.sel {
			row = selStyle.Render(row)
		}
		lines = append(lines, mark+" "+row)
	}
	if len(m.devs) > 0 {
		lines = append(lines, "")
	}
	if m.removed > 0 {
		lines = append(lines, dimStyle.Render(fmt.Sprintf("%d unpaired device(s) not shown", m.removed)))
	}
	switch {
	case m.confirm && m.sel < len(m.devs):
		lines = append(lines, warnStyle.Render("Unpair "+m.devs[m.sel].Name+"? y/n"))
	case m.flash != "":
		lines = append(lines, m.flash)
	default:
		lines = append(lines, "")
	}
	lines = append(lines, dimStyle.Render("● connected   ↑/↓ select · u unpair · p pair a phone · Esc close"))
	return strings.Join(lines, "\n")
}

// Run shows the popup until the user closes it. However it ends (Esc,
// SIGTERM, SIGHUP when herdr closes the pane), a code still on screen is
// burned.
func Run(ctl Control, session string, start Mode) error {
	p := tea.NewProgram(New(ctl, session, start), tea.WithAltScreen())
	hup := make(chan os.Signal, 1)
	signal.Notify(hup, syscall.SIGHUP)
	go func() {
		if _, ok := <-hup; ok {
			p.Quit()
		}
	}()
	final, err := p.Run()
	signal.Stop(hup)
	close(hup)
	if m, ok := final.(Model); ok {
		m.cancelPair()
	}
	return err
}
