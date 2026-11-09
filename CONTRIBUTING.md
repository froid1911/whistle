# Contributing

Thanks for your interest. Whistle is a small project; issues and pull requests
are welcome.

- **Before larger changes**, open an issue to discuss the approach.
- **Bridge** (`bridge/`): `go test -race ./...` must pass; keep `gofmt` clean.
  If you change the wire format, update `docs/reference/wire-protocol.md` and
  both sides' tests. Signed-request fixtures are shared between the Go and
  Android tests (see `bridge/internal/devauth/devauth_test.go` and
  `WhistleAuthTest`).
- **App** (`android/`): `./gradlew :app:testDebugUnitTest :app:assembleRelease`
  must pass. UI tests run on Robolectric; no device is needed.
- **Plugin manifest:** edit `bridge/plugin/herdr-plugin.toml` and mirror the
  change in the repo-root `herdr-plugin.toml` (a test enforces this).
- Never commit secrets: keystores, `keystore.properties`, `local.properties`,
  real hostnames/IPs or transcripts. Use `100.64.0.x` and `myserver` in
  examples.
- By contributing you agree that your contribution is licensed under the
  AGPL-3.0 (see `LICENSE`).
