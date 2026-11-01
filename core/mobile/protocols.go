// SPDX-License-Identifier: GPL-3.0-only
package mobile

import (
	"errors"
	"strings"

	"github.com/metacubex/mihomo/adapter"
	C "github.com/metacubex/mihomo/constant"
)

var protocolFields = func() map[string]string {
	out := map[string]string{}
	for kind := range nativeNodeTypes {
		for key := range nodeFields(kind) {
			out[kind] += " " + key
		}
	}
	return out
}()

// Use the pinned Mihomo parser after validating its native transport schema.
// File paths, external programs and uncontrolled route overrides remain excluded.
func parseExtendedNode(text string) (C.ProxyAdapter, error) {
	return parseExtendedNodeWithDialer(text, nil)
}

func parseExtendedNodeWithDialer(text string, via C.Dialer) (C.ProxyAdapter, error) {
	invalid := errors.New("config_invalid")
	mapping, err := strictNodeObject(text)
	if err != nil {
		return nil, invalid
	}
	kind, _ := mapping["type"].(string)
	if !nativeMappingValid(mapping, nodeFields(kind), 0) {
		return nil, invalid
	}
	if nativeNodeNeedsEndpoint(kind, mapping) {
		server, ok := mapping["server"].(string)
		if !ok || !validHost(server) || !nativePort(mapping) {
			return nil, invalid
		}
		mapping["server"] = strings.TrimSuffix(server, ".")
	}
	if !prepareNativeNode(mapping) {
		return nil, invalid
	}
	mapping["name"] = "node-01"
	for _, key := range []string{"sni", "servername"} {
		if v, exists := mapping[key]; exists {
			s, ok := v.(string)
			if !ok || s != "" && !validHost(s) {
				return nil, invalid
			}
		}
	}
	if kind == "socks5" {
		for _, key := range []string{"username", "password"} {
			if s, _ := mapping[key].(string); len(s) > 255 {
				return nil, invalid
			}
		}
	}
	if plugin, ok := mapping["plugin"].(string); ok && plugin != "" && plugin != "obfs" && plugin != "v2ray-plugin" && plugin != "shadow-tls" && plugin != "restls" && plugin != "gost-plugin" {
		return nil, invalid
	}
	if meshKind(kind) {
		return acquireMesh(mapping, via)
	}
	proxy, err := adapter.ParseProxy(mapping, adapter.WithDialerForAPI(via))
	if err != nil {
		return nil, invalid
	}
	return proxy, nil
}
