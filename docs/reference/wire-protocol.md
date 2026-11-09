# whistle wire protocol v1 (Android app ⇄ whistle-bridge)

Transport: WebSocket at `ws://<host>:<port>/v1/ws`, JSON text frames, one message per frame, every message has a `t` (type) field. HTTP endpoints on the same listener: `POST /v1/upload`, `GET /v1/health`, `POST /v1/pair`. Browser-style requests are refused with 403 first: any request carrying an `Origin` header (the Android client must not send one), and any request whose `Host` is not the bridge's bound `ip:port` (DNS-rebinding guard). Then every request (WS upgrade and HTTP) must be authorized — see "Authentication".

Default ports: prod `8787`, dev `8788`, bound to the machine's Tailscale IPv4 only.

## Authentication (device pairing; design: `docs/design/device-pairing.md`)

Bridge mode (`auth.conf` in the plugin config dir, or `-auth`): `devices` (default) accepts only paired devices; `devices+tailscale-users` (legacy) also accepts unsigned requests from the Tailscale logins in the allowlist (`allow`; no built-in default).

**Pairing.** The herdr action "Whistle: pair a phone" (`whistle.bridge.pair`) opens a popup pane that asks the running bridge, over its control socket `~/.local/share/whistle-bridge/<session>.ctl` (0600, 0700 dir, same-uid peers only), for a one-time token (128-bit, 26 base32 chars, valid 5 min, single use, memory only) and shows a QR code (Unicode half blocks, drawn black-on-white whatever the terminal theme) of
`whistle://pair?v=1&u=<ip:port>&t=<token>&f=<fingerprint>` (the optional `n=<machine name>` is no longer emitted, to keep the QR small) plus the grouped code (`ABCD-EFGH-…`, case/dashes ignored) and the link as text. `f` = base64url(sha256(bridge SPKI DER)), no padding.

