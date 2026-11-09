# Override on the command line, e.g.
#   make plugin-deploy-prod SERVER=myserver SERVER_IP=100.64.0.10
# SERVER: ssh host running herdr; SERVER_IP: its Tailscale IPv4 (health checks).
GO ?= $(shell command -v go 2>/dev/null || echo $(HOME)/.local/go/bin/go)
SERVER ?= myserver
WHISTLE_BRIDGE_URL ?= ws://100.64.0.10:8788/v1/ws
SERVER_IP ?= 100.64.0.10
HERDR ?= /home/linuxbrew/.linuxbrew/bin/herdr
PLUGIN_DIR ?= .local/share/whistle/bridge-plugin

.PHONY: build build-linux test test-live plugin-deploy-dev plugin-deploy-prod plugin-status
build: build-linux

build-linux:
	CGO_ENABLED=0 GOOS=linux GOARCH=amd64 $(GO) build -trimpath -o bin/whistle-bridge ./cmd/whistle-bridge

test:
	$(GO) test -race ./...

# Live integration test against a dev bridge on $(SERVER) (run from a tailnet machine).
test-live:
	WHISTLE_BRIDGE_URL=$(WHISTLE_BRIDGE_URL) $(GO) test -tags live -count=1 -v ./internal/integration/

# ---- herdr plugin (primary deploy path; see deploy/README.md) ----
# One plugin dir on $(SERVER), linked once (plugins are global to all sessions).
# Dev ships bin/whistle-bridge-whistle-dev (the plugin execs it for that session
# only), prod ships bin/whistle-bridge. The "restart" action starts the new
# binary under the session's running herdr server; the server is not restarted.
define plugin_sync
	ssh $(SERVER) 'mkdir -p ~/$(PLUGIN_DIR)/bin'
	rsync -a --exclude bin/ plugin/ $(SERVER):$(PLUGIN_DIR)/
endef

# $(1): herdr session flag, $(2): port to health-check
define plugin_link_restart
	ssh $(SERVER) '$(HERDR) plugin list --plugin whistle.bridge 2>/dev/null | grep -q whistle.bridge || $(HERDR) plugin link ~/$(PLUGIN_DIR) >/dev/null'
	ssh $(SERVER) '$(HERDR) $(1) plugin action invoke whistle.bridge.restart >/dev/null'
	for i in $$(seq 1 30); do curl -sf http://$(SERVER_IP):$(2)/v1/health && echo && exit 0; sleep 1; done; \
	echo "health check failed on :$(2)"; exit 1
endef

plugin-deploy-dev: build-linux
	$(plugin_sync)
	rsync -a bin/whistle-bridge $(SERVER):$(PLUGIN_DIR)/bin/whistle-bridge-whistle-dev
	ssh $(SERVER) 'cd ~/$(PLUGIN_DIR)/bin && { test -x whistle-bridge || cp -p whistle-bridge-whistle-dev whistle-bridge; }'
	$(call plugin_link_restart,--session whistle-dev,8788)

plugin-deploy-prod: build-linux
	$(plugin_sync)
	ssh $(SERVER) 'cd ~/$(PLUGIN_DIR)/bin && { test ! -x whistle-bridge || cp -p whistle-bridge whistle-bridge.prev; }'
	rsync -a bin/whistle-bridge $(SERVER):$(PLUGIN_DIR)/bin/whistle-bridge
	$(call plugin_link_restart,,8787)

plugin-status:
	ssh $(SERVER) 'B=~/$(PLUGIN_DIR)/bin/whistle-bridge; $$B plugin status -session default; $$B plugin status -session whistle-dev' || true
