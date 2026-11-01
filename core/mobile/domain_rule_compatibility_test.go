// SPDX-License-Identifier: GPL-3.0-only
package mobile

import (
	"encoding/json"
	"github.com/metacubex/mihomo/component/trie"
	C "github.com/metacubex/mihomo/constant"
	RC "github.com/metacubex/mihomo/rules/common"
	"net/netip"
	"strings"
	"testing"
)

func TestLiteralDomainRulesMatchPinnedNativeWithoutHostnameGate(t *testing.T) {
	for _, kind := range []string{"DOMAIN", "DOMAIN-SUFFIX", "DOMAIN-KEYWORD"} {
		for _, value := range []string{"Example.test", ".", "Example.test.", "https://example.invalid/path",
			"literal space", "word:term", `literal\term`, strings.Repeat("x", 300), strings.Repeat("例", 100)} {
			line := kind + "," + value + ",PROXY,no-resolve,ignored-native-param"
			compiled, _, code := compileRules([]string{line})
			if code != "" || len(compiled) != 1 {
				t.Fatal("native literal rule rejected by extra bridge validation")
			}
			var native C.Rule
			switch kind {
			case "DOMAIN":
				native = RC.NewDomain(value, "PROXY")
			case "DOMAIN-SUFFIX":
				native = RC.NewDomainSuffix(value, "PROXY")
			case "DOMAIN-KEYWORD":
				native = RC.NewDomainKeyword(value, "PROXY")
			}
			for _, host := range []string{strings.ToLower(value), "sub." + strings.ToLower(value), "unrelated.invalid"} {
				expected, _ := native.Match(&C.Metadata{Host: host}, C.RuleMatchHelper{})
				actual := compiled[0].matches(host, netip.Addr{}, 443, "tcp")
				if expected != actual {
					t.Fatal("literal native rule meaning changed")
				}
			}
		}
	}
}

func TestProviderLiteralSuffixRetainsNativeAcceptedPayload(t *testing.T) {
	payload := "+.https://example.invalid/path"
	if _, err := trie.ValidAndSplitDomain(payload); err != nil {
		t.Fatal("fixture is not native-accepted")
	}
	bodies := remoteBodies()
	bodies["rule:domains"] = "payload: ['" + payload + "']"
	result := resolveFixture(t, remoteFixture, bodies)
	if result["state"] != "passed" {
		t.Fatal("literal suffix provider rejected")
	}
	var profile importedProfile
	if json.Unmarshal([]byte(ImportProfile(result["content"].(string))), &profile) != nil ||
		len(profile.Rules) == 0 || profile.Rules[0] != "RULE-SET,domains,main" {
		t.Fatal("literal provider suffix was changed or lost")
	}
	rules, _, code := compileRuntimeRules(profile.Rules, profile.Definition)
	if code != "" || !rules[0].matches("https://example.invalid/path", netip.Addr{}, 443, "tcp") {
		t.Fatal("literal domain set payload lost")
	}
}

func TestDomainSuffixRule394LoadsWithoutDiscardingItsMeaning(t *testing.T) {
	lines := make([]string, 393)
	for i := range lines {
		lines[i] = "DOMAIN,unmatched.invalid,DIRECT"
	}
	lines = append(lines, "DOMAIN-SUFFIX,https://example.invalid/path,PROXY", "MATCH,REJECT")
	raw := optionJSON(t, func(o *runtimeOptions) { o.Mode = "rule"; o.Rules = lines })
	if !strings.Contains(ValidateRuntimeOptions(raw), `"state":"passed"`) {
		t.Fatal("native-compatible suffix refused at 394")
	}
	rules, _, code := compileRules(lines)
	if code != "" || len(rules) != 395 || rules[393].native == nil {
		t.Fatal("suffix was dropped or rewritten")
	}
	for _, invalid := range []string{"DOMAIN-SUFFIX,,PROXY", "DOMAIN-SUFFIX,example.test,",
		"DOMAIN-SUFFIX,example.test,CANARY-PASSWORD-1234\n" + string(rune(0))} {
		raw = optionJSON(t, func(o *runtimeOptions) { o.Rules = []string{invalid} })
		result := ValidateRuntimeOptions(raw)
		if strings.Contains(result, `"state":"passed"`) {
			t.Fatal("structurally invalid rule accepted")
		}
		assertNoCanaries(t, result)
	}
}
