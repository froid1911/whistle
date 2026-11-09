package server

import (
	"bufio"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"os"
	"path/filepath"
	"time"

	"github.com/froid1911/whistle/bridge/internal/store"
)

// Control socket: a unix socket (0600 in a 0700 directory, same-uid peers
// only) through which the pair/devices popups and the `pair`, `devices` and
// `unpair` CLI commands reach the running bridge.
// Pairing tokens live only in the bridge's memory, and unpairing must close
// the device's open WebSockets, so these cannot just touch the database.
// One JSON request line, one JSON response line per connection.

// ControlRequest is a control-socket request.
type ControlRequest struct {
	Op    string `json:"op"` // "pair" | "pair_status" | "cancel" | "devices" | "unpair"
	ID    string `json:"id,omitempty"`
	Token string `json:"token,omitempty"` // pair_status, cancel
}

// ControlDevice is a device in the "devices" listing.
type ControlDevice struct {
	store.Device
	Connections int `json:"connections"` // open WebSockets right now
}

// ControlResponse is a control-socket response.
type ControlResponse struct {
	Error   string          `json:"error,omitempty"`
	Pairing *PairingToken   `json:"pairing,omitempty"`
	State   string          `json:"state,omitempty"`  // pair_status: pending|paired|gone
	Device  *store.Device   `json:"device,omitempty"` // pair_status: the paired device
	Devices []ControlDevice `json:"devices,omitempty"`
	OK      bool            `json:"ok,omitempty"`
}

// ServeControl serves the control socket at path until ctx ends. The
// socket is created in a fresh private directory and renamed into place, so
// it is never reachable with looser permissions; its directory must be ours
// (it is tightened to 0700); peers must run as our uid.
func (s *Server) ServeControl(ctx context.Context, path string) error {
	dir := filepath.Dir(path)
	if err := privateDir(dir); err != nil {
		return err
	}
	tmpDir, err := os.MkdirTemp(dir, ".ctl-")
	if err != nil {
		return err
	}
	defer os.RemoveAll(tmpDir)
	tmp := filepath.Join(tmpDir, "s")
	ln, err := net.Listen("unix", tmp)
	if err != nil {
		return err
	}
	ln.(*net.UnixListener).SetUnlinkOnClose(false)
	if err := os.Chmod(tmp, 0o600); err != nil {
		ln.Close()
		return err
	}
	// Replaces a stale socket of a previous run (we hold the session lock).
	if err := os.Rename(tmp, path); err != nil {
		ln.Close()
		return err
	}
	os.Remove(tmpDir)
	mine, _ := os.Lstat(path)
	defer func() { // unless a newer bridge already replaced it
		if st, err := os.Lstat(path); err == nil && mine != nil && os.SameFile(st, mine) {
			os.Remove(path)
		}
	}()
	go func() {
		<-ctx.Done()
		ln.Close()
	}()
	uid := os.Getuid()
	for {
		c, err := ln.Accept()
		if err != nil {
			if ctx.Err() != nil {
				return nil
			}
			return err
		}
		if peer, err := peerUID(c); err != nil || peer != uid {
			s.log.Warn("control socket: refused peer", "uid", peer, "err", err)
			c.Close()
			continue
		}
		go s.handleControl(c)
	}
}

// privateDir makes sure dir exists, is a directory we own, and is 0700.
func privateDir(dir string) error {
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return err
	}
	st, err := os.Lstat(dir)
	if err != nil {
		return err
	}
	if !st.IsDir() {
		return fmt.Errorf("%s is not a directory", dir)
	}
	if owner, ok := fileOwner(st); ok && owner != os.Getuid() {
		return fmt.Errorf("%s is owned by uid %d, not us", dir, owner)
	}
	if st.Mode().Perm() != 0o700 {
		return os.Chmod(dir, 0o700)
	}
	return nil
}

func (s *Server) handleControl(c net.Conn) {
	defer c.Close()
	_ = c.SetDeadline(time.Now().Add(10 * time.Second))
	line, err := bufio.NewReader(c).ReadBytes('\n')
	if err != nil && len(line) == 0 {
		return
	}
	var req ControlRequest
	var resp ControlResponse
	if err := json.Unmarshal(line, &req); err != nil {
		resp.Error = "bad request"
	} else {
		resp = s.control(req)
	}
	b, _ := json.Marshal(resp)
	_, _ = c.Write(append(b, '\n'))
}

func (s *Server) control(req ControlRequest) ControlResponse {
	switch req.Op {
	case "pair":
		p, err := s.IssuePairingToken()
		if err != nil {
			return ControlResponse{Error: err.Error()}
		}
		return ControlResponse{Pairing: &p}
	case "pair_status":
		st, d := s.PairingStatus(req.Token)
		return ControlResponse{State: st, Device: d, OK: true}
	case "cancel":
		s.CancelPairing(req.Token)
		return ControlResponse{OK: true}
	case "devices":
		ds, err := s.deps.Store.Devices()
		if err != nil {
			return ControlResponse{Error: err.Error()}
		}
		conns := s.connections()
		out := make([]ControlDevice, len(ds))
		for i, d := range ds {
			d.PublicKey = "" // not needed by the listing
			out[i] = ControlDevice{Device: d, Connections: conns[d.ID]}
		}
		return ControlResponse{Devices: out, OK: true}
	case "unpair":
		if err := s.RevokeDevice(req.ID); err != nil {
			if errors.Is(err, store.ErrNoDevice) {
				return ControlResponse{Error: "no device " + req.ID}
			}
			return ControlResponse{Error: err.Error()}
		}
		return ControlResponse{OK: true}
	}
	return ControlResponse{Error: "unknown op " + req.Op}
}

// CallControl sends one request to a bridge's control socket.
func CallControl(path string, req ControlRequest) (ControlResponse, error) {
	var resp ControlResponse
	c, err := net.DialTimeout("unix", path, 3*time.Second)
	if err != nil {
		return resp, err
	}
	defer c.Close()
	_ = c.SetDeadline(time.Now().Add(10 * time.Second))
	b, _ := json.Marshal(req)
	if _, err := c.Write(append(b, '\n')); err != nil {
		return resp, err
	}
	line, err := bufio.NewReader(c).ReadBytes('\n')
	if err != nil && len(line) == 0 {
		return resp, err
	}
	if err := json.Unmarshal(line, &resp); err != nil {
		return resp, err
	}
	if resp.Error != "" {
		return resp, errors.New(resp.Error)
	}
	return resp, nil
}
