// SPDX-License-Identifier: GPL-3.0-only
// Run from core/: go run ../tools/generate-mrs-fixtures.go
package main

import (
	"bytes"
	"encoding/base64"
	"encoding/json"
	"os"
	"path/filepath"

	P "github.com/metacubex/mihomo/constant/provider"
	RP "github.com/metacubex/mihomo/rules/provider"
)

func main() {
	var domain []byte
	for _, row := range []struct {
		name, text string
		behavior   P.RuleBehavior
	}{
		{"domain", "+.example.test\n.exact.test\n*.wild.test\nplain.test\n例子.test\n", P.Domain},
		{"ipcidr", "192.0.2.0/24\n2001:db8::/32\n", P.IPCIDR},
	} {
		var data bytes.Buffer
		if e := RP.ConvertToMrs([]byte(row.text), row.behavior, P.TextRule, &data); e != nil {
			panic(e)
		}
		path := filepath.Join("..", "app", "src", "androidTest", "assets", "provider-"+row.name+"-fixture.mrs")
		if e := os.WriteFile(path, data.Bytes(), 0600); e != nil {
			panic(e)
		}
		if row.name == "domain" {
			domain = append([]byte{}, data.Bytes()...)
		}
	}
	path := "mobile/testdata/provider-compatibility/manifest.json"
	body, e := os.ReadFile(path)
	if e != nil {
		panic(e)
	}
	var manifest map[string]any
	if e = json.Unmarshal(body, &manifest); e != nil {
		panic(e)
	}
	for _, raw := range manifest["cases"].([]any) {
		row := raw.(map[string]any)
		if row["id"] != "mrs-rule" {
			continue
		}
		row["plan"] = "passed"
		row["resolve"] = "passed"
		row["rules"] = 2
		row["bodies"].(map[string]any)["rule:ruleset"] = "pingclash-mrs-v1:base64:" + base64.StdEncoding.EncodeToString(domain)
	}
	body, e = json.MarshalIndent(manifest, "", "  ")
	if e != nil {
		panic(e)
	}
	if os.WriteFile(path, append(body, '\n'), 0600) != nil {
		panic("manifest write failed")
	}
}
