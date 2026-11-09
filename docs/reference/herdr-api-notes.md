# herdr API — verified facts (herdr 0.9.5, protocol 22)

Captured 2026-11-08 against a live herdr 0.9.5 server. Full schema: run `herdr api schema --json` against your installed herdr (not vendored here).

## Transport
- Unix socket `~/.config/herdr/herdr.sock` (mode 0600). Named session `X` → `~/.config/herdr/sessions/X/herdr.sock`.
- Newline-delimited JSON. One request per line; responses carry the same `id`.
  - **One request per connection** (verified 2026-11-08, dev session): after answering a non-subscription request the server closes the connection; a second request on it gets EPIPE. Dial a fresh connection per call. `events.subscribe` connections stay open.
  - Request `{"id":"r1","method":"agent.list","params":{}}`
  - Success `{"id":"r1","result":{"type":"agent_list","agents":[...]}}`
  - Error `{"id":"r1","error":{"code":"not_found","message":"..."}}`
- With Homebrew on Linux the herdr binary is `/home/linuxbrew/.linuxbrew/bin/herdr`, which is not on the PATH of non-login SSH commands; use the full path there.

## session.snapshot
`result = {"type":"session_snapshot"?, "snapshot":{ "protocol":22, "version":"0.9.5", "focused_workspace_id", "focused_tab_id", "focused_pane_id", "workspaces":[], "tabs":[], "panes":[], "agents":[], "layouts":[] }}`

- workspace: `{workspace_id:"w8", number:1, label:"~", active_tab_id, agent_status, focused, pane_count, tab_count, tokens:{topic}}`
- tab: `{tab_id:"w8:t1", workspace_id, number, label, agent_status, focused, pane_count}`
- pane: `{pane_id:"w8:p1", tab_id, workspace_id, terminal_id, cwd, foreground_cwd, label, terminal_title, terminal_title_stripped, focused, revision, scroll:{offset_from_bottom,max_offset_from_bottom,viewport_rows}, agent?, agent_status?, agent_session?}`
- agent: pane fields plus `completion_seq`, `state_change_seq`, `screen_detection_skipped?`
- agent_session: `{agent:"claude", kind:"id"|"path", source:"herdr:claude", value:"<uuid>"|"/abs/path.jsonl"}`
- layout: `{tab_id, workspace_id, area:{x,y,width,height}, focused_pane_id, panes:[{pane_id, focused, rect}], splits:[], zoomed}`
- AgentStatus: `idle | working | blocked | done | unknown`

`revision` on panes bumps on every screen/title change (many per second while an agent works) — do NOT treat revision changes as meaningful state changes.

## events.subscribe
Long-lived connection. Params `{"subscriptions":[{"type":"pane.updated"}, ...]}`. First response `{"id":..,"result":{"type":"subscription_started"}}`, then event lines:
`{"event":"pane_updated","data":{"type":"pane_updated","pane":{...}}}`

- Global types (only `type` needed): `workspace.created/updated/metadata_updated/renamed/moved/reordered/closed/focused`, `worktree.created/opened/removed`, `tab.created/closed/focused/renamed/moved`, `pane.created/closed/updated/focused/moved/exited/agent_detected`, `layout.updated`.
- Per-pane types (REQUIRE `pane_id`): `pane.agent_status_changed {pane_id, agent_status?}`, `pane.scroll_changed {pane_id}`, `pane.output_matched {pane_id, source, match, ...}`. Omitting `pane_id` → `invalid_request: missing field pane_id`.
- Not durable: a slow subscriber may receive `error.code:"events_lost"`; recover by re-snapshotting.

