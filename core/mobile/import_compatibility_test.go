// SPDX-License-Identifier: GPL-3.0-only
package mobile

import (
	"encoding/base64"
	"encoding/json"
	"strings"
	"testing"
	"time"
)

func TestNativeTransportFieldsAndLegacyLinksImportWithoutRewriting(t *testing.T) {
	configs := []string{
		`{"type":"anytls","password":"fixture","ip-version":"ipv4-prefer","tfo":true,"client-metadata":"local"}`,
		`{"type":"socks5","username":"fixture","password":"fixture","udp":true}`,
		`{"type":"http","username":"fixture","password":"fixture","tls":true,"sni":"example.invalid"}`,
		`{"type":"ss","cipher":"aes-128-cfb","password":"fixture"}`,
		`{"type":"ssr","cipher":"aes-128-cfb","password":"fixture","protocol":"auth_sha1_v4","obfs":"plain"}`,
		`{"type":"vmess","uuid":"00000000-0000-4000-8000-000000000001","cipher":"none","alterId":64}`,
		`{"type":"vless","uuid":"00000000-0000-4000-8000-000000000001","tls":false,"network":"ws","smux":{"enabled":true,"protocol":"smux","max-connections":2}}`,
		`{"type":"trojan","password":"fixture","fingerprint":"0123456789012345678901234567890123456789012345678901234567890123"}`,
		`{"type":"hysteria","auth-str":"fixture","up":10,"down":50,"protocol":"udp"}`,
		`{"type":"hysteria2","password":"fixture","up":10,"down":50,"hop-interval":"30s","cwnd":32,"udp-mtu":1200}`,
		`{"type":"tuic","uuid":"00000000-0000-4000-8000-000000000001","password":"fixture","disable-sni":true,"reduce-rtt":true,"cwnd":32}`,
		`{"type":"snell","psk":"fixture","version":3}`,
		`{"type":"mieru","username":"fixture","password":"fixture","transport":"TCP"}`,
	}
	for _, raw := range configs {
		var m map[string]any
		_ = json.Unmarshal([]byte(raw), &m)
		m["server"] = "example.invalid"
		m["port"] = 443
		m["name"] = m["type"]
		b, _ := json.Marshal(map[string]any{"proxies": []any{m}})
		p := imported(t, string(b))
		if p.State != "passed" || len(p.Nodes) != 1 || p.Unsupported != 0 {
			t.Errorf("core transport rejected: %s, %v", m["type"], p.FieldIssues)
		}
	}
	pass := base64.RawURLEncoding.EncodeToString([]byte("fixture"))
	ssr := base64.RawURLEncoding.EncodeToString([]byte("example.invalid:443:auth_sha1_v4:aes-128-cfb:plain:" + pass + "/?remarks=" + pass))
	ss := base64.RawURLEncoding.EncodeToString([]byte("aes-128-gcm:fixture"))
	links := []string{"socks5://user:fixture@example.invalid:1080#socks", "https://user:fixture@example.invalid:443#http", "ssr://" + ssr,
		"ss://" + ss + "@example.invalid:443?plugin=obfs-local%3Bobfs%3Dhttp%3Bobfs-host%3Dexample.org#ss",
		"vless://00000000-0000-4000-8000-000000000001@example.invalid:443?security=none&type=ws&path=%2F#vless"}
	p := imported(t, strings.Join(links, "\n"))
	if p.State != "passed" || len(p.Nodes) != len(links) || p.Unsupported != 0 {
		t.Fatal("common link formats rejected", p.Rejections, p.FieldIssues)
	}
}

