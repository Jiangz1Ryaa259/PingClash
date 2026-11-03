// SPDX-License-Identifier: GPL-3.0-only
package mobile

import (
	"errors"
	"strconv"
	"strings"
)

// Decode data only. URI feeds are never treated as URLs to fetch recursively.
func providerProxyNodes(body string, sourceIndex int) ([]any, error) {
	invalid := errors.New("provider_payload")
	if len(body) == 0 || len(body) > maxImportNodeBytes || strings.Count(body, "\n") > maxImportRules {
		return nil, invalid
	}
	text, _ := unwrapSubscription(body)
	if looksLikeShareList(text) {
		nodes := []any{}
		for _, line := range strings.Split(text, "\n") {
			line = strings.TrimSpace(line)
			if line == "" || strings.HasPrefix(line, "#") {
				continue
			}
			node, err := parseShareLink(line)
			if err != nil {
				if len(nodes) >= maxImportNodes {
					return nil, invalid
				}
				nodes = append(nodes, map[string]any{"name": "Provider " + strconv.Itoa(sourceIndex) + " / skipped " + strconv.Itoa(len(nodes)+1), "type": "unsupported"})
				continue
			}
			if len(nodes) >= maxImportNodes {
				return nil, invalid
			}
			if name, _ := node["name"].(string); name == "" {
				node["name"] = "Provider " + strconv.Itoa(sourceIndex) + " / " + strconv.Itoa(len(nodes)+1)
			}
			nodes = append(nodes, node)
		}
		return nodes, nil
	}
	v, err := remoteYAML(text)
	if err != nil {
		return nil, invalid
	}
	if object, ok := v.(map[string]any); ok {
		if _, single := object["type"]; single {
			v = []any{object}
		} else {
			v = object["proxies"]
		}
	}
	nodes, ok := v.([]any)
	if !ok || len(nodes) == 0 || len(nodes) > maxImportNodes {
		return nil, invalid
	}
	valid := false
	for _, raw := range nodes {
		if m, ok := raw.(map[string]any); ok {
			kind, _ := m["type"].(string)
			if kind != "" && kind != "unsupported" {
				valid = true
			}
		}
	}
	if !valid {
		return nil, invalid
	}
	return nodes, nil
}

func providerExcludesType(exclude, kind string) bool {
	for _, item := range strings.FieldsFunc(exclude, func(r rune) bool { return r == '|' || r == ',' }) {
		if item = strings.TrimSpace(item); item != "" && strings.EqualFold(item, kind) {
			return true
		}
	}
	return false
}
