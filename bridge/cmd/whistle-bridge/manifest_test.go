package main

import (
	"os"
	"strings"
	"testing"
)

// manifestBody drops comments and blank lines so only the TOML content is compared.
func manifestBody(t *testing.T, path string) string {
	t.Helper()
	b, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	var out []string
	for _, l := range strings.Split(string(b), "\n") {
		if l = strings.TrimSpace(l); l != "" && !strings.HasPrefix(l, "#") {
			out = append(out, l)
		}
	}
	return strings.Join(out, "\n")
}

// TestRootManifestInSync keeps the repo-root manifest (`herdr plugin install`)
// identical to bridge/plugin/herdr-plugin.toml (`herdr plugin link`) apart
// from the bridge/plugin/ path prefix.
func TestRootManifestInSync(t *testing.T) {
	root := strings.ReplaceAll(manifestBody(t, "../../../herdr-plugin.toml"), `"bridge/plugin/`, `"`)
	if plugin := manifestBody(t, "../../plugin/herdr-plugin.toml"); root != plugin {
		t.Fatalf("herdr-plugin.toml (repo root) and bridge/plugin/herdr-plugin.toml differ:\n%s\n---\n%s", root, plugin)
	}
}
