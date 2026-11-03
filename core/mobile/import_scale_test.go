// SPDX-License-Identifier: GPL-3.0-only
package mobile

import (
	"encoding/json"
	"fmt"
	"strings"
	"testing"
)

func importScaleFixture(nodes, groups, rules int) string {
	var b strings.Builder
	b.WriteString("proxies:\n")
	for i := 0; i < nodes; i++ {
		fmt.Fprintf(&b, "  - {name: n%d, type: trojan, server: 203.0.113.7, port: 443, password: x, sni: example.com}\n", i)
	}
	b.WriteString("proxy-groups:\n")
	for i := 0; i < groups; i++ {
		kind := "select"
		if i%3 == 1 {
			kind = "url-test"
		}
		fmt.Fprintf(&b, "  - {name: g%d, type: %s, proxies: [n%d, n%d], url: 'https://www.gstatic.com/generate_204', interval: 300}\n", i, kind, i%nodes, (i+1)%nodes)
	}
	b.WriteString("rules:\n")
	for i := 0; i < rules; i++ {
		fmt.Fprintf(&b, "  - DOMAIN-SUFFIX,d%d.example,g1\n", i)
	}
	b.WriteString("  - MATCH,g0\n")
	return b.String()
}

func importScaleResult(t *testing.T, text string) importedProfile {
	t.Helper()
	var result importedProfile
	if err := json.Unmarshal([]byte(ImportProfile(text)), &result); err != nil {
		t.Fatal(err)
	}
	return result
}

// Airport subscriptions routinely ship dozens of regional and service groups,
// thousands of nodes and rule lists in the tens of thousands.
func TestLargeSubscriptionKeepsEveryNodeGroupAndRule(t *testing.T) {
	result := importScaleResult(t, importScaleFixture(1200, 90, 6000))
	if result.State != "passed" || len(result.Nodes) != 1200 || result.Unsupported != 0 {
		t.Fatalf("nodes: state=%s nodes=%d unsupported=%d", result.State, len(result.Nodes), result.Unsupported)
	}
	if len(result.Groups) != 90 || result.GroupsUnsupported != 0 {
		t.Fatalf("groups: %d kept, %d unsupported", len(result.Groups), result.GroupsUnsupported)
	}
	if len(result.Rules) != 6001 || result.RulesOmitted != 0 {
		t.Fatalf("rules: %d kept, %d omitted", len(result.Rules), result.RulesOmitted)
	}
	if _, _, code := compileRules(result.Rules); code != "" {
		t.Fatalf("runtime rules: %s", code)
	}
}

// Past a limit only the excess is dropped and counted; the rest keeps working.
func TestImportPastTheLimitsDropsOnlyTheExcess(t *testing.T) {
	result := importScaleResult(t, importScaleFixture(maxImportNodes+7, maxImportGroups+5, 10))
	if result.State != "passed" || len(result.Nodes) != maxImportNodes {
		t.Fatalf("state=%s nodes=%d", result.State, len(result.Nodes))
	}
	if result.Unsupported != 7 || result.Rejections["resource_limit"] != 7 {
		t.Fatalf("unsupported=%d rejections=%v", result.Unsupported, result.Rejections)
	}
	if len(result.Groups) != maxImportGroups || result.GroupsUnsupported < 5 {
		t.Fatalf("groups: %d kept, %d unsupported", len(result.Groups), result.GroupsUnsupported)
	}
	supported := 0
	for _, group := range result.Groups {
		if group.Supported {
			supported++
		}
	}
	if supported == 0 {
		t.Fatal("every group was lost")
	}
}

func TestShareLinksIgnoreParametersThisClientDoesNotUse(t *testing.T) {
	result := importScaleResult(t, "trojan://pw@203.0.113.7:443?sni=example.com&spx=%2F&fragment=1#T1\n"+
		"vless://0b6f4f1e-7f7e-4f62-9d4a-2f0f2f0f2f0f@203.0.113.8:443?security=tls&sni=example.com&remarks=x#V1")
	if result.State != "passed" || len(result.Nodes) != 2 || result.Unsupported != 0 {
		t.Fatalf("state=%s nodes=%d unsupported=%d rejections=%v", result.State, len(result.Nodes), result.Unsupported, result.Rejections)
	}
	// Security-relevant parameters are still checked, not ignored.
	bad := importScaleResult(t, "trojan://pw@203.0.113.7:443?sni=example.com&allowInsecure=maybe#T1")
	if len(bad.Nodes) != 0 {
		t.Fatal("an invalid certificate policy was accepted")
	}
}
