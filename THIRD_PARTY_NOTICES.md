# Third-party notices

Whistle is licensed under the GNU Affero General Public License v3.0 (see
`LICENSE`). It bundles or links the third-party components below, each under
its own license.

## Fonts (Android app)

All three fonts are licensed under the SIL Open Font License 1.1. The full
license texts ship in the app at `android/app/src/main/assets/licenses/`.

| Font | Files | Copyright | License text |
|---|---|---|---|
| Bricolage Grotesque | `res/font/bricolage_grotesque_*.ttf` | The Bricolage Grotesque Project Authors | `OFL-BricolageGrotesque.txt` |
| Instrument Sans | `res/font/instrument_sans_*.ttf` | The Instrument Sans Project Authors | `OFL-InstrumentSans.txt` |
| Commit Mono | `res/font/commit_mono_regular.ttf`, `assets/xterm/CommitMono-Regular.ttf` | Eigil Nikolajsen | `OFL-CommitMono.txt` |

## JavaScript (Android app, `android/app/src/main/assets/xterm/`)

| Component | Version | License |
|---|---|---|
| xterm.js (`@xterm/xterm`) | 5.5.0 | MIT, see `assets/xterm/LICENSE` |
| `@xterm/addon-fit` | 0.10.0 | MIT (same authors and license) |
| `@xterm/addon-webgl` | 0.18.0 | MIT (same authors and license) |

## Android libraries

Pulled by Gradle at build time (`android/gradle/libs.versions.toml`); all
Apache License 2.0:

- AndroidX (Core, Activity, Lifecycle, Navigation, Fragment, Room, WorkManager,
  Glance, Biometric, CameraX) and Jetpack Compose (UI, Material 3, icons)
- Kotlin standard library, kotlinx.coroutines, kotlinx.serialization
- OkHttp (Square)
- multiplatform-markdown-renderer (Mike Penz)
- ZXing core

Test-only: JUnit 4 (EPL-1.0), Robolectric (MIT), MockWebServer (Apache-2.0).

## Go modules (bridge)

Modules linked into `whistle-bridge` (`go list -deps ./cmd/whistle-bridge`),
with the license found in each module's source. The Go standard library is
BSD-3-Clause.

| Module | Version | License |
|---|---|---|
| filippo.io/edwards25519 | v1.2.0 | BSD-3-Clause |
| github.com/aymanbagabas/go-osc52/v2 | v2.0.1 | MIT |
| github.com/charmbracelet/bubbletea | v1.3.10 | MIT |
| github.com/charmbracelet/colorprofile | v0.4.3 | MIT |
| github.com/charmbracelet/lipgloss | v1.1.0 | MIT |
| github.com/charmbracelet/x/ansi | v0.11.8 | MIT |
| github.com/charmbracelet/x/cellbuf | v0.0.15 | MIT |
| github.com/charmbracelet/x/term | v0.2.2 | MIT |
| github.com/clipperhouse/displaywidth | v0.11.0 | MIT |
| github.com/clipperhouse/uax29/v2 | v2.7.0 | MIT |
| github.com/coder/websocket | v1.8.15 | ISC |
| github.com/dustin/go-humanize | v1.1.0 | MIT |
| github.com/fsnotify/fsnotify | v1.10.1 | BSD-3-Clause |
| github.com/fxamacker/cbor/v2 | v2.9.3 | MIT |
| github.com/go-json-experiment/json | v0.0.0-20260820222146-c27c302e5fc3 | BSD-3-Clause |
| github.com/google/uuid | v1.6.0 | BSD-3-Clause |
| github.com/hdevalence/ed25519consensus | v0.2.0 | BSD-3-Clause |
| github.com/jsimonetti/rtnetlink | v1.4.2 | MIT |
| github.com/lucasb-eyer/go-colorful | v1.4.1 | MIT |
| github.com/mattn/go-isatty | v0.0.24 | MIT |
| github.com/mattn/go-runewidth | v0.0.24 | MIT |
| github.com/mdlayher/netlink | v1.11.2 | MIT |
| github.com/mdlayher/socket | v0.7.0 | MIT |
| github.com/mitchellh/go-ps | v1.0.0 | MIT |
| github.com/muesli/ansi | v0.0.0-20230316100256-276c6243b2f6 | MIT |
| github.com/muesli/cancelreader | v0.2.2 | MIT |
| github.com/muesli/termenv | v0.16.0 | MIT |
| github.com/remyoudompheng/bigfft | v0.0.0-20230129092748-24d4a6f8daec | BSD-3-Clause |
| github.com/rivo/uniseg | v0.4.7 | MIT |
| github.com/skip2/go-qrcode | v0.0.0-20200617195104-da1b6568686e | MIT |
| github.com/x448/float16 | v0.8.4 | MIT |
| github.com/xo/terminfo | v1.0.0 | MIT |
| go4.org/mem | v0.0.0-20240501181205-ae6ca9944745 | Apache-2.0 |
| go4.org/netipx | v0.0.0-20260823151212-3075585bcbeb | BSD-3-Clause |
| golang.org/x/crypto | v0.58.0 | BSD-3-Clause |
| golang.org/x/exp | v0.0.0-20260908205506-85c1c2202aba | BSD-3-Clause |
| golang.org/x/net | v0.60.0 | BSD-3-Clause |
| golang.org/x/sync | v0.24.0 | BSD-3-Clause |
| golang.org/x/sys | v0.49.0 | BSD-3-Clause |
| modernc.org/libc | v1.80.1 | BSD-3-Clause |
| modernc.org/mathutil | v1.7.1 | BSD-3-Clause |
| modernc.org/memory | v1.12.1 | BSD-3-Clause |
| modernc.org/sqlite | v1.62.0 | MIT |
| tailscale.com | v1.104.5 | BSD-3-Clause |
