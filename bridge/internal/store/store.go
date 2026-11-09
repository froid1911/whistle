// Package store is the bridge's durable change log and message store.
//
// Every change gets a strictly increasing seq; clients resume with "everything
// after seq N" via Since. All writes are serialized by one mutex, so seq
// assignment and subscriber fan-out happen in commit order.
package store

import (
	"crypto/rand"
	"database/sql"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"sync"

	_ "modernc.org/sqlite"
)

// Message is the wire Message (docs/reference/wire-protocol.md).
type Message struct {
	ID         string          `json:"id"`
	Seq        int64           `json:"seq"` // first-insert change-log seq; assigned by the store
	SessionKey string          `json:"session_key"`
	TS         string          `json:"ts"`
	Role       string          `json:"role"`
	Text       string          `json:"text"`
	ToolName   string          `json:"tool_name,omitempty"`
	ToolCallID string          `json:"tool_call_id,omitempty"`
	Input      json.RawMessage `json:"input,omitempty"`
	IsError    bool            `json:"is_error,omitempty"`
	Truncated  bool            `json:"truncated,omitempty"`
}

// Entry is one change-log row. Payload is the wire change object minus t/seq.
type Entry struct {
	Seq     int64
	Kind    string
	Payload json.RawMessage
}

// subBuffer is the per-subscriber channel capacity.
const subBuffer = 256

type Store struct {
	db       *sql.DB
	serverID string
	openHead int64

	mu   sync.Mutex // serializes writes and fan-out
	subs map[*sub]struct{}
}

type sub struct {
	ch     chan Entry
	closed bool // guarded by Store.mu
}

const schema = `
CREATE TABLE IF NOT EXISTS meta(k TEXT PRIMARY KEY, v TEXT NOT NULL);
CREATE TABLE IF NOT EXISTS log(seq INTEGER PRIMARY KEY AUTOINCREMENT, kind TEXT NOT NULL, payload BLOB NOT NULL);
CREATE TABLE IF NOT EXISTS messages(
  session_key TEXT NOT NULL, id TEXT NOT NULL, seq INTEGER NOT NULL, ts TEXT NOT NULL, json BLOB NOT NULL,
  PRIMARY KEY(session_key, id));
CREATE INDEX IF NOT EXISTS messages_seq ON messages(session_key, seq);
CREATE TABLE IF NOT EXISTS cursors(session_key TEXT PRIMARY KEY, path TEXT NOT NULL, off INTEGER NOT NULL);
CREATE TABLE IF NOT EXISTS devices(
  id TEXT PRIMARY KEY, name TEXT NOT NULL, public_key TEXT NOT NULL,
  created_at INTEGER NOT NULL, last_seen INTEGER NOT NULL DEFAULT 0, revoked_at INTEGER NOT NULL DEFAULT 0);
`

// Open opens (creating if needed) the database at path in WAL mode.
func Open(path string) (*Store, error) {
	db, err := sql.Open("sqlite", "file:"+path+"?_pragma=journal_mode(WAL)&_pragma=busy_timeout(5000)&_pragma=synchronous(NORMAL)")
	if err != nil {
		return nil, err
	}
	db.SetMaxOpenConns(1)
	if _, err := db.Exec(schema); err != nil {
		db.Close()
		return nil, err
	}
	s := &Store{db: db, subs: map[*sub]struct{}{}}
	err = db.QueryRow(`SELECT v FROM meta WHERE k='server_id'`).Scan(&s.serverID)
	if err == sql.ErrNoRows {
		b := make([]byte, 16)
		if _, err = rand.Read(b); err == nil {
			s.serverID = hex.EncodeToString(b)
			_, err = db.Exec(`INSERT INTO meta(k,v) VALUES('server_id',?)`, s.serverID)
		}
	}
	if err == nil {
		s.openHead, err = s.Head()
	}
	if err != nil {
		db.Close()
		return nil, err
	}
	return s, nil
}

func (s *Store) ServerID() string { return s.serverID }

func (s *Store) Close() error {
	s.mu.Lock()
	for sb := range s.subs {
		s.closeSub(sb)
	}
	s.mu.Unlock()
	return s.db.Close()
}

// closeSub must be called with s.mu held.
func (s *Store) closeSub(sb *sub) {
	if !sb.closed {
		sb.closed = true
		close(sb.ch)
	}
	delete(s.subs, sb)
}

