package store

import (
	"database/sql"
	"encoding/json"
	"fmt"
	"path/filepath"
	"testing"
	"time"
)

func open(t *testing.T) (*Store, string) {
	t.Helper()
	p := filepath.Join(t.TempDir(), "b.db")
	s, err := Open(p)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { s.Close() })
	return s, p
}

func msg(id, text string) Message {
	return Message{ID: id, SessionKey: "k1", TS: "2026-01-01T00:00:00Z", Role: "assistant", Text: text}
}

func TestServerIDStable(t *testing.T) {
	s, p := open(t)
	id := s.ServerID()
	if len(id) != 32 {
		t.Fatalf("id %q", id)
	}
	s.Close()
	s2, err := Open(p)
	if err != nil {
		t.Fatal(err)
	}
	defer s2.Close()
	if s2.ServerID() != id {
		t.Fatal("server id changed")
	}
}

func TestSeqMonotonic(t *testing.T) {
	s, _ := open(t)
	var last int64
	for i := 0; i < 5; i++ {
		e, err := s.Append("status", map[string]any{"kind": "status", "n": i})
		if err != nil {
			t.Fatal(err)
		}
		if e.Seq <= last {
			t.Fatalf("seq %d after %d", e.Seq, last)
		}
		last = e.Seq
	}
	if h, err := s.Head(); err != nil || h != last {
		t.Fatalf("head %d %v", h, err)
	}
}

func TestAppendMessagesDedup(t *testing.T) {
	s, _ := open(t)
	es, err := s.AppendMessages("k1", []Message{msg("a", "hi")})
	if err != nil || len(es) != 1 {
		t.Fatal(err, es)
	}
	es, _ = s.AppendMessages("k1", []Message{msg("a", "hi")})
	if len(es) != 0 {
		t.Fatalf("dup produced %d entries", len(es))
	}
	es, _ = s.AppendMessages("k1", []Message{msg("a", "hi!")})
	if len(es) != 1 {
		t.Fatal("update produced no entry")
	}
	var p struct {
		Kind       string  `json:"kind"`
		SessionKey string  `json:"session_key"`
		Message    Message `json:"message"`
	}
	if err := json.Unmarshal(es[0].Payload, &p); err != nil || p.Kind != "message" || p.SessionKey != "k1" || p.Message.Text != "hi!" {
		t.Fatalf("payload %s %v", es[0].Payload, err)
	}
	h, more, err := s.History("k1", 0, 100)
	if err != nil || more || len(h) != 1 || h[0].Text != "hi!" {
		t.Fatal(h, more, err)
	}
}

func TestResumeAfterGap(t *testing.T) {
	s, _ := open(t)
	e0, _ := s.Append("status", map[string]any{"kind": "status"})
	s.AppendMessages("k1", []Message{msg("a", "1"), msg("b", "2"), msg("c", "3")})
	s.Append("status", map[string]any{"kind": "status"})
	got, err := s.Since(e0.Seq, 100)
	if err != nil || len(got) != 4 {
		t.Fatal(err, len(got))
	}
	for i := 1; i < len(got); i++ {
		if got[i].Seq <= got[i-1].Seq {
			t.Fatal("order")
		}
	}
	if got[3].Kind != "status" || got[0].Kind != "message" {
		t.Fatal("kinds")
	}
	lim, _ := s.Since(0, 2)
	if len(lim) != 2 {
		t.Fatal("limit")
	}
}

func TestHistoryPagination(t *testing.T) {
	s, _ := open(t)
	var ms []Message
	for i := 0; i < 250; i++ {
		ms = append(ms, msg(fmt.Sprintf("m%03d", i), fmt.Sprint(i)))
	}
	if _, err := s.AppendMessages("k1", ms); err != nil {
		t.Fatal(err)
	}
	s.AppendMessages("other", []Message{{ID: "x", SessionKey: "other", Role: "user"}})
	h, more, err := s.History("k1", 0, 100)
	if err != nil || !more || len(h) != 100 || h[0].ID != "m150" || h[99].ID != "m249" {
		t.Fatal(err, more, len(h), h[0].ID)
	}
	es, _ := s.Since(0, 1000)
	var firstSeq int64
	for _, e := range es {
		var p struct {
			Message Message `json:"message"`
		}
		json.Unmarshal(e.Payload, &p)
		if p.Message.ID == "m150" {
			firstSeq = e.Seq
		}
	}
	h2, more2, _ := s.History("k1", firstSeq, 100)
	if !more2 || len(h2) != 100 || h2[0].ID != "m050" || h2[99].ID != "m149" {
		t.Fatal(more2, len(h2), h2[0].ID)
	}
	h3, more3, _ := s.History("k1", firstSeq-100, 100)
	if more3 || len(h3) != 50 {
		t.Fatal(more3, len(h3))
	}
}

