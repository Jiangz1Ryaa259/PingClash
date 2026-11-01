// SPDX-License-Identifier: GPL-3.0-only
package mobile

import (
	"regexp"
	"strconv"
	"strings"
	"time"

	"github.com/dlclark/regexp2"
	C "github.com/metacubex/mihomo/constant"
	R "github.com/metacubex/mihomo/rules"
	RC "github.com/metacubex/mihomo/rules/common"
)

// Use the pinned native constructors. Guard the constructors that initialize
// external databases and replace unbounded backtracking regex with a deadline.
func compileNativeRule(kind, payload, target string, params []string) (C.Rule, string) {
	switch kind {
	case "GEOSITE", "GEOIP", "SRC-GEOIP", "IP-ASN", "SRC-IP-ASN":
		nativeConfigParse.Lock()
		defer nativeConfigParse.Unlock()
	}
	if target == "" || len(target) > 512 || len(params) > 32 {
		return nil, "rule_invalid"
	}
	switch kind {
	case "DST-PORT", "SRC-PORT", "IN-PORT":
		if !validUnsignedRuleRanges(payload, 16) {
			return nil, "rule_invalid"
		}
	case "UID":
		if !validUnsignedRuleRanges(payload, 32) {
			return nil, "rule_invalid"
		}
	case "RULE-SET", "SUB-RULE":
		return nil, "rule_unsupported"
	case "DSCP":
		if !validUnsignedRuleRanges(payload, 6) {
			return nil, "rule_invalid"
		}
	case "IP-ASN", "SRC-IP-ASN":
		if _, e := strconv.ParseUint(payload, 10, 32); e != nil {
			return nil, "rule_invalid"
		}
		if !ruleDataAvailable() {
			return nil, "rule_data_unavailable"
		}
	case "GEOSITE", "GEOIP", "SRC-GEOIP":
		if !geoTagPattern.MatchString(payload) {
			return nil, "rule_invalid"
		}
		if !ruleDataAvailable() {
			return nil, "rule_data_unavailable"
		}
	}
	if kind == "DOMAIN-REGEX" || kind == "PROCESS-NAME-REGEX" || kind == "PROCESS-PATH-REGEX" {
		return compileBoundedRulePattern(kind, payload, target)
	}
	native, e := R.ParseRule(kind, payload, target, params, nil)
	if e != nil {
		if kind == "GEOIP" || kind == "SRC-GEOIP" || kind == "GEOSITE" {
			return nil, "rule_geo_unknown"
		}
		if publicNativeRuleType(kind) == "UNRECOGNIZED" {
			return nil, "rule_unsupported"
		}
		return nil, "rule_invalid"
	}
	return native, ""
}

func validUnsignedRuleRanges(value string, bits int) bool {
	for _, span := range strings.Split(value, "/") {
		if strings.TrimSpace(span) == "" {
			continue
		}
		ends := strings.Split(span, "-")
		if len(ends) > 2 {
			return false
		}
		_, e := strconv.ParseUint(strings.Trim(ends[0], "[ ]"), 10, bits)
		if e != nil {
			return false
		}
		if len(ends) == 2 {
			_, e := strconv.ParseUint(strings.Trim(ends[1], "[ ]"), 10, bits)
			if e != nil {
				return false
			}
		}
	}
	return true
}

// The public rule vocabulary, shared by import checks and connection reports.
var nativeRuleKinds = []string{"DOMAIN", "DOMAIN-SUFFIX", "DOMAIN-KEYWORD", "DOMAIN-REGEX", "DOMAIN-WILDCARD",
	"GEOSITE", "GEOIP", "SRC-GEOIP", "IP-ASN", "SRC-IP-ASN", "IP-CIDR", "IP-CIDR6", "SRC-IP-CIDR",
	"IP-SUFFIX", "SRC-IP-SUFFIX", "SRC-PORT", "DST-PORT", "IN-PORT", "DSCP", "PROCESS-NAME",
	"PROCESS-PATH", "PROCESS-NAME-REGEX", "PROCESS-PATH-REGEX", "PROCESS-NAME-WILDCARD",
	"PROCESS-PATH-WILDCARD", "NETWORK", "UID", "IN-TYPE", "IN-USER", "IN-NAME", "REMATCH-NAME",
	"SUB-RULE", "AND", "OR", "NOT", "RULE-SET", "MATCH"}

func publicNativeRuleType(kind string) string {
	for _, known := range nativeRuleKinds {
		if kind == known {
			return kind
		}
	}
	return "UNRECOGNIZED"
}

type boundedRulePattern struct {
	RC.Base
	kind, payload, target string
	fast                  *regexp.Regexp
	slow                  *regexp2.Regexp
}

func compileBoundedRulePattern(kind, value, target string) (C.Rule, string) {
	if value == "" || len(value) > 512 {
		return nil, "rule_invalid"
	}
	rule := &boundedRulePattern{kind: kind, payload: value, target: target}
	var err error
	rule.fast, err = regexp.Compile("(?i)" + value)
	if err != nil {
		rule.slow, err = regexp2.Compile(value, regexp2.IgnoreCase)
		if err != nil {
			return nil, "rule_invalid"
		}
		rule.slow.MatchTimeout = 10 * time.Millisecond
	}
	return rule, ""
}
func (r *boundedRulePattern) RuleType() C.RuleType {
	switch r.kind {
	case "PROCESS-NAME-REGEX":
		return C.ProcessNameRegex
	case "PROCESS-PATH-REGEX":
		return C.ProcessPathRegex
	}
	return C.DomainRegex
}
func (r *boundedRulePattern) Adapter() string { return r.target }
func (r *boundedRulePattern) Payload() string { return r.payload }
func (r *boundedRulePattern) Match(m *C.Metadata, h C.RuleMatchHelper) (bool, string) {
	value := m.RuleHost()
	if strings.HasPrefix(r.kind, "PROCESS-") {
		if h.FindProcess != nil {
			h.FindProcess()
		}
		if r.kind == "PROCESS-NAME-REGEX" {
			value = m.Process
		} else {
			value = m.ProcessPath
		}
	}
	if len(value) > 2048 {
		return false, ""
	}
	if r.fast != nil {
		return r.fast.MatchString(value), r.target
	}
	matched, e := r.slow.MatchString(value)
	return e == nil && matched, r.target
}