// publish fans out committed entries. Called with s.mu held. A subscriber
// whose buffer is full is closed (never silently skipped): it sees a clean
// prefix and then channel close, and must resume via Since(lastSeq).
func (s *Store) publish(es []Entry) {
	for sb := range s.subs {
		for _, e := range es {
			select {
			case sb.ch <- e:
			default:
				s.closeSub(sb)
			}
			if sb.closed {
				break
			}
		}
	}
}

// Append writes one change-log entry; payload is JSON-marshaled.
func (s *Store) Append(kind string, payload any) (Entry, error) {
	b, err := json.Marshal(payload)
	if err != nil {
		return Entry{}, err
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	res, err := s.db.Exec(`INSERT INTO log(kind,payload) VALUES(?,?)`, kind, b)
	if err != nil {
		return Entry{}, err
	}
	seq, _ := res.LastInsertId()
	e := Entry{Seq: seq, Kind: kind, Payload: b}
	s.publish([]Entry{e})
	return e, nil
}

// AppendMessages upserts msgs for sessionKey atomically and logs one
// "message" entry per new or changed message (byte-identical JSON = no entry).
func (s *Store) AppendMessages(sessionKey string, msgs []Message) ([]Entry, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	tx, err := s.db.Begin()
	if err != nil {
		return nil, err
	}
	defer tx.Rollback()
	var out []Entry
	for _, m := range msgs {
		m.SessionKey = sessionKey
		var old []byte
		var oldSeq int64
		err = tx.QueryRow(`SELECT json, seq FROM messages WHERE session_key=? AND id=?`, sessionKey, m.ID).Scan(&old, &oldSeq)
		if err != nil && err != sql.ErrNoRows {
			return nil, err
		}
		exists := err == nil
		if exists {
			m.Seq = oldSeq
			mj, err := json.Marshal(m)
			if err != nil {
				return nil, err
			}
			if string(old) == string(mj) {
				continue
			}
		}
		// Reserve the log row first: a new message's seq is its own log seq.
		res, err := tx.Exec(`INSERT INTO log(kind,payload) VALUES('message','{}')`)
		if err != nil {
			return nil, err
		}
		seq, _ := res.LastInsertId()
		if !exists {
			m.Seq = seq
		}
		mj, err := json.Marshal(m)
		if err != nil {
			return nil, err
		}
		p, err := json.Marshal(struct {
			Kind       string          `json:"kind"`
			SessionKey string          `json:"session_key"`
			Message    json.RawMessage `json:"message"`
		}{"message", sessionKey, mj})
		if err != nil {
			return nil, err
		}
		if _, err := tx.Exec(`UPDATE log SET payload=? WHERE seq=?`, p, seq); err != nil {
			return nil, err
		}
		if _, err := tx.Exec(`INSERT INTO messages(session_key,id,seq,ts,json) VALUES(?,?,?,?,?)
			ON CONFLICT(session_key,id) DO UPDATE SET ts=excluded.ts, json=excluded.json`,
			sessionKey, m.ID, seq, m.TS, mj); err != nil {
			return nil, err
		}
		out = append(out, Entry{Seq: seq, Kind: "message", Payload: p})
	}
	if err := tx.Commit(); err != nil {
		return nil, err
	}
	s.publish(out)
	return out, nil
}

// Since returns up to limit entries with seq > seq, ascending.
func (s *Store) Since(seq int64, limit int) ([]Entry, error) {
	rows, err := s.db.Query(`SELECT seq,kind,payload FROM log WHERE seq>? ORDER BY seq LIMIT ?`, seq, limit)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []Entry
	for rows.Next() {
		var e Entry
		var p []byte
		if err := rows.Scan(&e.Seq, &e.Kind, &p); err != nil {
			return nil, err
		}
		e.Payload = p
		out = append(out, e)
	}
	return out, rows.Err()
}

// Head is the highest seq ever assigned (0 if none).
func (s *Store) Head() (int64, error) {
	var n sql.NullInt64
	if err := s.db.QueryRow(`SELECT MAX(seq) FROM log`).Scan(&n); err != nil {
		return 0, err
	}
	if n.Valid {
		return n.Int64, nil
	}
	// Log may be fully pruned; AUTOINCREMENT keeps the counter.
	err := s.db.QueryRow(`SELECT seq FROM sqlite_sequence WHERE name='log'`).Scan(&n)
	if err == sql.ErrNoRows {
		return 0, nil
	}
	return n.Int64, err
}

// Oldest is the lowest seq still in the log (0 if empty).
func (s *Store) Oldest() (int64, error) {
	var n sql.NullInt64
	if err := s.db.QueryRow(`SELECT MIN(seq) FROM log`).Scan(&n); err != nil {
		return 0, err
	}
	return n.Int64, nil
}

// Prune keeps only the newest keep log entries. Messages are never pruned.
func (s *Store) Prune(keep int64) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	_, err := s.db.Exec(`DELETE FROM log WHERE seq <= (SELECT MAX(seq) FROM log) - ?`, keep)
	return err
}

