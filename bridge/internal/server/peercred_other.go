//go:build !linux

package server

import (
	"net"
	"os"
	"syscall"
)

// peerUID: no SO_PEERCRED here; the 0700 directory and 0600 socket are the
// guard, so every peer counts as us.
func peerUID(net.Conn) (int, error) { return os.Getuid(), nil }

func fileOwner(fi os.FileInfo) (int, bool) {
	st, ok := fi.Sys().(*syscall.Stat_t)
	if !ok {
		return 0, false
	}
	return int(st.Uid), true
}
