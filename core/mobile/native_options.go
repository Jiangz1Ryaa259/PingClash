// SPDX-License-Identifier: GPL-3.0-only
package mobile

import (
	"crypto/tls"
	"encoding/json"
	"reflect"
	"strconv"
	"strings"

	"github.com/metacubex/mihomo/adapter/outbound"
)

// Derive transport fields from the pinned core rather than maintaining a second,
// narrower protocol implementation. This is not the global config/listener API.
var nativeNodeTypes = map[string]reflect.Type{
	"anytls":      reflect.TypeOf(outbound.AnyTLSOption{}),
	"socks5":      reflect.TypeOf(outbound.Socks5Option{}),
	"http":        reflect.TypeOf(outbound.HttpOption{}),
	"ss":          reflect.TypeOf(outbound.ShadowSocksOption{}),
	"ssr":         reflect.TypeOf(outbound.ShadowSocksROption{}),
	"vmess":       reflect.TypeOf(outbound.VmessOption{}),
	"vless":       reflect.TypeOf(outbound.VlessOption{}),
	"trojan":      reflect.TypeOf(outbound.TrojanOption{}),
	"hysteria":    reflect.TypeOf(outbound.HysteriaOption{}),
	"hysteria2":   reflect.TypeOf(outbound.Hysteria2Option{}),
	"tuic":        reflect.TypeOf(outbound.TuicOption{}),
	"snell":       reflect.TypeOf(outbound.SnellOption{}),
	"mieru":       reflect.TypeOf(outbound.MieruOption{}),
	"direct":      reflect.TypeOf(outbound.DirectOption{}),
	"dns":         reflect.TypeOf(outbound.DnsOption{}),
	"reject":      reflect.TypeOf(outbound.RejectOption{}),
	"rematch":     reflect.TypeOf(outbound.RematchOption{}),
	"wireguard":   reflect.TypeOf(outbound.WireGuardOption{}),
	"ssh":         reflect.TypeOf(outbound.SshOption{}),
	"shadowquic":  reflect.TypeOf(outbound.ShadowQuicOption{}),
	"gost-relay":  reflect.TypeOf(outbound.GostRelayOption{}),
	"sudoku":      reflect.TypeOf(outbound.SudokuOption{}),
	"masque":      reflect.TypeOf(outbound.MasqueOption{}),
	"trusttunnel": reflect.TypeOf(outbound.TrustTunnelOption{}),
	"openvpn":     reflect.TypeOf(outbound.OpenVPNOption{}),
	"tailscale":   reflect.TypeOf(outbound.TailscaleOption{}),
	"zerotier":    reflect.TypeOf(outbound.ZeroTierOption{}),
	"easytier":    reflect.TypeOf(outbound.EasyTierOption{}),
}

type nativeField struct {
	typ      reflect.Type
	optional bool
}

func nativeFields(t reflect.Type) map[string]nativeField {
	for t.Kind() == reflect.Pointer {
		t = t.Elem()
	}
	result := map[string]nativeField{}
	for i := 0; i < t.NumField(); i++ {
		f := t.Field(i)
		if f.Anonymous {
			for k, v := range nativeFields(f.Type) {
				result[k] = v
			}
			continue
		}
		parts := strings.Split(f.Tag.Get("proxy"), ",")
		if parts[0] == "" || parts[0] == "-" {
			continue
		}
		result[parts[0]] = nativeField{f.Type, len(parts) > 1 && parts[1] == "omitempty"}
	}
	return result
}
func nodeFields(kind string) map[string]nativeField {
	t, ok := nativeNodeTypes[kind]
	if !ok {
		return nil
	}
	fields := nativeFields(t)
	fields["type"] = nativeField{reflect.TypeOf(""), false}
	// QUIC adapters are intrinsically UDP-capable; native parsing accepts this
	// common generator field even when their option struct has no UDP member.
	fields["udp"] = nativeField{reflect.TypeOf(false), true}
	// Inherently TLS/QUIC transports ignore this generated template flag in
	// Mihomo. Accept its proper boolean form without changing their encryption.
	if kind == "anytls" || kind == "trojan" || kind == "hysteria" || kind == "hysteria2" || kind == "tuic" {
		fields["tls"] = nativeField{reflect.TypeOf(false), true}
	}
	fields["smux"] = nativeField{reflect.TypeOf(outbound.SingMuxOption{}), true}
	return fields
}

