// SPDX-License-Identifier: GPL-3.0-only
package mobile

import (
	"bytes"
	"encoding/json"
	"strings"

	"go.yaml.in/yaml/v3"
)

const nativeDNSDocumentKey = "pingclash-native-dns"

// Keep ordered DNS policy maps across the JSON/JNI bridge. A previously saved
// fragment is reused only when its decoded DNS value matches the current one.
// The fragment is private profile content, never diagnostic output.
func retainNativeDNSDocument(document *yaml.Node, decoded any) bool {
	root, ok := decoded.(map[string]any)
	if !ok {
		return true
	}
	dnsValue, exists := root["dns"]
	if !exists {
		return true
	}
	if saved, ok := root[nativeDNSDocumentKey].(string); ok && len(saved) <= 1024*1024 && strings.Count(saved, "\n")+strings.Count(saved, ",")+strings.Count(saved, "[")+strings.Count(saved, "{") <= 50000 {
		var n yaml.Node
		if yaml.Unmarshal([]byte(saved), &n) == nil {
			count := 0
			if safeYAML(&n, 0, &count) {
				var old map[string]any
				if n.Decode(&old) == nil && len(old) == 1 {
					a, _ := json.Marshal(old["dns"])
					b, _ := json.Marshal(dnsValue)
					if bytes.Equal(a, b) {
						return true
					}
				}
			}
		}
	}
	if len(document.Content) != 1 || document.Content[0].Kind != yaml.MappingNode {
		return false
	}
	mapping := document.Content[0]
	for i := 0; i+1 < len(mapping.Content); i += 2 {
		if mapping.Content[i].Value == "dns" {
			budget := 50000
			value := cloneDNSNode(mapping.Content[i+1], 0, &budget)
			if value == nil {
				return false
			}
			fragment := &yaml.Node{Kind: yaml.MappingNode, Content: []*yaml.Node{mapping.Content[i], value}}
			encoded, e := yaml.Marshal(fragment)
			if e != nil || len(encoded) > 1024*1024 {
				return false
			}
			root[nativeDNSDocumentKey] = string(encoded)
			return true
		}
	}
	return false
}

func cloneDNSNode(n *yaml.Node, depth int, budget *int) *yaml.Node {
	*budget--
	if n == nil || depth > 24 || *budget < 0 {
		return nil
	}
	if n.Kind == yaml.AliasNode {
		return cloneDNSNode(n.Alias, depth+1, budget)
	}
	copy := *n
	copy.Anchor = ""
	copy.Alias = nil
	copy.Content = nil
	for _, child := range n.Content {
		value := cloneDNSNode(child, depth+1, budget)
		if value == nil {
			return nil
		}
		copy.Content = append(copy.Content, value)
	}
	return &copy
}
