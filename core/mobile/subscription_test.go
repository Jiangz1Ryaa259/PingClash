// SPDX-License-Identifier: GPL-3.0-only
package mobile

import (
	"encoding/base64"
	"encoding/json"
	"strconv"
	"strings"
	"testing"

	"github.com/metacubex/mihomo/adapter"
	"go.yaml.in/yaml/v3"
)

func imported(t *testing.T, text string) importedProfile {
	t.Helper()
	var p importedProfile
	if json.Unmarshal([]byte(ImportProfile(text)), &p) != nil {
		t.Fatal("invalid profile JSON")
	}
	return p
}

func TestShareLinksAndBase64SubscriptionsImportOffline(t *testing.T) {
	vmess := base64.RawStdEncoding.EncodeToString([]byte(`{"v":"2","ps":"VMess","add":"example.invalid","port":"443","id":"ca3ea70c-6845-4e3b-88d4-e4ac64ffbfa8","aid":"0","scy":"auto","net":"ws","host":"example.invalid","path":"/connect","tls":"tls","sni":"example.invalid"}`))
	ss := base64.RawURLEncoding.EncodeToString([]byte("aes-128-gcm:fixture:private"))
	content := "anytls://fixture%3Aprivate@example.invalid:443?sni=example.invalid&insecure=0#HK%20AnyTLS\n" +
		"trojan://fixture@example.invalid:443?sni=example.invalid#Trojan\n" +
		"vless://ca3ea70c-6845-4e3b-88d4-e4ac64ffbfa8@example.invalid:443?security=tls&type=ws&host=example.invalid&path=%2Fconnect&encryption=none#VLESS\n" +
		"hy2://fixture@example.invalid:443?sni=example.invalid#HY2\n" +
		"tuic://ca3ea70c-6845-4e3b-88d4-e4ac64ffbfa8:fixture@example.invalid:443?congestion_control=cubic#TUIC\n" +
		"ss://" + ss + "@example.invalid:443#SS\nvmess://" + vmess
	for _, encoding := range []*base64.Encoding{nil, base64.StdEncoding, base64.RawStdEncoding, base64.URLEncoding, base64.RawURLEncoding} {
		text := content
		if encoding != nil {
			text = encoding.EncodeToString([]byte(text))
		}
		p := imported(t, "\ufeff \n"+text+"\n")
		if p.State != "passed" || len(p.Nodes) != 7 || p.Unsupported != 0 {
			t.Fatalf("common subscription rejected: state=%s nodes=%d rejected=%v", p.State, len(p.Nodes), p.Rejections)
		}
		for _, node := range p.Nodes {
			if decoded(t, ValidateNode(node.Config)).Code != "config_valid_not_connected" {
				t.Fatal("converted node is invalid")
			}
		}
	}
}

func TestSubscriptionRejectionsAreActionableAndDoNotEchoSecrets(t *testing.T) {
	p := imported(t, "anytls://valid-private@example.invalid:443#valid\nanytls://bad-private@example.invalid:443?insecure=1\nhttp://user:private@example.invalid:443\nanytls://bad-private@example.invalid:443?hpkp=unsupported-pin")
	if len(p.Nodes) != 3 || p.Unsupported != 1 || p.VerificationEnabled != 0 || p.Rejections["unsupported_fields"] != 1 {
		t.Fatalf("rejection reasons missing: %+v", p.Rejections)
	}
	encoded := ImportProfile("anytls://secret-token@example.invalid:443?insecure=invalid")
	if strings.Contains(encoded, "secret-token") || strings.Contains(encoded, "example.invalid") {
		t.Fatal("failed import echoed secret URI")
	}
	for _, text := range []string{"anytls://private@example.invalid:443?insecure=0&insecure=1", "anytls://private@example.invalid:443?insecure=0&allowInsecure=1", "trojan://private@example.invalid:443?pbk=must-not-ignore"} {
		if imported(t, text).State == "passed" {
			t.Fatal("ambiguous security settings accepted")
		}
	}
}