// Optional nulls emitted by generators mean "unset". Only import normalizes
// these; the private JSON runtime boundary still rejects ambiguous/null inputs.
func normalizeOptionalNodeFields(m map[string]any) {
	kind, _ := m["type"].(string)
	for key, field := range nodeFields(kind) {
		if key == "dialer-proxy" {
			continue
		}
		if m[key] == nil && field.optional {
			delete(m, key)
		}
	}
}

// Mihomo matches option tags case-insensitively, treating underscores as hyphens,
// and ignores unused options. Apply that behavior only at the subscription
// boundary. Ambiguous aliases and private runtime envelopes remain invalid.
func normalizeImportedOptions(m map[string]any, fields map[string]nativeField, depth int) (map[string]any, bool) {
	if fields == nil || depth > 8 || len(m) > 100 {
		return m, false
	}
	result := make(map[string]any, len(m))
	tags := make(map[string]string, len(fields))
	for tag := range fields {
		tags[strings.ToLower(tag)] = tag
	}
	for key, value := range m {
		canonical := strings.ToLower(strings.ReplaceAll(key, "_", "-"))
		resolved := tags[canonical]
		field, known := fields[resolved]
		if !known {
			// Never discard the application's private routing envelope marker.
			if canonical == "dialer-chain" {
				return m, false
			}
			continue
		}
		canonical = resolved
		if _, exists := result[canonical]; exists {
			return m, false
		}
		t := field.typ
		for t.Kind() == reflect.Pointer {
			t = t.Elem()
		}
		if t.Kind() == reflect.String {
			switch n := value.(type) {
			case json.Number:
				value = n.String()
			case int:
				value = strconv.Itoa(n)
			case int64:
				value = strconv.FormatInt(n, 10)
			case float64:
				value = strconv.FormatFloat(n, 'f', -1, 64)
			}
		}
		childFields := map[string]nativeField(nil)
		if t.Kind() == reflect.Struct {
			childFields = nativeFields(t)
		} else if canonical == "plugin-opts" || canonical == "obfs-opts" {
			childFields = pluginFields()
		}
		if child, ok := value.(map[string]any); ok && childFields != nil {
			var valid bool
			value, valid = normalizeImportedOptions(child, childFields, depth+1)
			if !valid {
				return m, false
			}
		}
		// Header maps are data, not option tags: preserve case and validation.
		result[canonical] = value
	}
	return result, true
}

func nativeMappingValid(m map[string]any, fields map[string]nativeField, depth int) bool {
	if fields == nil || depth > 8 || len(m) > 100 {
		return false
	}
	cert, key := "", ""
	kind, _ := m["type"].(string)
	for name, value := range m {
		field, ok := fields[name]
		if !ok {
			return false
		}
		if name == "dialer-proxy" || name == "interface-name" {
			if value != "" {
				return false
			}
			continue
		}
		if name == "routing-mark" {
			if value != json.Number("0") {
				return false
			}
			continue
		}
		if name == "realm-opts" {
			if v, ok := value.(map[string]any); !ok || v["enable"] == true {
				return false
			}
		}
		if name == "certificate" || name == "private-key" {
			s, ok := value.(string)
			if !ok || len(s) > 16384 {
				return false
			}
			if name == "private-key" && (kind == "wireguard" || kind == "ssh" || kind == "masque") {
				if !safeNativePrivateKey(kind, s) {
					return false
				}
				continue
			}
			if name == "certificate" {
				cert = s
			} else {
				key = s
			}
			continue
		}
		if kind == "openvpn" && (name == "ca" || name == "cert" || name == "key" || name == "tls-auth" || name == "tls-crypt" || name == "tls-crypt-v2") {
			value, ok := value.(string)
			if !ok || len(value) > 16384 {
				return false
			}
			continue
		}
		if name == "headers" || name == "ws-headers" {
			if !safeHeaders(value, field.typ.Elem().Kind() == reflect.Slice) {
				return false
			}
			continue
		}
		if name == "plugin-opts" || name == "obfs-opts" {
			child, ok := value.(map[string]any)
			if !ok || !safePluginOptions(child, depth+1) {
				return false
			}
			continue
		}
		if !nativeValueValid(name, value, field.typ, depth+1) {
			return false
		}
	}
	// Never let Mihomo interpret subscription certificate strings as local paths.
	if cert != "" || key != "" {
		if _, err := tls.X509KeyPair([]byte(cert), []byte(key)); err != nil {
			return false
		}
	}
	return true
}

