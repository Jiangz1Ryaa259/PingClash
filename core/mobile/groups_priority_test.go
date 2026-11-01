// SPDX-License-Identifier: GPL-3.0-only
package mobile

import (
	"encoding/json"
	"testing"
)

func TestGroupDeclarationRetainedWithoutInventingReplacement(t *testing.T) {
	nodes := []any{map[string]any{"name": "A", "type": "trojan", "server": "example.invalid", "port": 443, "password": "fixture"}}
	tooMany := []any{}
	for i := 0; i < 65; i++ {
		tooMany = append(tooMany, map[string]any{"name": "A", "type": "select", "proxies": []any{"DIRECT"}})
	}
	for _, groups := range []any{nil, "invalid", map[string]any{"invalid": true}, []any{}, tooMany} {
		raw, _ := json.Marshal(map[string]any{"proxies": nodes, "proxy-groups": groups})
		p := imported(t, string(raw))
		if !p.GroupsDeclared || len(p.Nodes) != 1 || len(p.Groups) != 0 || p.Definition == "" {
			t.Fatal("declared groups lost or replaced")
		}
		var definition map[string]any
		_ = json.Unmarshal([]byte(p.Definition), &definition)
		if _, exists := definition["proxy-groups"]; !exists {
			t.Fatal("original declaration discarded")
		}
	}
	raw, _ := json.Marshal(map[string]any{"proxies": nodes})
	if p := imported(t, string(raw)); p.GroupsDeclared {
		t.Fatal("node-only subscription acquired groups")
	}
	nodes[0].(map[string]any)["dialer-proxy"] = "DIRECT"
	raw, _ = json.Marshal(map[string]any{"proxies": nodes})
	p := imported(t, string(raw))
	var definition map[string]any
	_ = json.Unmarshal([]byte(p.Definition), &definition)
	if _, exists := definition["proxy-groups"]; p.GroupsDeclared || exists {
		t.Fatal("dialer-only profile acquired a group declaration")
	}
}