func TestPruneKeepsMessages(t *testing.T) {
	s, _ := open(t)
	for i := 0; i < 10; i++ {
		s.AppendMessages("k1", []Message{msg(fmt.Sprint("m", i), "x")})
	}
	if err := s.Prune(3); err != nil {
		t.Fatal(err)
	}
	es, _ := s.Since(0, 100)
	head, _ := s.Head()
	old, _ := s.Oldest()
	if len(es) != 3 || es[2].Seq != head || old != es[0].Seq {
		t.Fatal(len(es), old)
	}
	h, _, _ := s.History("k1", 0, 100)
	if len(h) != 10 {
		t.Fatalf("messages pruned: %d", len(h))
	}
}

func TestCursorPersists(t *testing.T) {
	s, p := open(t)
	if _, _, ok := s.Cursor("k1"); ok {
		t.Fatal("unexpected cursor")
	}
	if err := s.SetCursor("k1", "/a/b.jsonl", 42); err != nil {
		t.Fatal(err)
	}
	s.SetCursor("k1", "/a/b.jsonl", 99)
	s.Close()
	s2, _ := Open(p)
	defer s2.Close()
	path, off, ok := s2.Cursor("k1")
	if !ok || path != "/a/b.jsonl" || off != 99 {
		t.Fatal(path, off, ok)
	}
}

func TestSubscribeDelivers(t *testing.T) {
	s, _ := open(t)
	ch, cancel := s.Subscribe()
	defer cancel()
	e, _ := s.Append("status", map[string]any{"kind": "status"})
	select {
	case got := <-ch:
		if got.Seq != e.Seq {
			t.Fatal("seq")
		}
	case <-time.After(time.Second):
		t.Fatal("timeout")
	}
}

func TestSlowSubscriberClosedNotDropped(t *testing.T) {
	s, _ := open(t)
	ch, cancel := s.Subscribe()
	defer cancel()
	n := subBuffer + 10
	for i := 0; i < n; i++ { // writers must not block
		if _, err := s.Append("status", map[string]any{"kind": "status"}); err != nil {
			t.Fatal(err)
		}
	}
	// Channel must be closed after delivering a contiguous prefix: no gaps.
	var prev int64
	count := 0
	for e := range ch {
		if prev != 0 && e.Seq != prev+1 {
			t.Fatalf("gap: %d after %d", e.Seq, prev)
		}
		prev = e.Seq
		count++
	}
	if count != subBuffer {
		t.Fatalf("got %d buffered", count)
	}
	// Resume via Since recovers the rest.
	rest, _ := s.Since(prev, 1000)
	if len(rest) != 10 {
		t.Fatal(len(rest))
	}
	cancel() // safe after close
}

func TestUpdateKeepsHistoryOrder(t *testing.T) {
	s, _ := open(t)
	s.AppendMessages("k1", []Message{msg("a", "1"), msg("b", "2")})
	es, _ := s.AppendMessages("k1", []Message{msg("a", "1x")})
	if len(es) != 1 {
		t.Fatal("no entry")
	}
	h, _, _ := s.History("k1", 0, 10)
	if len(h) != 2 || h[0].ID != "a" || h[0].Text != "1x" || h[1].ID != "b" {
		t.Fatalf("%+v", h)
	}
}

func TestPaginationStableAcrossUpdate(t *testing.T) {
	s, _ := open(t)
	var ms []Message
	for i := 0; i < 10; i++ {
		ms = append(ms, msg(fmt.Sprintf("m%d", i), "x"))
	}
	first, _ := s.AppendMessages("k1", ms)
	h1, more, _ := s.History("k1", 0, 5) // m5..m9
	if !more || h1[0].ID != "m5" {
		t.Fatal(more, h1[0].ID)
	}
	s.AppendMessages("k1", []Message{msg("m2", "updated"), msg("m7", "updated")})
	h2, more2, _ := s.History("k1", first[5].Seq, 5) // m0..m4
	if more2 || len(h2) != 5 || h2[0].ID != "m0" || h2[4].ID != "m4" {
		t.Fatal(more2, h2)
	}
	if h2[2].Text != "updated" {
		t.Fatal("update not visible")
	}
}

