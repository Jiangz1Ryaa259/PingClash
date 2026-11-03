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
	budget := 50000
	source := inheritedYAMLValue(document.Content[0], "dns", 0, &budget)
	if source == nil {
		return false
	}
	budget = 50000
	value := cloneDNSNode(source, 0, &budget)
	if value == nil {
		return false
	}
	key := &yaml.Node{Kind: yaml.ScalarNode, Tag: "!!str", Value: "dns"}
	fragment := &yaml.Node{Kind: yaml.MappingNode, Content: []*yaml.Node{key, value}}
	encoded, e := yaml.Marshal(fragment)
	if e != nil || len(encoded) > 1024*1024 {
		return false
	}
	root[nativeDNSDocumentKey] = string(encoded)
	return true
}

// Explicit keys win over inherited keys; the first map in a merge sequence
// wins. Retain the original DNS node so policy priority survives the bridge.
func inheritedYAMLValue(n *yaml.Node, key string, depth int, budget *int) *yaml.Node {
	*budget--
	if n == nil || depth > 24 || *budget < 0 {
		return nil
	}
	if n.Kind == yaml.AliasNode {
		return inheritedYAMLValue(n.Alias, key, depth+1, budget)
	}
	if n.Kind == yaml.SequenceNode {
		for _, child := range n.Content {
			if value := inheritedYAMLValue(child, key, depth+1, budget); value != nil {
				return value
			}
		}
	}
	if n.Kind != yaml.MappingNode {
		return nil
	}
	for i := 0; i+1 < len(n.Content); i += 2 {
		if n.Content[i].Value == key {
			return n.Content[i+1]
		}
	}
	for i := 0; i+1 < len(n.Content); i += 2 {
		if n.Content[i].Tag == "!!merge" {
			if value := inheritedYAMLValue(n.Content[i+1], key, depth+1, budget); value != nil {
				return value
			}
		}
	}
	return nil
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
