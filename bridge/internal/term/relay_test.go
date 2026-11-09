package term

import (
	"context"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func fake(t *testing.T) (Factory, string, string) {
	d := t.TempDir()
	args, in := filepath.Join(d, "args"), filepath.Join(d, "stdin")
	t.Setenv("FAKE_ARGS", args)
	t.Setenv("FAKE_STDIN", in)
	return Factory{HerdrBin: "testdata/fakeherdr-term.sh", Session: "s1"}, args, in
}

func waitFile(t *testing.T, p, want string) string {
	t.Helper()
	for i := 0; i < 200; i++ {
		b, _ := os.ReadFile(p)
		if strings.Contains(string(b), want) {
			return string(b)
		}
		time.Sleep(10 * time.Millisecond)
	}
	b, _ := os.ReadFile(p)
	t.Fatalf("%s lacks %q: %q", p, want, b)
	return ""
}

func TestRelayParsesFrames(t *testing.T) {
	f, args, _ := fake(t)
	s, err := f.Open(context.Background(), "p1", "observe", 80, 24)
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()
	a := <-s.Frames()
	b := <-s.Frames()
	if a.Seq != 1 || a.Width != 80 || !a.Full || string(a.Bytes) != "hello" || string(b.Bytes) != "world" {
		t.Fatalf("%+v %+v", a, b)
	}
	got := waitFile(t, args, "--rows 24")
	if want := "--session s1 terminal session observe p1 --cols 80 --rows 24"; strings.TrimSpace(got) != want {
		t.Fatalf("args %q want %q", got, want)
	}
}

func TestRelayInputWritesControlJSON(t *testing.T) {
	f, _, in := fake(t)
	s, err := f.Open(context.Background(), "p1", "control", 0, 0)
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()
	s.Input("ls\r", nil)
	s.Input("", []byte("hi"))
	s.Resize(100, 30)
	got := waitFile(t, in, "terminal.resize")
	for _, w := range []string{`{"type":"terminal.input","text":"ls\r"}`, `{"type":"terminal.input","bytes":"aGk="}`, `{"type":"terminal.resize","cols":100,"rows":30}`} {
		if !strings.Contains(got, w) {
			t.Fatalf("missing %s in %q", w, got)
		}
	}
}

func TestRelayCloseKillsProcess(t *testing.T) {
	f, _, in := fake(t)
	s, _ := f.Open(context.Background(), "p1", "control", 0, 0)
	<-s.Frames()
	if err := s.Close(); err != nil {
		t.Fatal(err)
	}
	waitFile(t, in, "terminal.release")
	select {
	case _, ok := <-s.Frames():
		for ok {
			_, ok = <-s.Frames()
		}
	case <-time.After(3 * time.Second):
		t.Fatal("frames not closed")
	}
	s.Close() // idempotent
}

func TestRelayCtxCancelEndsFrames(t *testing.T) {
	f, _, _ := fake(t)
	ctx, cancel := context.WithCancel(context.Background())
	s, _ := f.Open(ctx, "p1", "observe", 0, 0)
	defer s.Close()
	cancel()
	deadline := time.After(3 * time.Second)
	for {
		select {
		case _, ok := <-s.Frames():
			if !ok {
				return
			}
		case <-deadline:
			t.Fatal("not closed")
		}
	}
}

func TestRelayProcessExitClosesFrames(t *testing.T) {
	f, _, _ := fake(t)
	t.Setenv("FAKE_EXIT", "1")
	s, _ := f.Open(context.Background(), "p1", "observe", 0, 0)
	defer s.Close()
	n := 0
	for range s.Frames() {
		n++
	}
	if n != 2 {
		t.Fatalf("frames %d", n)
	}
}
