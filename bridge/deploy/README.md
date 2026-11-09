# whistle-bridge: deployment and operations

For a normal install see the top-level `README.md` (`herdr plugin install` or
`herdr plugin link`). This page covers how the plugin behaves, its files, and
deploying from a dev machine to a server without Go.

Examples use `myserver` (ssh host) and `100.64.0.10` (its Tailscale IPv4);
substitute your own.

## The herdr plugin `whistle.bridge` ("Whistle bridge")

The bridge runs as a herdr plugin, as a child of each session's herdr server.
Plugin dir in the repo: `bridge/plugin/` (`herdr-plugin.toml`,
`scripts/install.sh`); the repo-root `herdr-plugin.toml` is the same manifest
for `herdr plugin install`.

How it works (herdr facts: `docs/reference/herdr-api-notes.md`, "Plugins"):

- Plugins are global: the `[[startup]]` command `bin/whistle-bridge plugin run`
  runs in every session's server at server start. It reads the session from
  herdr's env and the address from `sessions.conf` in
  `herdr plugin config-dir whistle.bridge` (created with
  `default tailscale:8787`); unlisted sessions or `off` exit 0 at once.
- `plugin link` starts nothing. Start/replace the bridge in a running server
  with the `restart` action
  (`herdr [--session NAME] plugin action invoke whistle.bridge.restart`);
  the herdr server is never restarted.
- One bridge per session: `~/.local/share/whistle-bridge/<session>.lock`
  (flock + pid). A new `run` SIGTERMs the holder and waits, so restart,
  server restart/handoff and double starts all end with exactly one bridge.
- herdr neither restarts crashed startup processes nor stops them with the
  server: `plugin run` supervises the bridge (restart with backoff on
  error/panic) and exits once the herdr socket has been dead for 90 s.
- If `bin/whistle-bridge-<session>` exists, `plugin run` execs it for that
  session, so a dev build can run in a dev session without replacing the
  binary the default session runs.
- Actions: `restart`, `status` (prints `session= pid= addr= health=`; read it
  with `herdr plugin log list --plugin whistle.bridge`), `stop`, `pair` and
  `devices` (popups, see below). From a shell:
  `<plugin dir>/bin/whistle-bridge plugin status -session default`.
- Logs: herdr keeps only the first 64 KiB of plugin output, shown after exit,
  so the bridge also logs to
  `~/.local/state/herdr/plugins/whistle.bridge/<session>.log` (0600, rotated
  at 10 MiB to `.log.1`).

## Who may connect: device pairing (`auth.conf`)

Phones pair with the bridge and sign every request with a key that never
leaves them (wire details: `docs/reference/wire-protocol.md`,
"Authentication"). `auth.conf` in the plugin config dir (all sessions; created
as `auth devices` when missing) picks the mode:

    auth devices                   # paired devices only (default)
    auth devices+tailscale-users   # legacy: also unsigned requests from
    allow you@github               #   these Tailscale logins (no built-in default)

    accept_v1 true                 # default: also accept older "Whistle v1" signatures
    accept_v1 false                # v2 only (server challenge); v1 -> 401 auth_v2_required

Changing `auth.conf` needs a `restart` action to take effect.

### Pair a phone (inside herdr)