func nativeValueValid(key string, v any, t reflect.Type, depth int) bool {
	for t.Kind() == reflect.Pointer {
		t = t.Elem()
	}
	if v == nil || depth > 8 {
		return false
	}
	switch t.Kind() {
	case reflect.Struct:
		m, ok := v.(map[string]any)
		return ok && nativeMappingValid(m, nativeFields(t), depth)
	case reflect.Bool:
		_, ok := v.(bool)
		return ok
	case reflect.Int, reflect.Int8, reflect.Int16, reflect.Int32, reflect.Int64, reflect.Uint, reflect.Uint8, reflect.Uint16, reflect.Uint32, reflect.Uint64:
		// DNSPrefer is a string-backed enum in the native decoder.
		if key == "ip-version" {
			s, ok := v.(string)
			return ok && (s == "" || s == "dual" || s == "ipv4" || s == "ipv6" || s == "ipv4-prefer" || s == "ipv6-prefer")
		}
		n, ok := v.(json.Number)
		if !ok && key == "port" {
			if s, is := v.(string); is {
				n, ok = json.Number(s), true
			}
		}
		if !ok {
			return false
		}
		i, e := n.Int64()
		if e != nil || i < 0 || i > 1<<30 {
			return false
		}
		if key == "port" {
			return i <= 65535
		}
		if key == "max-early-data" {
			return i <= 8192
		}
		if key == "workers" {
			return i <= 32
		}
		if key == "max-open-streams" {
			return i <= 8192
		}
		if key == "max-connections" || key == "min-streams" || key == "max-streams" || key == "min-idle-session" {
			return i <= 64
		}
		if strings.Contains(key, "window") {
			return i <= 64*1024*1024
		}
		return true
	case reflect.String:
		s, ok := v.(string)
		if !ok && (key == "up" || key == "down" || key == "hop-interval") {
			_, ok = v.(json.Number)
			return ok
		}
		if !ok || len(s) > 16384 {
			return false
		}
		if key == "password" || key == "username" || key == "token" || key == "obfs-password" || key == "psk" {
			return len(s) <= 1024
		}
		return !hasControl(s) && len(s) <= 4096
	case reflect.Slice:
		a, ok := v.([]any)
		if !ok || len(a) > 512 {
			return false
		}
		for _, item := range a {
			if !nativeValueValid(key, item, t.Elem(), depth+1) {
				return false
			}
		}
		return true
	case reflect.Map:
		m, ok := v.(map[string]any)
		if !ok || len(m) > 32 {
			return false
		}
		for k, item := range m {
			if hasControl(k) || len(k) > 128 || !nativeValueValid(k, item, t.Elem(), depth+1) {
				return false
			}
		}
		return true
	}
	return false
}

func pluginFields() map[string]nativeField {
	// These are built-in protocol transports, never external executables.
	fields := map[string]nativeField{}
	for _, k := range strings.Fields("mode host path password fingerprint name-cert-verify version-hint restls-script") {
		fields[k] = nativeField{reflect.TypeOf(""), true}
	}
	for _, k := range strings.Fields("tls mux skip-cert-verify v2ray-http-upgrade v2ray-http-upgrade-fast-open") {
		fields[k] = nativeField{reflect.TypeOf(false), true}
	}
	fields["headers"] = nativeField{reflect.TypeOf(map[string]string{}), true}
	fields["alpn"] = nativeField{reflect.TypeOf([]string{}), true}
	fields["version"] = nativeField{reflect.TypeOf(0), true}
	fields["certificate"] = nativeField{reflect.TypeOf(""), true}
	fields["private-key"] = nativeField{reflect.TypeOf(""), true}
	fields["ech-opts"] = nativeField{reflect.TypeOf(outbound.ECHOptions{}), true}
	return fields
}

func safePluginOptions(m map[string]any, depth int) bool {
	return nativeMappingValid(m, pluginFields(), depth)
}

func nativePort(m map[string]any) bool {
	if p, ok := m["port"].(json.Number); ok {
		n, e := strconv.Atoi(string(p))
		return e == nil && n > 0 && n <= 65535
	}
	if p, ok := m["port"].(string); ok {
		n, e := strconv.Atoi(p)
		return e == nil && n > 0 && n <= 65535
	}
	kind, _ := m["type"].(string)
	return (kind == "hysteria2" || kind == "hysteria") && m["ports"] != "" && m["ports"] != nil || kind == "mieru" && m["port-range"] != "" && m["port-range"] != nil
}
