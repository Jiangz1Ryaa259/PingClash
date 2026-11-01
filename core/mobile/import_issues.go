// SPDX-License-Identifier: GPL-3.0-only
package mobile

import (
	"encoding/json"
	"strings"
)

const basicNodeFields = "type name server port udp password username sni client-fingerprint alpn skip-cert-verify tls idle-session-check-interval idle-session-timeout min-idle-session disable-reuse"

// Unknown keys may themselves be secrets. Return only fixed schema labels.
const publicExtraFields = "tfo mptcp fast-open ip-version smux fingerprint name-cert-verify certificate private-key ech-opts shadow-tls-opts restls-opts jls-opts client-metadata dialer-proxy interface-name routing-mark handshake-timeout udp-mtu recv-window-conn recv-window cwnd bbr-profile"

var publicImportFields = func() map[string]bool {
	known := basicNodeFields + " " + publicExtraFields
	for _, fields := range protocolFields {
		known += " " + fields
	}
	fields := make(map[string]bool)
	for _, field := range strings.Fields(known) {
		fields[field] = true
	}
	return fields
}()

func publicField(key string) string {
	if publicImportFields[key] {
		return key
	}
	return "other"
}

func importFieldIssues(text string) []string {
	mapping, err := strictNodeObject(text)
	if err != nil {
		// Diagnostic-only decoding. The strict rejection remains unchanged.
		// Optional null fields are common in generated subscriptions; identify
		// their fixed schema names without exposing values or unknown key names.
		if len(text) <= 96*1024 {
			var raw map[string]json.RawMessage
			if json.Unmarshal([]byte(text), &raw) == nil {
				kind := "other"
				var typed string
				_ = json.Unmarshal(raw["type"], &typed)
				if _, ok := protocolFields[typed]; ok || typed == "anytls" || typed == "socks5" {
					kind = typed
				}
				seen := map[string]bool{}
				issues := []string{}
				for key, value := range raw {
					if strings.TrimSpace(string(value)) == "null" {
						label := kind + "." + publicField(key)
						if !seen[label] {
							issues = append(issues, label)
							seen[label] = true
						}
					}
				}
				if len(issues) > 0 {
					return issues
				}
			}
		}
		return []string{"other.structure"}
	}
	kind, _ := mapping["type"].(string)
	fields, ok := protocolFields[kind]
	if !ok {
		return []string{"other.protocol"}
	}
	unique := make(map[string]bool)
	allowed := make(map[string]bool)
	for _, field := range strings.Fields(fields) {
		allowed[field] = true
	}
	for key, value := range mapping {
		if !allowed[key] || !nativeMappingValid(map[string]any{key: value}, nodeFields(kind), 0) {
			unique[kind+"."+publicField(key)] = true
		}
	}
	issues := make([]string, 0, len(unique))
	for issue := range unique {
		issues = append(issues, issue)
	}
	if len(issues) == 0 {
		issues = append(issues, kind+".parameters")
	}
	return issues
}
