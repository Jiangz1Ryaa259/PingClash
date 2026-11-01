// SPDX-License-Identifier: GPL-3.0-only
package mobile

import (
	"encoding/json"
	"net/netip"
	"strings"
	"testing"

	C "github.com/metacubex/mihomo/constant"
	R "github.com/metacubex/mihomo/rules"
	RC "github.com/metacubex/mihomo/rules/common"
)

func TestNativeClassicalLeafParityWithSourceAndProcessMetadata(t *testing.T) {
	rows := []string{
		"SRC-IP-CIDR,10.0.0.0/8,PROXY", "IP-CIDR,10.0.0.0/8,PROXY,src,no-resolve",
		"IP-SUFFIX,0.0.0.7/8,PROXY", "SRC-IP-SUFFIX,0.0.0.9/8,PROXY", "SRC-PORT,53000/54000-55000,PROXY",
		"DST-PORT,0/443,PROXY", "DST-PORT,[444 - 442],PROXY", "IN-PORT,0,PROXY", "IN-TYPE,TUN,PROXY", "IN-NAME,main,PROXY",
		"IN-USER,local,PROXY", "REMATCH-NAME,flow,PROXY", "PROCESS-NAME,org.example.app,PROXY",
		"PROCESS-PATH,org.example.app,PROXY", "PROCESS-NAME-WILDCARD,org.*.app,PROXY",
		`PROCESS-NAME-REGEX,^org\.(?=example).*app$,PROXY`, "PROCESS-PATH-WILDCARD,org.example.*,PROXY",
		`DOMAIN-REGEX,^(?=a\.).*test$,PROXY`, "DOMAIN-WILDCARD,*.example.test,PROXY",
		"AND,((NETWORK,tcp),(PROCESS-NAME,org.example.app),(SRC-IP-CIDR,10.0.0.0/8)),PROXY",
	}
	for _, line := range rows {
		t.Run(strings.Split(line, ",")[0], func(t *testing.T) {
			parsed, _, code := compileRules([]string{line})
			if code != "" {
				t.Fatal("native-supported rule rejected", code)
			}
			tp, payload, target, params := RC.ParseRulePayload(line, true)
			native, e := R.ParseRule(tp, payload, target, params, nil)
			if e != nil {
				t.Fatal("bad native parity fixture")
			}
			for _, variant := range []bool{false, true} {
				m := &C.Metadata{Host: "a.example.test", SrcIP: netip.MustParseAddr("10.2.3.9"), DstIP: netip.MustParseAddr("192.0.2.7"), SrcPort: 53000, DstPort: 443, Type: C.TUN, InName: "main", InUser: "local", RematchName: "flow", Process: "org.example.app", ProcessPath: "org.example.app", NetWork: C.TCP}
				if variant {
					m.Host = "other.invalid"
					m.SrcIP = netip.MustParseAddr("203.0.113.1")
					m.SrcPort = 60000
					m.DstPort = 80
					m.Type = C.HTTP
					m.Process = "other"
					m.ProcessPath = "other"
					m.NetWork = C.UDP
					m.InName = "other"
				}
				want, _ := native.Match(m, C.RuleMatchHelper{})
				if parsed[0].matchesMetadata(m, C.RuleMatchHelper{}) != want {
					t.Fatal("native match semantics differ")
				}
			}
		})
	}
}

type ownerRulePlatform struct {
	calls       int
	destination string
}

