package state

import (
	"reflect"
	"sort"
)

// Change is one state change. Kind is upsert, remove or status.
type Change struct {
	Kind       string
	Entity     string // workspace|tab|pane|layout
	ID         string
	Data       any // wire struct value for upserts
	From, To   string
	PaneID     string
	SessionKey string
}

func diffEntities[T any](entity string, old, new []T, id func(T) string, out *[]Change, removes *[]Change) {
	om := make(map[string]T, len(old))
	for _, o := range old {
		om[id(o)] = o
	}
	seen := make(map[string]bool, len(new))
	for _, n := range new {
		k := id(n)
		seen[k] = true
		if o, ok := om[k]; !ok || !reflect.DeepEqual(o, n) {
			*out = append(*out, Change{Kind: "upsert", Entity: entity, ID: k, Data: n})
		}
	}
	var gone []string
	for k := range om {
		if !seen[k] {
			gone = append(gone, k)
		}
	}
	sort.Strings(gone)
	for _, k := range gone {
		*removes = append(*removes, Change{Kind: "remove", Entity: entity, ID: k})
	}
}

// Diff returns the changes turning old into new, in deterministic order:
// removes, then upserts (workspaces, tabs, panes, layouts), then status.
// A status change is emitted for agent panes present in both states whose
// agent_status differs.
func Diff(old, new State) []Change {
	var removes, ups, status []Change
	diffEntities("workspace", old.Workspaces, new.Workspaces, func(w Workspace) string { return w.ID }, &ups, &removes)
	diffEntities("tab", old.Tabs, new.Tabs, func(t Tab) string { return t.ID }, &ups, &removes)
	diffEntities("pane", old.Panes, new.Panes, func(p Pane) string { return p.ID }, &ups, &removes)
	diffEntities("layout", old.Layouts, new.Layouts, func(l Layout) string { return l.TabID }, &ups, &removes)

	op := make(map[string]Pane, len(old.Panes))
	for _, p := range old.Panes {
		op[p.ID] = p
	}
	for _, p := range new.Panes {
		if o, ok := op[p.ID]; ok && p.Kind == "agent" && o.AgentStatus != p.AgentStatus {
			status = append(status, Change{
				Kind: "status", Entity: "pane", ID: p.ID, PaneID: p.ID,
				SessionKey: p.SessionKey, From: o.AgentStatus, To: p.AgentStatus,
			})
		}
	}
	out := append(removes, ups...)
	return append(out, status...)
}
