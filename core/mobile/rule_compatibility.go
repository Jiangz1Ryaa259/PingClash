// SPDX-License-Identifier: GPL-3.0-only
package mobile

import (
	"errors"
	C "github.com/metacubex/mihomo/constant"
	RC "github.com/metacubex/mihomo/rules/common"
	"github.com/metacubex/mihomo/rules/logic"
	"strconv"
	"strings"
)

func validDestinationPorts(value string) bool {
	spans := strings.Split(value, "/")
	if len(spans) > 28 {
		return false
	}
	for _, span := range spans {
		ends := strings.Split(span, "-")
		if len(ends) > 2 {
			return false
		}
		low, err := strconv.Atoi(ends[0])
		if err != nil || low < 1 || low > 65535 {
			return false
		}
		if len(ends) == 2 {
			high, err := strconv.Atoi(ends[1])
			if err != nil || high < low || high > 65535 {
				return false
			}
		}
	}
	return true
}

// Delegate boolean matching to pinned Mihomo, with bounded recursion and only
// leaf metadata the mobile tunnel actually supplies. No global provider lookup.
func compileLogicalRule(kind, payload, target string, depth int, budget *int) (C.Rule, error) {
	return compileLogicalRuleUsing(kind, payload, target, depth, budget, compileNativeRule)
}

func compileLogicalRuleUsing(kind, payload, target string, depth int, budget *int, leaf nativeRuleCompiler) (C.Rule, error) {
	invalid := errors.New("rule_invalid")
	*budget--
	if depth > 8 || *budget < 0 {
		return nil, invalid
	}
	children := 0
	parse := func(tp, value, _ string, params []string, _ map[string][]C.Rule) (C.Rule, error) {
		children++
		if tp == "AND" || tp == "OR" || tp == "NOT" {
			return compileLogicalRuleUsing(tp, value, target, depth+1, budget, leaf)
		}
		*budget--
		if *budget < 0 {
			return nil, invalid
		}
		native, code := leaf(tp, value, target, params)
		if code != "" {
			return nil, &nativeRuleFailure{code}
		}
		return native, nil
	}
	var rule C.Rule
	var err error
	switch kind {
	case "AND":
		rule, err = logic.NewAND(payload, target, parse)
	case "OR":
		rule, err = logic.NewOR(payload, target, parse)
	case "NOT":
		rule, err = logic.NewNOT(payload, target, parse)
	default:
		return nil, invalid
	}
	if err != nil {
		return nil, err
	}
	if children == 0 {
		return nil, invalid
	}
	return rule, nil
}

type nativeRuleFailure struct{ code string }

func (e *nativeRuleFailure) Error() string { return e.code }

// Public diagnostics expose a category, never a rule payload or target name.
func publicRuleType(lines []string, line int) string {
	if line < 1 || line > len(lines) {
		return ""
	}
	kind, _, _, _ := RC.ParseRulePayload(lines[line-1], true)
	category := publicNativeRuleType(kind)
	if category == "UNRECOGNIZED" {
		return ""
	}
	return category
}
