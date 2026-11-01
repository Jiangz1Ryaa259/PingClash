// SPDX-License-Identifier: GPL-3.0-only
package mobile

import (
	"encoding/base64"
	"encoding/json"
	"errors"
	"net/url"
	"strconv"
	"strings"
	"unicode/utf8"
)

func decodeSubscriptionBase64(text string) (string, error) {
	if len(text) > 2*1024*1024 {
		return "", errors.New("invalid_node")
	}
	compact := strings.Map(func(r rune) rune {
		if r == '\r' || r == '\n' || r == ' ' || r == '\t' {
			return -1
		}
		return r
	}, text)
	for _, encoding := range []*base64.Encoding{base64.StdEncoding, base64.RawStdEncoding, base64.URLEncoding, base64.RawURLEncoding} {
		if value, err := encoding.Strict().DecodeString(compact); err == nil && utf8.Valid(value) {
			return string(value), nil
		}
	}
	return "", errors.New("invalid_node")
}
func looksLikeShareList(text string) bool {
	for _, line := range strings.Split(text, "\n") {
		line = strings.TrimSpace(line)
		if line == "" || strings.HasPrefix(line, "#") {
			continue
		}
		scheme, _, ok := strings.Cut(line, "://")
		if !ok || len(scheme) > 16 {
			return false
		}
		for _, r := range scheme {
			if !(r >= 'a' && r <= 'z' || r >= 'A' && r <= 'Z' || r >= '0' && r <= '9' || r == '+' || r == '-') {
				return false
			}
		}
		return true
	}
	return false
}
func unwrapSubscription(text string) (string, string) {
	text = strings.TrimSpace(strings.TrimPrefix(text, "\ufeff"))
	if looksLikeShareList(text) {
		return text, "uri_list"
	}
	if decoded, err := decodeSubscriptionBase64(text); err == nil {
		decoded = strings.TrimSpace(strings.TrimPrefix(decoded, "\ufeff"))
		if looksLikeShareList(decoded) || strings.HasPrefix(decoded, "proxies:") || strings.HasPrefix(decoded, "{") {
			return decoded, "base64"
		}
	}
	return text, "clash"
}

func importShareLinks(text, format string) string {
	nodes := make([]map[string]any, 0)
	rejected := make(map[string]int)
	count := 0
	for _, line := range strings.Split(text, "\n") {
		line = strings.TrimSpace(line)
		if line == "" || strings.HasPrefix(line, "#") {
			continue
		}
		count++
		if count > 500 {
			return `{"state":"failed","code":"profile_invalid","nodes":[]}`
		}
		node, err := parseShareLink(line)
		if err != nil {
			rejected[err.Error()]++
			continue
		}
		nodes = append(nodes, node)
	}
	result := importedProfile{State: "failed", Code: "no_supported_nodes", Nodes: []importedNode{}, Rejections: make(map[string]int)}
	if len(nodes) > 0 {
		encoded, _ := json.Marshal(map[string]any{"proxies": nodes})
		if json.Unmarshal([]byte(ImportProfile(string(encoded))), &result) != nil {
			return `{"state":"failed","code":"profile_invalid","nodes":[]}`
		}
	}
	if result.Rejections == nil {
		result.Rejections = make(map[string]int)
	}
	for key, count := range rejected {
		result.Rejections[key] += count
		result.Unsupported += count
	}
	result.Format = format
	encoded, _ := json.Marshal(result)
	return string(encoded)
}

