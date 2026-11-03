// SPDX-License-Identifier: GPL-3.0-only
package mobile

import (
	"encoding/json"
	"fmt"
	"net/netip"
	"strings"
	"testing"

	C "github.com/metacubex/mihomo/constant"
	P "github.com/metacubex/mihomo/constant/provider"
	RP "github.com/metacubex/mihomo/rules/provider"
)

func TestImportProviderNullSectionsAreEmpty(t *testing.T) {
	text := "proxies: [{name: local, type: socks5, server: example.invalid, port: 1080}]\nproxy-providers: null\nrule-providers: null\nrules: ['MATCH,local']"
	var plan struct {
		State     string
		Providers []remoteSpec
	}
	if json.Unmarshal([]byte(PlanRemoteProviders(text)), &plan) != nil || plan.State != "passed" || len(plan.Providers) != 0 {
		t.Fatal("empty optional provider sections rejected")
	}
	var imported importedProfile
	if json.Unmarshal([]byte(ImportProfile(text)), &imported) != nil || imported.State != "passed" {
		t.Fatal("empty optional provider sections failed profile import")
	}
}

func TestImportProviderRoundTripAboveSixteen(t *testing.T) {
	for _, count := range []int{17, 64, 128} {
		t.Run(fmt.Sprint(count), func(t *testing.T) {
			var source strings.Builder
			source.WriteString("proxies: [{name: local, type: socks5, server: example.invalid, port: 1080}]\nrule-providers:\n")
			for i := 0; i < count; i++ {
				fmt.Fprintf(&source, "  set%03d: {type: inline, behavior: domain, payload: ['+.example.test']}\n", i)
			}
			fmt.Fprintf(&source, "rules: ['RULE-SET,set%03d,local', 'MATCH,REJECT']", count-1)
			result := resolveFixture(t, source.String(), nil)
			if result["state"] != "passed" {
				t.Fatal("provider resolution failed", result)
			}
			var imported importedProfile
			if json.Unmarshal([]byte(ImportProfile(result["content"].(string))), &imported) != nil || imported.State != "passed" {
				t.Fatal("resolved providers failed import")
			}
			rules, _, code := compileRuntimeRules(imported.Rules, imported.Definition)
			if code != "" || len(rules) != 2 || !rules[0].matchesMetadata(ruleMetadata("sub.example.test", netip.Addr{}, 443, "tcp"), C.RuleMatchHelper{}) {
				t.Fatal("stored provider count/index rejected or changed routing", code)
			}
		})
	}
}

func TestImportProviderRulesAliasPreservesEntries(t *testing.T) {
	source := `proxies: [{name: local, type: socks5, server: example.invalid, port: 1080}]
rule-providers: {source: {type: http, url: https://example.invalid/CANARY-SUB-TOKEN-1234, behavior: domain}}
rules: ['RULE-SET,source,local', 'MATCH,REJECT']`
	result := resolveFixture(t, source, map[string]string{"rule:source": "rules:\n  - '+.example.test'\n"})
	if result["state"] != "passed" {
		t.Fatal("native rules envelope rejected", result)
	}
	var imported importedProfile
	_ = json.Unmarshal([]byte(ImportProfile(result["content"].(string))), &imported)
	rules, _, code := compileRuntimeRules(imported.Rules, imported.Definition)
	if imported.State != "passed" || code != "" || len(rules) == 0 || !rules[0].matchesMetadata(ruleMetadata("sub.example.test", netip.Addr{}, 443, "tcp"), C.RuleMatchHelper{}) {
		t.Fatal("rules alias lost matching semantics")
	}
}

func TestImportProviderInvalidRowsFollowNativeSemantics(t *testing.T) {
	for _, row := range []struct {
		behavior string
		native   P.RuleBehavior
		entries  []string
		host, ip string
	}{
		{"domain", P.Domain, []string{"invalid..domain", "+.example.test", "also..invalid"}, "sub.example.test", "203.0.113.2"},
		{"ipcidr", P.IPCIDR, []string{"not-a-network", "192.0.2.0/24", "999.999.0.0/24"}, "unmatched.test", "192.0.2.2"},
	} {
		t.Run(row.behavior, func(t *testing.T) {
			matcher, code := compileProviderRuleSet(storedRuleSet{Behavior: row.behavior, Source: 17, Entries: row.entries})
			if code != "" {
				t.Fatal("invalid row rejected entire native-compatible rule set", code)
			}
			native := RP.NewInlineProvider("synthetic", row.native, row.entries, nil)
			if err := native.Initial(); err != nil {
				t.Fatal(err)
			}
			metadata := ruleMetadata(row.host, netip.MustParseAddr(row.ip), 443, "tcp")
			if !matcher.matchesMetadata(metadata, C.RuleMatchHelper{}) || matcher.matchesMetadata(metadata, C.RuleMatchHelper{}) != native.Match(metadata, C.RuleMatchHelper{}) {
				t.Fatal("native rule matching diverged")
			}
			if matcher.skipped != 2 {
				t.Fatal("invalid rows not reported safely")
			}
			encoded, _ := json.Marshal(matcher.warnings)
			if strings.Contains(string(encoded), "invalid.test") || strings.Contains(string(encoded), "not-a-network") {
				t.Fatal("rule content leaked through warnings")
			}
		})
	}
}

func TestImportProviderRootErrorPreservesOnlyPublicCodes(t *testing.T) {
	var value map[string]any
	_ = json.Unmarshal([]byte(remoteError(fmt.Errorf("provider_config_limit"))), &value)
	if value["code"] != "provider_config_limit" {
		t.Fatal("specific bounded error replaced with generic invalid")
	}
	_ = json.Unmarshal([]byte(remoteError(fmt.Errorf("CANARY-SUB-TOKEN-1234"))), &value)
	if value["code"] != "provider_invalid" {
		t.Fatal("untrusted error text crossed bridge")
	}
}

func TestImportProviderLimitsRemainBounded(t *testing.T) {
	providers := map[string]any{}
	sets := map[string]storedRuleSet{}
	for i := 0; i < maxRemoteProviders+1; i++ {
		name := fmt.Sprint(i)
		providers[name] = map[string]any{"type": "inline", "behavior": "domain", "payload": []string{"+.example.test"}}
		sets[name] = storedRuleSet{Behavior: "domain", Source: 1, Entries: []string{"+.example.test"}}
	}
	source, _ := json.Marshal(map[string]any{"rule-providers": providers})
	var reply struct{ State, Code string }
	_ = json.Unmarshal([]byte(PlanRemoteProviders(string(source))), &reply)
	if reply.State != "failed" || reply.Code != "provider_count_limit" {
		t.Fatal("provider capacity no longer bounded")
	}
	encoded, _ := json.Marshal(sets)
	if _, err := parseStoredRuleSets(encoded); err == nil {
		t.Fatal("oversized stored provider map accepted")
	}
	encoded, _ = json.Marshal(map[string]storedRuleSet{"source": {Behavior: "domain", Source: maxRemoteProviders + 1}})
	if _, err := parseStoredRuleSets(encoded); err == nil {
		t.Fatal("out-of-range stored provider ordinal accepted")
	}
}
