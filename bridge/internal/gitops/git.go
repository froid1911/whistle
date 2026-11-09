// Package gitops implements server.GitOps with the git CLI.
package gitops

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"slices"
	"strings"

	"github.com/froid1911/whistle/bridge/internal/server"
)

const maxDiff = 512 << 10

// emptyTree is git's well-known empty tree, used as the base when HEAD is unborn.
const emptyTree = "4b825dc642cb6eb9a060e54bf8d69288fbee4904"

// Git runs git commands; it holds no state.
type Git struct{}

var _ server.GitOps = Git{}

func command(ctx context.Context, path string, args ...string) *exec.Cmd {
	cmd := exec.CommandContext(ctx, "git", append([]string{"-C", path}, args...)...)
	cmd.Env = append(os.Environ(), "GIT_TERMINAL_PROMPT=0", "GIT_OPTIONAL_LOCKS=0")
	return cmd
}

func run(ctx context.Context, path string, args ...string) ([]byte, error) {
	cmd := command(ctx, path, args...)
	var stderr bytes.Buffer
	cmd.Stderr = &stderr
	out, err := cmd.Output()
	if err != nil {
		if ctx.Err() != nil {
			return nil, ctx.Err()
		}
		return nil, fmt.Errorf("git %s: %w: %s", args[0], err, strings.TrimSpace(stderr.String()))
	}
	return out, nil
}

func (Git) Status(ctx context.Context, path string) (server.GitStatus, error) {
	out, err := run(ctx, path, "status", "--porcelain=v2", "--branch", "-z")
	if err != nil {
		return server.GitStatus{}, err
	}
	st := server.GitStatus{Files: []server.GitFile{}}
	recs := strings.Split(string(out), "\x00")
	for i := 0; i < len(recs); i++ {
		r := recs[i]
		switch {
		case strings.HasPrefix(r, "# branch.head "):
			st.Branch = strings.TrimPrefix(r, "# branch.head ")
		case strings.HasPrefix(r, "1 "):
			f := strings.SplitN(r, " ", 9)
			if len(f) == 9 {
				st.Files = append(st.Files, entry(f[1], f[8]))
			}
		case strings.HasPrefix(r, "2 "):
			f := strings.SplitN(r, " ", 10)
			if len(f) == 10 {
				st.Files = append(st.Files, entry(f[1], f[9]))
			}
			i++ // original path follows as its own record
		case strings.HasPrefix(r, "u "):
			f := strings.SplitN(r, " ", 11)
			if len(f) == 11 {
				st.Files = append(st.Files, server.GitFile{Path: f[10], Status: "M"})
			}
		case strings.HasPrefix(r, "? "):
			st.Files = append(st.Files, server.GitFile{Path: r[2:], Status: "?"})
		}
	}
	return st, nil
}

// entry maps an XY pair; the staged column wins when set.
func entry(xy, path string) server.GitFile {
	c, staged := xy[1], false
	if xy[0] != '.' {
		c, staged = xy[0], true
	}
	s := "M"
	switch c {
	case 'A':
		s = "A"
	case 'D':
		s = "D"
	case 'R', 'C':
		s = "R"
	}
	return server.GitFile{Path: path, Status: s, Staged: staged}
}

// capWriter keeps the first max bytes and discards the rest.
type capWriter struct {
	buf       bytes.Buffer
	truncated bool
}

func (w *capWriter) Write(p []byte) (int, error) {
	if room := maxDiff - w.buf.Len(); len(p) <= room {
		w.buf.Write(p)
	} else {
		if room > 0 {
			w.buf.Write(p[:room])
		}
		w.truncated = true
	}
	return len(p), nil
}

// plainDiff keeps user/repo config (color.ui=always, diff.external,
// textconv drivers) out of the wire diff.
var plainDiff = []string{"--no-color", "--no-ext-diff", "--no-textconv"}

func (Git) Diff(ctx context.Context, path, file string) (string, bool, error) {
	var spec []string
	if file != "" {
		if filepath.IsAbs(file) || !filepath.IsLocal(file) {
			return "", false, errors.New("invalid_params: file must be relative and inside the repo")
		}
		spec = []string{"--", file}
	}
	base := "HEAD"
	if _, err := run(ctx, path, "rev-parse", "--verify", "-q", "HEAD"); err != nil {
		if ctx.Err() != nil {
			return "", false, ctx.Err()
		}
		base = emptyTree
	}
	var w capWriter
	if err := stream(ctx, &w, path, append(append([]string{"diff"}, plainDiff...), append([]string{base}, spec...)...)...); err != nil {
		return "", false, err
	}
	un, err := run(ctx, path, append([]string{"ls-files", "--others", "--exclude-standard", "-z"}, spec...)...)
	if err != nil {
		return "", false, err
	}
	for _, f := range strings.Split(string(un), "\x00") {
		if f == "" || w.truncated {
			continue
		}
		// --no-index exits 1 when the files differ, which is the normal case.
		if err := stream(ctx, &w, path, append(append([]string{"diff"}, plainDiff...), "--no-index", "--", os.DevNull, f)...); err != nil {
			return "", false, err
		}
	}
	return w.buf.String(), w.truncated, nil
}

func stream(ctx context.Context, w *capWriter, path string, args ...string) error {
	cmd := command(ctx, path, args...)
	cmd.Stdout = w
	var stderr bytes.Buffer
	cmd.Stderr = &stderr
	err := cmd.Run()
	var ee *exec.ExitError
	if errors.As(err, &ee) && ee.ExitCode() == 1 && slices.Contains(args, "--no-index") {
		return nil
	}
	if err != nil {
		if ctx.Err() != nil {
			return ctx.Err()
		}
		return fmt.Errorf("git %s: %w: %s", args[0], err, strings.TrimSpace(stderr.String()))
	}
	return nil
}

func (Git) Commit(ctx context.Context, path, message string, all bool) (string, error) {
	if strings.TrimSpace(message) == "" {
		return "", errors.New("invalid_params: message required")
	}
	if all {
		if _, err := run(ctx, path, "add", "-A"); err != nil {
			return "", err
		}
	}
	if _, err := run(ctx, path, "commit", "-m", message); err != nil {
		return "", err
	}
	out, err := run(ctx, path, "rev-parse", "HEAD")
	return strings.TrimSpace(string(out)), err
}