## Methods used by the bridge (params)
- `agent.prompt {target, text, wait?:{until:[AgentStatus], timeout_ms}}` — errors `agent_blocked`, `agent_prompt_stalled`, `timeout`.
- `agent.read {target, source:"visible"|"recent"|"recent_unwrapped"|"detection", lines?, format:"text"|"ansi", strip_ansi:true}` → `{"type":"pane_read","read":{pane_id, workspace_id, tab_id, source, format, text, revision, truncated}}` (text is nested under `read`, verified).
- `agent.send_keys {target, keys:["enter","esc","ctrl+c","y",...]}`; `agent.start {name, kind, pane_id, args?, timeout_ms?}`; `agent.rename`.
- `pane.split {direction, target_pane_id?, workspace_id?, cwd?, focus:false}` → `.result.pane.pane_id`; `pane.close {pane_id}`; `pane.rename`; `pane.send_text {pane_id,text}`; `pane.send_keys {pane_id,keys}`; `pane.read {pane_id, source, lines?, format, strip_ansi}`.
- `tab.create {workspace_id?, cwd?, label?, focus:false}` → `{type:"tab_created", tab:{tab_id,...}, root_pane:{pane_id,...}}` (`root_pane` not in the schema but present; same for `workspace.create`), `tab.rename`, `tab.close`; `workspace.create {cwd?, label?, focus:false}`, `workspace.rename`, `workspace.close`; `worktree.list`.
- Always pass `focus:false` from the phone so the desktop's focus is not stolen.

## Terminal streaming (CLI only)
- `herdr terminal session observe [--cols N --rows N] <target>` — NDJSON on stdout. Frame: `{"type":"terminal.frame","seq":N,"encoding":"ansi","width":W,"height":H,"full":bool,"bytes":"<base64>"}`, final `{"type":"terminal.closed",...}`. (Observed: first line may be a bare `{"bytes":...}`-bearing frame; parse by `bytes` presence, tolerate missing fields.)
- `herdr terminal session control [--takeover] [--cols N --rows N] <target>` — same stdout; stdin NDJSON: `{"type":"terminal.input","text":"..."}` or `{"type":"terminal.input","bytes":"<b64>"}`, `{"type":"terminal.resize","cols":C,"rows":R}`, `{"type":"terminal.scroll",...}`, `{"type":"terminal.release"}`.
- The target must come BEFORE `--cols/--rows` (`... observe <target> --cols N --rows N`); options first → `unknown terminal session observe option: N`.
- Verified: observe + `--cols/--rows` renders frames at that size (first frame 60x20) and does NOT resize the real pane. control + `--cols/--rows` DOES resize the pane's terminal (`scroll.viewport_rows` 40→20; layout rect unchanged) and the size persists after the control session ends.
- Control session with stdin at EOF ends immediately with `{"type":"terminal.closed","reason":"detached"}`.
- Frames: `{type:"terminal.frame", seq, encoding:"ansi", width, height, full, bytes}`; first frame `full:true`, later ones diffs.

## Agent lifecycle (verified, Claude Code 2.1.x)
- `agent.start` launches the agent in the pane's current shell cwd. In an untrusted folder Claude shows a trust dialog whose DEFAULT is "No, exit" → start returns `agent_not_ready` (status `blocked`); answer with `down` then `enter`.
- `agent_session` (Claude id) appears once Claude is ready (before the first prompt).
- A finished turn in an unfocused pane goes `working → done`, not `→ idle`; treat `done` and `idle` both as turn end.

## Transcript files
- Claude Code (`agent_session.kind=="id"`): `~/.claude/projects/*/<id>.jsonl`. One JSON object per line. Relevant: `type:"user"` with `message.content` string (prompt) or list of `tool_result` blocks (`tool_use_id`, `content`, `is_error`); `type:"assistant"` with `message.content` list, ONE block per line (`text` | `thinking` | `tool_use{id,name,input}`), consecutive lines share `message.id`. Use top-level `uuid` as entry id, `timestamp`. Skip `isSidechain:true`, `isMeta:true`, and all other `type`s (`system`, `attachment`, `api-request*`, `mode`, `file-history-snapshot`, ...). User messages whose content starts with `<` command/system tags (e.g. `<command-name>`, `<local-command-stdout>`, `<system-reminder>`) are meta: skip.
- omp (`kind=="path"`): `type:"message"` lines, `id`, `timestamp`, `message.role` ∈ `user|assistant|toolResult|developer|custom`; content list blocks `text{text}`, `thinking{thinking}`, `toolCall{id,name,arguments}`; toolResult has `toolCallId`, `toolName`, `content[{type:text,text}|{type:image}]`, `isError?`. Keep user/assistant/toolResult; skip developer/custom and non-`message` types (`custom`, `model_usage`, `compaction`, ...).
- Claude writes a line only when a block completes → live text must come from the screen while working.

