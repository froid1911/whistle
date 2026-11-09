#!/bin/sh
# Build step for the whistle.bridge herdr plugin: produce bin/whistle-bridge
# (next to this script's parent, i.e. bridge/plugin/bin/).
# Inside the whistle repo with Go available -> build it (this is what
# `herdr plugin install froid1911/whistle` runs, via the repo-root manifest);
# otherwise require the prebuilt binary shipped in bin/ (servers without Go:
# `make plugin-deploy-*` copies a cross-compiled one in).
set -eu
root=$(CDPATH='' cd -- "$(dirname -- "$0")/.." && pwd)
cd "$root"
mod=$(dirname -- "$root")
go=$(command -v go 2>/dev/null || true)
[ -z "$go" ] && [ -x "$HOME/.local/go/bin/go" ] && go=$HOME/.local/go/bin/go
if [ -n "$go" ] && grep -q '^module github.com/froid1911/whistle/bridge$' "$mod/go.mod" 2>/dev/null; then
	mkdir -p bin
	(cd "$mod" && CGO_ENABLED=0 "$go" build -trimpath -o "$root/bin/whistle-bridge.new" ./cmd/whistle-bridge)
	mv bin/whistle-bridge.new bin/whistle-bridge
	echo "whistle.bridge: built bin/whistle-bridge with $go" >&2
elif [ -x bin/whistle-bridge ]; then
	echo "whistle.bridge: using prebuilt bin/whistle-bridge" >&2
else
	echo "whistle.bridge: no Go toolchain and no prebuilt bin/whistle-bridge" >&2
	exit 1
fi
