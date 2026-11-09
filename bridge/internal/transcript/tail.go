package transcript

import (
	"bytes"
	"context"
	"io"
	"os"
	"path/filepath"
	"time"

	"github.com/fsnotify/fsnotify"

	"github.com/froid1911/whistle/bridge/internal/store"
)

const pollInterval = 500 * time.Millisecond

// Tail emits messages parsed from complete lines of path, starting at offset.
// emit's newOffset is the byte offset just past the last complete line
// consumed. It returns when ctx is cancelled.
func Tail(ctx context.Context, path string, offset int64, p Parser, sessionKey string, emit func(msgs []store.Message, newOffset int64)) error {
	w, err := fsnotify.NewWatcher()
	if err != nil {
		return err
	}
	defer w.Close()
	// Watch the directory so replacement/rotation of the file is seen.
	if err := w.Add(filepath.Dir(path)); err != nil {
		return err
	}
	base := filepath.Base(path)
	tick := time.NewTicker(pollInterval)
	defer tick.Stop()

	var ident os.FileInfo

	drain := func() {
		f, err := os.Open(path)
		if err != nil {
			return
		}
		defer f.Close()
		st, err := f.Stat()
		if err != nil {
			return
		}
		if ident != nil && !os.SameFile(ident, st) {
			offset = 0
		}
		ident = st
		if st.Size() < offset {
			offset = 0
		}
		if st.Size() == offset {
			return
		}
		if _, err := f.Seek(offset, io.SeekStart); err != nil {
			return
		}
		data, err := io.ReadAll(f)
		if err != nil {
			return
		}
		last := bytes.LastIndexByte(data, '\n')
		if last < 0 {
			return
		}
		var msgs []store.Message
		for _, line := range bytes.Split(data[:last], []byte{'\n'}) {
			if len(bytes.TrimSpace(line)) == 0 {
				continue
			}
			if ms, err := p.ParseLine(sessionKey, line); err == nil {
				msgs = append(msgs, ms...)
			}
		}
		offset += int64(last + 1)
		emit(msgs, offset)
	}

	drain()
	for {
		select {
		case <-ctx.Done():
			return nil
		case ev := <-w.Events:
			if filepath.Base(ev.Name) == base {
				drain()
			}
		case <-w.Errors:
		case <-tick.C:
			drain()
		}
	}
}