func parseShareLink(line string) (map[string]any, error) {
	invalid := errors.New("invalid_node")
	if len(line) > 96*1024 {
		return nil, invalid
	}
	scheme, body, ok := strings.Cut(line, "://")
	if !ok {
		return nil, invalid
	}
	scheme = strings.ToLower(scheme)
	if scheme == "http" || scheme == "https" || scheme == "socks" || scheme == "socks5" {
		return parseProxyURL(line)
	}
	if scheme == "ssr" {
		return parseSSRShare(body)
	}
	if scheme == "vmess" {
		return parseVMessShare(body)
	}
	if scheme == "ss" && !strings.Contains(strings.SplitN(body, "#", 2)[0], "@") {
		parts := strings.SplitN(body, "#", 2)
		decoded, err := decodeSubscriptionBase64(parts[0])
		if err != nil {
			return nil, invalid
		}
		line = "ss://" + decoded
		if len(parts) == 2 {
			line += "#" + parts[1]
		}
	}
	link, err := url.Parse(line)
	if err != nil || link.Opaque != "" || !validHost(link.Hostname()) || link.Path != "" && link.Path != "/" || link.User == nil {
		return nil, invalid
	}
	port, err := strconv.Atoi(link.Port())
	if err != nil || port < 1 || port > 65535 {
		return nil, invalid
	}
	node := map[string]any{"type": scheme, "name": link.Fragment, "server": link.Hostname(), "port": port}
	user := link.User.Username()
	password, hasPassword := link.User.Password()
	switch scheme {
	case "anytls", "trojan", "hysteria2", "hy2":
		if !hasPassword {
			password = user
		}
		node["password"] = password
		node["udp"] = true
		if scheme == "hy2" {
			node["type"] = "hysteria2"
		}
	case "vless":
		node["uuid"] = user
	case "tuic":
		node["uuid"] = user
		node["password"] = password
		node["udp"] = true
	case "ss":
		if !hasPassword {
			decoded, err := decodeSubscriptionBase64(user)
			if err != nil {
				return nil, invalid
			}
			user, password, ok = strings.Cut(decoded, ":")
			if !ok {
				return nil, invalid
			}
		}
		node["cipher"] = user
		node["password"] = password
		node["udp"] = true
	default:
		return nil, errors.New("unsupported_protocol")
	}
	query, err := url.ParseQuery(link.RawQuery)
	if err != nil || len(query) > 32 {
		return nil, invalid
	}
	options := map[string]string{}
	aliases := map[string]string{"peer": "sni", "allowInsecure": "insecure", "allow_insecure": "insecure", "skip-cert-verify": "insecure", "fp": "client-fingerprint", "congestion_control": "congestion-controller", "udp_relay_mode": "udp-relay-mode", "mport": "ports", "obfsParam": "obfs-password"}
	fields := "sni insecure alpn"
	switch scheme {
	case "anytls":
		fields += " client-fingerprint idle-session-check-interval idle-session-timeout min-idle-session"
	case "vless":
		fields += " client-fingerprint security type path host serviceName flow encryption pbk sid headerType mode extra"
	case "trojan":
		fields += " client-fingerprint security type path host serviceName headerType"
	case "hysteria2", "hy2":
		fields += " obfs obfs-password ports hop-interval up down"
	case "tuic":
		fields += " congestion-controller udp-relay-mode"
	case "ss":
		fields = "plugin udp-over-tcp"
	}
	allowed := strings.Fields(fields)
	for key, values := range query {
		if len(values) != 1 {
			return nil, invalid
		}
		if alias, ok := aliases[key]; ok {
			key = alias
		}
		known := false
		for _, allowedKey := range allowed {
			if key == allowedKey {
				known = true
				break
			}
		}
		if !known {
			return nil, errors.New("unsupported_fields")
		}
		if _, exists := options[key]; exists {
			return nil, invalid
		}
		options[key] = values[0]
	}
	if scheme == "ss" {
		if value, exists := options["plugin"]; exists {
			kind, opts, e := ssPlugin(value)
			if e != nil {
				return nil, e
			}
			node["plugin"] = kind
			node["plugin-opts"] = opts
		}
		if v, exists := options["udp-over-tcp"]; exists {
			b, e := strconv.ParseBool(v)
			if e != nil {
				return nil, invalid
			}
			node["udp-over-tcp"] = b
		}
	}
	// Preserve the subscription's explicit certificate policy.
	if insecure, exists := options["insecure"]; exists {
		switch insecure {
		case "0", "false":
			node["skip-cert-verify"] = false
		case "1", "true":
			node["skip-cert-verify"] = true
		default:
			return nil, invalid
		}
	}
	for _, key := range []string{"sni", "client-fingerprint", "flow", "encryption", "obfs", "obfs-password", "congestion-controller", "udp-relay-mode", "ports", "hop-interval", "up", "down"} {
		if value, ok := options[key]; ok {
			node[key] = value
		}
	}
	if alpn, ok := options["alpn"]; ok {
		node["alpn"] = strings.Split(alpn, ",")
	}
	for _, key := range []string{"idle-session-check-interval", "idle-session-timeout", "min-idle-session"} {
		if value, ok := options[key]; ok {
			n, err := strconv.Atoi(value)
			if err != nil {
				return nil, invalid
			}
			node[key] = n
		}
	}
	if scheme == "vless" {
		node["tls"] = options["security"] == "tls" || options["security"] == "reality"
		if sni, ok := node["sni"]; ok {
			node["servername"] = sni
			delete(node, "sni")
		}
		if options["security"] == "reality" {
			node["reality-opts"] = map[string]any{"public-key": options["pbk"], "short-id": options["sid"]}
		}
		if options["security"] != "reality" && (options["pbk"] != "" || options["sid"] != "") {
			return nil, errors.New("unsupported_fields")
		}
	} else if options["security"] != "" && options["security"] != "tls" {
		return nil, errors.New("unsupported_fields")
	}
	if network := options["type"]; network != "" && network != "tcp" {
		node["network"] = network
		switch network {
		case "ws":
			node["ws-opts"] = map[string]any{"path": options["path"], "headers": map[string]any{"Host": options["host"]}}
		case "grpc":
			node["grpc-opts"] = map[string]any{"grpc-service-name": options["serviceName"]}
		case "h2":
			node["h2-opts"] = map[string]any{"path": options["path"], "host": strings.Split(options["host"], ",")}
		case "http":
			node["http-opts"] = map[string]any{"path": []string{options["path"]}, "headers": map[string]any{"Host": strings.Split(options["host"], ",")}}
		case "xhttp":
			opts := map[string]any{"path": options["path"], "host": options["host"]}
			if options["mode"] != "" {
				opts["mode"] = options["mode"]
			}
			if extra := options["extra"]; extra != "" {
				if len(extra) > 8192 {
					return nil, invalid
				}
				raw, e := strictNodeObject(extra)
				if e != nil {
					return nil, invalid
				}
				for k, v := range raw {
					if _, exists := opts[k]; !exists {
						opts[k] = v
					}
				}
			}
			node["xhttp-opts"] = opts
		default:
			return nil, errors.New("unsupported_fields")
		}
	}
	if options["headerType"] != "" && options["headerType"] != "none" {
		return nil, errors.New("unsupported_fields")
	}
	return node, nil
}

