// SPDX-License-Identifier: GPL-3.0-only
package mobile

import (
	"net/url"
	"path"
	"strings"
)

func canonicalProviderOptions(input map[string]any, kind string) map[string]any {
	m := make(map[string]any, len(input))
	for k, v := range input {
		m[k] = v
	}
	for _, key := range []string{"type", "behavior", "format"} {
		if value, ok := m[key].(string); ok {
			m[key] = strings.ToLower(value)
		}
	}
	// Templates often omit type: an HTTPS url means http, a payload means inline.
	if _, set := m["type"]; !set {
		if source, ok := m["url"].(string); ok && strings.HasPrefix(strings.ToLower(source), "https://") {
			m["type"] = "http"
		} else if _, ok := m["payload"]; ok {
			m["type"] = "inline"
		}
	}
	// A remote URL is fetched through the normal HTTPS policy. Never open the
	// file/path from a subscription, even when its template labels it "file".
	if m["type"] == "file" {
		if source, ok := m["url"].(string); ok && strings.HasPrefix(source, "https://") {
			m["type"] = "http"
		}
	}
	if _, set := m["format"]; !set && kind == "rule" {
		source, _ := m["url"].(string)
		if source == "" {
			source, _ = m["path"].(string)
		}
		if source != "" {
			// Only the binary format is taken from the name. Text and YAML are
			// told apart by content when the body arrives: many ".txt" rule
			// lists are YAML payload documents.
			if u, e := url.Parse(source); e == nil && strings.ToLower(path.Ext(u.Path)) == ".mrs" {
				m["format"] = "mrs"
			}
		}
	}
	return m
}

// A logical archive entry, never an operating-system path. Files are supplied
// explicitly through Android's document picker and remain encrypted in cache.
func safeProviderBundlePath(value string) bool {
	if value == "" || len(value) > 512 || hasControl(value) || strings.ContainsAny(value, "\\:\x00") || strings.HasPrefix(value, "/") {
		return false
	}
	for _, part := range strings.Split(value, "/") {
		if part == ".." || part == "" {
			return false
		}
	}
	return path.Clean(value) != "."
}
