package store

import (
	"crypto/rand"
	"database/sql"
	"encoding/hex"
	"errors"
	"time"
)

// Device is a paired phone. Times are unix seconds; 0 = never/not revoked.
type Device struct {
	ID        string `json:"id"`
	Name      string `json:"name"`
	PublicKey string `json:"public_key"` // SPKI, standard base64
	CreatedAt int64  `json:"created_at"`
	LastSeen  int64  `json:"last_seen"`
	RevokedAt int64  `json:"revoked_at"`
}

// ErrNoDevice: no device with that id.
var ErrNoDevice = errors.New("no such device")

// ErrKeyInUse: a device (revoked ones included) already has this public key.
var ErrKeyInUse = errors.New("public key already paired")

// AddDevice stores a new device and returns it with its generated id.
func (s *Store) AddDevice(name, publicKey string, now time.Time) (Device, error) {
	b := make([]byte, 6)
	if _, err := rand.Read(b); err != nil {
		return Device{}, err
	}
	d := Device{ID: "d" + hex.EncodeToString(b), Name: name, PublicKey: publicKey, CreatedAt: now.Unix()}
	s.mu.Lock()
	defer s.mu.Unlock()
	var n int
	if err := s.db.QueryRow(`SELECT COUNT(*) FROM devices WHERE public_key=?`, publicKey).Scan(&n); err != nil {
		return Device{}, err
	}
	if n > 0 {
		return Device{}, ErrKeyInUse
	}
	_, err := s.db.Exec(`INSERT INTO devices(id,name,public_key,created_at) VALUES(?,?,?,?)`,
		d.ID, d.Name, d.PublicKey, d.CreatedAt)
	return d, err
}

// KeyInUse reports whether a device (revoked ones included) has publicKey.
func (s *Store) KeyInUse(publicKey string) (bool, error) {
	var n int
	err := s.db.QueryRow(`SELECT COUNT(*) FROM devices WHERE public_key=?`, publicKey).Scan(&n)
	return n > 0, err
}

// Device looks up one device (revoked ones included).
func (s *Store) Device(id string) (Device, error) {
	var d Device
	err := s.db.QueryRow(`SELECT id,name,public_key,created_at,last_seen,revoked_at FROM devices WHERE id=?`, id).
		Scan(&d.ID, &d.Name, &d.PublicKey, &d.CreatedAt, &d.LastSeen, &d.RevokedAt)
	if errors.Is(err, sql.ErrNoRows) {
		return d, ErrNoDevice
	}
	return d, err
}

// Devices lists all devices, oldest first.
func (s *Store) Devices() ([]Device, error) {
	rows, err := s.db.Query(`SELECT id,name,public_key,created_at,last_seen,revoked_at FROM devices ORDER BY created_at, id`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []Device
	for rows.Next() {
		var d Device
		if err := rows.Scan(&d.ID, &d.Name, &d.PublicKey, &d.CreatedAt, &d.LastSeen, &d.RevokedAt); err != nil {
			return nil, err
		}
		out = append(out, d)
	}
	return out, rows.Err()
}

// RevokeDevice marks a device revoked (kept for the listing).
func (s *Store) RevokeDevice(id string, now time.Time) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	res, err := s.db.Exec(`UPDATE devices SET revoked_at=? WHERE id=? AND revoked_at=0`, now.Unix(), id)
	if err != nil {
		return err
	}
	if n, _ := res.RowsAffected(); n == 0 {
		if _, err := s.Device(id); err != nil {
			return err
		}
	}
	return nil
}

// TouchDevice records that the device was just seen.
func (s *Store) TouchDevice(id string, now time.Time) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	_, err := s.db.Exec(`UPDATE devices SET last_seen=? WHERE id=?`, now.Unix(), id)
	return err
}
