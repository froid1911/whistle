package transcript

import (
	"os"
	"path/filepath"
	"strings"
	"time"

	"github.com/froid1911/whistle/bridge/internal/herdr"
)

// Locate finds the transcript file and parser for an agent session.
func Locate(home string, s herdr.AgentSession) (string, Parser, bool) {
	switch s.Kind {
	case "id":
		if s.Value == "" || filepath.Base(s.Value) != s.Value {
			return "", nil, false
		}
		if s.Value == "" || s.Value == "." || s.Value == ".." || strings.ContainsAny(s.Value, `/\\`) {
			return "", nil, false
		}
		root := filepath.Join(home, ".claude", "projects")
		dirs, _ := os.ReadDir(root)
		var best string
		var bestT time.Time
		for _, d := range dirs {
			c := filepath.Join(root, d.Name(), s.Value+".jsonl")
			st, err := os.Stat(c)
			if err != nil || !st.Mode().IsRegular() {
				continue
			}
			if best == "" || st.ModTime().After(bestT) {
				best, bestT = c, st.ModTime()
			}
		}
		if best == "" {
			return "", nil, false
		}
		return best, Claude{}, true
	case "path":
		if st, err := os.Stat(s.Value); err == nil && st.Mode().IsRegular() {
			return s.Value, Omp{}, true
		}
	}
	return "", nil, false
}