func TestFortyEightNodesPreserveConfiguredCertificatePolicy(t *testing.T) {
	line := "{type: anytls, server: example.invalid, port: 443, password: fixture, skip-cert-verify: true}"
	p := imported(t, "proxies: ["+strings.TrimSuffix(strings.Repeat(line+",", 48), ",")+"]")
	if p.State != "passed" || len(p.Nodes) != 48 || p.Unsupported != 0 || p.VerificationEnabled != 0 {
		t.Fatalf("48-node subscription failed: nodes=%d adjusted=%d rejected=%v", len(p.Nodes), p.VerificationEnabled, p.Rejections)
	}
	for _, node := range p.Nodes {
		var config map[string]any
		_ = json.Unmarshal([]byte(node.Config), &config)
		if config["skip-cert-verify"] != true || decoded(t, ValidateNode(node.Config)).Code != "config_valid_not_connected" {
			t.Fatal("import rewrote configured verification")
		}
	}
	for _, bad := range []string{`"true"`, `1`, `[]`, `{}`} {
		if imported(t, `{"type":"anytls","server":"example.invalid","port":443,"password":"fixture","skip-cert-verify":`+bad+`}`).State == "passed" {
			t.Fatal("malformed flag was silently removed")
		}
	}
	plugin := imported(t, `{"type":"ss","server":"example.invalid","port":443,"cipher":"aes-128-gcm","password":"fixture","plugin":"v2ray-plugin","plugin-opts":{"mode":"websocket","tls":true,"host":"example.invalid","skip-cert-verify":true}}`)
	if plugin.State != "passed" || plugin.VerificationEnabled != 0 || !strings.Contains(plugin.Nodes[0].Config, `"skip-cert-verify":true`) {
		t.Fatal("nested verification policy changed")
	}
	vmess := `{"add":"example.invalid","port":"443","id":"ca3ea70c-6845-4e3b-88d4-e4ac64ffbfa8","tls":"tls","allowInsecure":%s}`
	for _, flag := range []string{`true`, `"true"`, `1`, `"1"`} {
		input := strings.Replace(vmess, "%s", flag, 1)
		if p := imported(t, "vmess://"+base64.RawStdEncoding.EncodeToString([]byte(input))); p.State != "passed" || p.VerificationEnabled != 0 {
			t.Fatal("VMess certificate policy changed")
		}
	}
}

func TestSkipCertificatePolicyDoesNotMaskActualImportRejection(t *testing.T) {
	for _, skip := range []string{"true", "false"} {
		p := imported(t, `{"type":"hysteria2","server":"example.invalid","port":443,"password":"fixture","skip-cert-verify":`+skip+`,"handshake-timeout":"invalid"}`)
		if p.State == "passed" || p.Rejections["unsupported_fields"] != 1 || p.Rejections["tls_policy"] != 0 {
			t.Fatalf("certificate policy masked actual rejection: %v", p.Rejections)
		}
	}
	p := imported(t, `{"type":"tuic","server":"example.invalid","port":443,"uuid":"ca3ea70c-6845-4e3b-88d4-e4ac64ffbfa8","password":"fixture","disable-sni":true}`)
	if p.State != "passed" || len(p.Nodes) != 1 {
		t.Fatal("native TUIC option rejected")
	}
}

func TestCommonAnyTLSSubscriptionOptionsAndQuotedPort(t *testing.T) {
	input := `proxies:
 - {name: HK, type: anytls, server: example.invalid, port: "443", password: fixture, udp: true, tfo: false, mptcp: false, idle-session-check-interval: 30, idle-session-timeout: 30, min-idle-session: 0}
`
	p := imported(t, input)
	if p.State != "passed" || len(p.Nodes) != 1 {
		t.Fatal("common AnyTLS fields rejected")
	}
	for _, value := range []string{"-1", "999999"} {
		if imported(t, strings.Replace(input, "min-idle-session: 0", "min-idle-session: "+value, 1)).State == "passed" {
			t.Fatal("unbounded AnyTLS session option accepted")
		}
	}
}

func TestUnsupportedSubscriptionFormatsHaveSpecificErrors(t *testing.T) {
	for _, item := range []struct{ input, code string }{{"<!doctype html><html>login</html>", "subscription_html"}, {`{"outbounds":[{"type":"anytls"}]}`, "singbox_format"}, {"proxy-providers: {remote: {url: 'https://must-not-fetch.invalid/token'}}", "provider_only"}} {
		if got := imported(t, item.input); got.Code != item.code {
			t.Fatalf("wrong error code: %s", got.Code)
		}
	}
}

