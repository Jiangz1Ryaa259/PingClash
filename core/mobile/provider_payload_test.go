// SPDX-License-Identifier: GPL-3.0-only
package mobile

import (
	"encoding/base64"
	"encoding/json"
	"strings"
	"testing"
)

func TestProxyProvidersAcceptUriBase64AndJsonPreservingGroupsAndNativeTypeExclusion(t *testing.T) {
	const profile = `proxy-providers:
 source: {type: http, url: 'https://example.invalid/CANARY-SUB-TOKEN-1234', exclude-type: 'Trojan|VMESS'}
proxy-groups: [{name: Official, type: select, use: [source], proxies: [DIRECT]}]
`
	uri := "anytls://CANARY-PASSWORD-1234@example.invalid:443#Keep\n" +
		"trojan://CANARY-PASSWORD-1234@example.invalid:443#Exclude"
	for _, body := range []string{uri, base64.StdEncoding.EncodeToString([]byte(uri)),
		`[{"name":"Keep","type":"anytls","server":"example.invalid","port":443,"password":"fixture"}]`,
		`{"name":"Keep","type":"anytls","server":"example.invalid","port":443,"password":"fixture"}`} {
		r := resolveFixture(t, profile, map[string]string{"proxy:source": body})
		if r["state"] != "passed" {
			t.Fatal("compatible provider payload rejected")
		}
		p := imported(t, r["content"].(string))
		if len(p.Nodes) != 1 || len(p.Groups) != 1 || !p.Groups[0].Supported || p.Nodes[0].Name != "Keep" ||
			strings.Join(p.Groups[0].Proxies, ",") != "DIRECT,Keep" {
			t.Fatal("provider group membership changed")
		}
	}
	r := resolveFixture(t, "proxies: [{name: Local, type: socks5, server: example.invalid, port: 1080}]\n"+profile, map[string]string{"proxy:source": "trojan://CANARY-PASSWORD-1234@example.invalid:443#Exclude"})
	if r["state"] != "passed" {
		t.Fatal("empty filtered provider rejected a valid explicit DIRECT member")
	}
	p := imported(t, r["content"].(string))
	if len(p.Nodes) != 1 || len(p.Groups) != 1 || strings.Join(p.Groups[0].Proxies, ",") != "DIRECT" {
		t.Fatal("filter fallback membership changed")
	}

}

func TestProviderDialerRetainedAndMalformedUriDoesNotLeakOrPartiallyReplace(t *testing.T) {
	const profile = `proxies: [{name: Hop, type: socks5, server: example.invalid, port: 1080}]
proxy-providers:
 source: {type: http, url: 'https://example.invalid/CANARY-SUB-TOKEN-1234', dialer-proxy: Hop}
proxy-groups: [{name: Official, type: select, use: [source]}]
`
	body := "anytls://CANARY-PASSWORD-1234@example.invalid:443#Keep"
	r := resolveFixture(t, profile, map[string]string{"proxy:source": body})
	if r["state"] != "passed" {
		t.Fatal("provider-level dialer rejected")
	}
	p := imported(t, r["content"].(string))
	if len(p.Nodes) != 2 || p.Unsupported != 0 || !strings.Contains(p.Nodes[1].Config, "dialer-chain") {
		t.Fatal("provider dialer lost or silently changed to direct")
	}
	partial := resolveFixture(t, profile, map[string]string{"proxy:source": body + "\ninvalid://CANARY-PASSWORD-1234"})
	if partial["state"] != "passed" {
		t.Fatal("one bad URI discarded valid provider members")
	}
	importedPartial := imported(t, partial["content"].(string))
	if len(importedPartial.Nodes) != 2 || importedPartial.Unsupported != 1 {
		t.Fatal("bad URI was not isolated and counted")
	}
	for _, bad := range []string{strings.Repeat(body+"\n", 501)} {
		r := resolveFixture(t, profile, map[string]string{"proxy:source": bad})
		encoded, _ := json.Marshal(r)
		if r["state"] != "failed" || strings.Contains(string(encoded), "CANARY-") || r["content"] != nil {
			t.Fatal("invalid source partially succeeded or exposed source data")
		}
	}
}
