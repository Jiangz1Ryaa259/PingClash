// SPDX-License-Identifier: GPL-3.0-only
package mobile

import (
	"crypto/sha256"
	"crypto/x509"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"encoding/pem"
	"path/filepath"
	"strings"
)

func nativeNodeNeedsEndpoint(kind string, m map[string]any) bool {
	switch kind {
	case "direct", "dns", "reject", "rematch", "tailscale", "zerotier", "easytier":
		return false
	case "wireguard":
		peers, _ := m["peers"].([]any)
		return len(peers) == 0
	}
	return true
}

func safeNativePrivateKey(kind, key string) bool {
	if key == "" {
		return true
	}
	if kind == "wireguard" {
		b, e := base64.StdEncoding.DecodeString(key)
		return e == nil && len(b) == 32
	}
	if kind == "masque" {
		b, e := base64.StdEncoding.DecodeString(key)
		if e != nil {
			return false
		}
		_, e = x509.ParseECPrivateKey(b)
		return e == nil
	}
	block, rest := pem.Decode([]byte(key))
	return block != nil && len(strings.TrimSpace(string(rest))) == 0 && strings.Contains(block.Type, "PRIVATE KEY")
}

// Subscription paths never become file capabilities. Native mesh identities
// use a stable application-owned directory, separate from other app data.
// No constructor starts a mesh connection; native adapters connect on use.
func prepareNativeNode(m map[string]any) bool {
	kind, _ := m["type"].(string)
	if kind == "tailscale" || kind == "easytier" || kind == "zerotier" {
		material := map[string]any{}
		for k, v := range m {
			if k != "state-dir" && k != "name" {
				material[k] = v
			}
		}
		b, e := json.Marshal(material)
		if e != nil {
			return false
		}
		sum := sha256.Sum256(b)
		m["state-dir"] = filepath.Join("runtime-state", kind, hex.EncodeToString(sum[:16]))
	}
	if kind == "easytier" {
		// Inbound listeners/exit-node service are device capabilities, never feed options.
		delete(m, "listeners")
		delete(m, "mapped-listeners")
		m["no-listener"] = true
		m["enable-exit-node"] = false
		if s, _ := m["hostname"].(string); s == "" {
			m["hostname"] = "pingclash"
		}
	}
	if kind == "tailscale" {
		if s, _ := m["hostname"].(string); s == "" {
			m["hostname"] = "pingclash"
		}
	}
	if kind == "zerotier" {
		if s, _ := m["planet"].(string); s != "" {
			return false
		} // explicit local-file grant required
		if s, _ := m["remote-trace-target"].(string); s != "" {
			return false
		}
	}
	return true
}
