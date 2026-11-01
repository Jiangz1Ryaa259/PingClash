// SPDX-License-Identifier: GPL-3.0-only
package mobile

import (
	"encoding/json"
	"net/netip"
	"testing"

	C "github.com/metacubex/mihomo/constant"
)

func TestPrivateRuleSetCompositionSourceSwapAndIsolation(t *testing.T) {
	remote := `proxies: [{name: Local, type: socks5, server: example.invalid, port: 1080}]
rule-providers:
  network: {type: inline, behavior: ipcidr, payload: ['192.0.2.0/24']}
rules: ['AND,((NETWORK,tcp),(RULE-SET,network,src)),REJECT', 'RULE-SET,network,PROXY,src,no-resolve', 'MATCH,DIRECT']`
	resolved := resolveFixture(t, remote, map[string]string{})
	if resolved["state"] != "passed" {
		t.Fatal("remote source parameter gate rejected native rule")
	}
	var profile importedProfile
	if json.Unmarshal([]byte(ImportProfile(resolved["content"].(string))), &profile) != nil || profile.State != "passed" {
		t.Fatal("composition import failed")
	}
	if _, _, code := compileRuntimeRules(profile.Rules, profile.Definition); code != "" {
		t.Fatal("imported composition cannot run")
	}
	encoded, e := packRuleData([]byte(`{"networks":{"behavior":"ipcidr","entries":["192.0.2.0/24"]},"domains":{"behavior":"domain","entries":["+.example.test"]}}`))
	if e != nil {
		t.Fatal(e)
	}
	definition, _ := json.Marshal(map[string]string{providerRuleDataKey: encoded})
	for _, row := range []struct {
		line string
		want bool
	}{
		{"RULE-SET,networks,REJECT,src,no-resolve", true},
		{"AND,((NETWORK,tcp),(RULE-SET,networks,src)),REJECT", true},
		{"OR,((RULE-SET,domains),(RULE-SET,networks)),REJECT", true},
		{"NOT,((RULE-SET,networks)),REJECT", true},
		{"AND,((RULE-SET,domains),(RULE-SET,networks)),REJECT", false},
	} {
		rules, _, code := compileRuntimeRules([]string{row.line}, string(definition))
		if code != "" {
			t.Fatal("composition refused", row.line, code)
		}
		meta := ruleMetadata("a.example.test", netip.MustParseAddr("203.0.113.1"), 443, "tcp")
		meta.SrcIP = netip.MustParseAddr("192.0.2.2")
		meta.SrcPort = 12345
		if rules[0].matchesMetadata(meta, C.RuleMatchHelper{}) != row.want {
			t.Fatal("composition match wrong", row.line)
		}
		if meta.SrcIP.String() != "192.0.2.2" || meta.DstIP.String() != "203.0.113.1" || meta.SrcPort != 12345 || meta.DstPort != 443 {
			t.Fatal("source swap escaped rule scope")
		}
	}
	for _, line := range []string{"RULE-SET,absent,REJECT", "AND,((NETWORK,tcp),(RULE-SET,absent)),REJECT"} {
		_, lineNumber, code := compileRuntimeRules([]string{line}, string(definition))
		if code != "rule_provider_invalid" || lineNumber != 1 {
			t.Fatal("missing set failed open")
		}
	}
	if _, _, code := compileRuntimeRules([]string{"RULE-SET,networks,REJECT"}, `{"pingclash-rule-data":"{}"}`); code == "" {
		t.Fatal("private registry contaminated another profile")
	}
}
