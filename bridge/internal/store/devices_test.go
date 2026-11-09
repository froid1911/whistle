package store

import (
	"database/sql"
	"path/filepath"
	"testing"
	"time"
)

// A database written by a bridge before device pairing (no devices table)
// opens, keeps its server_id and log, and gains the devices table.
func TestMigrationAddsDevices(t *testing.T) {
	p := filepath.Join(t.TempDir(), "old.db")
	db, err := sql.Open("sqlite", "file:"+p)
	if err != nil {
		t.Fatal(err)
	}
	for _, q := range []string{
		`CREATE TABLE meta(k TEXT PRIMARY KEY, v TEXT NOT NULL)`,
		`CREATE TABLE log(seq INTEGER PRIMARY KEY AUTOINCREMENT, kind TEXT NOT NULL, payload BLOB NOT NULL)`,
		`INSERT INTO meta(k,v) VALUES('server_id','oldserver')`,
		`INSERT INTO log(kind,payload) VALUES('epoch','{}')`,
	} {
		if _, err := db.Exec(q); err != nil {
			t.Fatal(err)
		}
	}
	db.Close()

	s, err := Open(p)
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()
	if s.ServerID() != "oldserver" {
		t.Fatal(s.ServerID())
	}
	if h, _ := s.Head(); h != 1 {
		t.Fatal("log lost", h)
	}
	now := time.Unix(1762689600, 0)
	d, err := s.AddDevice("Pixel 8a", "KEY", now)
	if err != nil {
		t.Fatal(err)
	}
	if err := s.TouchDevice(d.ID, now.Add(time.Minute)); err != nil {
		t.Fatal(err)
	}
	if err := s.RevokeDevice(d.ID, now.Add(2*time.Minute)); err != nil {
		t.Fatal(err)
	}
	if err := s.RevokeDevice(d.ID, now.Add(3*time.Minute)); err != nil {
		t.Fatal("revoking twice is fine:", err)
	}
	if err := s.RevokeDevice("dnope", now); err != ErrNoDevice {
		t.Fatal(err)
	}
	got, _ := s.Device(d.ID)
	if got.LastSeen != now.Unix()+60 || got.RevokedAt != now.Unix()+120 || got.Name != "Pixel 8a" {
		t.Fatalf("%+v", got)
	}
	if ds, _ := s.Devices(); len(ds) != 1 {
		t.Fatal(ds)
	}
	if _, err := s.AddDevice("again", "KEY", now); err != ErrKeyInUse {
		t.Fatal("duplicate key (even of a revoked device):", err)
	}
}
