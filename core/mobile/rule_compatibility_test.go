// SPDX-License-Identifier: GPL-3.0-only
package mobile

import (
	"encoding/json"
	"net/netip"
	"strings"
	"testing"
)

func TestNativeRuleCompatibilityAtReportedLine394(t *testing.T) {
	for _, rule := range []string{
		"DST-PORT,443/8443-8444,PROXY",
		"IP-CIDR,2001:db8::/32,PROXY,no-resolve",
		"AND,((NETWORK,tcp),(OR,((DOMAIN-SUFFIX,example.test),(DST-PORT,443/8443)))),PROXY",
		"NOT,((NETWORK,udp)),PROXY",
		`DOMAIN-REGEX,^a{1,3}\.example\.test$,PROXY`,
	} {
		lines := make([]string, 393)
		for i := range lines {
			lines[i] = "DOMAIN,unmatched.invalid,DIRECT"
		}
		lines = append(lines, rule)
		raw := optionJSON(t, func(o *runtimeOptions) { o.Mode = "rule"; o.Rules = lines })
		if !strings.Contains(ValidateRuntimeOptions(raw), `"state":"passed"`) {
			t.Fatal("native-compatible line 394 rejected")
		}
		compiled, _, code := compileRules([]string{rule})
		if code != "" || !compiled[0].matches("aa.example.test", netip.MustParseAddr("2001:db8::1"), 443, "tcp") {
			t.Fatal("native-compatible rule did not match")
		}
		if compiled[0].matches("unmatched.invalid", netip.MustParseAddr("192.0.2.1"), 80, "udp") {
			t.Fatal("native-compatible rule matched unrelated flow")
		}
	}
}

func TestLogicalRulesKeepBudgetsSafePatternsAndPrivateDiagnostics(t *testing.T) {
	for _, rule := range []string{"AND,(),PROXY", "NOT,((NETWORK,tcp),(NETWORK,udp)),PROXY",

		"DST-PORT,65536,PROXY", "IP-CIDR,invalid,PROXY"} {
		raw := optionJSON(t, func(o *runtimeOptions) { o.Rules = []string{rule} })
		reply := ValidateRuntimeOptions(raw)
		if strings.Contains(reply, `"state":"passed"`) {
			t.Fatalf("invalid rule accepted: %s", rule)
		}
		assertNoCanaries(t, reply)
	}
	nested := "NETWORK,tcp"
	for i := 0; i < 12; i++ {
		nested = "AND,((" + nested + "))"
	}
	if _, _, code := compileRules([]string{nested + ",PROXY"}); code == "" {
		t.Fatal("logical depth unbounded")
	}
	children := strings.Repeat("(NETWORK,tcp),", 65) + "(NETWORK,tcp)"
	if _, _, code := compileRules([]string{"OR,(" + children + "),PROXY"}); code == "" {
		t.Fatal("logical count unbounded")
	}
	rules, _, code := compileRules([]string{`AND,((DOMAIN-REGEX,^(a+)+$),(NETWORK,tcp)),PROXY`})
	if code != "" || rules[0].matches(strings.Repeat("a", 253)+"!", netip.Addr{}, 443, "tcp") {
		t.Fatal("safe nested regex changed")
	}
	raw := optionJSON(t, func(o *runtimeOptions) { o.Rules = []string{"DST-PORT,invalid,CANARY-PASSWORD-1234"} })
	var reply map[string]any
	_ = json.Unmarshal([]byte(ValidateRuntimeOptions(raw)), &reply)
	if reply["ruleType"] != "DST-PORT" || reply["line"] != float64(1) {
		t.Fatal("safe category missing")
	}
	assertNoCanaries(t, ValidateRuntimeOptions(raw))
	raw = optionJSON(t, func(o *runtimeOptions) { o.Rules = []string{"CANARY-SUB-TOKEN-1234,x,DIRECT"} })
	assertNoCanaries(t, ValidateRuntimeOptions(raw))
}
