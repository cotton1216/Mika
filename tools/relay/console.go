package main

import (
	"bytes"
	"context"
	"io"
	"net/http"
	"strings"
	"sync"
	"time"
)

// The console keeps the phone access token in gateway memory only. It is never written to the
// pairing store, never sent to the browser, and disappears when the gateway restarts.
type consoleSession struct {
	token   string
	created time.Time
	seen    time.Time
}

const (
	consoleCookie      = "mika_console"
	consoleIdle        = 8 * time.Hour
	consoleMax         = 24 * time.Hour
	consoleMaxSessions = 16
)

type consoleStore struct {
	mu       sync.Mutex
	sessions map[string]*consoleSession
	now      func() time.Time
}

func newConsoleStore() *consoleStore {
	return &consoleStore{sessions: map[string]*consoleSession{}, now: time.Now}
}

func (c *consoleStore) open(token string) string {
	id := secret()
	c.mu.Lock()
	defer c.mu.Unlock()
	now := c.now()
	c.cleanupLocked(now)
	if len(c.sessions) >= consoleMaxSessions {
		var oldest string
		var at time.Time
		for k, s := range c.sessions {
			if oldest == "" || s.seen.Before(at) {
				oldest, at = k, s.seen
			}
		}
		delete(c.sessions, oldest)
	}
	c.sessions[id] = &consoleSession{token: token, created: now, seen: now}
	return id
}

func (c *consoleStore) token(id string) (string, bool) {
	if id == "" {
		return "", false
	}
	c.mu.Lock()
	defer c.mu.Unlock()
	now := c.now()
	c.cleanupLocked(now)
	s := c.sessions[id]
	if s == nil {
		return "", false
	}
	s.seen = now
	return s.token, true
}

func (c *consoleStore) close(id string) {
	c.mu.Lock()
	defer c.mu.Unlock()
	delete(c.sessions, id)
}

func (c *consoleStore) cleanupLocked(now time.Time) {
	for k, s := range c.sessions {
		if now.Sub(s.seen) > consoleIdle || now.Sub(s.created) > consoleMax {
			delete(c.sessions, k)
		}
	}
}

// consoleStore returns the process-wide session table, creating it for tests that build a gateway literal.
func (g *gateway) consoleStore() *consoleStore {
	g.mu.Lock()
	defer g.mu.Unlock()
	if g.console == nil {
		g.console = newConsoleStore()
	}
	return g.console
}

const (
	consoleWriteLimit = 8192
	consoleWriteRate  = 30
	consoleHeader     = "X-Mika-Console"
)

func consolePath(path string) bool {
	switch path {
	case "/api/v1", "/api/v1/tasks", "/api/v1/running", "/api/v1/conversations":
		return true
	}
	for _, prefix := range []string{"/api/v1/tasks/", "/api/v1/conversations/"} {
		if rest, ok := strings.CutPrefix(path, prefix); ok {
			return rest != "" && !strings.Contains(rest, "/")
		}
	}
	return false
}

// consoleWritePath is the fixed list of mutations the browser console may trigger. Reads stay on
// consolePath; anything else is rejected before the request reaches the phone.
func consoleWritePath(path string) bool {
	if path == "/api/v1/tasks" {
		return true
	}
	rest, ok := strings.CutPrefix(path, "/api/v1/tasks/")
	if !ok {
		return false
	}
	parts := strings.Split(rest, "/")
	if len(parts) != 2 || parts[0] == "" {
		return false
	}
	switch parts[1] {
	case "answer", "steer", "cancel":
		return true
	}
	return false
}

func (g *gateway) consolePage(w http.ResponseWriter, r *http.Request) {
	if r.Method != "GET" {
		failure(w, 405, "method_not_allowed")
		return
	}
	b, err := webFiles.ReadFile("web/console.html")
	if err != nil {
		failure(w, 404, "not_found")
		return
	}
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	_, _ = w.Write(b)
}

func (g *gateway) consoleSession(w http.ResponseWriter, r *http.Request) {
	if r.Method != "GET" {
		failure(w, 405, "method_not_allowed")
		return
	}
	connected := false
	if c, err := r.Cookie(consoleCookie); err == nil {
		_, connected = g.consoleStore().token(c.Value)
	}
	writeJSON(w, 200, map[string]bool{"connected": connected})
}

