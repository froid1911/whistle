# Whistle device pairing — design

Date: 2026-11-09. Owner approved ("yes") after the security review. Goal: authorize **devices**, not Tailscale users, so Whistle can be published for other herdr users and a lost phone can be cut off.

## Today
Bridge listens on the machine's Tailscale IP; every request is authorized by Tailscale WhoIs against a hard-coded allowlist (`you@github`). Any device of that Tailscale user is trusted; nothing is per phone; not publishable.

## Model
- **Network layer** stays: bridge binds to the Tailscale IP only (later also reachable via SSH tunnel). Origin/Host guards stay.
- **Authorization** = a paired device proves possession of its private key on every request.
- **Owner bootstrap**: only someone who can run a herdr plugin action on the machine (i.e. has a shell as that user) can create a pairing code. No hard-coded users.

## Pairing flow
1. On the machine: `herdr plugin action invoke whistle.bridge.pair` (also reachable from herdr's action list). The bridge creates a one-time pairing token (128-bit random, valid 5 min, single use) and prints:
   - a QR code (Unicode half-blocks in the terminal/pane) encoding `whistle://pair?v=1&u=<ip:port>&t=<token>&f=<bridge key fingerprint>&n=<machine name>`,
   - the same as a text fallback: `ip:port` + 8-character code (base32 of the token prefix… no: the full token as 26-char base32, grouped) for manual entry.
2. In Whistle: Machines → "Pair a machine" → scan QR (CameraX + ZXing core, fully on-device; manual entry fallback). The app:
   - generates an **EC P-256** key pair in the **Android Keystore** (StrongBox when available, non-exportable, `PURPOSE_SIGN`, digest SHA-256), alias per machine;
   - `POST /v1/pair {token, device_name (e.g. "Pixel 8a"), public_key (SPKI base64)}`.
3. Bridge validates the token (unused, unexpired, constant-time compare), stores the device, burns the token, replies `{device_id, bridge_public_key, server_id}`. App checks `sha256(bridge_public_key)` against the QR's fingerprint (pins the bridge identity), stores machine {url, device_id, key alias, bridge key}.
4. Wrong/expired token → 403 `pairing_invalid`; 5 failed attempts per minute → 429.

## Request authentication (every WS upgrade and HTTP request)
Header `Authorization: Whistle v1 device=<id>, ts=<unix seconds>, nonce=<16 random bytes b64url>, sig=<b64url>` where `sig = ECDSA-P256-SHA256(key, "whistle-v1\n" + METHOD + "\n" + PATH_AND_QUERY + "\n" + HOST + "\n" + ts + "\n" + nonce + "\n" + sha256_hex(body or ""))`.
Bridge checks: device exists and not revoked; |now − ts| ≤ 60 s; nonce unseen within 120 s (in-memory cache); signature valid. Failures → 401 with code `device_unknown` / `device_revoked` / `auth_stale` / `auth_bad_sig`. Origin/Host guards run first, as today.
The WS session is bound to the device id (logged; `last_seen` updated).
Bridge identity: the bridge also signs the `welcome` frame (`sig` over server_id + client nonce from the upgrade header) with its own P-256 key stored in the plugin data dir (0600); the app verifies against the pinned key → protects against a different bridge on the same address.

## Managing devices
- Plugin actions: `devices` (list: id, name, created, last seen), `unpair <id>`, `pair`.
- Revoking a device immediately closes its open WebSockets.
- App: Machines screen shows "Paired as Pixel 8a"; on 401 `device_revoked` shows "This phone was removed from <machine>. Pair again." and stops reconnecting.

## Migration / compatibility
- New bridge config key `auth` in the plugin config: `"devices"` (default for new installs) or `"devices+tailscale-users"` (legacy: also accept the old WhoIs allowlist). The owner's myserver install starts in legacy mode so the current app keeps working; after the phone is paired, the owner switches to `"devices"` (one command, documented). The hard-coded `you@github` default is removed; legacy mode reads the allowlist from config only.
- App: machines without a device key keep working only against legacy-mode bridges; the Machines screen nudges "Pair this machine".

## Storage
Bridge: `devices` table in the existing SQLite store (id, name, public_key, created_at, last_seen, revoked_at) + migration; pairing tokens in memory only. Bridge private key: `<plugin data dir>/bridge-key.pem` (0600).
App: key in Android Keystore; machine record gains device_id + bridge public key (Room/SharedPreferences migration, no data loss).

## Tests
Bridge: token lifecycle (expiry, single use, rate limit), signature verification (valid, wrong key, stale ts, replayed nonce, body hash mismatch, path/host tamper), revoke closes sockets, legacy mode, migration from previous DB.
App: signing (Robolectric with a software key provider fake — Keystore isn't available on JVM; keep signing behind an interface), QR URI parsing (versions, missing fields, fingerprint mismatch → refuse), 401 handling, machine record migration.
Live: pair the owner's Pixel against myserver (owner scans QR shown in a herdr pane), verify, then switch myserver to `"devices"` mode and verify the old unsigned path is refused.

## Non-goals
Multi-user per bridge with roles; cloud relay; push notifications.

## Implementation notes (2026-11-09, bridge plugin 0.2.0 / app 0.4.0)
Decisions made while building it; the wire details live in `docs/reference/wire-protocol.md` ("Authentication").
- The config "key `auth`" is a file, `auth.conf` in the plugin config dir (`auth …`, `allow …` lines), next to `sessions.conf`.
- Plugin actions run as separate processes while tokens live only in the bridge's memory, so `pair`/`devices`/`unpair` talk to the running bridge over a unix control socket (`~/.local/share/whistle-bridge/<session>.ctl`, 0600). herdr actions take no arguments: `unpair <id>` is CLI only (`bin/whistle-bridge plugin unpair <id>`). Action output lands in the plugin log; in a shell pane `bin/whistle-bridge plugin pair` prints the QR directly.
- Extra codes: `401 auth_required` (unsigned request to a `devices`-mode bridge; the app shows "pair this machine"), `400 bad_request` on `/v1/pair` (token not burned). A replayed nonce answers `auth_stale`; a malformed header `auth_bad_sig`. Revocation closes WebSockets with close code 4001.
- `/v1/health` stays unauthenticated but answers only `{"ok":true}` unless the caller is authenticated.
- Bridge key: `<plugin state dir>/bridge-key.pem`, one per machine (shared by sessions); fingerprint = base64url(sha256(SPKI DER)).
- QR: light modules printed (normal polarity on dark terminals); the app's ZXing decoder also tries the inverted image.
- Manual entry of address + code has no fingerprint: trust on first use (the app says so); pasting the `whistle://` link keeps the pin.
- The app generates a fresh key alias per pairing and deletes the previous key only after the new pairing succeeded.

## Update 2026-11-09 (bridge plugin 0.3.0): pairing popups, v2 auth, hardening
- **Pairing UX inside herdr.** The `pair` and `devices` actions open popup plugin panes (`[[panes]]` `pair`/`devices`, placement popup; the action execs `herdr plugin pane open --plugin whistle.bridge --entrypoint … --placement popup --focus`). Pair popup: QR (black-on-white, sized to the pane, text fallback when too small), machine + address, code, mm:ss countdown; polls the control socket (`pair_status`) and shows "✓ Paired: <name>", closing after 3 s; Esc/q burns the token (`cancel`); expiry offers `r`. Devices popup: list with connected marker, `u` unpair (y/n), `p` pair. Nothing is printed to the plugin log any more; `bin/whistle-bridge plugin pair|devices|unpair` remain for scripts.
- **v2 request auth (security review Important #2).** `GET /v1/challenge?device=<id>` hands out a single-use 128-bit server challenge (30 s, bound to the device, per-IP rate limit). `Authorization: Whistle v2 …, challenge=…, body=<sha256 hex>, sig=…` signs `"whistle-v2\n" + <v1 fields> + "\n" + challenge + "\n" + device_id` (the v1 string with its own prefix, plus challenge and device id). Captured requests cannot be replayed even within the 60 s skew window, and the signature is checked before the body is read. v1 stays accepted while `auth.conf` says `accept_v1 true` (default for now; turn off once the app speaks v2 → `401 auth_v2_required`). Wire details: `docs/reference/wire-protocol.md`.
- **Hardening (review minors).** Signed bodies are spooled to a 0600 temp file while hashed (no 25 MiB in memory; ≤2 per device; 5 min body deadline; no server-wide ReadTimeout because it would cut WebSockets). A device revoked between verification and WebSocket registration is closed with 4001 (checked under the revocation lock). Pairing misses are rate-limited per source IP and a correct token always pairs. A public key can be paired only once (`409 key_in_use`). The control socket is created in a fresh private dir and renamed into place, its dir forced to 0700, peers checked with SO_PEERCRED (Linux). Device names lose Unicode format characters (bidi overrides). The bridge refuses a `bridge-key.pem` with group/other permission bits; its log file is forced to 0600.

## Update 2026-11-09 (app 0.5.0): v2 only, welcome first
- The app signs every request with v2 (fresh `GET /v1/challenge` per WS upgrade and upload; one immediate retry on `auth_stale`). v1 is gone from the app: a paired machine needs bridge plugin ≥ 0.3.0; a 404 on `/v1/challenge` shows "bridge too old" (no downgrade). Kotlin v2 fixture: `bridge/internal/devauth/testdata/whistle_fixture_kotlin_v2.json` (TestKotlinFixtureV2).
- Security review Important #1: the first frame of a connection must be the welcome, verified against the pinned key for paired machines; nothing but `hello` is sent and nothing applied before. Every new connection resets the cache applier's baseline (an in-band `ConnectionStarted` marker on the sync stream). Uploads wait (≤ 10 s) for the machine's connection to have a verified welcome, else "Not connected".
- Keystore: StrongBox failures surfacing as `ProviderException`/`KeyStoreException` (not only `StrongBoxUnavailableException`) fall back to a TEE key.
- Machines screen: "Paired as <phone> · secure login" / "Legacy login — pair this machine".