func TestMessageSeqIsFirstInsertSeq(t *testing.T) {
	s, _ := open(t)
	es, _ := s.AppendMessages("k1", []Message{msg("a", "1"), msg("b", "2")})
	first := es[0].Seq
	up, _ := s.AppendMessages("k1", []Message{msg("a", "1x")})
	if len(up) != 1 || up[0].Seq <= es[1].Seq {
		t.Fatal(up)
	}
	var p struct {
		Message Message `json:"message"`
	}
	json.Unmarshal(es[1].Payload, &p)
	if p.Message.Seq != es[1].Seq {
		t.Fatalf("new msg payload seq %d want %d", p.Message.Seq, es[1].Seq)
	}
	json.Unmarshal(up[0].Payload, &p)
	if p.Message.Seq != first {
		t.Fatalf("updated msg payload seq %d want %d", p.Message.Seq, first)
	}
	h, _, _ := s.History("k1", 0, 10)
	if h[0].Seq != first || h[1].Seq != es[1].Seq {
		t.Fatalf("history seqs %d %d", h[0].Seq, h[1].Seq)
	}
	// Re-appending identical content (seq is assigned by the store) is a no-op.
	if again, _ := s.AppendMessages("k1", []Message{msg("a", "1x")}); len(again) != 0 {
		t.Fatal("dup after update")
	}
	// Since replays the same payload.
	got, _ := s.Since(up[0].Seq-1, 1)
	json.Unmarshal(got[0].Payload, &p)
	if p.Message.Seq != first {
		t.Fatal("since payload seq")
	}
}

// C2: the last mapped state survives a restart in the meta table.
func TestStateRoundTripAndOpenHead(t *testing.T) {
	s, p := open(t)
	if b, ok := s.LoadState(); ok || b != nil {
		t.Fatalf("fresh db has state %s", b)
	}
	if s.OpenHead() != 0 {
		t.Fatalf("open head %d", s.OpenHead())
	}
	for i := 0; i < 3; i++ {
		s.Append("remove", map[string]any{"kind": "remove"})
	}
	if err := s.SaveState(json.RawMessage(`{"panes":[1]}`)); err != nil {
		t.Fatal(err)
	}
	if err := s.SaveState(json.RawMessage(`{"panes":[2]}`)); err != nil {
		t.Fatal(err)
	}
	if s.OpenHead() != 0 {
		t.Fatal("open head moved")
	}
	s.Close()
	s2, err := Open(p)
	if err != nil {
		t.Fatal(err)
	}
	defer s2.Close()
	if b, ok := s2.LoadState(); !ok || string(b) != `{"panes":[2]}` {
		t.Fatalf("state %s %v", b, ok)
	}
	if s2.OpenHead() != 3 {
		t.Fatalf("open head %d", s2.OpenHead())
	}
}

// A database written by the previous release (no 'state' meta row) opens
// unchanged: same server_id, head, messages; no state to seed from.
func TestOpenPreviousReleaseDB(t *testing.T) {
	p := filepath.Join(t.TempDir(), "old.db")
	db, err := sql.Open("sqlite", "file:"+p)
	if err != nil {
		t.Fatal(err)
	}
	for _, q := range []string{
		schema,
		`INSERT INTO meta(k,v) VALUES('server_id','0123456789abcdef0123456789abcdef')`,
		`INSERT INTO log(kind,payload) VALUES('remove','{"kind":"remove","entity":"pane","id":"x"}')`,
		`INSERT INTO log(kind,payload) VALUES('remove','{"kind":"remove","entity":"pane","id":"y"}')`,
		`INSERT INTO messages(session_key,id,seq,ts,json) VALUES('k1','m1',2,'t','{"id":"m1","session_key":"k1","ts":"t","role":"user","text":"hi"}')`,
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
	if s.ServerID() != "0123456789abcdef0123456789abcdef" || s.OpenHead() != 2 {
		t.Fatalf("id %s head %d", s.ServerID(), s.OpenHead())
	}
	if _, ok := s.LoadState(); ok {
		t.Fatal("unexpected state")
	}
	ms, _, err := s.History("k1", 0, 10)
	if err != nil || len(ms) != 1 || ms[0].Seq != 2 {
		t.Fatalf("%v %+v", err, ms)
	}
}
