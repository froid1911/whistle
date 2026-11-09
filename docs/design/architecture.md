# Herdr for Android — Design

Date: 2026-11-08. Original design spec, kept for background; the code and `docs/reference/` are authoritative where they differ (e.g. auth is now device pairing, see `device-pairing.md`, and the bridge runs as a herdr plugin instead of a systemd service).

## Intent

A native Android app that is a full, touch-first client for **herdr** (the terminal workspace manager for AI coding agents, https://herdr.dev, repo `herdrdev/herdr`). Today the owner drives herdr from the phone through ConnectBot + the herdr TUI, which is clunky. Paid alternatives (Moshhi / "Herdr" mobile) are too expensive for what they are. The owner wants their own: push prompts to agents, read what comes back, and run the whole herdr workspace from the phone.

Single user (the owner), personal tool, sideloaded. Not a store product.

## Anti-goals (failure even if it "works")

1. **Laggy.** Opening an agent, sending a prompt, seeing output stream in must feel instant. Target: cached UI on screen <300 ms after app open; agent output visible on phone <500 ms after it appears on myserver (on a direct Tailscale link).
2. **Missing agent replies.** A reply must never silently go missing — not on reconnect, not after the phone slept, not if herdr drops events. If the app can't be sure it's up to date, it must show that, not show stale data as current.

## Goals (all in v1)

Base shape, chosen by owner ("B — mirror of herdr"): navigation mirrors herdr's own model — **machines → workspaces → tabs → panes**. Agent panes render as chat; shell panes render as terminal. Panes in a tab are full-screen pages you swipe between (no side-by-side splits on a phone). Workspace/tab/pane lifecycle from the phone: create, rename, close, split pane, start an agent in a pane.

Plus all nine extras (owner selected 1–9):

1. **Real chat from session files.** Agent conversations are read from the agents' own transcript files (Claude Code `~/.claude/projects/<dir>/<session-id>.jsonl`; omp session `.jsonl` paths — herdr reports these via `agent_session` in `agent.list`). Render user/assistant messages, collapsible tool calls, diffs. Agents without a parseable transcript fall back to cleaned screen text from `agent.read`.
2. **Approval cards** (in-app only in v1). When an agent goes `blocked`, its pane, its workspace row and the widget show it; the card shows what is being asked (from the screen), with answer buttons mapped to keys via `agent.send_keys`. **System notifications are deferred** (owner: "no alerts for now"); the bridge still flags notifiable transitions so alerts can be added later.
3. **Voice prompts.** Hold-to-talk using Android's speech recognizer → editable prompt text.
4. **Share into herdr.** Android share target: text, links, images → "send to agent…". Images are uploaded to the machine (bridge stores them in a per-agent upload dir) and the file path is put into the prompt.
5. **Home-screen widget + Quick Settings tile.** Counts of working / blocked / done agents; tap to jump in.
6. **Changes view.** Per workspace (and per git worktree): changed files, readable diff, commit from phone, and "tell the agent about this line" (prefills a prompt quoting file:line).
7. **Prompt snippets + broadcast.** Saved prompts; send one prompt to several selected agents.
8. **Multiple machines** — data model and machine switcher support several machines, but **v1 ships and is tested with myserver only** (owner: "for now only myserver"). Bridge targets Linux/amd64 only; no macOS build.
9. **Real terminal emulator.** Live ANSI terminal for shell panes (and an agent's raw terminal on demand): colors, cursor, full-screen TUIs, soft-key row (Esc, Tab, Ctrl, Alt, arrows, PgUp/Dn).

## Non-goals (v1)

- No Play Store release, no multi-user accounts, no iOS.
- No access from outside the owner's tailnet (no public tunnel, no Cloudflare).
- No changes to herdr itself; the bridge only uses herdr's public socket API and CLI.
- No Google/Firebase push dependency.
- No background service, no system notifications (deferred by owner).
- No macOS bridge; no machines other than myserver tested.

## Context: what herdr gives us (verified on myserver, herdr 0.9.5, API protocol 22)

- `herdr server` runs on myserver (`100.64.0.10`, Tailscale name `myserver`) under the owner's login user. Binary: `/home/linuxbrew/.linuxbrew/Cellar/herdr/0.9.5/bin/herdr` (not on non-interactive SSH PATH).
- Control surface is **only** a local Unix socket, `~/.config/herdr/herdr.sock` (mode 0600), newline-delimited JSON: request `{"id","method","params"}`, response `{"id","result"}` or `{"id","error":{"code","message"}}`. No network listener. Full schema: `herdr api schema --json` (a copy was captured during brainstorming; regenerate on myserver).
- Methods available include: `session.snapshot`; `workspace.*`, `tab.*`, `pane.*` (list/get/split/close/rename/read/send_text/send_keys/send_input/resize/scroll…); `agent.list/get/read/prompt/wait/send_keys/start/rename/focus/explain`; `worktree.list/create/open/remove`; `events.subscribe` (long-lived, pushes `pane.agent_status_changed`, `pane.created/updated/closed`, `tab.*`, `workspace.*`, `worktree.*`, …).
- Agent statuses: `idle | working | blocked | done | unknown`. Agent records carry `completion_seq`, `state_change_seq`, `revision`, `agent_session` (transcript id or path), `cwd`, `terminal_title`.
- `agent.read` returns scraped terminal text (`source`: visible / recent / recent_unwrapped / detection). There is no structured transcript in the API — hence extra #1.
- Event history is **not durable**: a slow subscriber gets `events_lost` and must re-snapshot.
- Live terminal streaming is available via CLI `herdr terminal session observe|control <target>` (NDJSON on stdout: `terminal.frame` with base64 ANSI bytes, width/height/full; stdin accepts `terminal.input`, `terminal.resize`, `terminal.scroll`, `terminal.release`). This goes over herdr's internal client protocol; the bridge drives it as a subprocess.
- Live agents at time of writing: `claude` (w8:p1, idle) and `omp` (w9:p1, working). A third-party plugin `herdr-auto-title` is installed.

## Architecture (decided)

```
Android app  ⇄  WebSocket (+HTTP for uploads/diffs) over Tailscale  ⇄  whistle-bridge (per machine)  ⇄  herdr.sock / herdr CLI / transcript files / git
```

### whistle-bridge (runs on each herdr machine, as the herdr user)

- **Language: Go** (single static binary; first-class Tailscale client library for identity checks). *My call.*
- Listens **only** on the machine's Tailscale address. Every connection is authorized by asking the local `tailscaled` who the peer is (Tailscale LocalAPI WhoIs) and allowing only the owner's tailnet login (configurable allowlist). No passwords or keys on the phone. *My call.*
- Runs as a systemd **user** service on myserver.
- Responsibilities:
  - Holds one persistent `events.subscribe` connection to herdr and a periodic `session.snapshot` reconcile (and immediately on `events_lost` or reconnect).
  - **Durable, sequenced change log**: every state change the bridge observes (workspace/tab/pane/agent changes, status transitions, new transcript entries) gets a monotonically increasing bridge sequence number and is kept in a bounded on-disk log (SQLite). Clients resume with "give me everything after seq N"; if N is too old, the bridge tells the client to do a full resync. This is what guarantees no missed replies across phone sleep and reconnects.
  - **Transcript tailing**: for each live agent with a known session file, tail the file (inotify) and parse entries into a normalized message model (user / assistant text / tool call / tool result / diff). Parsers for Claude Code and omp in v1; parser is pluggable per agent type. Fallback: screen-text via `agent.read`, cleaned of TUI chrome.
  - **Completion reconciliation**: when an agent transitions to `idle`/`done`, the bridge re-reads the transcript tail to make sure the final reply is captured before emitting the "finished" event.
  - **Terminal streaming**: spawns `herdr terminal session observe|control` per open terminal view, relays frames/input over the WebSocket. Only while a client is viewing.
  - **Commands**: thin, typed passthrough to herdr methods (prompt, send_keys, split, close, rename, start agent, worktree ops).
  - **Git**: changed files, diffs, commit for a workspace's cwd / worktree.
  - **Uploads**: accept images/files from the share sheet into `~/.cache/whistle-bridge/uploads/<agent>/`.
  - **Notification feed**: status transitions to `blocked`, and `working → idle/done`, are flagged as notifiable.

### Android app

- **Kotlin + Jetpack Compose**, min SDK 29 (Android 10) *(my call)*, sideloaded APK.
- **Local cache** (Room) of the last known machine state and transcripts → instant open; then resume from last seq. Shows a clear "syncing / out of date" indicator when not caught up.
- **Connection**: one WebSocket per machine while the app is in the foreground; on returning to the foreground it resumes from the last seq (catch-up must be fast enough to meet the lag target). No background service in v1.
- Optimistic UI for sends (prompt appears immediately, marked pending until herdr acknowledges).
- Screens: machine switcher → workspace list (status rollup) → workspace (tab strip; swipeable panes) → pane (chat or terminal) ; changes view per workspace; snippets; settings (machines, allowlisted bridge hosts).
- Terminal: an embeddable terminal emulator view (e.g. Termux's `terminal-emulator`/`terminal-view` libraries — builder to verify license fit) fed by bridge frames.
- Widget + Quick Settings tile read from the local cache; refreshed when the app syncs and by a periodic WorkManager job (Android's ~15 min minimum). Stale counts show their age. *My call.*

## Constraints

- Network: phone and machines on the owner's tailnet only.
- The bridge is cross-compiled on a dev machine and copied when the server has no Go toolchain. Gradle wrapper in-repo.
- herdr API is protocol 22 / 0.9.5; bridge must check `protocol` on connect and refuse loudly on mismatch.

## Left to the builder

- Wire protocol between app and bridge (message shapes, framing), as long as it supports resume-from-seq and full resync.
- Exact transcript parsing details per agent format; how much of Claude's tool-call detail to render.
- UI visual design within the "mirror of herdr" structure (Material 3 is fine).
- Repo layout (suggest `bridge/` and `android/` in one repo), test strategy, CI.
- How snippets and machine list are stored (local on phone is fine).

## Settled after builder review

- **Streaming replies (follows from anti-goal 1).** Replies must appear while being written. Claude Code only writes complete messages to its transcript, so the chat has two sources: an **in-progress bubble** fed by live screen text (`agent.read` / terminal frames, cleaned) while the agent is `working`, which is **replaced** by the finished transcript entries as they land. The transcript stays the source of truth; the live bubble is display-only and never persisted as a message. The bridge streams live updates for every open chat view, not just terminal views.
- **Terminal size.** The phone never resizes a herdr pane by default: it renders the pane at its real size with fit-to-width zoom and horizontal pan, watch-and-type without reflowing the desktop. An explicit per-view "fit to phone" action may resize, and restores the previous size on leaving. *My call.*
- **Development against myserver.** All development and integration testing uses a **separate named herdr session** on myserver (`herdr --session whistle-dev`, its own socket under `~/.config/herdr/sessions/whistle-dev/`) and a dev bridge on a different port. The owner's default session (where `claude` and `omp` work) is read-only for the bridge until the owner explicitly says to point it there. Unit tests use recorded herdr socket traffic and sample transcript files. SSH access to myserver works (`ssh myserver`). *My call.*

## Calls made by Claude (owner hasn't explicitly agreed)

- Go for the bridge; Kotlin/Compose for the app; min SDK 29.
- Tailscale-identity auth instead of SSH or tokens.
- SQLite-backed sequenced change log in the bridge as the "never miss a reply" mechanism.
- Monorepo (bridge + app + docs).
