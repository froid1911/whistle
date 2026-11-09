package main

import (
	"strings"
	"testing"
)

func TestPopupArgs(t *testing.T) {
	got := strings.Join(popupArgs(pluginEnv{}, "pair"), " ")
	want := "plugin pane open --plugin whistle.bridge --entrypoint pair --placement popup --focus --env HERDR_BRIDGE_SESSION=default"
	if got != want {
		t.Fatalf("%s", got)
	}
	got = strings.Join(popupArgs(pluginEnv{session: "whistle-dev"}, "devices"), " ")
	if !strings.HasPrefix(got, "--session whistle-dev plugin pane open") || !strings.HasSuffix(got, "HERDR_BRIDGE_SESSION=whistle-dev") {
		t.Fatalf("%s", got)
	}
}
