// SPDX-License-Identifier: GPL-3.0-only
package mobile

import (
	"encoding/json"
	"net/netip"
	"strconv"
	"testing"
)

func TestDevicePolicyAppliesBeforeProfileRouting(t *testing.T) {
	s := &vpnSession{options: runtimeOptions{IPv6: false, LAN: "direct"}}
	cases := map[string]string{
		"192.168.1.10": "DIRECT", "10.0.0.2": "DIRECT", "fe80::1": "BLOCKED",
		"2001:db8::1": "BLOCKED", "224.0.0.251": "BLOCKED", "0.0.0.0": "BLOCKED", "203.0.113.5": "",
	}
	for address, want := range cases {
		if got := s.devicePolicy(netip.MustParseAddr(address)); got != want {
			t.Fatalf("%s: got %q want %q", address, got, want)
		}
	}
	s.options = runtimeOptions{IPv6: true, LAN: "block"}
	if s.devicePolicy(netip.MustParseAddr("192.168.1.10")) != "REJECT" || s.devicePolicy(netip.MustParseAddr("2001:db8::1")) != "" {
		t.Fatal("LAN block or IPv6 enable not honoured")
	}
	s.options = runtimeOptions{IPv6: true, LAN: "proxy"}
	if s.devicePolicy(netip.MustParseAddr("192.168.1.10")) != "" {
		t.Fatal("LAN proxy must leave routing to the profile")
	}
	// The complete native tunnel receives DIRECT as a special proxy.
	s.options = runtimeOptions{IPv6: true, LAN: "direct"}
	m := ruleMetadata("", netip.MustParseAddr("192.168.1.10"), 631, "tcp")
	if !s.applyDevicePolicy(m) || m.SpecialProxy != "DIRECT" {
		t.Fatal("native LAN direct not pinned")
	}
	s.options.LAN = "block"
	if s.applyDevicePolicy(ruleMetadata("", netip.MustParseAddr("192.168.1.10"), 631, "tcp")) || s.rejected.Load() != 1 {
		t.Fatal("native LAN block not refused")
	}
}

func TestProcessLookupOnlyForOwnerRules(t *testing.T) {
	if rulesNeedProcess(runtimeOptions{Mode: "rule", Rules: []string{"DOMAIN-SUFFIX,fluid.example,PROXY", "MATCH,PROXY"}}) {
		t.Fatal("ordinary rules must not trigger owner lookups")
	}
	if rulesNeedProcess(runtimeOptions{Mode: "global", Rules: []string{"PROCESS-NAME,org.example.app,DIRECT"}}) {
		t.Fatal("global mode never evaluates rules")
	}
	for _, line := range []string{"PROCESS-NAME,org.example.app,DIRECT", "uid,10123,DIRECT", "AND,((NETWORK,udp),(PROCESS-PATH-REGEX,^org)),DIRECT"} {
		if !rulesNeedProcess(runtimeOptions{Mode: "rule", Rules: []string{line}}) {
			t.Fatal("owner rule missed:", line)
		}
	}
	definition, _ := json.Marshal(map[string]any{"sub-rules": map[string][]string{"apps": {"UID,10000-19999,DIRECT"}}})
	if !rulesNeedProcess(runtimeOptions{Mode: "rule", Rules: []string{"SUB-RULE,(NETWORK,tcp),apps"}, Definition: string(definition)}) {
		t.Fatal("owner sub-rule missed")
	}
}

func TestSelectionFingerprintIgnoresOnlyPicks(t *testing.T) {
	document := func(pick string, extra string) string {
		b, _ := json.Marshal(map[string]any{"proxies": []any{}, "rules": []string{extra}, "pingclash-selections": map[string]string{"main": pick}})
		return string(b)
	}
	request := func(pick, extra string) (string, string) {
		config, _ := json.Marshal(map[string]string{"type": "profile-target", "target": "main", "definition": document(pick, extra)})
		options, _ := json.Marshal(map[string]any{"mode": "rule", "definition": document(pick, extra)})
		return string(config), string(options)
	}
	c1, o1 := request("hk", "MATCH,main")
	c2, o2 := request("jp", "MATCH,main")
	c3, o3 := request("jp", "MATCH,DIRECT")
	a, b, c := newSelectionState(c1, "1.1.1.1", o1), newSelectionState(c2, "1.1.1.1", o2), newSelectionState(c3, "1.1.1.1", o3)
	if a == nil || b == nil || c == nil || a.key != b.key || a.key == c.key || b.picks["main"] != "jp" {
		t.Fatal("selection fingerprint incorrect")
	}
	if d := newSelectionState(c2, "8.8.8.8", o2); d == nil || d.key == b.key {
		t.Fatal("DNS change must force a rebuild")
	}
	if newSelectionState(c1, "1.1.1.1", o2) != nil {
		t.Fatal("mismatched config and option picks must force a rebuild")
	}
	if (&vpnSession{}).hotSelect(b) {
		t.Fatal("hot selection without a live session")
	}
}

func TestDeferredRuleProviderResolvesEmptyButProxyProviderStillRequired(t *testing.T) {
	bodies := remoteBodies()
	bodies["rule:domains"] = ""
	r := resolveFixture(t, remoteFixture, bodies)
	if r["state"] != "passed" {
		t.Fatal("deferred rule provider rejected the profile", r)
	}
	bodies = remoteBodies()
	bodies["proxy:alpha"] = ""
	if r := resolveFixture(t, remoteFixture, bodies); r["state"] == "passed" {
		t.Fatal("an empty proxy provider must not pass")
	}
}

