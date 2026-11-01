// SPDX-License-Identifier: GPL-3.0-only
package mobile

import (
	"bytes"
	"encoding/base64"
	"encoding/binary"
	"encoding/json"
	"net/netip"
	"strings"
	"testing"

	"github.com/klauspost/compress/zstd"
	C "github.com/metacubex/mihomo/constant"
	P "github.com/metacubex/mihomo/constant/provider"
	RP "github.com/metacubex/mihomo/rules/provider"
)

func nativeMrsFixture(t testing.TB, behavior P.RuleBehavior, text string) []byte {
	t.Helper()
	var output bytes.Buffer
	if RP.ConvertToMrs([]byte(text), behavior, P.TextRule, &output) != nil {
		t.Fatal("native MRS fixture failed")
	}
	return output.Bytes()
}
func TestMRSNativeRoundTripMatchingAndPrivateReload(t *testing.T) {
	for _, behavior := range []string{"domain", "ipcidr"} {
		text := "+.example.test\n.exact.test\n*.wild.test\nplain.test\n例子.test\n"
		kind := P.Domain
		if behavior == "ipcidr" {
			text = "192.0.2.0/24\n2001:db8::/32\n"
			kind = P.IPCIDR
		}
		body := nativeMrsFixture(t, kind, text)
		entries, e := decodeMrsEntries(body, behavior)
		if e != nil {
			t.Fatal("native MRS rejected", behavior, e)
		}
		lines := make([]string, len(entries))
		for i, v := range entries {
			lines[i] = v.(string)
		}
		set, code := compileProviderRuleSet(storedRuleSet{Behavior: behavior, Entries: lines})
		if code != "" {
			t.Fatal(code)
		}
		var native interface {
			Reset()
			Insert(string)
			FinishInsert()
			Match(*C.Metadata, C.RuleMatchHelper) bool
		}
		if behavior == "domain" {
			native = RP.NewDomainStrategy()
		} else {
			native = RP.NewIPCidrStrategy()
		}
		native.Reset()
		for _, line := range strings.Fields(text) {
			native.Insert(line)
		}
		native.FinishInsert()
		for _, host := range []string{"example.test", "a.example.test", "exact.test", "a.exact.test", "a.b.wild.test", "a.wild.test", "plain.test", "例子.test", "other.invalid"} {
			for _, address := range []string{"192.0.2.1", "203.0.113.1", "2001:db8::1", "2001:db9::1"} {
				meta := ruleMetadata(host, netip.MustParseAddr(address), 443, "tcp")
				if set.matchesMetadata(meta, C.RuleMatchHelper{}) != native.Match(meta, C.RuleMatchHelper{}) {
					t.Fatal("native MRS semantics differ")
				}
			}
		}
		profile := `proxies: [{name: Local, type: socks5, server: example.invalid, port: 1080}]
rule-providers:
  source: {type: http, url: https://example.invalid/CANARY-SUB-TOKEN-1234, behavior: ` + behavior + `, format: mrs, path: ../../CANARY-PATH-1234}
rules: ['RULE-SET,source,REJECT','MATCH,PROXY']`
		result := resolveFixture(t, profile, map[string]string{"rule:source": mrsBodyPrefix + base64.StdEncoding.EncodeToString(body)})
		if result["state"] != "passed" {
			t.Fatal("MRS provider resolve failed", result)
		}
		var imported importedProfile
		if json.Unmarshal([]byte(ImportProfile(result["content"].(string))), &imported) != nil || imported.State != "passed" {
			t.Fatal("MRS import failed")
		}
		var reread importedProfile
		_ = json.Unmarshal([]byte(ImportProfile(imported.Definition)), &reread)
		if reread.State != "passed" {
			t.Fatal("MRS reload failed")
		}
		if _, _, code := compileRuntimeRules(reread.Rules, reread.Definition); code != "" {
			t.Fatal("MRS runtime failed", code)
		}
	}
}

func TestMRSRejectsHostileLengthsTreeAndBehavior(t *testing.T) {
	valid := nativeMrsFixture(t, P.Domain, "+.example.test\n")
	if _, e := decodeMrsEntries(valid, "ipcidr"); e == nil {
		t.Fatal("MRS behavior mismatch accepted")
	}
	if _, e := decodeMrsEntries(valid[:len(valid)/2], "domain"); e == nil {
		t.Fatal("truncated MRS accepted")
	}
	reader, _ := zstd.NewReader(nil)
	raw, e := reader.DecodeAll(valid, nil)
	reader.Close()
	if e != nil {
		t.Fatal(e)
	}
	mutations := [][]byte{[]byte("not mrs"), append([]byte{}, raw...)}
	for _, offset := range []int{5, 13, 22} {
		copy := append([]byte{}, raw...)
		binary.BigEndian.PutUint64(copy[offset:], 1<<62)
		mutations = append(mutations, copy)
	}
	mutations[1][0] = 'X'
	for _, invalid := range mutations {
		writer, _ := zstd.NewWriter(nil, zstd.WithEncoderConcurrency(1))
		encoded := writer.EncodeAll(invalid, nil)
		writer.Close()
		if _, e := decodeMrsEntries(encoded, "domain"); e == nil {
			t.Fatal("hostile MRS header accepted")
		}
	}
}

func FuzzMRSProvider(f *testing.F) {
	seed := nativeMrsFixture(f, P.Domain, "+.example.test\n")
	f.Add(seed, false)
	f.Add([]byte("CANARY-SUB-TOKEN-1234"), false)
	decoder, _ := zstd.NewReader(nil)
	frame, _ := decoder.DecodeAll(seed, nil)
	decoder.Close()
	f.Add(frame, true)
	f.Fuzz(func(t *testing.T, raw []byte, frame bool) {
		if len(raw) > 1<<20 {
			return
		}
		if frame {
			writer, _ := zstd.NewWriter(nil, zstd.WithEncoderConcurrency(1))
			raw = writer.EncodeAll(raw, nil)
			writer.Close()
		}
		for _, behavior := range []string{"domain", "ipcidr"} {
			entries, e := decodeMrsEntries(raw, behavior)
			if e != nil {
				assertNoCanaries(t, e.Error())
				continue
			}
			if len(entries) > maxProviderRules {
				t.Fatal("MRS count unbounded")
			}
		}
	})
}
