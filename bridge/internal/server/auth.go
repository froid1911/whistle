package server

import (
	"context"
	"errors"
	"fmt"
	"net/http"
	"strings"
	"time"

	"tailscale.com/client/local"

	"github.com/froid1911/whistle/bridge/internal/devauth"
)

// Authorizer maps a request's remote address to an allowed login, or errors.
type Authorizer interface {
	Authorize(ctx context.Context, remoteAddr string) (login string, err error)
}

// TailscaleAuth allows peers whose Tailscale login (WhoIs via the local
// tailscaled) is in Allowed.
type TailscaleAuth struct {
	Allowed []string

	whois func(ctx context.Context, remoteAddr string) (string, error) // test hook
}

var lc local.Client

func tailscaleWhoIs(ctx context.Context, remoteAddr string) (string, error) {
	who, err := lc.WhoIs(ctx, remoteAddr)
	if err != nil {
		return "", err
	}
	if who == nil || who.UserProfile == nil {
		return "", errors.New("whois: no user profile")
	}
	return who.UserProfile.LoginName, nil
}

func (a TailscaleAuth) Authorize(ctx context.Context, remoteAddr string) (string, error) {
	whois := a.whois
	if whois == nil {
		whois = tailscaleWhoIs
	}
	ctx, cancel := context.WithTimeout(ctx, 5*time.Second)
	defer cancel()
	login, err := whois(ctx, remoteAddr)
	if err != nil {
		return "", err
	}
	for _, al := range a.Allowed {
		if strings.EqualFold(al, login) {
			return login, nil
		}
	}
	return "", fmt.Errorf("login %q not allowed", login)
}

// AllowAll authorizes everyone. Tests and loopback-only debugging only.
type AllowAll struct{}

func (AllowAll) Authorize(context.Context, string) (string, error) { return "anonymous", nil }

// addHost allows Host header value hostport (the bound ip:port). Empty is ignored.
func (s *Server) addHost(hostport string) {
	if hostport == "" {
		return
	}
	s.hmu.Lock()
	defer s.hmu.Unlock()
	if s.hosts == nil {
		s.hosts = map[string]bool{}
	}
	s.hosts[strings.ToLower(hostport)] = true
}

// hostAllowed fails closed: with no configured address nothing is allowed.
func (s *Server) hostAllowed(host string) bool {
	s.hmu.Lock()
	defer s.hmu.Unlock()
	return s.hosts[strings.ToLower(host)]
}

// authorize guards every endpoint. Network identity alone is not enough:
// a browser page on an allowed device could otherwise reach us, either
// cross-origin (no preflight for multipart POST) or via DNS rebinding (which
// passes a same-origin check). So: no Origin header at all (the app sends
// none), Host must be the bound ip:port. Then, in order:
//   - GET /v1/challenge (v2 server nonces; rate-limited per source IP);
//   - a Whistle device signature (Authorization: Whistle v1|v2 …) -> verified or 401;
//   - POST /v1/pair (authorized by its one-time token);
//   - the legacy Tailscale allowlist (Deps.Auth; nil in "devices" mode);
//   - /v1/health answers {ok} to anyone else; everything else 401/403.
func (s *Server) authorize(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("Origin") != "" || !s.hostAllowed(r.Host) {
			s.log.Warn("rejected browser-style request", "remote", r.RemoteAddr, "host", r.Host,
				"origin", r.Header.Get("Origin"), "path", r.URL.Path)
			http.Error(w, "forbidden", http.StatusForbidden)
			return
		}
		if r.URL.Path == "/v1/challenge" {
			s.serveChallenge(w, r)
			return
		}
		health := r.URL.Path == "/v1/health"
		if devauth.IsWhistle(r.Header.Get("Authorization")) {
			info, status, code, done := s.verifyDevice(w, r)
			defer done()
			if code != "" {
				s.log.Warn("device auth failed", "remote", r.RemoteAddr, "path", r.URL.Path, "device", info.device, "code", code)
				if health && status == http.StatusUnauthorized {
					s.serveHealthMinimal(w)
					return
				}
				writeErr(w, status, code, "device authentication failed")
				return
			}
			next.ServeHTTP(w, r.WithContext(withAuth(r.Context(), info)))
			return
		}
		if r.URL.Path == "/v1/pair" {
			s.servePair(w, r)
			return
		}
		if s.deps.Auth != nil {
			login, err := s.deps.Auth.Authorize(r.Context(), r.RemoteAddr)
			if err == nil {
				s.log.Debug("request", "login", login, "path", r.URL.Path)
				next.ServeHTTP(w, r.WithContext(withAuth(r.Context(), authInfo{login: login})))
				return
			}
			if health {
				s.serveHealthMinimal(w)
				return
			}
			s.log.Warn("unauthorized", "remote", r.RemoteAddr, "path", r.URL.Path, "err", err)
			http.Error(w, "forbidden", http.StatusForbidden)
			return
		}
		if health {
			s.serveHealthMinimal(w)
			return
		}
		s.log.Warn("unsigned request refused", "remote", r.RemoteAddr, "path", r.URL.Path)
		writeErr(w, http.StatusUnauthorized, "auth_required", "pair this device first")
	})
}