func parseVMessShare(body string) (map[string]any, error) {
	invalid := errors.New("invalid_node")
	decoded, err := decodeSubscriptionBase64(body)
	if err != nil {
		return nil, invalid
	}
	raw, err := strictNodeObject(decoded)
	if err != nil {
		return nil, invalid
	}
	allowed := " v ps add port id aid scy net type host path tls sni alpn fp allowInsecure "
	for key := range raw {
		if !strings.Contains(allowed, " "+key+" ") {
			return nil, errors.New("unsupported_fields")
		}
	}
	text := func(key string) string {
		switch v := raw[key].(type) {
		case string:
			return v
		case json.Number:
			return string(v)
		}
		return ""
	}
	port, err := strconv.Atoi(text("port"))
	if err != nil {
		return nil, invalid
	}
	node := map[string]any{"type": "vmess", "name": text("ps"), "server": text("add"), "port": port, "uuid": text("id"), "cipher": text("scy"), "tls": text("tls") == "tls"}
	if value, exists := raw["allowInsecure"]; exists {
		switch value {
		case false, "0", "false", json.Number("0"):
			node["skip-cert-verify"] = false
		case true, "1", "true", json.Number("1"):
			node["skip-cert-verify"] = true
		default:
			return nil, invalid
		}
	}
	if node["cipher"] == "" {
		node["cipher"] = "auto"
	}
	id := text("aid")
	if id == "" {
		id = "0"
	}
	node["alterId"] = json.Number(id)
	if text("sni") != "" {
		node["servername"] = text("sni")
	}
	if text("fp") != "" {
		node["client-fingerprint"] = text("fp")
	}
	if text("alpn") != "" {
		node["alpn"] = strings.Split(text("alpn"), ",")
	}
	switch text("net") {
	case "", "tcp":
		if text("type") != "" && text("type") != "none" {
			return nil, errors.New("unsupported_fields")
		}
	case "ws":
		node["network"] = "ws"
		node["ws-opts"] = map[string]any{"path": text("path"), "headers": map[string]any{"Host": text("host")}}
	case "grpc":
		node["network"] = "grpc"
		node["grpc-opts"] = map[string]any{"grpc-service-name": text("path")}
	default:
		return nil, errors.New("unsupported_fields")
	}
	return node, nil
}

func rejectionReason(text string, issues []string) string {
	mapping, err := strictNodeObject(text)
	if err != nil {
		return "invalid_node"
	}
	kind, _ := mapping["type"].(string)
	if kind != "anytls" && kind != "socks5" {
		if _, ok := protocolFields[kind]; !ok {
			return "unsupported_protocol"
		}
	}
	if value, ok := mapping["dialer-proxy"].(string); ok && value != "" {
		return "proxy_chain"
	}
	if len(issues) == 1 && issues[0] == kind+".parameters" {
		return "invalid_parameters"
	}
	return "unsupported_fields"
}
