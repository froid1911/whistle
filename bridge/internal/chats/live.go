package chats

import (
	"context"
	"sync"
	"time"
)

// LiveLines is how many recent_unwrapped lines are read per live poll.
const LiveLines = 120

// Live polls cleaned screen text of watched agent panes.
type Live struct {
	hc       HerdrReader
	interval time.Duration
}

func NewLive(hc HerdrReader, interval time.Duration) *Live {
	return &Live{hc: hc, interval: interval}
}

func active(status string) bool { return status == "working" || status == "blocked" }

// Watch polls paneID every interval while status() is working or blocked and
// calls send with the cleaned text whenever it changed. The returned stop
// func ends polling and waits for the poller to exit; it is idempotent.
func (l *Live) Watch(paneID string, agent string, status func() string, send func(text string)) (stop func()) {
	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan struct{})
	go func() {
		defer close(done)
		tick := time.NewTicker(l.interval)
		defer tick.Stop()
		var last string
		have := false
		for {
			if active(status()) {
				rctx, rcancel := context.WithTimeout(ctx, 5*time.Second)
				txt, err := l.hc.Read(rctx, paneID, "recent_unwrapped", LiveLines)
				rcancel()
				if err == nil && ctx.Err() == nil {
					if c := Clean(agent, txt); !have || c != last {
						last, have = c, true
						send(c)
					}
				}
			} else {
				have = false // next turn starts fresh
			}
			select {
			case <-ctx.Done():
				return
			case <-tick.C:
			}
		}
	}()
	var once sync.Once
	return func() {
		once.Do(func() {
			cancel()
			<-done
		})
	}
}