func TestShareSubscriptionsRetainInputLimitsAndPolicies(t *testing.T) {
	// Past the node limit only the excess is dropped and counted.
	many := imported(t, strings.Repeat("anytls://x@example.invalid:443\n", maxImportNodes+1))
	if len(many.Nodes) != maxImportNodes || many.Rejections["resource_limit"] != 1 {
		t.Fatalf("share list limit: nodes=%d rejections=%v", len(many.Nodes), many.Rejections)
	}
	for _, input := range []string{"anytls://x@example.invalid:443?dialer-proxy=DIRECT", "anytls://x@example.invalid:443#trusted%0Aforged"} {
		if imported(t, input).State == "passed" {
			t.Fatal("unsafe or oversized share subscription accepted")
		}
	}
	p := imported(t, `{"type":"anytls","server":"example.invalid","port":443,"password":"https://not-a-share.invalid"}`)
	if p.State != "passed" {
		t.Fatal("URL in a password was mistaken for a subscription list")
	}
}

func TestYAMLMappingBudgetPrecedesQuadraticDuplicateChecking(t *testing.T) {
	var input strings.Builder
	input.WriteString("proxies: [{type: anytls, server: example.invalid, port: 443, password: fixture}]\n")
	for index := 0; index < 1000; index++ {
		input.WriteString("key" + strconv.Itoa(index) + ": value\n")
	}
	if imported(t, input.String()).Code != "profile_invalid" {
		t.Fatal("oversized YAML mapping accepted")
	}
}

func TestImportIssuesIdentifyVLESSFieldsWithoutEchoingSecrets(t *testing.T) {
	const base = `"type":"vless","server":"private.invalid","port":443,"uuid":"ca3ea70c-6845-4e3b-88d4-e4ac64ffbfa8","tls":true`
	p := imported(t, `{"proxies":[{`+base+`,"certificate":"/private.pem"},{`+base+`,"skip-cert-verify":"private-value","private-token-secret":"private-value"},{`+base+`,"ws-opts":{"headers":{"Host":"bad\r\nsecret"}}}]}`)
	if p.Unsupported != 3 || p.FieldIssues["vless.certificate"] != 1 || p.FieldIssues["vless.skip-cert-verify"] != 1 || p.FieldIssues["vless.ws-opts"] != 1 {
		t.Fatalf("missing field details: %v", p.FieldIssues)
	}
	encoded, _ := json.Marshal(p)
	for _, private := range []string{"private.invalid", "private-token-secret", "private-value", "ca3ea70c"} {
		if strings.Contains(string(encoded), private) {
			t.Fatal("failed profile leaked secrets")
		}
	}
	p = imported(t, `{"type":"vless","server":"example.invalid","port":0,"uuid":"ca3ea70c-6845-4e3b-88d4-e4ac64ffbfa8","tls":true}`)
	if p.Rejections["invalid_parameters"] != 1 || p.FieldIssues["vless.parameters"] != 1 {
		t.Fatal("constructor failure falsely blamed on unknown fields")
	}
}

func TestVLESSLegacyPlaceholdersDoNotDiscardThirtyTwoNodes(t *testing.T) {
	var nodes []map[string]any
	for i := 0; i < 48; i++ {
		node := map[string]any{"name": "fixture-" + strconv.Itoa(i), "server": "example.invalid", "port": 443, "skip-cert-verify": true}
		if i < 16 {
			node["type"] = "hysteria2"
			node["password"] = "fixture-password"
		} else {
			node["type"] = "vless"
			node["uuid"] = "ca3ea70c-6845-4e3b-88d4-e4ac64ffbfa8"
			node["tls"] = true
			node["alterId"] = 0
			node["cipher"] = "auto"
			if i%2 == 0 {
				node["alterId"] = "0"
			}
		}
		nodes = append(nodes, node)
	}
	root := map[string]any{"proxies": nodes}
	jsonText, _ := json.Marshal(root)
	yamlText, _ := yaml.Marshal(root)
	for _, text := range []string{string(jsonText), string(yamlText)} {
		p := imported(t, text)
		if p.State != "passed" || len(p.Nodes) != 48 || p.Unsupported != 0 || len(p.FieldIssues) != 0 {
			t.Fatalf("mixed subscription still loses nodes: total=%d rejected=%d fields=%v", len(p.Nodes), p.Unsupported, p.FieldIssues)
		}
		vless := 0
		for _, n := range p.Nodes {
			if decoded(t, ValidateNode(n.Config)).Code != "config_valid_not_connected" {
				t.Fatal("normalized node invalid")
			}
			if n.Type == "vless" {
				vless++
				var config map[string]any
				_ = json.Unmarshal([]byte(n.Config), &config)
				if _, ok := config["alterId"]; ok {
					t.Fatal("legacy alterId leaked into VLESS runtime")
				}
				if _, ok := config["cipher"]; ok {
					t.Fatal("legacy cipher leaked into VLESS runtime")
				}
				if config["tls"] != true || config["skip-cert-verify"] != true {
					t.Fatal("configured TLS policy changed")
				}
			}
		}
		if vless != 32 {
			t.Fatal("32 VLESS nodes not retained")
		}
	}
}

