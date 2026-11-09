# Whistle

Whistle is an Android remote for [herdr](https://herdr.dev) agent sessions.
herdr runs your coding agents (Claude Code, Codex, omp, ...) in terminal
workspaces on a server; Whistle puts that herd in your pocket: see which agents
are working or waiting for you, read their conversations as chat, answer
approval prompts, send prompts (typed or dictated), share files and screenshots
into an agent, and drop into a live terminal when you need one.

Status: early (app 0.5.0, bridge plugin 0.3.1). Tested with herdr 0.9.5 on
Linux and a Pixel phone.

## How it works

```
Android app  ⇄  HTTP + WebSocket over Tailscale  ⇄  whistle-bridge  ⇄  herdr socket / CLI / transcripts
                                                    (herdr plugin, one per session)
```

- **Bridge** (`bridge/`, Go): a herdr plugin (`whistle.bridge`). herdr starts
  it in each session's server; it reads herdr's API socket and the agents'
  transcript files, keeps a sequenced change log in SQLite (so the phone never
  misses a reply), and serves the app on the machine's Tailscale address.
- **App** (`android/`, Kotlin/Jetpack Compose): caches everything locally so it
  opens instantly, follows live changes, renders agent panes as chat and other
  panes through xterm.js. Home-screen widget and quick-settings tile included.
- Wire protocol: [`docs/reference/wire-protocol.md`](docs/reference/wire-protocol.md).
  Background design notes: [`docs/design/`](docs/design/).

## Security model

- **Tailscale only.** The bridge listens only on the machine's Tailscale IPv4
  address. There is no public endpoint, relay or cloud service.
- **Device pairing.** A phone pairs by scanning a one-time QR code (5 minutes,
  single use) that only someone with a shell on the machine can create. The
  phone generates a P-256 key in the Android Keystore (StrongBox when
  available); the private key never leaves the device.
- **Challenge auth.** Every request is signed (`Whistle v2`): method, path,
  host, timestamp, nonce, body hash and a fresh server challenge. The app pins
  the bridge's identity key from the QR code and refuses a bridge that cannot
  prove it.
- **Revocation.** Unpair a phone from the "Whistle: devices" popup; its
  connections close at once.
- **On the phone.** Optional app lock (biometric / device credential) and
  step-up confirmation for sensitive actions.

More in [SECURITY.md](SECURITY.md).

## Requirements

- herdr **0.9.5 or newer** on the server (Linux or macOS).
- Go (version in `bridge/go.mod`) on the server for `herdr plugin install`,
  or build the bridge elsewhere and link it (below).
- Tailscale on the server and on the phone, same tailnet.
- Android 10 (API 29) or newer.

## Install the herdr plugin

From GitHub (herdr clones the repo, runs the build step, which compiles
`bridge/` with your Go toolchain into `bridge/plugin/bin/`):

```sh
herdr plugin install froid1911/whistle
```

The repo-root `herdr-plugin.toml` is the manifest for this; `bridge/plugin/`
holds the same manifest for linked installs. Note: while the repository is
private, the install only works where `git clone` of it is authenticated.

Or from a clone (`plugin link` never builds, so build first):

```sh
git clone https://github.com/froid1911/whistle && cd whistle/bridge
make build                                   # -> bin/whistle-bridge (linux/amd64)
mkdir -p plugin/bin && cp bin/whistle-bridge plugin/bin/
herdr plugin link "$PWD/plugin"
```

The bridge starts with the next herdr server start. To start it in an already
running server: `herdr plugin action invoke whistle.bridge.restart`. Check it
with `herdr plugin action invoke whistle.bridge.status` and
`herdr plugin log list --plugin whistle.bridge`.

Configuration lives in `herdr plugin config-dir whistle.bridge`:

- `sessions.conf`: which herdr session gets a bridge and where it listens
  (default: the default session on `tailscale:8787`).
- `auth.conf`: who may connect (default `auth devices`: paired phones only).

Data: `~/.local/share/whistle-bridge/<session>.db`; bridge identity key and
logs under `~/.local/state/herdr/plugins/whistle.bridge/`. Operations details:
[`bridge/deploy/README.md`](bridge/deploy/README.md).

## Pair a phone

In herdr, run **"Whistle: pair a phone"** from the command list. A popup shows
a QR code, the machine's address and a 26-character code. In the app:
Machines → Pair a machine → scan → Pair this phone. **"Whistle: devices"**
lists paired phones (`u` unpairs).

Bind keys in herdr's `config.toml`, for example Ctrl+Alt+W:

```toml
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
```

## Build the app

Needs JDK 17+ and the Android SDK (`android/local.properties` with
`sdk.dir=...`, or `ANDROID_HOME`).

```sh
cd android
./gradlew :app:testDebugUnitTest      # unit + Robolectric UI tests
./gradlew :app:assembleDebug          # app/build/outputs/apk/debug/
./gradlew :app:assembleRelease        # R8-minified; see signing below
```

Release signing reads, in this order, environment variables or a gitignored
`android/keystore.properties`:

| Env var | `keystore.properties` key |
|---|---|
| `WHISTLE_KEYSTORE` (path, relative to `android/`) | `storeFile` |
| `WHISTLE_KEYSTORE_PASSWORD` | `storePassword` |
| `WHISTLE_KEY_ALIAS` | `keyAlias` |
| `WHISTLE_KEY_PASSWORD` | `keyPassword` |

Without them, `assembleRelease` still succeeds but signs with the local debug
key: fine for sideloading your own build, not for distribution.

## Build and test the bridge

```sh
cd bridge
go test -race ./...
make build                # static linux/amd64 binary in bin/
```

`make plugin-deploy-prod SERVER=<ssh host> SERVER_IP=<tailscale ip>` cross-builds
and deploys to a server without Go; see `bridge/deploy/README.md`.

### Migrating from an older install

Earlier builds stored data in `~/.local/share/herdr-bridge/` under a different
plugin id. To keep paired phones and history, stop the old bridge, then copy
the databases, the bridge key and the config before starting Whistle's:

```sh
old_id=<previous plugin id>    # see `herdr plugin list`
mkdir -p ~/.local/share/whistle-bridge
cp -p ~/.local/share/herdr-bridge/*.db ~/.local/share/whistle-bridge/
new=~/.local/state/herdr/plugins/whistle.bridge
mkdir -p "$new" && cp -p ~/.local/state/herdr/plugins/$old_id/bridge-key.pem "$new/"
mkdir -p ~/.config/herdr/plugins/config/whistle.bridge
cp -p ~/.config/herdr/plugins/config/$old_id/*.conf ~/.config/herdr/plugins/config/whistle.bridge/
# then unlink/remove or disable the old plugin (see `herdr plugin --help`)
```

(The app's package name also changed, so the app itself must be reinstalled and
re-paired unless you keep the old build.)

## License

[GNU Affero General Public License v3.0](LICENSE). Third-party components:
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
