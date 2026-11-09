# Whistle UI — "Night pasture" (direction B)

Date: 2026-11-09. Owner picked direction B from the mockups ("b is a good start").

## Intent
Whistle is a pocket remote for a herd of AI coding agents, used one-handed, often late at night. The screen's job: show at a glance who needs you, let you read what they said, and send a nudge. The old UI read as stock 2010s Android; the new one should feel calm, warm and characterful, with exactly one loud signal: **an agent that needs you glows lamp-amber**.

## Tokens
Colors (dark is the primary theme):
- `night` #161D1A — app background
- `card` #1F2924 — cards, composer, sheets
- `cardHi` #2C3A33 — your own messages, pressed/selected surfaces
- `inset` #0F1412 — terminal strips, code
- `wool` #E8E3D6 — primary text
- `sage` #93A39A — secondary text
- `line` #3A4741 — hairline outlines (sparingly)
- `lamp` #F5A524 — **needs you** (blocked / approval) — the only saturated warm color; on-lamp text #1B1206
- `sky` #8CC4E8 — working
- `heather` #B9A6E8 — done (finished, not yet looked at)
- `moss` #5F7268 — idle / unknown
- `ember` #E5684C — errors (failed sends, git errors)

Light theme (follows system setting): background #F3F1EA is NOT allowed (cliché); use `mist` #EEF2EF bg, `card` #FFFFFF, text #1E2622, secondary #5B6B62; status colors darkened for contrast (lamp #B86E00, sky #2B6E99, heather #6A55B0, moss #5F7268, ember #B33A22). Contrast ≥ 4.5:1 for text.

Type (bundled fonts, no runtime downloads — `res/font`):
- **Bricolage Grotesque** — names, screen titles (600–700).
- **Instrument Sans** — all body/UI text (400/500/600).
- **Commit Mono** — terminal, commands, tool inputs only.
Scale (sp): title 28/32 · heading 20/26 · name 16/20 · body 15/22 · secondary 13/18 · small 12/16. Sentence case everywhere; no all-caps labels.

Shape & space: cards radius 20dp, chips/pills 999, composer 24, sheets 28 top. 8dp grid; screen gutters 16dp; card padding 14dp. No drop shadows; elevation by surface color only.

Motion: exactly one ambient animation — the lamp ripple ring around a needs-you agent's orb (2.4 s ease-out, scale .8→1.5, fade), disabled under reduced-motion/animator-scale 0. Everything else moves only in response to touch (expand/collapse 180 ms).

## Components
- **Agent orb**: 40dp circle in status color with the agent-kind glyph (claude ✳, omp π, codex ◆, others first letter), on-color text = night. Needs-you orb gets the ripple.
- **Herd screen** (replaces workspaces list as the start screen for a machine): title "Your herd", subline "3 agents on myserver · 1 needs you" (or "all quiet"). Sections: **Needs you** (pinned, lamp-glow cards with the question text from agent.screen and answer buttons inline), then agents grouped by workspace (workspace label as a quiet secondary heading), each a card: orb, name, "workspace · working 2m" / "done 14m ago". Shell panes listed per workspace as compact rows with a `>_` glyph. Workspace/tab/pane management (create, rename, close, split) moves to a per-workspace overflow and to the workspace screen — the herd screen is for agents.
- **Workspace screen**: keeps tabs → swipeable panes (the herdr mirror), restyled: tab strip as pills, pane header = orb + name + status line, overflow menu.
- **Chat**: no avatar bubbles for the agent — agent text is plain prose on night (Markdown styled with the type scale; code blocks in `inset`). Your prompts: `cardHi` bubbles, right-aligned, radius 18. Tool calls of one turn fold into a row of small chips ("read skill", "grep schema", "+2"); tapping expands the step list; each step expands to input/result (diffs keep red/green but tuned to the palette: add #2E4A36 bg / #9FD8A8 text, del #4A2A2A / #F0A8A0). Thinking collapsed to a single sage line. Live text: inset terminal strip, cleaned, max 8 lines, with a "working" sky dot. Approval card: lamp-glow card in the stream with the question and pill buttons.
- **Composer**: floating `card` pill above the keyboard (edge-to-edge, IME insets), placeholder "Whistle to omp", mic + snippets icons, round wool send button.
- **Connection banner**: slim pill under the title, not a full-width bar: "Syncing…" (sky), "Offline — showing cached" (sage), "herdr offline" (ember).
- **Terminal**: inset background, soft-key row as pill chips.
- **Changes, snippets, broadcast, machines, share sheet**: same tokens; lists as cards; destructive actions in ember.
- **Widget / tile**: night background, orb-colored counts; lamp glow if anything needs you.
- **Icon**: a whistle silhouette in lamp on night (adaptive icon, monochrome layer for themed icons).

## Non-goals
No behavior changes beyond layout (sync, outbox, terminal logic untouched); no new features; no dynamic color (Material You) — the palette is the brand.

## Acceptance
- Every screen uses the tokens (no stray Material default purple/teal).
- Needs-you is visible on the herd screen within one glance, with inline answer buttons.
- Herd screen and chat render from cache < 300 ms (no regressions).
- Reduced motion disables the ripple.
- All existing unit tests pass; screenshot each main screen in Robolectric (or on device) for the review.
