package transcript

import (
	"os"
	"path/filepath"
	"testing"
	"time"

	"github.com/froid1911/whistle/bridge/internal/herdr"
)

func TestLocateClaude(t *testing.T) {
	home := t.TempDir()
	d := filepath.Join(home, ".claude", "projects", "-proj")
	os.MkdirAll(d, 0o755)
	want := filepath.Join(d, "abc.jsonl")
	os.WriteFile(want, nil, 0o644)
	p, pr, ok := Locate(home, herdr.AgentSession{Agent: "claude", Kind: "id", Value: "abc"})
	if !ok || p != want {
		t.Fatalf("%s %v", p, ok)
	}
	if _, isC := pr.(Claude); !isC {
		t.Fatalf("parser %T", pr)
	}
}

func TestLocateOmp(t *testing.T) {
	f := filepath.Join(t.TempDir(), "s.jsonl")
	os.WriteFile(f, nil, 0o644)
	p, pr, ok := Locate("/nonexistent", herdr.AgentSession{Agent: "omp", Kind: "path", Value: f})
	if !ok || p != f {
		t.Fatal(p, ok)
	}
	if _, isO := pr.(Omp); !isO {
		t.Fatalf("parser %T", pr)
	}
}

func TestLocateMissing(t *testing.T) {
	home := t.TempDir()
	if _, _, ok := Locate(home, herdr.AgentSession{Kind: "id", Value: "nope"}); ok {
		t.Fatal("ok")
	}
	if _, _, ok := Locate(home, herdr.AgentSession{Kind: "path", Value: home + "/nope"}); ok {
		t.Fatal("ok")
	}
}

func TestLocateRejectsGlobMetachars(t *testing.T) {
	home := t.TempDir()
	d := filepath.Join(home, ".claude", "projects", "-p")
	os.MkdirAll(d, 0o755)
	os.WriteFile(filepath.Join(d, "abc.jsonl"), nil, 0o644)
	for _, v := range []string{"*", "a*", "[a]bc", "?bc", `a\`, "../x", "a/b", ".."} {
		if _, _, ok := Locate(home, herdr.AgentSession{Kind: "id", Value: v}); ok {
			t.Errorf("accepted %q", v)
		}
	}
}

func TestLocatePicksNewestOnDuplicate(t *testing.T) {
	home := t.TempDir()
	var want string
	for i, n := range []string{"-a", "-b"} {
		d := filepath.Join(home, ".claude", "projects", n)
		os.MkdirAll(d, 0o755)
		f := filepath.Join(d, "abc.jsonl")
		os.WriteFile(f, nil, 0o644)
		os.Chtimes(f, time.Unix(1000+int64(i)*100, 0), time.Unix(1000+int64(i)*100, 0))
		want = f
	}
	if p, _, ok := Locate(home, herdr.AgentSession{Kind: "id", Value: "abc"}); !ok || p != want {
		t.Fatal(p, ok)
	}
}