func (p *ownerRulePlatform) Protect(int) bool          { return true }
func (p *ownerRulePlatform) Lookup(string, int) string { return "" }
func (p *ownerRulePlatform) ConnectionOwner(_ int, _, destination string) string {
	p.calls++
	p.destination = destination
	return "10123\norg.example.app"
}
func TestOwnerMetadataIsLazyEphemeralAndUsesNumericEndpoints(t *testing.T) {
	p := &ownerRulePlatform{}
	SetPlatform(p)
	defer SetPlatform(nil)
	m := ruleMetadata("a.example.test", netip.MustParseAddr("192.0.2.1"), 443, "tcp")
	m.SrcIP = netip.MustParseAddr("10.0.0.2")
	m.SrcPort = 54000
	helper := connectionRuleHelper(m)
	ordinary, _, _ := compileRules([]string{"DOMAIN,a.example.test,PROXY"})
	if !ordinary[0].matchesMetadata(m, helper) || p.calls != 0 {
		t.Fatal("ordinary rule queried application identity")
	}
	process, _, code := compileRules([]string{"PROCESS-NAME,org.example.app,PROXY"})
	if code != "" {
		t.Fatal(code)
	}
	if !process[0].matchesMetadata(m, helper) || !process[0].matchesMetadata(m, helper) || p.calls != 1 || m.Uid != 10123 || p.destination != "192.0.2.1:443" {
		t.Fatal("owner metadata lookup incorrect")
	}
	_, code = compileNativeRule("IP-ASN", "123", "PROXY", nil)
	if !ruleDataAvailable() && code != "rule_data_unavailable" || ruleDataAvailable() && code != "" {
		t.Fatal("ASN availability guard incorrect")
	}
}

func TestClassicalInvalidRowsFollowNativeSkipPolicyWithSafePersistentDetails(t *testing.T) {
	text := `proxies: [{name: local, type: socks5, server: example.invalid, port: 1080}]
rule-providers:
  classical: {type: inline, behavior: classical, payload: ['DOMAIN-SUFFIX,example.test', 'MATCH,REJECT', 'SRC-PORT,54000', 'DSCP,8', 'CANARY-TYPE-1234,CANARY-PASSWORD-1234', 'CANARY-TYPE-9999,CANARY-SUB-TOKEN-1234']}
rules: ['RULE-SET,classical,REJECT', 'MATCH,DIRECT']`
	result := resolveFixture(t, text, map[string]string{})
	if result["state"] != "passed" {
		t.Fatal("classical rows rejected whole provider", result)
	}
	var profile importedProfile
	if json.Unmarshal([]byte(ImportProfile(result["content"].(string))), &profile) != nil || profile.State != "passed" || profile.ProviderRulesSkipped != 3 || len(profile.ProviderRuleWarnings) != 2 {
		t.Fatal("skip details missing")
	}
	warnings, _ := json.Marshal(profile.ProviderRuleWarnings)
	assertNoCanaries(t, string(warnings))
	if profile.ProviderRuleWarnings[0].Source != 1 || profile.ProviderRuleWarnings[0].Entry != 2 || profile.ProviderRuleWarnings[0].Type != "MATCH" {
		t.Fatal("source ordinal/entry/type missing")
	}
	var reread importedProfile
	_ = json.Unmarshal([]byte(ImportProfile(profile.Definition)), &reread)
	if reread.ProviderRulesSkipped != profile.ProviderRulesSkipped {
		t.Fatal("reload lost skipped rule warning")
	}
	options := defaultRuntimeOptions()
	options.Mode = "rule"
	options.Rules = reread.Rules
	options.Definition = reread.Definition
	b, _ := json.Marshal(options)
	assertNoCanaries(t, ValidateRuntimeOptions(string(b)))
	rules, _, code := compileRuntimeRules(reread.Rules, reread.Definition)
	if code != "" || !rules[0].matches("a.example.test", netip.Addr{}, 443, "tcp") {
		t.Fatal("remaining native rule not executable")
	}
	m := ruleMetadata("other.invalid", netip.MustParseAddr("192.0.2.1"), 443, "tcp")
	m.SrcPort = 54000
	if !rules[0].matchesMetadata(m, C.RuleMatchHelper{}) {
		t.Fatal("source-port entry not effective")
	}
	m.SrcPort = 1
	m.DSCP = 8
	if !rules[0].matchesMetadata(m, C.RuleMatchHelper{}) {
		t.Fatal("DSCP entry was skipped instead of executing")
	}
}
