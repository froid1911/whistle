package gitops

import (
	"context"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"
)

func grun(t *testing.T, dir string, args ...string) {
	t.Helper()
	cmd := exec.Command("git", append([]string{"-C", dir}, args...)...)
	if out, err := cmd.CombinedOutput(); err != nil {
		t.Fatalf("git %v: %v %s", args, err, out)
	}
}

func write(t *testing.T, dir, name, body string) {
	t.Helper()
	if err := os.WriteFile(filepath.Join(dir, name), []byte(body), 0o644); err != nil {
		t.Fatal(err)
	}
}

func newRepo(t *testing.T) string {
	d := t.TempDir()
	grun(t, d, "init", "-q", "-b", "main")
	grun(t, d, "config", "user.name", "T")
	grun(t, d, "config", "user.email", "t@e.x")
	grun(t, d, "config", "commit.gpgsign", "false")
	write(t, d, "a.txt", "one\n")
	grun(t, d, "add", "-A")
	grun(t, d, "commit", "-q", "-m", "init")
	return d
}

func TestStatusLists(t *testing.T) {
	d := newRepo(t)
	write(t, d, "a.txt", "two\n")
	write(t, d, "new.txt", "x\n")
	st, err := Git{}.Status(context.Background(), d)
	if err != nil || st.Branch != "main" {
		t.Fatalf("%v %+v", err, st)
	}
	got := map[string]string{}
	for _, f := range st.Files {
		got[f.Path] = f.Status
		if f.Staged {
			t.Fatalf("staged: %+v", f)
		}
	}
	if got["a.txt"] != "M" || got["new.txt"] != "?" || len(got) != 2 {
		t.Fatalf("%v", got)
	}
}

func TestStatusStagedAndNoCommits(t *testing.T) {
	d := t.TempDir()
	grun(t, d, "init", "-q", "-b", "main")
	write(t, d, "f", "x\n")
	grun(t, d, "add", "f")
	st, err := Git{}.Status(context.Background(), d)
	if err != nil || len(st.Files) != 1 || st.Files[0].Status != "A" || !st.Files[0].Staged {
		t.Fatalf("%v %+v", err, st)
	}
	df, _, err := Git{}.Diff(context.Background(), d, "")
	if err != nil || !strings.Contains(df, "+x") {
		t.Fatalf("%v %q", err, df)
	}
}

func TestDiffIncludesUntracked(t *testing.T) {
	d := newRepo(t)
	write(t, d, "a.txt", "two\n")
	write(t, d, "new.txt", "hello\n")
	df, tr, err := Git{}.Diff(context.Background(), d, "")
	if err != nil || tr {
		t.Fatal(err, tr)
	}
	for _, w := range []string{"+two", "new file mode", "+hello"} {
		if !strings.Contains(df, w) {
			t.Fatalf("missing %q in %s", w, df)
		}
	}
	df, _, err = Git{}.Diff(context.Background(), d, "new.txt")
	if err != nil || strings.Contains(df, "a.txt") || !strings.Contains(df, "+hello") {
		t.Fatalf("%v %s", err, df)
	}
	for _, bad := range []string{"../x", "/etc/passwd", "a/../../x"} {
		if _, _, err := (Git{}).Diff(context.Background(), d, bad); err == nil {
			t.Fatalf("accepted %q", bad)
		}
	}
}

func TestDiffCaps512KiB(t *testing.T) {
	d := newRepo(t)
	write(t, d, "big.txt", strings.Repeat("0123456789abcdef\n", 60000))
	df, tr, err := Git{}.Diff(context.Background(), d, "")
	if err != nil || !tr || len(df) != 512<<10 {
		t.Fatalf("%v %v %d", err, tr, len(df))
	}
}

func TestCommitAll(t *testing.T) {
	d := newRepo(t)
	write(t, d, "new.txt", "x\n")
	if _, err := (Git{}).Commit(context.Background(), d, "", true); err == nil {
		t.Fatal("empty message accepted")
	}
	sha, err := Git{}.Commit(context.Background(), d, "add new", true)
	if err != nil || len(sha) < 40 {
		t.Fatalf("%v %q", err, sha)
	}
	st, _ := Git{}.Status(context.Background(), d)
	if len(st.Files) != 0 {
		t.Fatalf("%+v", st)
	}
}

// T8: user/repo config must not leak ANSI colors or external diff tools
// into the wire diff.
func TestDiffIgnoresColorAndExtDiffConfig(t *testing.T) {
	d := newRepo(t)
	grun(t, d, "config", "color.ui", "always")
	grun(t, d, "config", "color.diff", "always")
	grun(t, d, "config", "diff.external", "/bin/false")
	write(t, d, "a.txt", "two\n")
	write(t, d, "u.txt", "new\n")
	out, _, err := Git{}.Diff(context.Background(), d, "")
	if err != nil {
		t.Fatal(err)
	}
	if strings.Contains(out, "\x1b[") {
		t.Fatalf("ANSI in diff: %q", out)
	}
	if !strings.Contains(out, "+two") || !strings.Contains(out, "+new") {
		t.Fatalf("diff %q", out)
	}
}
