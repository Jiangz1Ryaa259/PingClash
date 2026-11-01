// SPDX-License-Identifier: GPL-3.0-only
package mobile

import (
	"encoding/json"
	"net/netip"

	C "github.com/metacubex/mihomo/constant"
	RC "github.com/metacubex/mihomo/rules/common"
)

// Rule-set references use a per-runtime registry. Native boolean constructors
// receive this resolver too; no process-global provider registry is consulted.
func compileRuntimeRules(lines []string, definition string) ([]outboundRule, int, string) {
	var sets map[string]storedRuleSet
	loaded := false
	compiled := map[string]*providerRuleMatcher{}
	load := func() bool {
		if loaded {
			return sets != nil
		}
		loaded = true
		if len(definition) > maxPrivateProfileBytes {
			return false
		}
		var root map[string]json.RawMessage
		var encoded string
		if json.Unmarshal([]byte(definition), &root) != nil || json.Unmarshal(root[providerRuleDataKey], &encoded) != nil {
			return false
		}
		var e error
		sets, e = decodeStoredRuleSets(encoded)
		if e != nil {
			sets = nil
			return false
		}
		total := 0
		for _, set := range sets {
			total += len(set.Entries)
		}
		if total > maxProviderRules {
			sets = nil
			return false
		}
		return true
	}
	leaf := func(kind, payload, target string, params []string) (C.Rule, string) {
		if kind != "RULE-SET" {
			return compileNativeRule(kind, payload, target, params)
		}
		if len(params) > 32 {
			return nil, "rule_invalid"
		}
		if !load() {
			return nil, "rule_provider_invalid"
		}
		set, exists := sets[payload]
		if !exists {
			return nil, "rule_provider_invalid"
		}
		matcher := compiled[payload]
		if matcher == nil {
			var code string
			matcher, code = compileProviderRuleSet(set)
			if code != "" {
				return nil, "rule_provider_invalid"
			}
			compiled[payload] = matcher
		}
		source, noResolve := RC.ParseParams(params)
		return &privateRuleSet{target: target, name: payload, source: source, noResolve: noResolve, matcher: matcher}, ""
	}
	compiler, targets, code := compileSubRuleRegistry(definition, leaf)
	if code != "" {
		return nil, 0, code
	}
	rules, line, code := compileRulesUsing(lines, compiler)
	for i := range rules {
		if rules[i].kind == "SUB-RULE" {
			rules[i].exits = targets[rules[i].target]
		}
	}
	return rules, line, code
}

type privateRuleSet struct {
	RC.Base
	target, name      string
	source, noResolve bool
	matcher           *providerRuleMatcher
}

func (r *privateRuleSet) RuleType() C.RuleType { return C.RuleSet }
func (r *privateRuleSet) Adapter() string      { return r.target }
func (r *privateRuleSet) Payload() string      { return r.name }
func (r *privateRuleSet) MatchDomain(domain string) bool {
	return r.matcher.matchesMetadata(&C.Metadata{Host: domain}, C.RuleMatchHelper{})
}
func (r *privateRuleSet) MatchIp(ip netip.Addr) bool {
	return r.matcher.matchesMetadata(&C.Metadata{DstIP: ip}, C.RuleMatchHelper{})
}
func (r *privateRuleSet) Match(m *C.Metadata, h C.RuleMatchHelper) (bool, string) {
	if r.source {
		m.SwapSrcDst()
		defer m.SwapSrcDst()
		h.ResolveIP = nil
	} else if r.noResolve {
		h.ResolveIP = nil
	}
	return r.matcher.matchesMetadata(m, h), r.target
}
