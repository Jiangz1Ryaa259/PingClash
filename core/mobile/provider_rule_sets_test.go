// SPDX-License-Identifier: GPL-3.0-only
package mobile

import (
	"encoding/json"
	"fmt"
	"net/netip"
	"strings"
	"testing"
)

func TestLargeRuleSourceRetainsSetsAndRoutesAfterReload(t *testing.T) {
	for _, format := range []string{"yaml", "text"} {
		t.Run(format, func(t *testing.T) {
			text := `proxies: [{name: local, type: socks5, server: example.invalid, port: 1080}]
proxy-groups: [{name: main, type: select, proxies: [local, DIRECT]}]
rule-providers:
  domains: {type: http, url: https://example.invalid/CANARY-SUB-TOKEN-1234, behavior: domain, format: ` + format + `}
rules: ['RULE-SET,domains,REJECT', 'MATCH,DIRECT']`
			var body strings.Builder
			if format == "yaml" {
				body.WriteString("\ufeffpayload:\n")
			}
			for i := 0; i < 100000; i++ {
				if format == "yaml" {
					body.WriteString(" - '")
				}
				fmt.Fprintf(&body, "+.host%d.example.test", i)
				if format == "yaml" {
					body.WriteByte('\'')
				}
				body.WriteByte('\n')
			}
			if body.Len() <= 2*1024*1024 {
				t.Fatal("fixture does not cross the old download limit")
			}
			result := resolveFixture(t, text, map[string]string{"rule:domains": body.String()})
			if result["state"] != "passed" {
				t.Fatal("large source resolution failed", result)
			}
			var p importedProfile
			if json.Unmarshal([]byte(ImportProfile(result["content"].(string))), &p) != nil || p.State != "passed" || len(p.Rules) != 2 || p.RulesOmitted != 0 {
				t.Fatal("large source import failed")
			}
			var reload importedProfile
			if json.Unmarshal([]byte(ImportProfile(p.Definition)), &reload) != nil || reload.State != "passed" || len(reload.Rules) != 2 {
				t.Fatal("private definition reload lost rules")
			}
			options := defaultRuntimeOptions()
			options.Mode = "rule"
			options.Rules = append([]string{}, reload.Rules...)
			options.Rules[0] = " rule-set , domains , REJECT "
			options.Definition = reload.Definition
			b, _ := json.Marshal(options)
			if !strings.Contains(ValidateRuntimeOptions(string(b)), `"state":"passed"`) {
				t.Fatal("rule-mode validation failed")
			}
			s := &vpnSession{}
			if e := s.configure(string(b)); e != nil {
				t.Fatal("rule-mode configure failed")
			}
			defer s.direct.Close()
			if len(s.rules) != 2 || !s.trackDomains {
				t.Fatal("set was flattened or domain tracking disabled")
			}
			for _, host := range []string{"host99999.example.test", "sub.host99999.example.test"} {
				if !s.rules[0].matches(host, netip.Addr{}, 443, "tcp") {
					t.Fatal("last source entry did not match")
				}
			}
			if s.rules[0].matches("unlisted.example.test", netip.Addr{}, 443, "tcp") {
				t.Fatal("set matched unrelated domain")
			}
			assertNoCanaries(t, ValidateRuntimeOptions(string(b)))
		})
	}
}

func TestProviderNativeWildcardAndClassicalSemantics(t *testing.T) {
	m, code := compileProviderRuleSet(storedRuleSet{Behavior: "domain", Entries: []string{"*.one.test", ".suffix.test", "+.both.test"}})
	if code != "" {
		t.Fatal(code)
	}
	for host, want := range map[string]bool{"x.one.test": true, "x.y.one.test": false, "one.test": false, "suffix.test": false, "x.suffix.test": true, "both.test": true, "x.both.test": true} {
		if m.matches(host, netip.Addr{}, 443, "tcp") != want {
			t.Fatalf("native wildcard mismatch: %s", host)
		}
	}
	m, code = compileProviderRuleSet(storedRuleSet{Behavior: "classical", Entries: []string{"AND,((DOMAIN-SUFFIX,example.test),(NETWORK,tcp))", "IP-CIDR,192.0.2.0/24,no-resolve"}})
	if code != "" || !m.matches("x.example.test", netip.Addr{}, 443, "tcp") || m.matches("x.example.test", netip.Addr{}, 443, "udp") || !m.matches("", netip.MustParseAddr("192.0.2.2"), 80, "tcp") {
		t.Fatal("classical logical rule lost semantics", code)
	}
	m, code = compileProviderRuleSet(storedRuleSet{Behavior: "ipcidr", Entries: []string{"192.0.2.0/24", "2001:db8::/32"}})
	if code != "" || !m.matches("", netip.MustParseAddr("2001:db8::1"), 443, "udp") || m.matches("", netip.MustParseAddr("198.51.100.1"), 443, "tcp") {
		t.Fatal("native CIDR set mismatch")
	}
}

func TestProviderRuleFailuresAreSpecificAndPrivate(t *testing.T) {
	text := `proxies: [{name: local, type: socks5, server: example.invalid, port: 1080}]
rule-providers: {third: {type: http, url: https://example.invalid/CANARY-SUB-TOKEN-1234, behavior: domain}}
rules: ['RULE-SET,third,DIRECT', 'MATCH,REJECT']`
	cases := map[string]string{"<!doctype html><html>CANARY-PASSWORD-1234</html>": "provider_html", "proxies: []": "provider_rule_document", "payload: [123]": "provider_rule_syntax"}
	for body, code := range cases {
		r := resolveFixture(t, text, map[string]string{"rule:third": body})
		if r["code"] != code || r["index"] != float64(1) || r["kind"] != "rule" {
			t.Fatal("non-specific source failure", r)
		}
		b, _ := json.Marshal(r)
		assertNoCanaries(t, string(b))
	}
	_, code := compileProviderRuleSet(storedRuleSet{Behavior: "domain", Entries: make([]string, maxProviderRules+1)})
	if code != "provider_rule_limit" {
		t.Fatal("source limit missing")
	}
	rules, _, code := compileRuntimeRules([]string{"RULE-SET,missing,DIRECT"}, `{"pingclash-rule-data":"{}"}`)
	if code != "rule_provider_invalid" || len(rules) != 0 {
		t.Fatal("missing source silently bypassed")
	}
}
