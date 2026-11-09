package transcript

import (
	"context"
	"os"
	"path/filepath"
	"sync"
	"testing"
	"time"

	"github.com/froid1911/whistle/bridge/internal/store"
)

type collector struct {
	mu   sync.Mutex
	msgs []store.Message
	off  int64
}

func (c *collector) emit(m []store.Message, off int64) {
	c.mu.Lock()
	c.msgs = append(c.msgs, m...)
	c.off = off
	c.mu.Unlock()
}
func (c *collector) snap() (int, int64) {
	c.mu.Lock()
	defer c.mu.Unlock()
	return len(c.msgs), c.off
}

func startTail(t *testing.T, path string, off int64) (*collector, func()) {
	t.Helper()
	c := &collector{}
	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan struct{})
	go func() { Tail(ctx, path, off, Omp{}, "k", c.emit); close(done) }()
	return c, func() { cancel(); <-done }
}

func waitFor(t *testing.T, c *collector, n int, off int64) {
	t.Helper()
	for i := 0; i < 100; i++ {
		if gn, go_ := c.snap(); gn == n && go_ == off {
			return
		}
		time.Sleep(50 * time.Millisecond)
	}
	gn, go_ := c.snap()
	t.Fatalf("want n=%d off=%d got n=%d off=%d", n, off, gn, go_)
}

const testOmpLine = `{"type":"message","id":"o1","timestamp":"t","message":{"role":"user","content":[{"type":"text","text":"hi"}]}}` + "\n"

func TestTailerPartialLine(t *testing.T) {
	p := filepath.Join(t.TempDir(), "s.jsonl")
	os.WriteFile(p, []byte(testOmpLine[:40]), 0o644)
	c, stop := startTail(t, p, 0)
	defer stop()
	time.Sleep(700 * time.Millisecond)
	if n, _ := c.snap(); n != 0 {
		t.Fatal("emitted partial")
	}
	f, _ := os.OpenFile(p, os.O_APPEND|os.O_WRONLY, 0)
	f.WriteString(testOmpLine[40:])
	f.Close()
	waitFor(t, c, 1, int64(len(testOmpLine)))
}

func TestTailerResumesFromOffset(t *testing.T) {
	p := filepath.Join(t.TempDir(), "s.jsonl")
	two := testOmpLine + testOmpLine
	os.WriteFile(p, []byte(two), 0o644)
	c, stop := startTail(t, p, int64(len(testOmpLine)))
	defer stop()
	waitFor(t, c, 1, int64(len(two)))
}

func TestTailerTruncatedFileRestarts(t *testing.T) {
	p := filepath.Join(t.TempDir(), "s.jsonl")
	os.WriteFile(p, []byte(testOmpLine+testOmpLine), 0o644)
	c, stop := startTail(t, p, 0)
	defer stop()
	waitFor(t, c, 2, int64(2*len(testOmpLine)))
	os.WriteFile(p, []byte(testOmpLine), 0o644)
	waitFor(t, c, 3, int64(len(testOmpLine)))
}

func TestTailerDetectsReplaceWithLargerFile(t *testing.T) {
	dir := t.TempDir()
	p := filepath.Join(dir, "s.jsonl")
	os.WriteFile(p, []byte(testOmpLine), 0o644)
	c, stop := startTail(t, p, 0)
	defer stop()
	waitFor(t, c, 1, int64(len(testOmpLine)))
	b := filepath.Join(dir, "b.tmp")
	big := testOmpLine + testOmpLine + testOmpLine
	os.WriteFile(b, []byte(big), 0o644)
	os.Rename(b, p)
	waitFor(t, c, 4, int64(len(big)))
}
