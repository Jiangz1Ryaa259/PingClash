// SPDX-License-Identifier: GPL-3.0-only
package mobile

import (
	"encoding/json"
	"fmt"
	"github.com/metacubex/mihomo/component/geodata"
	M "github.com/metacubex/sing/common/metadata"
	"net/netip"
	"path/filepath"
	"strings"
	"testing"
)

func TestBundledGeoRulesMatchNativeDomainAndCountryWithoutNetwork(t *testing.T) {
	directory, err := filepath.Abs("../../app/src/main/assets/ruledata")
	if err != nil || !InitializeRuleData(directory) {
		t.Fatal("pinned rule data unavailable")
	}
	if geodata.GeoIpUrl() != "" || geodata.GeoSiteUrl() != "" {
		t.Fatal("runtime downloader enabled")
	}
	raw := optionJSON(t, func(o *runtimeOptions) {
		o.Mode = "rule"
		o.Rules = []string{"GEOSITE,google,PROXY", "GEOIP,CN,DIRECT,no-resolve", "MATCH,REJECT"}
	})
	if !strings.Contains(ValidateRuntimeOptions(raw), `"state":"passed"`) {
		t.Fatal("native geo rules refused")
	}
	s, err := newSessionConfigured(offlineSelected, "127.0.0.1", raw)
	if err != nil {
		t.Fatal(err)
	}
	defer s.close()
	for _, entry := range []struct{ destination, target string }{
		{"www.google.com:443", "PROXY"}, {"223.5.5.5:443", "DIRECT"}, {"192.0.2.1:443", "REJECT"},
	} {
		_, target, _ := s.route(M.ParseSocksaddr(entry.destination), "tcp")
		if target != entry.target {
			t.Fatal("native rule order/match changed")
		}
	}
	_, line, code := compileRules([]string{"GEOSITE,../../CANARY-SUB-TOKEN-1234,DIRECT"})
	if code != "rule_invalid" || line != 1 {
		t.Fatal("untrusted geo path accepted")
	}
	if InitializeRuleData("relative/subscription/path") {
		t.Fatal("relative data path accepted")
	}
}

func TestRuleGraphAllowsTwentyExplicitTargetsAndSafeRegex(t *testing.T) {
	groups := []any{}
	rules := []string{}
	for i := 0; i < 20; i++ {
		name := fmt.Sprintf("route-%d", i)
		groups = append(groups, map[string]any{"type": "select", "name": name, "proxies": []string{"unused"}})
		rules = append(rules, fmt.Sprintf("DOMAIN,service%d.test,%s", i, name))
	}
	definition, _ := json.Marshal(map[string]any{"proxies": []any{map[string]any{
		"name": "unused", "type": "socks5", "server": "127.0.0.1", "port": 1}}, "proxy-groups": groups})
	raw := optionJSON(t, func(o *runtimeOptions) { o.Mode = "rule"; o.Rules = rules; o.Definition = string(definition) })
	if !strings.Contains(ValidateRuntimeOptions(raw), `"state":"passed"`) {
		t.Fatal("twenty valid targets refused")
	}
	s, err := newSessionConfigured(offlineSelected, "127.0.0.1", raw)
	if err != nil {
		t.Fatal(err)
	}
	defer s.close()
	_, target, err := s.route(M.ParseSocksaddr("service19.test:443"), "tcp")
	if err != nil || target != "route-19" {
		t.Fatal("native group target changed")
	}
	patterns, _, code := compileRules([]string{`DOMAIN-REGEX,^(a+)+$,PROXY`, `DOMAIN-WILDCARD,*.example.test,DIRECT`})
	if code != "" || patterns[0].matches(strings.Repeat("a", 253)+"!", netip.Addr{}, 443, "tcp") ||
		!patterns[1].matches("a.example.test", netip.Addr{}, 443, "tcp") {
		t.Fatal("safe pattern semantics changed")
	}
}

func TestStartupFailureCodesDoNotConflateDNSNodeAndRouting(t *testing.T) {
	_, err := newSessionConfigured(offlineSelected, "invalid-dns", "")
	if vpnStartupCode(err) != "vpn_dns_invalid" {
		t.Fatal("DNS boundary lost")
	}
	_, err = newSessionConfigured("CANARY-PASSWORD-1234", "127.0.0.1", "")
	if vpnStartupCode(err) != "vpn_node_invalid" {
		t.Fatal("node boundary lost")
	}
	raw := optionJSON(t, func(o *runtimeOptions) { o.Mode = "rule"; o.Rules = []string{"CANARY-UNKNOWN,private,PROXY"} })
	_, err = newSessionConfigured(offlineSelected, "127.0.0.1", raw)
	code := vpnStartupCode(err)
	if code != "vpn_rule_unsupported" {
		t.Fatal("routing boundary lost")
	}
	assertNoCanaries(t, code)
}