func TestVLESSPlaceholdersMatchPinnedMihomoAndKeepSecurityBoundary(t *testing.T) {
	const base = `"type":"vless","server":"example.invalid","port":443,"uuid":"ca3ea70c-6845-4e3b-88d4-e4ac64ffbfa8","tls":true,"servername":"example.invalid","encryption":"none","network":"ws","ws-opts":{"path":"/connect","headers":{"Host":"example.invalid"}}`
	for _, cipher := range []string{"auto", "none", "aes-128-gcm", ""} {
		text := `{` + base + `,"alterId":0,"cipher":"` + cipher + `"}`
		var original map[string]any
		_ = json.Unmarshal([]byte(text), &original)
		original["name"] = "fixture-raw"
		upstream, err := adapter.ParseProxy(original)
		if err != nil {
			t.Fatalf("pinned mihomo does not accept fixture placeholders: %v", err)
		}
		_ = upstream.Close()
		p := imported(t, text)
		if len(p.Nodes) != 1 {
			t.Fatal("valid VLESS placeholders rejected")
		}
		var actual map[string]any
		_ = json.Unmarshal([]byte(p.Nodes[0].Config), &actual)
		delete(original, "alterId")
		delete(original, "cipher")
		delete(original, "name")
		delete(actual, "name")
		want, _ := json.Marshal(original)
		got, _ := json.Marshal(actual)
		if string(want) != string(got) {
			t.Fatal("real VLESS options changed")
		}
	}
	for _, extra := range []string{`"dialer-proxy":"missing-private-group"`, `"certificate":"C:/private.pem"`} {
		if imported(t, `{`+base+`,"alterId":0,"cipher":"auto",`+extra+`}`).State == "passed" {
			t.Fatal("unsafe or ambiguous VLESS input accepted")
		}
	}
	// VLESS does not consume VMess template fields. Their values cannot affect
	// transport behavior and must not survive in the effective runtime config.
	for _, extra := range []string{`"alterId":{}`, `"alterId":true`, `"alterId":-1`, `"alterId":"invalid"`, `"cipher":[]`, `"cipher":null`, `"cipher":"forged\nfield"`} {
		p := imported(t, `{`+base+`,`+extra+`}`)
		if len(p.Nodes) != 1 || strings.Contains(p.Nodes[0].Config, `"alterId"`) || strings.Contains(p.Nodes[0].Config, `"cipher"`) {
			t.Fatal("unused VLESS template field rejected or retained")
		}
	}
	for _, text := range []string{
		`{"type":"vless","server":"example.invalid","port":443,"uuid":"ca3ea70c-6845-4e3b-88d4-e4ac64ffbfa8","tls":false,"alterId":0,"cipher":"auto"}`,
		`{"type":"vmess","server":"example.invalid","port":443,"uuid":"ca3ea70c-6845-4e3b-88d4-e4ac64ffbfa8","alterId":1,"cipher":"auto"}`,
		`{"type":"vmess","server":"example.invalid","port":443,"uuid":"ca3ea70c-6845-4e3b-88d4-e4ac64ffbfa8","alterId":0,"cipher":"none"}`,
	} {
		if imported(t, text).State != "passed" {
			t.Fatal("explicit native TLS or VMess policy rejected")
		}
	}
}
