// SPDX-License-Identifier: GPL-3.0-only
package mobile

import (
	"errors"
	"net/url"
	"strconv"
	"strings"
)

func parseProxyURL(line string) (map[string]any, error) {
	invalid := errors.New("invalid_node")
	u, e := url.Parse(line)
	if e != nil || !validHost(u.Hostname()) || u.Path != "" && u.Path != "/" {
		return nil, invalid
	}
	kind := strings.ToLower(u.Scheme)
	tls := kind == "https"
	if kind == "socks" || kind == "socks5" {
		kind = "socks5"
	} else {
		kind = "http"
	}
	port := u.Port()
	if port == "" {
		if tls {
			port = "443"
		} else if kind == "http" {
			port = "80"
		} else {
			port = "1080"
		}
	}
	n, e := strconv.Atoi(port)
	if e != nil || n < 1 || n > 65535 {
		return nil, invalid
	}
	m := map[string]any{"type": kind, "name": u.Fragment, "server": u.Hostname(), "port": n, "tls": tls}
	if u.User != nil {
		m["username"] = u.User.Username()
		p, _ := u.User.Password()
		m["password"] = p
	}
	q, e := url.ParseQuery(u.RawQuery)
	if e != nil || len(q) > 8 {
		return nil, invalid
	}
	for key, v := range q {
		if len(v) != 1 {
			return nil, invalid
		}
		switch key {
		case "sni":
			m[key] = v[0]
		case "tls", "udp", "skip-cert-verify":
			b, e := strconv.ParseBool(v[0])
			if e != nil {
				return nil, invalid
			}
			m[key] = b
		default:
			return nil, errors.New("unsupported_fields")
		}
	}
	return m, nil
}
func parseSSRShare(body string) (map[string]any, error) {
	invalid := errors.New("invalid_node")
	text, e := decodeSubscriptionBase64(body)
	if e != nil {
		return nil, invalid
	}
	endpoint, query, _ := strings.Cut(text, "/?")
	// SSR's six colon fields precede the base64 password; bracketed IPv6 is
	// deliberately left to YAML rather than guessing an ambiguous URI authority.
	parts := strings.Split(endpoint, ":")
	if len(parts) != 6 {
		return nil, invalid
	}
	port, e := strconv.Atoi(parts[1])
	if e != nil {
		return nil, invalid
	}
	pass, e := decodeSubscriptionBase64(parts[5])
	if e != nil {
		return nil, invalid
	}
	m := map[string]any{"type": "ssr", "name": "", "server": parts[0], "port": port, "protocol": parts[2], "cipher": parts[3], "obfs": parts[4], "password": pass, "udp": true}
	q, e := url.ParseQuery(query)
	if e != nil || len(q) > 8 {
		return nil, invalid
	}
	for key, v := range q {
		if len(v) != 1 {
			return nil, invalid
		}
		if key == "group" {
			continue
		}
		s, e := decodeSubscriptionBase64(v[0])
		if e != nil && v[0] != "" {
			return nil, invalid
		}
		switch key {
		case "remarks":
			m["name"] = s
		case "protoparam":
			m["protocol-param"] = s
		case "obfsparam":
			m["obfs-param"] = s
		default:
			return nil, errors.New("unsupported_fields")
		}
	}
	return m, nil
}
func ssPlugin(value string) (string, map[string]any, error) {
	invalid := errors.New("unsupported_fields")
	parts := strings.Split(value, ";")
	if len(parts) > 20 {
		return "", nil, invalid
	}
	kind := parts[0]
	if kind == "obfs-local" || kind == "simple-obfs" {
		kind = "obfs"
	}
	if kind != "obfs" && kind != "v2ray-plugin" && kind != "shadow-tls" && kind != "restls" {
		return "", nil, invalid
	}
	options := map[string]any{}
	for _, part := range parts[1:] {
		k, v, has := strings.Cut(part, "=")
		if _, duplicate := options[k]; duplicate {
			return "", nil, invalid
		}
		switch k {
		case "obfs":
			k = "mode"
		case "obfs-host":
			k = "host"
		case "obfs-uri":
			k = "path"
		}
		if k == "tls" || k == "mux" || k == "skip-cert-verify" {
			b := true
			var e error
			if has {
				b, e = strconv.ParseBool(v)
			}
			if e != nil {
				return "", nil, invalid
			}
			options[k] = b
		} else if has {
			options[k] = v
		} else {
			return "", nil, invalid
		}
	}
	if kind == "v2ray-plugin" && options["mode"] == nil {
		options["mode"] = "websocket"
	}
	return kind, options, nil
}
