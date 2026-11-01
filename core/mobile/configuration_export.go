// SPDX-License-Identifier: GPL-3.0-only
package mobile

import (
	"bytes"
	"encoding/json"
	"go.yaml.in/yaml/v3"
	"strings"
)

// ExportConfiguration is PRIVATE, explicitly authorized credential-bearing
// output. Rebuild standard inline sources, never expose bridge/cache metadata.
// JSON member order is written from YAML nodes so DNS policy priority survives.
func ExportConfiguration(definition string) (result string) {
	defer func() {
		if recover() != nil {
			result = ""
		}
	}()
	if len(definition) > maxPrivateProfileBytes || !json.Valid([]byte(definition)) {
		return ""
	}
	var doc yaml.Node
	if yaml.Unmarshal([]byte(normalizeJSONStringTokens(definition)), &doc) != nil || len(doc.Content) != 1 {
		return ""
	}
	root := doc.Content[0]
	if root.Kind != yaml.MappingNode {
		return ""
	}
	var packed, ordered string
	for i := 0; i+1 < len(root.Content); i += 2 {
		switch root.Content[i].Value {
		case providerRuleDataKey:
			packed = root.Content[i+1].Value
		case nativeDNSDocumentKey:
			ordered = root.Content[i+1].Value
		}
	}
	content := root.Content[:0]
	for i := 0; i+1 < len(root.Content); i += 2 {
		if !strings.HasPrefix(root.Content[i].Value, "pingclash-") {
			content = append(content, root.Content[i], root.Content[i+1])
		}
	}
	root.Content = content
	if packed != "" {
		sets, e := decodeStoredRuleSets(packed)
		if e != nil {
			return ""
		}
		providers := map[string]any{}
		for name, set := range sets {
			providers[name] = map[string]any{"type": "inline", "behavior": set.Behavior, "payload": set.Entries}
		}
		n := &yaml.Node{}
		if n.Encode(providers) != nil {
			return ""
		}
		setExportMember(root, "rule-providers", n)
	}
	if ordered != "" {
		var fragment yaml.Node
		if len(ordered) > 1024*1024 || yaml.Unmarshal([]byte(ordered), &fragment) != nil || len(fragment.Content) != 1 {
			return ""
		}
		n := fragment.Content[0]
		if n.Kind != yaml.MappingNode || len(n.Content) != 2 || n.Content[0].Value != "dns" {
			return ""
		}
		budget := 50000
		dns := cloneDNSNode(n.Content[1], 0, &budget)
		if dns == nil {
			return ""
		}
		setExportMember(root, "dns", dns)
	}
	var out bytes.Buffer
	if !writeExportJSON(&out, root, 0) || out.Len() > 32*1024*1024 {
		return ""
	}
	return out.String()
}
func setExportMember(root *yaml.Node, key string, value *yaml.Node) {
	for i := 0; i+1 < len(root.Content); i += 2 {
		if root.Content[i].Value == key {
			root.Content[i+1] = value
			return
		}
	}
	root.Content = append(root.Content, &yaml.Node{Kind: yaml.ScalarNode, Tag: "!!str", Value: key}, value)
}
func writeExportJSON(out *bytes.Buffer, n *yaml.Node, depth int) bool {
	if n == nil || depth > 24 || out.Len() > 32*1024*1024 {
		return false
	}
	switch n.Kind {
	case yaml.MappingNode:
		members, ok := exportMapping(n, depth)
		if !ok {
			return false
		}
		out.WriteByte('{')
		for i := 0; i+1 < len(members); i += 2 {
			if i > 0 {
				out.WriteByte(',')
			}
			if members[i].Tag != "!!str" {
				return false
			}
			b, _ := json.Marshal(members[i].Value)
			out.Write(b)
			out.WriteByte(':')
			if !writeExportJSON(out, members[i+1], depth+1) {
				return false
			}
		}
		out.WriteByte('}')
	case yaml.SequenceNode:
		out.WriteByte('[')
		for i, v := range n.Content {
			if i > 0 {
				out.WriteByte(',')
			}
			if !writeExportJSON(out, v, depth+1) {
				return false
			}
		}
		out.WriteByte(']')
	case yaml.ScalarNode:
		var value any
		if n.Decode(&value) != nil {
			return false
		}
		b, e := json.Marshal(value)
		if e != nil {
			return false
		}
		out.Write(b)
	default:
		return false
	}
	return true
}

func exportMapping(n *yaml.Node, depth int) ([]*yaml.Node, bool) {
	if depth > 24 || n.Kind != yaml.MappingNode {
		return nil, false
	}
	explicit := map[string]bool{}
	for i := 0; i+1 < len(n.Content); i += 2 {
		if n.Content[i].Tag != "!!merge" {
			explicit[n.Content[i].Value] = true
		}
	}
	result := []*yaml.Node{}
	seen := map[string]bool{}
	for i := 0; i+1 < len(n.Content); i += 2 {
		key, value := n.Content[i], n.Content[i+1]
		if key.Tag != "!!merge" {
			result = append(result, key, value)
			continue
		}
		maps := []*yaml.Node{value}
		if value.Kind == yaml.SequenceNode {
			maps = value.Content
		}
		for _, source := range maps {
			members, ok := exportMapping(source, depth+1)
			if !ok {
				return nil, false
			}
			for j := 0; j+1 < len(members); j += 2 {
				name := members[j].Value
				if !explicit[name] && !seen[name] {
					result = append(result, members[j], members[j+1])
					seen[name] = true
				}
			}
		}
	}
	return result, true
}