func TestGeneratedAnchorsOptionalNullAndLookaroundGroups(t *testing.T) {
	text := `base: &base {type: vless, server: example.invalid, port: 443, uuid: 00000000-0000-4000-8000-000000000001, tls: false, ip-version: ipv4-prefer, alpn: null}
proxies:
 - {<<: *base, name: HK-fast}
 - {<<: *base, name: HK-expired}
 - {<<: *base, name: JP-fast}
proxy-groups:
 - {name: main, type: select, proxies: [auto, DIRECT]}
 - {name: auto, type: url-test, include-all-proxies: true, filter: '^(?=.*HK)(?!.*expired)', interval: 300}
rules: ['MATCH,main']
`
	p := imported(t, text)
	if p.State != "passed" || len(p.Nodes) != 3 || p.GroupsUnsupported != 0 || len(p.Groups) != 2 || strings.Join(p.Groups[1].Proxies, ",") != "HK-fast" {
		t.Fatal("generated anchored groups rejected", p.FieldIssues, p.Groups)
	}
	for _, n := range p.Nodes {
		if strings.Contains(n.Config, "null") || decoded(t, ValidateNode(n.Config)).State != "passed" {
			t.Fatal("normalized runtime unavailable")
		}
	}
	for _, bad := range []string{"a: &a [*a]", "a: &a [x,x,x,x,x,x,x,x,x,x]\nb: &b [*a,*a,*a,*a,*a,*a,*a,*a,*a,*a]\nc: &c [*b,*b,*b,*b,*b,*b,*b,*b,*b,*b]\nd: &d [*c,*c,*c,*c,*c,*c,*c,*c,*c,*c]\ne: [*d,*d,*d,*d,*d,*d,*d,*d,*d,*d]"} {
		if _, e := remoteYAML(bad); e == nil {
			t.Fatal("cyclic or amplified aliases accepted")
		}
	}
	f, e := compileFilters(`(?=a)(a+)+$`)
	if e != nil {
		t.Fatal("fallback compile")
	}
	start := time.Now()
	_ = f.match(strings.Repeat("a", 500) + "!")
	if !f.failed || time.Since(start) > 2*time.Second {
		t.Fatal("extended filter is not bounded")
	}
}

func TestInlineOverridesAndIdenticalProviderReferences(t *testing.T) {
	text := `proxy-providers:
 local:
  type: inline
  payload:
   - {name: HK-one, type: anytls, server: example.invalid, port: 443, password: CANARY-PASSWORD-1234}
  override: {additional-prefix: 'A-', udp: true}
rule-providers:
 domains: {type: inline, behavior: domain, payload: ['+.example.org', '*.example.net']}
proxy-groups:
 - {name: main, type: select, use: [local], filter: '^(?!.*expired)'}
rules: ['RULE-SET,domains,main', 'MATCH,main']
`
	r := resolveFixture(t, text, map[string]string{})
	if r["state"] != "passed" {
		t.Fatal("inline resolution", r)
	}
	p := imported(t, r["content"].(string))
	if p.State != "passed" || len(p.Nodes) != 1 || p.Nodes[0].Name != "A-HK-one" || p.GroupsUnsupported != 0 || len(p.Rules) != 2 {
		t.Fatal("inline semantics", p.Rejections, p.Groups)
	}
	if strings.Contains(p.Definition, "proxy-providers") || strings.Contains(p.Definition, "CANARY-SUB-TOKEN") {
		t.Fatal("source credentials in runtime")
	}
	body := `proxies: [{name: same, type: socks5, server: example.invalid, port: 1080}]`
	dup := `proxy-providers:
 a: {type: http, url: https://example.invalid/a, header: {Authorization: CANARY-HEADER-1234}}
 b: {type: http, url: https://example.invalid/b}
proxy-groups: [{name: main, type: select, use: [a,b]}]
`
	r = resolveFixture(t, dup, map[string]string{"proxy:a": body, "proxy:b": body})
	if r["state"] != "passed" {
		t.Fatal("identical provider references rejected")
	}
	p = imported(t, r["content"].(string))
	if len(p.Nodes) != 1 || len(p.Groups[0].Proxies) != 1 {
		t.Fatal("duplicate references not deduplicated")
	}
	r = resolveFixture(t, dup, map[string]string{"proxy:a": body, "proxy:b": strings.Replace(body, "1080", "1081", 1)})
	if r["state"] != "passed" {
		t.Fatal("conflicting provider names were not disambiguated")
	}
	p = imported(t, r["content"].(string))
	if len(p.Nodes) != 2 || p.Nodes[0].Name == p.Nodes[1].Name || len(p.Groups[0].Proxies) != 2 {
		t.Fatal("provider identity or membership lost")
	}

	separate := `proxies: [{name: Local, type: socks5, server: example.invalid, port: 1080}]
proxy-providers:
 remote: {type: inline, payload: [{name: Remote, type: socks5, server: example.invalid, port: 1081}]}
proxy-groups:
 - {name: Locals, type: select, include-all-proxies: true}
 - {name: Remotes, type: select, use: [remote]}
 - {name: All, type: select, include-all: true}
`
	r = resolveFixture(t, separate, map[string]string{})
	if r["state"] != "passed" {
		t.Fatal("source pools did not resolve")
	}
	p = imported(t, r["content"].(string))
	if p.GroupsUnsupported != 0 || len(p.Groups) != 3 || strings.Join(p.Groups[0].Proxies, ",") != "Local" ||
		strings.Join(p.Groups[1].Proxies, ",") != "Remote" || len(p.Groups[2].Proxies) != 2 {
		t.Fatal("local/provider pools were mixed")
	}
}