Run **"Whistle: pair a phone"** from herdr's command list (or a key bound to
it). A popup opens with a QR code, the machine name and address, the
26-character code and a live countdown (5 min, single use). In Whistle:
Machines → Pair a machine (or "Pair this machine" on the machine's card) →
scan → confirm the phone's name → Pair this phone. The popup shows
"✓ Paired: <phone>" and closes after 3 s. Esc or q cancels and burns the
code; an expired code shows "press r for a new one". In a small window the QR
is replaced by the code and address to type by hand.

**"Whistle: devices"** lists paired phones (name, short id, paired date, last
seen, ● = connected now): ↑/↓ select, `u` unpair (asks y/n; closes the
phone's connections at once), `p` pair another phone, Esc closes.

Key bindings (herdr's `config.toml`; herdr adds none by itself):

    [[keys.command]]
    key = "ctrl+alt+w"
    type = "plugin_action"
    command = "whistle.bridge.pair"
    description = "Whistle: pair a phone"

    [[keys.command]]
    key = "ctrl+alt+shift+w"
    type = "plugin_action"
    command = "whistle.bridge.devices"
    description = "Whistle: devices"

The popups only talk to the running bridge of the session they open in
(control socket); nothing is written to the plugin log. Over ssh, the same
popup can be opened with `herdr plugin action invoke whistle.bridge.pair`
(it shows in the attached client).

For scripts (plain text on stdout, from a shell on the machine):

    B=<plugin dir>/bin/whistle-bridge
    $B plugin pair            # QR + code on stdout (a terminal, not the plugin log)
    $B plugin devices         # id, name, paired, last seen, state
    $B plugin unpair <id>     # revoke; closes its connections at once

Check from another tailnet machine:

    curl -s http://100.64.0.10:8787/v1/health                               # unsigned: {"ok":true} only
    curl -s -o /dev/null -w '%{http_code}\n' http://100.64.0.10:8787/v1/ws  # unsigned: 401

Use the IP, not a hostname: the bridge rejects requests whose `Host` is not
its bound `ip:port`, and any request with an `Origin` header.

### Files

- Bridge identity key: `~/.local/state/herdr/plugins/whistle.bridge/bridge-key.pem`
  (must be 0600; the bridge refuses a group/other-accessible key; deleting it
  makes every paired app refuse the bridge until re-paired).
- Session DB (change log, devices): `~/.local/share/whistle-bridge/<session>.db`.
- Control socket: `~/.local/share/whistle-bridge/<session>.ctl` (0600 in a
  0700 dir, same-uid peers only; used by the popups and the CLI).
- Uploads: `~/.cache/whistle-bridge/uploads/`.

## Deploying from a dev machine (server without Go)

Run from `bridge/` on a machine that can `ssh myserver` and is on the same
tailnet. The binary is cross-compiled locally (linux/amd64) and rsynced into
`~/.local/share/whistle/bridge-plugin/` on the server, which is linked once
with `herdr plugin link`.

    make plugin-deploy-prod SERVER=myserver SERVER_IP=100.64.0.10   # default session, :8787, keeps bin/whistle-bridge.prev
    make plugin-deploy-dev  SERVER=myserver SERVER_IP=100.64.0.10   # whistle-dev session, :8788
    make plugin-status      SERVER=myserver

`HERDR` (default `/home/linuxbrew/.linuxbrew/bin/herdr`) is the herdr binary
on the server; non-login SSH commands usually lack it on `PATH`.

### Dev session

For testing, run a separate headless herdr session so dev builds never touch
the agents in your default session:

    scp deploy/herdr-whistle-dev.service myserver:.config/systemd/user/
    ssh myserver 'echo "whistle-dev  tailscale:8788" >> "$(herdr plugin config-dir whistle.bridge)/sessions.conf"'
    ssh myserver 'systemctl --user daemon-reload && systemctl --user enable --now herdr-whistle-dev'
    make plugin-deploy-dev SERVER=myserver SERVER_IP=100.64.0.10
    ssh myserver 'herdr --session whistle-dev workspace create --cwd ~ --label whistle-dev'   # once
    curl -s http://100.64.0.10:8788/v1/health

(`sessions.conf` first, so the plugin starts the bridge when the dev server
comes up.) Tear down with `systemctl --user disable --now herdr-whistle-dev`
and remove the `whistle-dev` line from `sessions.conf`.

### Live integration test

    make test-live WHISTLE_BRIDGE_URL=ws://100.64.0.10:8788/v1/ws   # go test -tags live ./internal/integration/

It refuses any port but 8788 or a session without a workspace labelled
`whistle-dev`. It creates and closes its own tabs and starts one Claude agent
(one short paid turn); `WHISTLE_SKIP_CLAUDE=1` skips that part. The Claude part
runs in `~/.cache/whistle-it-claude` on the server, which adds a project trust
entry for that folder to `~/.claude.json` there; remove it afterwards if you
care.
