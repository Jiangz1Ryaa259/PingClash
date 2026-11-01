// SPDX-License-Identifier: GPL-3.0-only
package mobile

import (
	"bytes"
	"compress/gzip"
	"encoding/base64"
	"encoding/json"
	"strings"
	"testing"
)

func TestProviderCapacityCompressionLimitsAndLegacy(t *testing.T) {
	plain := []byte(`{"domains":{"behavior":"domain","entries":["+.example.test"]}}`)
	packed, e := packRuleData(plain)
	if e != nil {
		t.Fatal(e)
	}
	for _, text := range []string{packed, string(plain)} {
		sets, e := decodeStoredRuleSets(text)
		if e != nil || len(sets["domains"].Entries) != 1 {
			t.Fatal("legacy/packed rule data lost")
		}
	}
	body := strings.Repeat("x", 1024)
	var buffer bytes.Buffer
	w := gzip.NewWriter(&buffer)
	_, _ = w.Write([]byte(body))
	_ = w.Close()
	compressed := packedProviderPrefix + base64.StdEncoding.EncodeToString(buffer.Bytes())
	raw, e := unpackBounded(compressed, packedProviderPrefix, 2048, 2048)
	if e != nil || string(raw) != body {
		t.Fatal("cache interoperability failed")
	}
	if _, e = unpackBounded(compressed, packedProviderPrefix, 64, 2048); e == nil {
		t.Fatal("decompression bomb not bounded")
	}
	for _, bad := range []string{packedRuleDataPrefix + "invalid", packed[:len(packed)-4], `{"x":{"behavior":"domain","entries":[],"extra":true}}`, `{"x":{"behavior":"domain","entries":[]},"x":{"behavior":"ipcidr","entries":[]}}`} {
		if _, e = decodeStoredRuleSets(bad); e == nil {
			t.Fatal("invalid packed data accepted")
		}
	}
}

func TestProviderDeclaredCapacityIsDistinctFromAppLimit(t *testing.T) {
	text := `proxies: [{name: local, type: socks5, server: example.invalid, port: 1080}]
rule-providers: {third: {type: http, url: https://example.invalid/CANARY-SUB-TOKEN-1234, behavior: domain, size-limit: 8}}
rules: ['RULE-SET,third,DIRECT', 'MATCH,REJECT']`
	r := resolveFixture(t, text, map[string]string{"rule:third": "payload: ['+.example.test']"})
	if r["code"] != "provider_declared_size_limit" || r["index"] != float64(1) {
		t.Fatal("declared limit mislabeled", r)
	}
	text = strings.Replace(text, "size-limit: 8", "size-limit: 67108864", 1)
	if !strings.Contains(PlanRemoteProviders(text), `"state":"passed"`) {
		t.Fatal("larger declared setting cannot be planned")
	}
	r = resolveFixture(t, text, map[string]string{"rule:third": strings.Repeat("x", maxRuleProviderBytes+1)})
	if r["code"] != "provider_size_limit" {
		t.Fatal("app limit not enforced", r)
	}
	output, _ := json.Marshal(r)
	assertNoCanaries(t, string(output))
}
