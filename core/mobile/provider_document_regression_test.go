// SPDX-License-Identifier: GPL-3.0-only
package mobile

import (
	"encoding/json"
	"reflect"
	"strings"
	"testing"

	"go.yaml.in/yaml/v3"
)

func TestProviderDocumentInheritedDNSAndEmptyUse(t *testing.T) {
	for _, suffix := range []string{"", "use: null,", "use: [],"} {
		text := `defaults: &defaults
  dns:
    enable: true
    nameserver: [1.1.1.1]
    nameserver-policy:
      '+.z.invalid': 1.0.0.1
      '+.a.invalid': 1.1.1.1
<<: *defaults
proxies: [{name: local, type: socks5, server: example.invalid, port: 1080}]
rule-providers: {source: {type: inline, behavior: domain, payload: ['+.example.test']}}
proxy-groups: [{name: Main, type: select, ` + suffix + ` proxies: [local]}]
rules: ['RULE-SET,source,Main', 'MATCH,REJECT']`
		var native map[string]any
		if yaml.Unmarshal([]byte(text), &native) != nil || native["dns"] == nil {
			t.Fatal("invalid inherited fixture")
		}
		result := resolveFixture(t, text, nil)
		if result["state"] != "passed" {
			t.Fatal("valid inherited DNS/empty use rejected", result)
		}
		var imported importedProfile
		_ = json.Unmarshal([]byte(ImportProfile(result["content"].(string))), &imported)
		if imported.State != "passed" || len(imported.Rules) != 2 {
			t.Fatal("inherited config failed full import")
		}
		var decoded map[string]any
		_ = json.Unmarshal([]byte(imported.Definition), &decoded)
		fragment, _ := decoded[nativeDNSDocumentKey].(string)
		if !strings.Contains(fragment, "z.invalid") || strings.Index(fragment, "z.invalid") > strings.Index(fragment, "a.invalid") {
			t.Fatal("inherited DNS policy order lost")
		}
	}
}

func TestProviderDocumentNullUseWithoutInheritance(t *testing.T) {
	text := `proxies: [{name: local, type: socks5, server: example.invalid, port: 1080}]
rule-providers: {source: {type: inline, behavior: domain, payload: ['+.example.test']}}
proxy-groups: [{name: Main, type: select, use: null, proxies: [local]}]
rules: ['RULE-SET,source,Main']`
	if result := resolveFixture(t, text, nil); result["state"] != "passed" {
		t.Fatal("empty optional provider reference rejected", result)
	}
}

func TestProviderDocumentCommentsDoNotConsumeNodeBudget(t *testing.T) {
	text := "# " + strings.Repeat("[],{}:", 9000) + "\nproxies: [{name: local, type: socks5, server: example.invalid, port: 1080}]\n"
	var reply struct{ State, Code string }
	_ = json.Unmarshal([]byte(PlanRemoteProviders(text)), &reply)
	if reply.State != "passed" {
		t.Fatal("comment punctuation mistaken for YAML node count", reply.Code)
	}
}

func TestProviderDocumentDNSInheritancePrecedence(t *testing.T) {
	for _, suffix := range []string{"", "dns: {enable: false, nameserver: [9.9.9.9]}\n", "dns: null\n"} {
		text := "a: &a {dns: {enable: true, nameserver: [1.1.1.1]}}\nb: &b {dns: {enable: true, nameserver: [8.8.8.8]}}\n<<: [*a, *b]\n" + suffix
		var expected map[string]any
		if yaml.Unmarshal([]byte(text), &expected) != nil {
			t.Fatal("invalid fixture")
		}
		decoded, err := remoteYAML(text)
		if err != nil {
			t.Fatal("DNS merge rejected", err)
		}
		root := decoded.(map[string]any)
		var fragment map[string]any
		if yaml.Unmarshal([]byte(root[nativeDNSDocumentKey].(string)), &fragment) != nil || !reflect.DeepEqual(fragment["dns"], expected["dns"]) {
			t.Fatal("DNS inheritance changed YAML precedence")
		}
	}
}

func TestProviderDocumentFailuresAreSpecificAndPrivate(t *testing.T) {
	base := "proxies: [{name: local, type: socks5, server: example.invalid, port: 1080}]\nrule-providers: {source: {type: inline, behavior: domain, payload: ['+.example.test']}}\n"
	for _, test := range []struct{ suffix, code string }{
		{"proxy-groups: [{name: main, type: select, use: CANARY-PRIVATE}]", "provider_group_use"},
		{"proxy-groups: [{name: main, type: select, use: [CANARY-PRIVATE]}]", "provider_group_reference"},
		{"proxy-groups: [{name: main, type: select, filter: '[CANARY-PRIVATE'}]", "provider_group_filter"},
		{"rules: ['RULE-SET,CANARY-PRIVATE,local']", "provider_rule_reference"},
		{"rules: [123]", "provider_rule_entry"},
		{"rules: [CANARY-PRIVATE", "provider_document_syntax"},
	} {
		result := resolveFixture(t, base+test.suffix, nil)
		if result["state"] != "failed" || result["code"] != test.code {
			t.Fatal("incorrect safe failure category", result)
		}
		encoded, _ := json.Marshal(result)
		if strings.Contains(string(encoded), "CANARY-") {
			t.Fatal("failure includes private configuration")
		}
	}
	if _, err := remoteYAML("a: &a [*a]"); err == nil || err.Error() != "provider_document_limit" {
		t.Fatal("recursive aliases no longer rejected with bounded error")
	}
}