func TestTemplateSizedGroupGraphStaysSupported(t *testing.T) {
	text := "proxies:\n"
	regions := []string{}
	for i := 0; i < 12; i++ {
		name := "node" + string(rune('a'+i))
		text += "  - {name: " + name + ", type: socks5, server: example.invalid, port: 1080}\n"
		regions = append(regions, "region"+string(rune('a'+i)))
	}
	text += "proxy-groups:\n  - {name: main, type: select, proxies: [all]}\n  - {name: all, type: fallback, url: https://example.invalid/check, interval: 300, proxies: ["
	for i, r := range regions {
		if i > 0 {
			text += ", "
		}
		text += r
	}
	text += "]}\n"
	for i, r := range regions {
		text += "  - {name: " + r + ", type: url-test, url: https://example.invalid/check, interval: 300, proxies: [node" + string(rune('a'+i)) + "]}\n"
	}
	var result struct {
		State  string
		Groups []struct {
			Name      string
			Supported bool
			Reason    string
		}
	}
	if e := json.Unmarshal([]byte(ImportProfile(text)), &result); e != nil || result.State != "passed" {
		t.Fatal("import failed", e)
	}
	for _, g := range result.Groups {
		if !g.Supported {
			t.Fatal("template group rejected:", g.Name, g.Reason)
		}
	}
}

func TestProviderWithoutTypeInfersHTTPOrInline(t *testing.T) {
	text := `proxy-providers:
  remote: {url: "https://example.invalid/nodes", path: ./nodes.yaml, interval: 3600}
rule-providers:
  listed: {url: "https://example.invalid/list.txt", behavior: domain}
  local: {behavior: domain, payload: ['+.example.org']}
proxy-groups:
  - {name: main, type: select, use: [remote]}
rules: ['RULE-SET,listed,main', 'RULE-SET,local,DIRECT', 'MATCH,main']
`
	var plan struct {
		State     string
		Providers []remoteSpec
	}
	if json.Unmarshal([]byte(PlanRemoteProviders(text)), &plan) != nil || plan.State != "passed" {
		t.Fatal("untyped providers rejected:", PlanRemoteProviders(text))
	}
	modes := map[string]string{}
	for _, p := range plan.Providers {
		modes[p.ID] = p.Mode
	}
	if modes["proxy:remote"] != "http" || modes["rule:listed"] != "http" || modes["rule:local"] != "inline" {
		t.Fatal("type inference incorrect:", modes)
	}
}

func TestProviderExcludeTypeAcceptsCommasAndSpaces(t *testing.T) {
	for _, exclude := range []string{"ss|vmess", "ss, vmess", " SS ,VMess", "trojan|vmess"} {
		if !providerExcludesType(exclude, "vmess") {
			t.Fatal("vmess not excluded by", exclude)
		}
	}
	if providerExcludesType("ss, vmess", "vless") || providerExcludesType(" , ", "") {
		t.Fatal("exclude-type matched an unlisted type")
	}
}

func TestTrackerRuleNamesCoverNativeRuleTypes(t *testing.T) {
	cases := map[string]string{
		"DomainSuffix": "DOMAIN-SUFFIX", "IPSuffix": "IP-SUFFIX", "SrcIPSuffix": "SRC-IP-SUFFIX",
		"InPort": "IN-PORT", "ProcessNameRegex": "PROCESS-NAME-REGEX", "ProcessPathWildcard": "PROCESS-PATH-WILDCARD",
		"InType": "IN-TYPE", "InUser": "IN-USER", "InName": "IN-NAME", "RematchName": "REMATCH-NAME",
		"SubRules": "SUB-RULE", "Uid": "UID", "secret.example": "UNRECOGNIZED",
	}
	for native, public := range cases {
		if got := publicTrackerRule(native); got != public {
			t.Fatalf("%s: got %s want %s", native, got, public)
		}
	}
}

// The app stores DNS as a bare IP; a profile-target (policy group) start must not
// inherit the "missing port" parse error as node_invalid.
func TestProfileTargetStartsWithBareDNSAddress(t *testing.T) {
	p := imported(t, `proxies: [{name: hk, type: socks5, server: 127.0.0.1, port: 9}]
proxy-groups: [{name: Main, type: select, proxies: [hk, DIRECT]}]
rules: ['MATCH,Main']`)
	o := defaultRuntimeOptions()
	o.Definition = p.Definition
	encoded, _ := json.Marshal(o)
	config := `{"type":"profile-target","target":"Main","definition":` + strconv.Quote(p.Definition) + `}`
	for _, dns := range []string{"1.1.1.1", "1.1.1.1:53", "[2606:4700:4700::1111]:53", "2606:4700:4700::1111"} {
		s, e := newSessionOwned(config, dns, string(encoded), nil, true)
		if e != nil {
			t.Fatalf("dns %s: %s", dns, vpnStartupCode(e))
		}
		n, e := prepareFullNative(s)
		if e != nil {
			t.Fatalf("dns %s: native %s", dns, vpnStartupCode(e))
		}
		n.stop()
		s.close()
	}
}