`POST /v1/pair` (no Authorization; the token authorizes) `{"token","device_name","public_key":"<SPKI, standard base64, EC P-256>"}` →
`200 {"device_id","bridge_public_key":"<SPKI, standard base64>","server_id"}`. The app refuses the pairing unless `f` matches `bridge_public_key` (manual address + code entry has no `f`: trust on first use). Errors: `400 bad_request` (bad JSON/key; the token is not burned), `409 key_in_use` (that public key is already paired, also by a revoked device; the token is not burned — generate a fresh key), `403 pairing_invalid` (wrong, expired or used token), `429 rate_limited` (after 5 misses within a minute from one source IP, that IP's further misses; a correct token always pairs, and other IPs are unaffected). The popup polls the bridge and shows "✓ Paired: <device_name>" (control characters and Unicode format characters such as bidi overrides are stripped from the name, max 64 runes).

**Signed requests, v2 (server challenge).** Before each request (WS upgrade, upload, health) the app fetches
`GET /v1/challenge?device=<id>` (no Authorization; Origin/Host guards apply; 60 per minute per source IP, then `429 rate_limited`) → `200 {"challenge":"<16 random bytes, base64url>","expires_in":30}`. The challenge is single use, valid 30 s, and bound to that device id. The request then carries
`Authorization: Whistle v2 device=<id>, ts=<unix seconds>, nonce=<16 random bytes, base64url>, challenge=<challenge>, body=<sha256 hex of the body, lowercase>, sig=<base64url DER ECDSA>` where
`sig = ECDSA-P256-SHA256(device key, "whistle-v2\n" + METHOD + "\n" + PATH_AND_QUERY + "\n" + HOST + "\n" + ts + "\n" + nonce + "\n" + body_sha256 + "\n" + challenge + "\n" + device_id)`
(same fields and encodings as v1 below, the prefix `whistle-v2`, then the challenge and the device id). The bridge verifies the signature over the declared `body` hash **before reading the body**, consumes the challenge (unknown, reused, expired or another device's → `401 auth_stale`; fetch a new one and retry), checks the nonce, then streams the body to a private temp file while hashing it; a mismatch at EOF → `401 auth_bad_sig`. At most 2 bodies per device (6 in total) are read at once (`429 busy`); a body has 5 min to arrive. Fixtures: `bridge/internal/devauth/testdata/whistle_fixture_{go,kotlin}_v2.json` (copies in the app's test resources; each side verifies the other's).
The app (≥ 0.5.0) sends **only v2**: it fetches a fresh challenge for every WS upgrade and every upload, retries once right away on `401 auth_stale` (or a refused challenge), then backs off. A bridge without `/v1/challenge` (404, plugin < 0.3.0) gets no v1/unsigned fallback; the app shows "bridge too old" and stops. So paired machines need bridge plugin ≥ 0.3.0, and once the phone runs 0.5.0 the bridge can set `accept_v1 false`.

**Signed requests, v1** (accepted while `auth.conf` has `accept_v1 true`, the default for now; with `accept_v1 false` a v1 header → `401 auth_v2_required`). Every request of a paired device carries
`Authorization: Whistle v1 device=<id>, ts=<unix seconds>, nonce=<16 random bytes, base64url>, sig=<base64url DER ECDSA>` where
`sig = ECDSA-P256-SHA256(device key, "whistle-v1\n" + METHOD + "\n" + PATH_AND_QUERY + "\n" + HOST + "\n" + ts + "\n" + nonce + "\n" + sha256_hex(body))`.
PATH_AND_QUERY is the request target exactly as sent (encoded path + `?` + encoded query), HOST the `Host` header (`ip:port`), the body hash of an empty body (GET, WS upgrade) is `e3b0c442…b855`. Base64url is unpadded. Cross-side fixtures: `bridge/internal/devauth/testdata/whistle_fixture_{go,kotlin}.json` (copies in the app's test resources).
The bridge checks (both versions): device known and not revoked, |now − ts| ≤ 60 s, signature, nonce unseen for this device within 120 s. Failures → `401 {"error":{"code","message"}}` with code `device_unknown`, `device_revoked`, `auth_stale` (clock skew or replayed nonce), `auth_bad_sig` (also a malformed header). An unsigned request to a `devices`-mode bridge → `401 auth_required`; to a legacy bridge from a login not in the allowlist → 403. The app stops reconnecting on `device_revoked`, `device_unknown` and `auth_required` (pair again) and retries on the others.

**Bridge identity.** The bridge's P-256 key lives in `<plugin state dir>/bridge-key.pem` (0600; `-key`, default next to the DB). On a signed WS connection the `welcome` carries `"sig"` = base64url DER ECDSA over `"whistle-welcome-v1\n" + server_id + "\n" + nonce` (nonce = the upgrade request's). A paired app verifies it against the key pinned at pairing before applying anything from the connection; missing or wrong → refuse ("This doesn't look like your <machine> bridge"), no reconnect. Legacy connections get no `sig`.
Welcome first (app ≥ 0.5.0): the first text frame of every connection must be the `welcome` (signed, for a paired machine) and there is exactly one. Until it is accepted the app sends nothing but `hello` (no `watch`, `cmd`, `term.*`; calls fail with "not connected") and applies nothing; any other first frame, or a second welcome, closes the connection (paired: as the identity mismatch above). Uploads go out only while the machine's current connection has an accepted welcome.

**Revocation.** `u` in the "Whistle: devices" popup, or `bin/whistle-bridge plugin unpair <device-id>`, marks the device revoked and closes its open WebSockets with close code **4001** (`device_revoked`); a WebSocket whose upgrade was verified just before the revocation is closed with 4001 right after the upgrade. The app treats it like the 401.

**Health.** `GET /v1/health` answers `{"ok":true}` to anyone who passes the Origin/Host guards; authenticated callers (paired device or legacy allowlist) also get `herdr_online` and `head_seq`.

## Keys
- **session_key**: stable chat identity = `<agent>:<agent_session.value>` (e.g. `claude:2af29644-…`, `omp:/home/you/.omp/…jsonl`). Pane ids (`w8:p1`) are NOT stable across herdr restarts; chats are keyed by session_key. An agent pane without an agent_session gets session_key `pane:<pane_id>` (screen-only chat).
- **seq**: bridge change-log sequence, int64, strictly increasing, starts at 1.
- **server_id**: random id created with the bridge DB. If it differs from what the client stored, the client must drop its cache for that machine and resync.

## Connect / resume
Client → `{"t":"hello","server_id":"<stored or empty>","since":<last applied seq or 0>,"client":"android/1"}`

Server → `{"t":"welcome","protocol":1,"server_id":"…","head_seq":N,"resync":bool,"herdr_version":"0.9.5","sig"?:"…"}` (`sig` only on device-signed connections, see "Authentication")
- `resync=false`: server then sends every change with seq > since, in order, then `{"t":"synced","seq":N}`.
- `resync=true` (unknown server_id, since==0, since older than retained log, or since ≤ the log head at bridge start — a restarted bridge cannot replay what changed in herdr while it was down, so every client whose cursor predates the restart is resynced once; before the first such resync the bridge logs one `{"kind":"epoch"}` entry so the resynced cursor lies above that head and later reconnects resume — clients ignore unknown change kinds): server sends `{"t":"state","seq":N,"state":<State>}` then `{"t":"synced","seq":N}`. Client replaces its machine state and re-fetches chat history for chats it shows (`chat.history`).
- After `synced`, live changes stream as they happen.
- If entries the client still needs are pruned while it is being served, the server closes the WS with status 1013 ("resync"); the reconnect's `hello{since}` then gets `resync=true`.

## State (also the payload of `state` and of state changes)
```
State = { "workspaces":[Workspace], "tabs":[Tab], "panes":[Pane], "layouts":[Layout] }
Workspace = {id, number, label, agent_status?, active_tab_id}
Tab       = {id, workspace_id, number, label, agent_status?}
Pane      = {id, tab_id, workspace_id, label, cwd, kind:"agent"|"shell",
             agent?:"claude"|"omp"|…, agent_status?, session_key?, has_transcript:bool}
Layout    = {tab_id, panes:[{pane_id, x, y, w, h}]}   // for ordering panes in a tab (sort by y, then x)
```
Pane `label` is the stripped terminal title (else herdr's label, else the pane id) with spinner/status glyphs removed (braille U+2800–U+28FF, ✳✻✶✢✽, ◐◓◑◒…, standalone `| / - \ * > ·`); workspace/tab labels are normalized the same way, so animation frames never produce upserts. No `revision`, no scroll, no focus fields (noise).

## Change log entries (durable, replayed on resume)
`{"t":"change","seq":N,"kind":K, …}` with kind:
- `upsert` — `{"entity":"workspace"|"tab"|"pane"|"layout","data":{…}}`
- `remove` — `{"entity":…,"id":"…"}`
- `message` — `{"session_key":"…","message":Message}` (new or updated transcript message)
- `status` — `{"pane_id":"…","session_key":"…"?, "from":"working","to":"idle","at":"RFC3339"}` (emitted for every agent_status transition, AFTER the final reply messages of that turn have been appended)

```
Message = {id, seq, session_key, ts:"RFC3339", role:"user"|"assistant"|"thinking"|"tool_call"|"tool_result"|"screen",
           text, tool_name?, tool_call_id?, input?:object, is_error?:bool, truncated?:bool}
```
- `text`/`input` capped at 16 KiB each; `truncated:true` if cut. `input` is always a JSON object: an oversized input is replaced by `{"_truncated":"<prefix of the serialized input>"}`. Clients should still decode `input` as any JSON value and tolerate non-objects.
- Same `id` sent again = update (replace).
- `seq` = the message's ordering seq (the change-log seq of its FIRST insert; never changes on update). History is ordered by it and `chat.history.before_seq` takes it.
- `screen` = final cleaned screen capture for agents without a transcript.

## Ephemeral (never logged, never replayed)
- Client → `{"t":"watch","pane_id":"…"}` / `{"t":"unwatch","pane_id":"…"}` — the chat view currently on screen. At most 4 watched panes per connection (active + pending). A watch for a pane not (yet) in state stays pending and activates when the pane appears.
- Server → `{"t":"herdr","online":bool}` — whether the bridge currently reaches herdr. Sent right after `welcome` (before `state`/replay) and again whenever it changes (checked every 250 ms once synced; a change during replay is reported right after `synced`). `online:false` means the stream is the bridge's last known state and herdr commands will fail with `herdr_unavailable`.
- Server → `{"t":"live","pane_id":"…","text":"…","status":"working"|"blocked"|…}` — cleaned in-progress screen text for a watched agent pane, sent when it changes (poll every 250 ms while working or blocked). `{"t":"live.clear","pane_id":"…"}` when the turn completes (after its messages + status change were sent).

## Commands
Client → `{"t":"cmd","id":"c42","method":"…","params":{…}}`
Server → `{"t":"res","id":"c42","result":{…}}` or `{"t":"res","id":"c42","error":{"code":"…","message":"…"}}`

herdr passthrough (bridge forces `focus:false` where the param exists; any other method → `error.code="method_not_allowed"`):
`workspace.create|rename|close`, `tab.create|rename|close`, `pane.split|close|rename|send_text|send_keys`, `agent.prompt|send_keys|start|rename`, `worktree.list`.
herdr error codes are passed through unchanged (`agent_blocked`, `not_found`, …).
Bridge error codes: `method_not_allowed`, `bad_request`, `not_available` (feature not wired on this bridge), `forbidden_path`, `git_error`, `herdr_unavailable` (herdr socket down), `timeout`, `internal`, `busy` (8 commands already in flight on this connection; the command was NOT executed).

Bridge methods:
- `chat.history {session_key, before_seq?:int, limit?:int(≤200, default 100)}` → `{messages:[Message], more:bool}` newest-last.
- `agent.screen {pane_id, lines?:int}` → `{text}` (cleaned `recent_unwrapped` text) — used for approval cards.
- `git.status {path}` → `{branch, files:[{path, status:"M"|"A"|"D"|"R"|"?", staged:bool}]}`
- `git.diff {path, file?:string}` → `{diff:"unified diff text", truncated:bool}` (cap 512 KiB)
- `git.commit {path, message, all:bool}` → `{commit:"<sha>"}`
  - `path` must equal a current pane cwd or a herdr worktree path (else `forbidden_path`).

## Terminal
- Client → `{"t":"term.open","sid":"s1","pane_id":"…","mode":"observe"|"control","cols"?:int,"rows"?:int}` (cols/rows only for "fit to phone")
- Server → `{"t":"term.frame","sid":"s1","seq":N,"width":W,"height":H,"full":bool,"bytes":"<base64 ANSI>"}`; `{"t":"term.closed","sid":"s1","reason":"…"}`
- Client → `{"t":"term.input","sid":"s1","text":"…"}` or `"bytes":"<b64>"`; `{"t":"term.resize","sid","cols","rows"}`; `{"t":"term.close","sid":"s1"}`
- Input on an observe-mode session → server upgrades to control (restart subprocess in control mode, always WITHOUT cols/rows so the desktop pane is never resized) transparently. Frames after the upgrade come at the pane's real size.
- A client `term.close` gets no `term.closed` back. Sessions are not restored after a WS reconnect, and no `term.closed` is sent for them; the client re-opens.
- Max 2 terminal sessions per connection; closing the WS kills its subprocesses.
- `term.closed.reason`: `not_available`, `too_many_sessions`, `duplicate_sid`, `ended`, or an open error message.

## Upload
`POST /v1/upload?pane_id=w9:p1` multipart field `file` (≤ 25 MiB; a signed upload's signature covers the sha256 of the whole multipart body, so the bridge reads it fully before verifying) → `{"path":"/home/you/.cache/whistle-bridge/uploads/<pane>/<unix_ms>-<sanitized name>"}`.

## Keepalive
WebSocket ping every 15 s from the server; client treats 35 s silence as dead and reconnects with `hello{since}`.
