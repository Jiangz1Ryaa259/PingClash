// SPDX-License-Identifier: GPL-3.0-only
package mobile

import (
	"encoding/json"
	"net/netip"
	"os"
	"os/exec"
	"path/filepath"
	"testing"

	"github.com/metacubex/mihomo/component/geodata"
	C "github.com/metacubex/mihomo/constant"
	M "github.com/metacubex/sing/common/metadata"
)

func TestASNRejectsMissingOrCorruptDatabase(t *testing.T) {
	const key = "PINGCLASH_TEST_ASN_DIRECTORY"
	if directory := os.Getenv(key); directory != "" {
		if InitializeRuleData(directory) || ruleDataAvailable() {
			t.Fatal("unverified ASN data accepted")
		}
		if _, code := compileNativeRule("IP-ASN", "13335", "PROXY", nil); code != "rule_data_unavailable" {
			t.Fatal("missing data bypassed constructor guard")
		}
		return
	}
	executable, e := os.Executable()
	if e != nil {
		t.Fatal(e)
	}
	for _, corrupt := range []bool{false, true} {
		directory := t.TempDir()
		for _, name := range []string{"GeoIP.dat", "GeoSite.dat"} {
			body, e := os.ReadFile(filepath.Join("../../app/src/main/assets/ruledata", name))
			if e != nil {
				t.Fatal(e)
			}
			if os.WriteFile(filepath.Join(directory, name), body, 0600) != nil {
				t.Fatal("fixture write failed")
			}
		}
		if corrupt && os.WriteFile(filepath.Join(directory, "ASN.mmdb"), []byte("invalid public fixture"), 0600) != nil {
			t.Fatal("fixture write failed")
		}
		command := exec.Command(executable, "-test.run=^TestASNRejectsMissingOrCorruptDatabase$")
		command.Env = append(os.Environ(), key+"="+directory)
		if _, e = command.CombinedOutput(); e != nil {
			t.Fatal("ASN integrity child regression failed")
		}
	}
}

func TestBundledASNMatchesDestinationSourceAndIPv6WithoutNetwork(t *testing.T) {
	directory, e := filepath.Abs("../../app/src/main/assets/ruledata")
	if e != nil || !InitializeRuleData(directory) || !ruleDataAvailable() {
		t.Fatal("pinned ASN unavailable")
	}
	if geodata.ASNUrl() != "" || geodata.MmdbUrl() != "" {
		t.Fatal("database download enabled")
	}
	for _, row := range []struct {
		line, destination, source string
		matches                   bool
	}{
		{"IP-ASN,13335,REJECT", "1.1.1.1", "192.0.2.1", true},
		{"IP-ASN,15169,REJECT,no-resolve", "8.8.8.8", "192.0.2.1", true},
		{"IP-ASN,13335,REJECT", "2606:4700:4700::1111", "2001:db8::1", true},
		{"SRC-IP-ASN,13335,REJECT", "8.8.8.8", "1.1.1.1", true},
		{"IP-ASN,13335,REJECT,src", "8.8.8.8", "1.1.1.1", true},
		{"IP-ASN,15169,REJECT", "1.1.1.1", "192.0.2.1", false},
		{"IP-ASN,13335,REJECT", "192.0.2.1", "192.0.2.1", false},
	} {
		rules, _, code := compileRules([]string{row.line})
		if code != "" {
			t.Fatal("ASN rule refused", code)
		}
		m := ruleMetadata("", netip.MustParseAddr(row.destination), 443, "tcp")
		m.SrcIP = netip.MustParseAddr(row.source)
		if rules[0].matchesMetadata(m, C.RuleMatchHelper{}) != row.matches {
			t.Fatal("ASN match semantics incorrect", row.line)
		}
	}
	raw := optionJSON(t, func(o *runtimeOptions) {
		o.Mode = "rule"
		o.Rules = []string{"IP-ASN,13335,REJECT", "MATCH,DIRECT"}
	})
	s, e := newSessionConfigured(offlineSelected, "127.0.0.1", raw)
	if e != nil {
		t.Fatal(e)
	}
	defer s.close()
	for destination, want := range map[string]string{"1.1.1.1:443": "REJECT", "8.8.8.8:443": "DIRECT"} {
		_, target, _ := s.route(M.ParseSocksaddr(destination), "tcp")
		if target != want {
			t.Fatal("ASN routing target incorrect")
		}
	}
}

func TestSevenClassicalASNEntriesRemainActiveAfterImportAndReload(t *testing.T) {
	directory, _ := filepath.Abs("../../app/src/main/assets/ruledata")
	if !InitializeRuleData(directory) {
		t.Fatal("pinned ASN unavailable")
	}
	text := `proxies: [{name: local, type: socks5, server: example.invalid, port: 1080}]
rule-providers:
  one: {type: inline, behavior: classical, payload: ['IP-ASN,13335']}
  two: {type: inline, behavior: classical, payload: ['IP-ASN,15169']}
  three: {type: inline, behavior: classical, payload: ['IP-ASN,37963', 'IP-ASN,45102', 'IP-ASN,16509', 'IP-ASN,8075', 'IP-ASN,32934']}
rules: ['RULE-SET,one,REJECT', 'RULE-SET,two,DIRECT', 'RULE-SET,three,PROXY', 'MATCH,PROXY']`
	resolved := resolveFixture(t, text, map[string]string{})
	if resolved["state"] != "passed" {
		t.Fatal("ASN providers refused")
	}
	var p importedProfile
	if json.Unmarshal([]byte(ImportProfile(resolved["content"].(string))), &p) != nil || p.State != "passed" ||
		p.ProviderRulesSkipped != 0 || len(p.ProviderRuleWarnings) != 0 {
		t.Fatal("ASN entries skipped")
	}
	var reread importedProfile
	if json.Unmarshal([]byte(ImportProfile(p.Definition)), &reread) != nil || reread.ProviderRulesSkipped != 0 {
		t.Fatal("reload lost ASN support")
	}
	rules, _, code := compileRuntimeRules(reread.Rules, reread.Definition)
	if code != "" || len(rules) != 4 {
		t.Fatal("ASN runtime refused")
	}
	for i, ip := range []string{"1.1.1.1", "8.8.8.8", "223.5.5.5"} {
		if !rules[i].matches("", netip.MustParseAddr(ip), 443, "tcp") {
			t.Fatal("provider ASN not active")
		}
	}
}
