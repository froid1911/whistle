package server

import (
	"errors"
	"fmt"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"time"
)

// sanitize keeps [A-Za-z0-9._-], maps everything else to '_', and never
// yields a path element that is empty or dot-only.
func sanitize(s string, max int) string {
	var b strings.Builder
	for _, r := range s {
		switch {
		case r >= 'a' && r <= 'z', r >= 'A' && r <= 'Z', r >= '0' && r <= '9', r == '.', r == '-', r == '_':
			b.WriteRune(r)
		default:
			b.WriteByte('_')
		}
	}
	out := b.String()
	if len(out) > max {
		out = out[len(out)-max:] // keep the extension
	}
	if strings.Trim(out, ".") == "" {
		out = "file"
	}
	return out
}

func (s *Server) serveUpload(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "POST only", http.StatusMethodNotAllowed)
		return
	}
	pane := r.URL.Query().Get("pane_id")
	if pane == "" {
		http.Error(w, "pane_id required", http.StatusBadRequest)
		return
	}
	// Multipart framing overhead on top of the file itself.
	r.Body = http.MaxBytesReader(w, r.Body, s.cfg.MaxUpload+64<<10)
	mr, err := r.MultipartReader()
	if err != nil {
		http.Error(w, err.Error(), http.StatusBadRequest)
		return
	}
	for {
		part, err := mr.NextPart()
		if err != nil {
			status := http.StatusBadRequest
			var mbe *http.MaxBytesError
			if errors.As(err, &mbe) {
				status = http.StatusRequestEntityTooLarge
			}
			http.Error(w, "no file field", status)
			return
		}
		if part.FormName() != "file" {
			part.Close()
			continue
		}
		path, status, err := s.saveUpload(pane, filepath.Base(part.FileName()), part)
		if err != nil {
			s.log.Warn("upload failed", "pane", pane, "err", err)
			http.Error(w, err.Error(), status)
			return
		}
		writeJSON(w, http.StatusOK, map[string]string{"path": path})
		return
	}
}

func (s *Server) saveUpload(pane, name string, src io.Reader) (string, int, error) {
	dir := filepath.Join(s.cfg.UploadDir, sanitize(pane, 64))
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return "", http.StatusInternalServerError, err
	}
	path := filepath.Join(dir, fmt.Sprintf("%d-%s", time.Now().UnixMilli(), sanitize(name, 128)))
	f, err := os.OpenFile(path, os.O_WRONLY|os.O_CREATE|os.O_EXCL, 0o600)
	if err != nil {
		return "", http.StatusInternalServerError, err
	}
	n, err := io.Copy(f, io.LimitReader(src, s.cfg.MaxUpload+1))
	if cerr := f.Close(); err == nil {
		err = cerr
	}
	status := http.StatusInternalServerError
	var mbe *http.MaxBytesError
	switch {
	case errors.As(err, &mbe), err == nil && n > s.cfg.MaxUpload:
		status, err = http.StatusRequestEntityTooLarge, fmt.Errorf("file exceeds %d bytes", s.cfg.MaxUpload)
	}
	if err != nil {
		os.Remove(path)
		return "", status, err
	}
	return path, http.StatusOK, nil
}
