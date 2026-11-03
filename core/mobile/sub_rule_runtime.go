// SPDX-License-Identifier: GPL-3.0-only
package mobile

import (
	"encoding/json"
	"errors"
	"sort"

	C "github.com/metacubex/mihomo/constant"
	"github.com/metacubex/mihomo/rules/logic"
)

// Compile the native SUB-RULE matcher against a private, immutable registry.
// Resolve references before constructing it so malformed cyclic input cannot
// recurse in Mihomo's Match. Targets are the actual exits, never sub-rule names.
func compileSubRuleRegistry(definition string, base nativeRuleCompiler) (nativeRuleCompiler, map[string][]string, string) {
	var root map[string]json.RawMessage
	if definition == "" {
		return base, nil, ""
	}
	if len(definition) > maxPrivateProfileBytes || json.Unmarshal([]byte(definition), &root) != nil {
		return nil, nil, "rule_invalid"
	}
	var source map[string][]string
	if raw, exists := root["sub-rules"]; exists {
		if json.Unmarshal(raw, &source) != nil || len(source) > 64 {
			return nil, nil, "rule_invalid"
		}
	}
	total, bytes := 0, 0
	for name, lines := range source {
		if name == "" || len(name) > 512 || hasControl(name) || lines == nil {
			return nil, nil, "rule_invalid"
		}
		total += len(lines)
		for _, line := range lines {
			bytes += len(line)
		}
	}
	if total > maxRuntimeRules || bytes > maxRuntimeRuleBytes {
		return nil, nil, "rule_limit"
	}
	registry := map[string][]C.Rule{}
	targets := map[string][]string{}
	state := map[string]int{}
	var compileGroup func(string, int) string
	var leaf nativeRuleCompiler
	depth := 0
	leaf = func(kind, payload, target string, params []string) (C.Rule, string) {
		if kind != "SUB-RULE" {
			return base(kind, payload, target, params)
		}
		if len(params) != 0 {
			return nil, "rule_invalid"
		}
		if code := compileGroup(target, depth+1); code != "" {
			return nil, code
		}
		budget := 64
		parse := func(tp, value, _ string, args []string, _ map[string][]C.Rule) (C.Rule, error) {
			if tp == "SUB-RULE" { // Native boolean predicates disallow this too.
				return nil, errors.New("rule_invalid")
			}
			if tp == "AND" || tp == "OR" || tp == "NOT" {
				return compileLogicalRuleUsing(tp, value, "PROXY", 0, &budget, base)
			}
			rule, code := base(tp, value, "PROXY", args)
			if code != "" {
				return nil, &nativeRuleFailure{code}
			}
			return rule, nil
		}
		rule, e := logic.NewSubRule(payload, target, registry, parse)
		if e != nil {
			return nil, "rule_invalid"
		}
		return rule, ""
	}
	compileGroup = func(name string, level int) string {
		if level > 8 || state[name] == 1 {
			return "rule_invalid"
		}
		if state[name] == 2 {
			return ""
		}
		lines, exists := source[name]
		if !exists {
			return "rule_invalid"
		}
		state[name] = 1
		previous := depth
		depth = level
		rules, _, code := compileRulesUsing(lines, leaf)
		depth = previous
		if code != "" {
			return code
		}
		seen := map[string]bool{}
		for _, rule := range rules {
			registry[name] = append(registry[name], rule.native)
			if rule.kind == "SUB-RULE" {
				for _, target := range targets[rule.target] {
					seen[target] = true
				}
			} else {
				seen[rule.target] = true
			}
		}
		for target := range seen {
			targets[name] = append(targets[name], target)
		}
		sort.Strings(targets[name])
		state[name] = 2
		return ""
	}
	// Validate unused sections too; errors must not depend on map iteration order.
	names := make([]string, 0, len(source))
	for name := range source {
		names = append(names, name)
	}
	sort.Strings(names)
	for _, name := range names {
		if code := compileGroup(name, 0); code != "" {
			return nil, nil, code
		}
	}
	return leaf, targets, ""
}

func runtimeRuleTargets(rules []outboundRule) []string {
	set := map[string]bool{}
	for _, rule := range rules {
		if rule.kind == "SUB-RULE" {
			for _, target := range rule.exits {
				set[target] = true
			}
		} else {
			set[rule.target] = true
		}
	}
	list := make([]string, 0, len(set))
	for target := range set {
		list = append(list, target)
	}
	sort.Strings(list)
	return list
}
