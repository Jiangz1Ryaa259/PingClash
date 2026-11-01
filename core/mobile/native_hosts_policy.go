// SPDX-License-Identifier: GPL-3.0-only
package mobile

import (
	"encoding/json"
	"strings"

	"github.com/metacubex/mihomo/component/resolver"
	"github.com/metacubex/mihomo/component/trie"
)

// Upstream hosts traversal assumes acyclic aliases. Validate the complete trie
// before calling its parser, including aliases that match wildcard host keys.
func boundedNativeHosts(hosts map[string]any) bool {
	if len(hosts) > 10000 {
		return false
	}
	tree := trie.New[resolver.HostValue]()
	for key, raw := range hosts {
		var values []string
		if single, ok := raw.(string); ok {
			values = []string{single}
		} else {
			data, _ := json.Marshal(raw)
			if json.Unmarshal(data, &values) != nil || len(values) > 256 {
				return false
			}
		}
		if len(values) == 1 && values[0] == "lan" {
			continue
		}
		value, e := resolver.NewHostValue(values)
		if e != nil {
			return false
		}
		if tree.Insert(key, value) != nil {
			continue
		} // Native parser ignores invalid host keys.
	}
	for key := range hosts {
		name := key
		seen := map[string]bool{}
		for depth := 0; depth <= 16; depth++ {
			node := tree.Search(name)
			if node == nil || !node.Data().IsDomain {
				break
			}
			name = strings.ToLower(node.Data().Domain)
			if depth == 16 || seen[name] {
				return false
			}
			seen[name] = true
		}
	}
	return true
}
