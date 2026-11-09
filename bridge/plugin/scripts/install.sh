#!/bin/sh
# Build step for the whistle.bridge herdr plugin: produce bin/whistle-bridge
# (next to this script's parent, i.e. bridge/plugin/bin/). This is what
# `herdr plugin install froid1911/whistle` runs, via the repo-root manifest.
#
# 1. Download the prebuilt binary for this OS/arch from the GitHub release
#    matching the manifest version (v<version>) and verify it against the
#    release's SHA256SUMS. No Go toolchain needed.
# 2. If that fails (offline, no release for this version, checksum mismatch),
#    build from source with Go when this dir sits inside the whistle repo.
# 3. Else keep a prebuilt bin/whistle-bridge (`make plugin-deploy-*` copies one in).
#
# Environment:
#   WHISTLE_BUILD_FROM_SOURCE=1  skip the download, build with Go
#   WHISTLE_RELEASE_BASE_URL     release download base (default GitHub releases)
set -eu
root=$(CDPATH='' cd -- "$(dirname -- "$0")/.." && pwd)
cd "$root"
mod=$(dirname -- "$root")
say() { echo "whistle.bridge: $*" >&2; }

version=$(sed -n 's/^version *= *"\([^"]*\)".*/\1/p' herdr-plugin.toml | head -n 1)
base=${WHISTLE_RELEASE_BASE_URL:-https://github.com/froid1911/whistle/releases/download}

platform() {
	case $(uname -s) in
	Linux) os=linux ;;
	Darwin) os=darwin ;;
	*) return 1 ;;
	esac
	case $(uname -m) in
	x86_64 | amd64) arch=amd64 ;;
	aarch64 | arm64) arch=arm64 ;;
	*) return 1 ;;
	esac
}

fetch() { # url dest
	if command -v curl >/dev/null 2>&1; then
		curl -fsSL --retry 2 --connect-timeout 15 -o "$2" "$1"
	elif command -v wget >/dev/null 2>&1; then
		wget -q -T 30 -O "$2" "$1"
	else
		say "neither curl nor wget found"
		return 1
	fi
}

sha256() {
	if command -v sha256sum >/dev/null 2>&1; then
		sha256sum "$1" | cut -d' ' -f1
	elif command -v shasum >/dev/null 2>&1; then
		shasum -a 256 "$1" | cut -d' ' -f1
	else
		say "neither sha256sum nor shasum found; cannot verify the download"
		return 1
	fi
}

# Runs in a subshell (see below): its EXIT trap removes the temp dir.
download() {
	[ -n "$version" ] || { say "no version in herdr-plugin.toml"; return 1; }
	platform || { say "no prebuilt binary for $(uname -s)/$(uname -m)"; return 1; }
	tarball="whistle-bridge_${version}_${os}_${arch}.tar.gz"
	url="$base/v$version"
	tmp=$(mktemp -d)
	trap 'rm -rf "$tmp"' EXIT
	say "downloading $url/$tarball"
	fetch "$url/SHA256SUMS" "$tmp/SHA256SUMS" || { say "no release v$version (SHA256SUMS not found)"; return 1; }
	fetch "$url/$tarball" "$tmp/$tarball" || { say "download of $tarball failed"; return 1; }
	want=$(awk -v f="$tarball" '$2 == f || $2 == "*" f { print $1 }' "$tmp/SHA256SUMS")
	got=$(sha256 "$tmp/$tarball") || return 1
	if [ -z "$want" ] || [ "$want" != "$got" ]; then
		say "checksum mismatch for $tarball (want ${want:-none}, got $got)"
		return 1
	fi
	tar -xzf "$tmp/$tarball" -C "$tmp" whistle-bridge || { say "bad archive $tarball"; return 1; }
	chmod 755 "$tmp/whistle-bridge"
	mkdir -p bin
	mv "$tmp/whistle-bridge" bin/whistle-bridge.new && mv bin/whistle-bridge.new bin/whistle-bridge || return 1
	say "installed whistle-bridge v$version ($os/$arch, sha256 verified)"
}

build() {
	go=$(command -v go 2>/dev/null || true)
	[ -z "$go" ] && [ -x "$HOME/.local/go/bin/go" ] && go=$HOME/.local/go/bin/go
	[ -n "$go" ] || return 1
	grep -q '^module github.com/froid1911/whistle/bridge$' "$mod/go.mod" 2>/dev/null || return 1
	mkdir -p bin || return 1
	(cd "$mod" && CGO_ENABLED=0 "$go" build -trimpath -ldflags "-X main.version=$version-src" \
		-o "$root/bin/whistle-bridge.new" ./cmd/whistle-bridge) || { say "go build failed"; return 1; }
	mv bin/whistle-bridge.new bin/whistle-bridge || return 1
	say "built bin/whistle-bridge with $go"
}

if [ "${WHISTLE_BUILD_FROM_SOURCE:-}" != 1 ] && (download); then
	exit 0
fi
if build; then
	exit 0
fi
if [ -x bin/whistle-bridge ]; then
	say "using prebuilt bin/whistle-bridge"
	exit 0
fi
say "could not install whistle-bridge v$version: no verified release binary (see above) and no Go build."
say "Install Go (see bridge/go.mod) and retry, or download a release binary from"
say "https://github.com/froid1911/whistle/releases into $root/bin/whistle-bridge"
exit 1
