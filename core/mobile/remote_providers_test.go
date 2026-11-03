// SPDX-License-Identifier: GPL-3.0-only
package mobile

import (
	"encoding/json"
	"strings"
	"testing"
)

const remoteFixture = `proxy-providers:
  alpha:
    type: http
    url: https://example.invalid/private-token
    interval: 60
    path: /must/not/be/written
    header: {Authorization: [fixture-authorization]}
rule-providers:
  domains: {type: http, url: https://example.invalid/domains, interval: 120, behavior: domain}
  networks: {type: http, url: https://example.invalid/ip, interval: 180, behavior: ipcidr, format: text}
  classic: {type: http, url: https://example.invalid/rules, behavior: classical}
proxy-groups:
  - {name: main, type: select, proxies: [auto, DIRECT]}
  - {name: auto, type: url-test, use: [alpha], url: https://example.invalid/check, interval: 60}
rules:
  - RULE-SET,domains,main
  - RULE-SET,networks,REJECT,no-resolve
  - RULE-SET,classic,DIRECT
  - MATCH,main
`

func remoteBodies() map[string]string {
	return map[string]string{
		"proxy:alpha":   `proxies: [{name: first, type: anytls, server: example.invalid, port: 443, password: synthetic-secret}]`,
		"rule:domains":  "payload: ['+.example.org', exact.example.net]",
		"rule:networks": "# comment\n192.0.2.0/24\n2001:db8::/32\n",
		"rule:classic":  "payload: ['DOMAIN-SUFFIX,example.edu', 'DST-PORT,443']",
	}
}
func resolveFixture(t *testing.T, text string, bodies map[string]string) map[string]any {
	t.Helper()
	b, _ := json.Marshal(bodies)
	var r map[string]any
	if e := json.Unmarshal([]byte(ResolveRemoteProviders(text, string(b))), &r); e != nil {
		t.Fatal(e)
	}
	return r
}
func TestRemoteProviderPlanExpansionAndNativeRuleValidation(t *testing.T) {
	var plan struct {
		State     string
		Providers []remoteSpec
	}
	if json.Unmarshal([]byte(PlanRemoteProviders(remoteFixture)), &plan) != nil || plan.State != "passed" || len(plan.Providers) != 4 {
		t.Fatal("provider plan failed")
	}
	if plan.Providers[0].Headers["Authorization"] != "fixture-authorization" || plan.Providers[0].Interval != 60 {
		t.Fatal("headers/interval lost")
	}
	r := resolveFixture(t, remoteFixture, remoteBodies())
	if r["state"] != "passed" {
		t.Fatal(r)
	}
	content := r["content"].(string)
	if strings.Contains(content, "private-token") || strings.Contains(content, "must/not") || strings.Contains(content, "Authorization") {
		t.Fatal("download-only settings retained in runtime")
	}
	var p importedProfile
	_ = json.Unmarshal([]byte(ImportProfile(content)), &p)
	if p.State != "passed" || len(p.Nodes) != 1 || len(p.Groups) != 2 || p.GroupsUnsupported != 0 || p.Groups[0].Name != "main" || p.Groups[1].Proxies[0] != "first" {
		t.Fatal("original group structure lost")
	}
	if len(p.Rules) != 4 || p.Rules[0] != "RULE-SET,domains,main" || p.Rules[1] != "RULE-SET,networks,REJECT,no-resolve" {
		t.Fatal("rule order or expansion lost")
	}
	options, _ := json.Marshal(runtimeOptions{Mode: "rule", MTU: 1500, IPv6: true, UDP: true, LAN: "proxy", ConnectTimeout: 10, UDPTimeout: 60, MaxConnections: 256, DNSMode: "tcp", Rules: p.Rules, Definition: p.Definition})
	if !strings.Contains(ValidateRuntimeOptions(string(options)), `"state":"passed"`) {
		t.Fatal("expanded rules not executable")
	}
}
func TestRemoteProviderFailuresAreBoundedAndDoNotExposePrivateData(t *testing.T) {
	for _, text := range []string{
		strings.Replace(remoteFixture, "type: http", "type: unsupported", 1),
		strings.Replace(remoteFixture, "https://example.invalid/private-token", "http://example.invalid/private-token", 1),
		strings.Replace(remoteFixture, "interval: 60", "interval: -1", 1),
		strings.Replace(remoteFixture, "path: /must/not/be/written", "override: {override-expr: [unsafe]}", 1),
		strings.Replace(remoteFixture, "Authorization: [fixture-authorization]", "Host: [private-host]", 1),
		strings.Replace(remoteFixture, "behavior: domain", "behavior: classical, format: mrs", 1),
		remoteFixture + "proxy-providers: {}\n",
		"proxy-providers: {x: &x {type: http, url: https://example.invalid, cycle: *x}}",
	} {
		result := PlanRemoteProviders(text)
		if !strings.Contains(result, `"state":"failed"`) || strings.Contains(result, "private-token") || strings.Contains(result, "fixture-authorization") || strings.Contains(result, "example.invalid") {
			t.Fatal("unsafe plan or leaked details")
		}
	}
	for _, body := range []string{"<!doctype html><html>login</html>", "proxies: [oops]", strings.Repeat("x", 2*1024*1024+1), "proxies: [{name: same}, {name: same}]"} {
		bodies := remoteBodies()
		bodies["proxy:alpha"] = body
		r := resolveFixture(t, remoteFixture, bodies)
		if r["state"] != "failed" || r["index"] != float64(1) || r["kind"] != "proxy" {
			t.Fatal("source-specific failure missing")
		}
	}
	bodies := remoteBodies()
	bodies["rule:domains"] = "payload: ['invalid..domain']"
	resolved := resolveFixture(t, remoteFixture, bodies)
	var imported importedProfile
	if resolved["state"] != "passed" || json.Unmarshal([]byte(ImportProfile(resolved["content"].(string))), &imported) != nil || imported.State != "passed" || imported.ProviderRulesSkipped != 1 {
		t.Fatal("invalid native rule entry must remain an explicit warning, not reject the subscription")
	}
}
func TestRemoteProviderMembershipFiltersAndNoImplicitFlattening(t *testing.T) {
	text := `proxy-providers:
  a: {type: http, url: https://example.invalid/a, filter: '^(HK|JP)'}
proxy-groups:
  - {name: only-a, type: select, use: [a], filter: ^HK}
  - {name: direct-only, type: select, proxies: [DIRECT]}
  - {name: all-providers, type: select, include-all-providers: true, filter: ^HK}
`
	bodies := map[string]string{"proxy:a": `proxies: [{name: HK-one, type: anytls, server: example.invalid, port: 443, password: fixture}, {name: JP-one, type: anytls, server: example.invalid, port: 443, password: fixture}]`}
	r := resolveFixture(t, text, bodies)
	if r["state"] != "passed" {
		t.Fatal(r)
	}
	var p importedProfile
	_ = json.Unmarshal([]byte(ImportProfile(r["content"].(string))), &p)
	if len(p.Nodes) != 2 || len(p.Groups) != 3 || strings.Join(p.Groups[0].Proxies, ",") != "HK-one" || strings.Join(p.Groups[1].Proxies, ",") != "DIRECT" || strings.Join(p.Groups[2].Proxies, ",") != "HK-one" {
		t.Fatal("provider memberships mixed")
	}
}
func FuzzRemoteProviders(f *testing.F) {
	f.Add(remoteFixture, `{}`)
	f.Add("proxies: []", `{}`)
	f.Add(remoteFixture, `{"proxy:alpha":"anytls://CANARY-PASSWORD-1234@example.invalid:443#Keep"}`)
	f.Fuzz(func(t *testing.T, text, bodies string) {
		for _, result := range []string{PlanRemoteProviders(text), ResolveRemoteProviders(text, bodies)} {
			if !json.Valid([]byte(result)) {
				t.Fatal("invalid bridge response")
			}
		}
	})
}