## Plugins (verified 2026-11-09 with a probe plugin; docs: herdr `docs/.../plugins.mdx` v0.9.5)
- **Global, not per session.** `plugins.json` lives in `~/.config/herdr/`; `herdr --session X plugin link DIR` registers the plugin for *every* session (`plugin list` without `--session` shows it). Config dir `~/.config/herdr/plugins/config/<id>/`, state dir `~/.local/state/herdr/plugins/<id>/` — also shared by all sessions.
- **`plugin link` runs nothing**: no `[[build]]` (only `plugin install` builds), no `[[startup]]`. The manifest is re-read live: actions added to a linked manifest are invocable at once without relink/reload.
- **`[[startup]]` runs once per server start** (and on live handoff), in every running session's server, *not* on link/enable/config reload/client attach. Process = direct child of `herdr [--session X] server`, cwd = plugin root. Long-running startup processes are fine (auto-title does it).
- **Not supervised**: a killed startup process is not restarted. **Not stopped**: on `herdr server stop` children are left running (reparented to init); plugins must exit themselves (auto-title watches the socket). Under a systemd-managed server (`herdr-whistle-dev.service`) the unit's cgroup kill does stop them.
- **Env** (startup + actions): `HERDR_SOCKET_PATH`, `HERDR_BIN_PATH` (Cellar path of the running herdr), `HERDR_ENV=1`, `HERDR_PLUGIN_ID`, `HERDR_PLUGIN_ROOT`, `HERDR_PLUGIN_CONFIG_DIR`, `HERDR_PLUGIN_STATE_DIR`, `HERDR_PLUGIN_CONTEXT_JSON`; named sessions also get `HERDR_SESSION=<name>`; the **default session has no `HERDR_SESSION`** (identify it by `HERDR_SOCKET_PATH=~/.config/herdr/herdr.sock`). Startup: `HERDR_PLUGIN_EVENT=startup`, context `{"invocation_source":"startup"}`. Actions: `HERDR_PLUGIN_ACTION_ID`, plus `HERDR_WORKSPACE_ID/TAB_ID/PANE_ID` of the focused pane. Server env is inherited (PATH etc.).
- **Actions**: `herdr [--session X] plugin action invoke <plugin.id>.<action>` (qualified id; `--plugin ID` before the id is rejected). Returns immediately (`status:"running"`); the command runs as a child of that session's server — this is how to start plugin work in an already-running server without restarting it.
- **Logs**: `herdr plugin log list --plugin ID [--limit N]` — per server, in memory; stdout/stderr appear only after the process exits and are cut at 64 KiB (`[herdr truncated plugin output after 65536 bytes]`). The pipe is drained while running (no blocking). Long-running plugins need their own log file.
- `herdr plugin list --plugin ID` for an unknown id prints `No plugins installed.` with rc 0.
- **Popup panes** (verified 2026-11-09, herdr 0.9.5): a manifest `[[panes]]` entry (`id`, `title`, `placement = "popup"`, `width`/`height` like `"60%"`, `command`) is opened from an action with `herdr [--session X] plugin pane open --plugin ID --entrypoint PANE --placement popup --focus [--env K=V]` (`popup` is accepted although `--help` lists only overlay/split/tab/zoomed); it prints `{"result":{"type":"ok"}}` and no pane id. The popup is not in `pane list`, its process gets no `HERDR_PANE_ID` (pass what it needs with `--env`), and it closes when its process exits.