// History returns up to limit messages of a session with seq < beforeSeq
// (0 = no bound), newest-last; more reports whether older ones exist.
// Ordering and pagination use the seq assigned when the message was first
// inserted (updates keep their position); beforeSeq is that first-insert seq.
func (s *Store) History(sessionKey string, beforeSeq int64, limit int) ([]Message, bool, error) {
	if beforeSeq <= 0 {
		beforeSeq = 1<<63 - 1
	}
	rows, err := s.db.Query(`SELECT seq, json FROM messages WHERE session_key=? AND seq<? ORDER BY seq DESC LIMIT ?`,
		sessionKey, beforeSeq, limit+1)
	if err != nil {
		return nil, false, err
	}
	defer rows.Close()
	var out []Message
	for rows.Next() {
		var b []byte
		var seq int64
		if err := rows.Scan(&seq, &b); err != nil {
			return nil, false, err
		}
		var m Message
		if err := json.Unmarshal(b, &m); err != nil {
			return nil, false, fmt.Errorf("corrupt message: %w", err)
		}
		m.Seq = seq // authoritative; rows written before Seq existed lack it in json
		out = append(out, m)
	}
	if err := rows.Err(); err != nil {
		return nil, false, err
	}
	more := len(out) > limit
	if more {
		out = out[:limit]
	}
	for i, j := 0, len(out)-1; i < j; i, j = i+1, j-1 {
		out[i], out[j] = out[j], out[i]
	}
	return out, more, nil
}

func (s *Store) SetCursor(sessionKey, path string, offset int64) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	_, err := s.db.Exec(`INSERT INTO cursors(session_key,path,off) VALUES(?,?,?)
		ON CONFLICT(session_key) DO UPDATE SET path=excluded.path, off=excluded.off`, sessionKey, path, offset)
	return err
}

func (s *Store) Cursor(sessionKey string) (string, int64, bool) {
	var p string
	var o int64
	if s.db.QueryRow(`SELECT path,off FROM cursors WHERE session_key=?`, sessionKey).Scan(&p, &o) != nil {
		return "", 0, false
	}
	return p, o, true
}

// Subscribe returns a channel of entries committed after the call, and a
// cancel func (idempotent). If the subscriber falls subBuffer entries behind,
// its channel is CLOSED (after delivering a gap-free prefix) rather than
// dropping entries or blocking writers; the consumer must then resubscribe and
// catch up with Since(lastSeqSeen). Subscribe first, then Since, to avoid gaps.
func (s *Store) Subscribe() (<-chan Entry, func()) {
	sb := &sub{ch: make(chan Entry, subBuffer)}
	s.mu.Lock()
	s.subs[sb] = struct{}{}
	s.mu.Unlock()
	return sb.ch, func() {
		s.mu.Lock()
		s.closeSub(sb)
		s.mu.Unlock()
	}
}

// OpenHead is Head() as of Open: every entry <= OpenHead was logged by an
// earlier bridge process.
func (s *Store) OpenHead() int64 { return s.openHead }

// SaveState stores the bridge's last mapped State (opaque JSON) in the meta
// table (no schema change: existing databases simply have no row yet).
func (s *Store) SaveState(b json.RawMessage) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	_, err := s.db.Exec(`INSERT INTO meta(k,v) VALUES('state',?) ON CONFLICT(k) DO UPDATE SET v=excluded.v`, string(b))
	return err
}

// LoadState returns the State saved by SaveState, if any.
func (s *Store) LoadState() (json.RawMessage, bool) {
	var v string
	if s.db.QueryRow(`SELECT v FROM meta WHERE k='state'`).Scan(&v) != nil {
		return nil, false
	}
	return json.RawMessage(v), true
}
