# Releasing Whistle

App and bridge plugin share one version `X.Y.Z` and one tag `vX.Y.Z`. The
release workflow (`.github/workflows/release.yml`) refuses a tag whose version
does not match all three places below.

## Checklist

1. Bump the version in
   - `herdr-plugin.toml` (repo root) **and** `bridge/plugin/herdr-plugin.toml`
     (`version = "X.Y.Z"`; `TestRootManifestInSync` keeps them identical),
   - `android/app/build.gradle.kts` (the `whistleVersionName` default,
     `?: "X.Y.Z"`; versionCode follows as `X*10000 + Y*100 + Z`),
   - the "Status" line in `README.md`.
2. Run the tests:
   ```sh
   (cd bridge && go test -race ./...)
   (cd android && ./gradlew -q :app:testDebugUnitTest :app:assembleDebug)
   ```
3. Commit, push `main`, wait for CI to pass.
4. Tag and push:
   ```sh
   git tag -a vX.Y.Z -m "Whistle X.Y.Z" && git push origin vX.Y.Z
   ```
5. The workflow builds `whistle-bridge_X.Y.Z_{linux,darwin}_{amd64,arm64}.tar.gz`,
   the signed `whistle-X.Y.Z.apk` (signature checked against the pinned
   certificate fingerprint), `SHA256SUMS`, and publishes the GitHub Release.
6. Check the release page; `herdr plugin install froid1911/whistle` now
   downloads the new binary (the manifest version points at the tag).

A tag like `vX.Y.Z-rc.1` is published as a pre-release. The app's update
check and Obtainium (by default) ignore pre-releases; the plugin install only
finds a pre-release binary when the manifest carries that exact version.

## Signing key

The APK is signed with the Whistle release key (alias `whistle`, RSA 4096).
Certificate SHA-256:
`CB:3C:B4:51:E3:50:4D:37:2D:8E:19:9E:A5:45:6D:33:94:39:2F:98:9C:0A:77:F4:27:C7:A4:14:5E:EA:62:60`

It lives only in the repository's Actions secrets
(`WHISTLE_KEYSTORE_BASE64`, `WHISTLE_KEYSTORE_PASSWORD`, `WHISTLE_KEY_ALIAS`,
`WHISTLE_KEY_PASSWORD`) and in the maintainer's offline backup, never in the
repository. Losing it means users must uninstall and reinstall to update.
