// SPDX-License-Identifier: GPL-3.0-only
package mobile

import (
	"reflect"
	"strings"

	"go.yaml.in/yaml/v3"
)

// Normalize only typed protocol options at the import boundary. Keeping the
// YAML token preserves leading zeros in numeric credentials/Reality short IDs;
// the private JSON execution API stays strict and header maps remain data.
func normalizeYAMLProtocolScalars(node *yaml.Node, fields map[string]nativeField, depth int) {
	if node == nil || depth > 24 {
		return
	}
	if node.Kind == yaml.AliasNode {
		normalizeYAMLProtocolScalars(node.Alias, fields, depth+1)
		return
	}
	if node.Kind == yaml.MappingNode {
		if fields == nil {
			for i := 0; i+1 < len(node.Content); i += 2 {
				if strings.EqualFold(node.Content[i].Value, "type") {
					fields = nodeFields(strings.ToLower(node.Content[i+1].Value))
					break
				}
			}
		}
		for i := 0; i+1 < len(node.Content); i += 2 {
			key := strings.ToLower(strings.ReplaceAll(node.Content[i].Value, "_", "-"))
			value := node.Content[i+1]
			if key == "headers" || key == "ws-headers" {
				continue
			}
			field, known := fields[key]
			var children map[string]nativeField
			if known {
				t := field.typ
				for t.Kind() == reflect.Pointer {
					t = t.Elem()
				}
				if t.Kind() == reflect.String && value.Kind == yaml.ScalarNode && (value.Tag == "!!int" || value.Tag == "!!float" || value.Tag == "!!bool") {
					value.Tag = "!!str"
				}
				if t.Kind() == reflect.Struct {
					children = nativeFields(t)
				}
				if key == "plugin-opts" || key == "obfs-opts" {
					children = pluginFields()
				}
			}
			normalizeYAMLProtocolScalars(value, children, depth+1)
		}
	} else {
		for _, child := range node.Content {
			normalizeYAMLProtocolScalars(child, nil, depth+1)
		}
	}
}
