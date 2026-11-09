// Package term relays herdr terminal streams (herdr terminal session ...).
package term

import (
	"bufio"
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
	"io"
	"os/exec"
	"strconv"
	"sync"
	"time"

	"github.com/froid1911/whistle/bridge/internal/server"
)

const killAfter = time.Second

var _ server.TermFactory = Factory{}

// Factory starts herdr terminal sessions. It implements server.TermFactory.
type Factory struct {
	HerdrBin string
	Session  string
}

type session struct {
	control bool
	cmd     *exec.Cmd
	frames  chan server.TermFrame

	wmu    sync.Mutex
	stdin  io.WriteCloser
	closed bool

	quit     chan struct{} // closed by Close
	done     chan struct{} // closed once the process is reaped
	quitOnce sync.Once
}

// Open runs herdr in observe or control mode for paneID.
func (f Factory) Open(ctx context.Context, paneID, mode string, cols, rows int) (server.TermSession, error) {
	var args []string
	if f.Session != "" {
		args = append(args, "--session", f.Session)
	}
	if mode != "control" {
		mode = "observe"
	}
	// herdr 0.9.5 requires the target before --cols/--rows ("unknown
	// option" otherwise). In observe mode they only size the rendered frames;
	// in control mode they resize the real pane.
	args = append(args, "terminal", "session", mode, paneID)
	if cols > 0 && rows > 0 {
		args = append(args, "--cols", strconv.Itoa(cols), "--rows", strconv.Itoa(rows))
	}

	cmd := exec.Command(f.HerdrBin, args...)
	stdin, err := cmd.StdinPipe()
	if err != nil {
		return nil, err
	}
	stdout, err := cmd.StdoutPipe()
	if err != nil {
		return nil, err
	}
	if err := cmd.Start(); err != nil {
		return nil, err
	}
	s := &session{
		control: mode == "control", cmd: cmd, stdin: stdin,
		frames: make(chan server.TermFrame, 64),
		quit:   make(chan struct{}), done: make(chan struct{}),
	}
	go s.read(stdout)
	go func() {
		select {
		case <-ctx.Done():
			s.Close()
		case <-s.done:
		}
	}()
	return s, nil
}

type wireFrame struct {
	Type   string `json:"type"`
	Seq    int64  `json:"seq"`
	Width  int    `json:"width"`
	Height int    `json:"height"`
	Full   bool   `json:"full"`
	Bytes  []byte `json:"bytes"` // base64 in JSON
}

func (s *session) read(stdout io.Reader) {
	defer func() {
		// Ensure the process is gone, reap it, then end the stream.
		s.cmd.Process.Kill()
		s.cmd.Wait()
		close(s.done)
		close(s.frames)
	}()
	r := bufio.NewReaderSize(stdout, 64<<10)
	for {
		line, err := r.ReadBytes('\n')
		if len(line) > 0 {
			var w wireFrame
			if json.Unmarshal(line, &w) == nil {
				if w.Type == "terminal.closed" {
					return
				}
				if w.Bytes != nil {
					select {
					case s.frames <- server.TermFrame{Seq: w.Seq, Width: w.Width, Height: w.Height, Full: w.Full, Bytes: w.Bytes}:
					case <-s.quit: // closing: drain until the process exits
					}
				}
			}
		}
		if err != nil {
			return
		}
	}
}

func (s *session) Frames() <-chan server.TermFrame { return s.frames }

func (s *session) send(v any) error {
	b, err := json.Marshal(v)
	if err != nil {
		return err
	}
	s.wmu.Lock()
	defer s.wmu.Unlock()
	if s.closed {
		return errors.New("term: session closed")
	}
	_, err = s.stdin.Write(append(b, '\n'))
	return err
}

type inputMsg struct {
	Type  string `json:"type"`
	Text  string `json:"text,omitempty"`
	Bytes string `json:"bytes,omitempty"`
}

type resizeMsg struct {
	Type string `json:"type"`
	Cols int    `json:"cols"`
	Rows int    `json:"rows"`
}

func (s *session) Input(text string, b []byte) error {
	if !s.control {
		return errors.New("term: observe session is read-only")
	}
	m := inputMsg{Type: "terminal.input", Text: text}
	if len(b) > 0 {
		m = inputMsg{Type: "terminal.input", Bytes: base64.StdEncoding.EncodeToString(b)}
	}
	return s.send(m)
}

func (s *session) Resize(cols, rows int) error {
	if !s.control {
		return errors.New("term: observe session cannot resize")
	}
	return s.send(resizeMsg{"terminal.resize", cols, rows})
}

// Close releases control, closes stdin and kills the process after 1s.
// It waits for the process to be reaped and is idempotent.
func (s *session) Close() error {
	s.quitOnce.Do(func() {
		close(s.quit)
		if s.control {
			s.send(map[string]string{"type": "terminal.release"})
		}
		s.wmu.Lock()
		s.closed = true
		s.stdin.Close()
		s.wmu.Unlock()
	})
	select {
	case <-s.done:
	case <-time.After(killAfter):
		s.cmd.Process.Kill()
		<-s.done
	}
	return nil
}
