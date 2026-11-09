# Security

## Reporting a vulnerability

Please report vulnerabilities privately via GitHub's
[private vulnerability reporting](https://github.com/froid1911/whistle/security/advisories/new)
for this repository. Do not open a public issue. Include the affected
component (bridge or app), version, and steps to reproduce. You should get an
answer within a week.

## Threat model (summary)

Whistle gives a phone remote control of the coding agents in a herdr session:
it can read their output and transcripts, send prompts and keystrokes, open
terminals and upload files. Treat a paired phone like an SSH key to that
account.

What protects the bridge:

- **Network:** it binds only to the machine's Tailscale IPv4 address; nothing
  listens on public or LAN interfaces. Requests whose `Host` is not the bound
  `ip:port`, and any request carrying an `Origin` header, are rejected
  (DNS rebinding / browser CSRF).
- **Pairing:** a pairing code is created only by someone who can run a herdr
  plugin action (a shell as the herdr user). Codes are random, single use,
  expire after 5 minutes and are rate limited. The QR code carries the
  fingerprint of the bridge's identity key, which the app pins.
- **Request auth:** each paired phone holds a non-exportable P-256 key in the
  Android Keystore. Every HTTP request and WebSocket upgrade is signed over
  method, path, host, timestamp, nonce, body hash and a single-use server
  challenge (`Whistle v2`), so captured requests cannot be replayed. The
  bridge proves its identity to the app with a signed welcome.
- **Revocation:** unpairing a device closes its connections immediately.
- **Local files:** the bridge key (0600; the bridge refuses a group/other
  readable key), database and control socket (0600 in a 0700 directory,
  same-uid peers only) live in the user's home directory.

Known limits:

- `accept_v1 true` in `auth.conf` (deprecated, insecure; off by default)
  re-enables the older `Whistle v1` signatures (no server challenge;
  replayable within the timestamp window) for apps older than 0.5. Leave it
  off.
- `auth devices+tailscale-users` (legacy) additionally trusts unsigned
  requests from listed Tailscale logins; avoid it.
- Anyone who can unlock the phone and the app (if app lock is enabled) can
  act as you on the paired machines. Anyone with a shell as the herdr user on
  the server is already fully trusted.
- herdr plugins are not sandboxed; the bridge runs with the herdr user's
  permissions.
