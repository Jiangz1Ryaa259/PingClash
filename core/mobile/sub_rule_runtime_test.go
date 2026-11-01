// SPDX-License-Identifier: GPL-3.0-only
package mobile

import (
	"encoding/json"
	"net/netip"
	"strings"
	"testing"

	C "github.com/metacubex/mihomo/constant"
	M "github.com/metacubex/sing/common/metadata"
)

func TestSubRulesUseNativeDynamicExit(t *testing.T) {
	definition := `{"proxies":[{"name":"server","type":"socks5","server":"127.0.0.1","port":9}],"sub-rules":{"outer":["SUB-RULE,(NETWORK,tcp),inner","MATCH,REJECT"],"inner":["DOMAIN-SUFFIX,example.invalid,DIRECT","MATCH,PROXY"]}}`
	lines := []string{"SUB-RULE,(DST-PORT,443),outer", "MATCH,REJECT"}
	rules, _, code := compileRuntimeRules(lines, definition)
	if code != "" {
		t.Fatal(code)
	}
	for _, tc := range []struct{ host, network, target string }{
		{"www.example.invalid", "tcp", "DIRECT"},
		{"else.invalid", "tcp", "PROXY"},
		{"www.example.invalid", "udp", "REJECT"},
	} {
		meta := ruleMetadata(tc.host, netip.MustParseAddr("192.0.2.1"), 443, tc.network)
		matched, target := rules[0].native.Match(meta, C.RuleMatchHelper{})
		if !matched || target != tc.target {
			t.Fatalf("dynamic native exit incorrect: %v %s", matched, target)
		}
	}
	options, _ := json.Marshal(map[string]any{"mode": "rule", "rules": lines, "definition": definition})
	if result := ValidateRuntimeOptions(string(options)); !strings.Contains(result, `"state":"passed"`) {
		t.Fatal(result)
	}
	s, e := newSessionConfigured(`{"type":"socks5","server":"127.0.0.1","port":9}`, "1.1.1.1", string(options))
	if e != nil {
		t.Fatal(e)
	}
	defer s.close()
	_, target, e := s.route(M.Socksaddr{Fqdn: "www.example.invalid", Port: 443}, "tcp")
	if e != nil || target != "DIRECT" {
		t.Fatalf("session failed to use actual exit: %s %v", target, e)
	}
}

func TestSubRulesRejectCyclesMissingReferencesAndExcessDepth(t *testing.T) {
	for _, definition := range []string{
		`{"sub-rules":{"a":["SUB-RULE,(NETWORK,tcp),a"]}}`,
		`{"sub-rules":{"a":["SUB-RULE,(NETWORK,tcp),b"],"b":["SUB-RULE,(NETWORK,tcp),a"]}}`,
		`{"sub-rules":{"a":["SUB-RULE,(NETWORK,tcp),missing"]}}`,
		`{"sub-rules":{"a":null}}`,
	} {
		_, _, code := compileRuntimeRules([]string{"SUB-RULE,(NETWORK,tcp),a"}, definition)
		if code == "" {
			t.Fatal("invalid sub-rule accepted")
		}
	}
	groups := map[string][]string{}
	for i := 0; i < 11; i++ {
		name := strings.Repeat("a", i+1)
		groups[name] = []string{"SUB-RULE,(NETWORK,tcp)," + name + "a"}
	}
	groups[strings.Repeat("a", 12)] = []string{"MATCH,DIRECT"}
	b, _ := json.Marshal(map[string]any{"sub-rules": groups})
	if _, _, code := compileRuntimeRules([]string{"SUB-RULE,(NETWORK,tcp),a"}, string(b)); code == "" {
		t.Fatal("excess nesting accepted")
	}
}

func TestImportReparseRetainsDNSHostsAndSubRules(t *testing.T) {
	raw := `proxies: [{name: server, type: socks5, server: 127.0.0.1, port: 9}]
dns: {enable: true, nameserver: ["tcp://127.0.0.1:53"], nameserver-policy: {"+.example.invalid": "1.1.1.1"}}
hosts: {local.example.invalid: 192.0.2.1}
sub-rules: {private: ["MATCH,DIRECT"]}
rules: ["SUB-RULE,(NETWORK,tcp),private"]
`
	for i := 0; i < 3; i++ {
		var profile importedProfile
		if json.Unmarshal([]byte(ImportProfile(raw)), &profile) != nil || profile.State != "passed" {
			t.Fatal("import failed")
		}
		var stored map[string]any
		if json.Unmarshal([]byte(profile.Definition), &stored) != nil {
			t.Fatal("private definition missing")
		}
		for _, key := range []string{"dns", "hosts", "sub-rules", "rules"} {
			if stored[key] == nil {
				t.Fatalf("configuration lost after import/reparse: %s", key)
			}
		}
		raw = profile.Definition
	}
}

func TestUnencodableFullDocumentCannotSilentlyDropDNS(t *testing.T) {
	raw := `proxies: [{name: server, type: socks5, server: 127.0.0.1, port: 9}]
dns: {enable: true, nameserver: [1.1.1.1], cache-max-size: .nan}
`
	var profile importedProfile
	if json.Unmarshal([]byte(ImportProfile(raw)), &profile) != nil || profile.State != "failed" {
		t.Fatal("unencodable DNS document was silently discarded")
	}
}
