// SPDX-License-Identifier: GPL-3.0-only
package mobile

import (
	"encoding/json"
	"net/netip"
	"reflect"
	"testing"
)

// Subscriptions serve YAML lists as ".txt", plain lists without a format, and
// sometimes declare the wrong one. Reading one as the other used to turn every
// entry into an invalid rule; the body now decides.
func TestRuleSourcesAreReadByTheirContentNotTheirName(t *testing.T) {
	profile := `proxies: [{name: Local, type: socks5, server: example.invalid, port: 1080}]
rule-providers:
  yamltxt: {type: http, behavior: domain, url: 'https://cdn.example/direct.txt', path: ./ruleset/direct.yaml, interval: 86400}
  plainlist: {type: http, behavior: ipcidr, url: 'https://cdn.example/cidr.list', interval: 86400}
  declaredtext: {type: http, behavior: classical, format: text, url: 'https://cdn.example/apps.txt', interval: 86400}
  declaredyaml: {type: http, behavior: domain, format: yaml, url: 'https://cdn.example/plain.yaml', interval: 86400}
rules:
  - RULE-SET,yamltxt,DIRECT
  - RULE-SET,plainlist,DIRECT
  - RULE-SET,declaredtext,DIRECT
  - RULE-SET,declaredyaml,DIRECT
  - MATCH,Local
`
	bodies := map[string]string{
		"rule:yamltxt":      "# NAME: direct\npayload:\n  - '+.example.com'\n  - 'example.org'\n",
		"rule:plainlist":    "1.0.1.0/24\n# comment\n2001:db8::/32\n",
		"rule:declaredtext": "payload:\n  - DOMAIN-SUFFIX,example.net\n  - IP-CIDR,10.0.0.0/8,no-resolve\n",
		"rule:declaredyaml": "example.com\n+.example.net\n",
	}
	r := resolveFixture(t, profile, bodies)
	if r["state"] != "passed" {
		t.Fatal(r)
	}
	content := r["content"].(string)
	p := imported(t, content)
	if p.ProviderRulesSkipped != 0 || len(p.ProviderRuleWarnings) != 0 {
		t.Fatalf("entries skipped: %d %+v", p.ProviderRulesSkipped, p.ProviderRuleWarnings)
	}
	var root map[string]any
	_ = json.Unmarshal([]byte(content), &root)
	sets, err := decodeStoredRuleSets(root[providerRuleDataKey].(string))
	if err != nil {
		t.Fatal(err)
	}
	for name, want := range map[string][]string{
		"yamltxt":      {"+.example.com", "example.org"},
		"plainlist":    {"1.0.1.0/24", "2001:db8::/32"},
		"declaredtext": {"DOMAIN-SUFFIX,example.net", "IP-CIDR,10.0.0.0/8,no-resolve"},
		"declaredyaml": {"example.com", "+.example.net"},
	} {
		if got := sets[name].Entries; !reflect.DeepEqual(got, want) {
			t.Fatalf("%s: got %q, want %q", name, got, want)
		}
	}
}

// Profiles saved before the fix hold YAML list lines; reparsing repairs them.
func TestSavedYAMLListLinesAreReadAsTheirEntries(t *testing.T) {
	m, code := compileProviderRuleSet(storedRuleSet{Behavior: "ipcidr", Source: 1, Entries: []string{"payload:", "- '1.0.1.0/24'", `- "10.0.0.0/8"`}})
	if code != "" || m.skipped != 0 {
		t.Fatalf("code=%q skipped=%d", code, m.skipped)
	}
	if !m.matches("", netip.MustParseAddr("10.1.2.3"), 443, "tcp") {
		t.Fatal("repaired entry does not match")
	}
	d, _ := compileProviderRuleSet(storedRuleSet{Behavior: "domain", Source: 1, Entries: []string{"payload:", "  - '+.example.com'"}})
	if d.skipped != 0 || !d.matches("www.example.com", netip.Addr{}, 443, "tcp") {
		t.Fatal("repaired domain entry does not match")
	}
}