func (g *gateway) consoleLogin(w http.ResponseWriter, r *http.Request) {
	if r.Method != "POST" {
		failure(w, 405, "method_not_allowed")
		return
	}
	if o := r.Header.Get("Origin"); o != "" && o != g.base {
		failure(w, 403, "invalid_origin")
		return
	}
	if g.limited(r, "console", 10) {
		w.Header().Set("Retry-After", "60")
		failure(w, 429, "rate_limited")
		return
	}
	r.Body = http.MaxBytesReader(w, r.Body, 4096)
	if err := r.ParseForm(); err != nil {
		failure(w, 400, "invalid_request")
		return
	}
	token := strings.TrimSpace(r.PostForm.Get("token"))
	if token == "" || len(token) > 512 || strings.ContainsAny(token, "\r\n") {
		failure(w, 400, "invalid_token")
		return
	}
	// The phone owns token validation. Only authenticated tokens can occupy a session slot.
	ctx, cancel := context.WithTimeout(r.Context(), 10*time.Second)
	defer cancel()
	probe := r.Clone(ctx)
	probe.Method = http.MethodGet
	probe.URL.Path, probe.URL.RawPath, probe.URL.RawQuery = "/api/v1", "", ""
	probe.Body = http.NoBody
	probe.Header = http.Header{"Authorization": {"Bearer " + token}, "Accept": {"application/json"}}
	if forwarded := r.Header.Values("X-Forwarded-For"); len(forwarded) != 0 {
		probe.Header["X-Forwarded-For"] = forwarded
	}
	result := consoleAuthResponse{headers: make(http.Header)}
	g.proxy(&result, probe)
	switch result.status {
	case http.StatusOK:
	case http.StatusUnauthorized, http.StatusForbidden:
		failure(w, http.StatusUnauthorized, "invalid_token")
		return
	case http.StatusTooManyRequests:
		if retry := result.headers.Get("Retry-After"); retry != "" {
			w.Header().Set("Retry-After", retry)
		}
		failure(w, http.StatusTooManyRequests, "rate_limited")
		return
	default:
		failure(w, http.StatusServiceUnavailable, "device_unavailable")
		return
	}
	id := g.consoleStore().open(token)
	http.SetCookie(w, &http.Cookie{
		Name: consoleCookie, Value: id, Path: "/web", HttpOnly: true,
		Secure: strings.HasPrefix(g.base, "https://"), SameSite: http.SameSiteStrictMode,
		MaxAge: int(consoleIdle.Seconds()),
	})
	writeJSON(w, 200, map[string]bool{"connected": true})
}

// The login probe needs only the phone's status and optional retry delay.
type consoleAuthResponse struct {
	headers http.Header
	status  int
}

func (r *consoleAuthResponse) Header() http.Header            { return r.headers }
func (r *consoleAuthResponse) WriteHeader(status int)         { r.status = status }
func (r *consoleAuthResponse) Write(body []byte) (int, error) { return len(body), nil }

func (g *gateway) consoleLogout(w http.ResponseWriter, r *http.Request) {
	if r.Method != "POST" {
		failure(w, 405, "method_not_allowed")
		return
	}
	if o := r.Header.Get("Origin"); o != "" && o != g.base {
		failure(w, 403, "invalid_origin")
		return
	}
	if c, err := r.Cookie(consoleCookie); err == nil {
		g.consoleStore().close(c.Value)
	}
	http.SetCookie(w, &http.Cookie{
		Name: consoleCookie, Value: "", Path: "/web", HttpOnly: true,
		Secure: strings.HasPrefix(g.base, "https://"), SameSite: http.SameSiteStrictMode, MaxAge: -1,
	})
	writeJSON(w, 200, map[string]bool{"connected": false})
}

// consoleProxy forwards a console request to the phone. The phone rejects browser requests
// (Origin), so the gateway authenticates the browser session here and removes Origin before
// forwarding with the stored token. Reads are a fixed path list; writes are a smaller fixed list
// that additionally needs the page's own header, a same-origin Origin and a per-client write budget.
func (g *gateway) consoleProxy(w http.ResponseWriter, r *http.Request) {
	if r.Method != "GET" && r.Method != "POST" {
		w.Header().Set("Allow", "GET, POST")
		failure(w, 405, "method_not_allowed")
		return
	}
	c, err := r.Cookie(consoleCookie)
	if err != nil {
		failure(w, 401, "not_connected")
		return
	}
	token, ok := g.consoleStore().token(c.Value)
	if !ok {
		failure(w, 401, "not_connected")
		return
	}
	path := strings.TrimPrefix(r.URL.Path, "/web")
	write := r.Method == "POST"
	if write && !consoleWritePath(path) {
		if consolePath(path) {
			w.Header().Set("Allow", "GET")
			failure(w, 405, "method_not_allowed")
		} else {
			failure(w, 404, "not_found")
		}
		return
	}
	if !write && !consolePath(path) {
		failure(w, 404, "not_found")
		return
	}
	var body []byte
	if write {
		// A browser cannot fake Origin or the custom header cross-site: both fail here.
		if r.Header.Get("Origin") != g.base {
			failure(w, 403, "invalid_origin")
			return
		}
		if r.Header.Get(consoleHeader) != "1" {
			failure(w, 403, "invalid_console_header")
			return
		}
		if g.limited(r, "console-write", consoleWriteRate) {
			w.Header().Set("Retry-After", "60")
			failure(w, 429, "rate_limited")
			return
		}
		body, err = io.ReadAll(http.MaxBytesReader(w, r.Body, consoleWriteLimit))
		if err != nil {
			failure(w, 413, "body_too_large")
			return
		}
	}
	next := r.Clone(r.Context())
	next.URL.Path = path
	next.Header.Del("Cookie")
	next.Header.Del("Origin")
	next.Header.Del(consoleHeader)
	next.Header.Set("Authorization", "Bearer "+token)
	next.Header.Set("Accept", "application/json")
	if write {
		next.Body = io.NopCloser(bytes.NewReader(body))
		next.ContentLength = int64(len(body))
	}
	g.proxy(w, next)
}
