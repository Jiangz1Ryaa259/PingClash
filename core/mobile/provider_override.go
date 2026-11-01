// SPDX-License-Identifier: GPL-3.0-only
package mobile

import (
	"encoding/json"
	"github.com/dlclark/regexp2"
	"strings"
	"time"
)

func validProviderOverride(m map[string]any) bool {
	if len(m) > 20 {
		return false
	}
	for k, v := range m {
		switch k {
		case "tfo", "mptcp", "udp", "udp-over-tcp", "skip-cert-verify":
			if _, ok := v.(bool); !ok {
				return false
			}
		case "up", "down", "dialer-proxy", "name-cert-verify", "ip-version", "additional-prefix", "additional-suffix":
			s, ok := v.(string)
			if !ok || len(s) > 512 || hasControl(s) {
				return false
			}
		case "proxy-name":
			list, ok := v.([]any)
			if !ok || len(list) > 16 {
				return false
			}
			for _, raw := range list {
				item, ok := raw.(map[string]any)
				if !ok || len(item) != 2 {
					return false
				}
				pattern, p := item["pattern"].(string)
				target, t := item["target"].(string)
				if !p || !t || len(pattern) > 1024 || len(target) > 512 || hasControl(target) {
					return false
				}
				if _, e := regexp2.Compile(pattern, regexp2.None); e != nil {
					return false
				}
			}
		default:
			return false
		}
	}
	return true
}
func applyProviderOverride(m map[string]any, override map[string]any) bool {
	if !validProviderOverride(override) {
		return false
	}
	for _, k := range strings.Fields("tfo mptcp udp udp-over-tcp up down dialer-proxy skip-cert-verify name-cert-verify ip-version") {
		if v, exists := override[k]; exists {
			m[k] = v
		}
	}
	name, _ := m["name"].(string)
	if list, ok := override["proxy-name"].([]any); ok {
		for _, raw := range list {
			item := raw.(map[string]any)
			r, e := regexp2.Compile(item["pattern"].(string), regexp2.None)
			if e != nil {
				return false
			}
			r.MatchTimeout = 20 * time.Millisecond
			name, e = r.Replace(name, item["target"].(string), 0, -1)
			if e != nil || len(name) > 512 {
				return false
			}
		}
	}
	prefix, _ := override["additional-prefix"].(string)
	suffix, _ := override["additional-suffix"].(string)
	name = prefix + name + suffix
	if name == "" || len(name) > 512 || hasControl(name) {
		return false
	}
	m["name"] = name
	return true
}

// Duplicate references to exactly the same definition share one node. Different
// credentials/routes under the same global name remain ambiguous and fail.
func sameProviderNode(a, b map[string]any) bool {
	x, _ := json.Marshal(normalizedImportNode(a))
	y, _ := json.Marshal(normalizedImportNode(b))
	return string(x) == string(y)
}
