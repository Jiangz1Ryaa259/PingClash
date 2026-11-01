// SPDX-License-Identifier: GPL-3.0-only
package mobile

import (
	"encoding/json"
	"strconv"
	"strings"
	"testing"
)

func TestElevenHysteria2TemplateNodesKeepOfficialGroups(t *testing.T) {
	nodes := []any{}
	names := []any{}
	for i := 0; i < 11; i++ {
		name := "HY-" + strconv.Itoa(i)
		names = append(names, name)
		nodes = append(nodes, map[string]any{
			"name": name, "TYPE": "hysteria2", "server": "example.invalid", "port": 443,
			"password": "CANARY-PASSWORD-1234", "skip_cert_verify": true,
			"up": "100 Mbps", "down": 200, "client_fingerprint": "chrome",
			"template-metadata": map[string]any{"private": "CANARY-UNUSED-1234"},
		})
	}
	raw, _ := json.Marshal(map[string]any{"proxies": nodes, "proxy-groups": []any{
		map[string]any{"name": "Official", "type": "select", "proxies": []any{"Auto", "Fallback"}},
		map[string]any{"name": "Auto", "type": "url-test", "proxies": names},
		map[string]any{"name": "Fallback", "type": "fallback", "proxies": names},
	}})
	p := imported(t, string(raw))
	if len(p.Nodes) != 11 || p.Unsupported != 0 || p.GroupsUnsupported != 0 || len(p.Groups) != 3 {
		t.Fatalf("template nodes rejected: %s / %v / %v", p.Code, p.Rejections, p.FieldIssues)
	}
	for _, group := range p.Groups {
		if !group.Supported || group.Unavailable != 0 {
			t.Fatal("official group quarantined valid template members")
		}
	}
	for _, node := range p.Nodes {
		if strings.Contains(node.Config, "CANARY-UNUSED") || strings.Contains(node.Config, "skip_cert_verify") {
			t.Fatal("unused template data or aliases reached effective node config")
		}
		if decoded(t, ValidateNode(node.Config)).Code != "config_valid_not_connected" {
			t.Fatal("normalized import failed the private runtime boundary")
		}
	}
}

func TestImportedAliasesNeverRemoveRoutingOrFileGuards(t *testing.T) {
	base := `"type":"hysteria2","name":"HY","server":"example.invalid","port":443,"password":"fixture"`
	for _, option := range []string{
		`"skip-cert-verify":false,"skip_cert_verify":true`,
		`"certificate":"/private.pem"`,
		`"private_key":"/private.key"`,
		`"dialer_proxy":"missing-member"`,
		`"dialer_chain":[]`,
		`"smux":{"enabled":true,"Enabled":false}`,
	} {
		if p := imported(t, "{"+base+","+option+"}"); len(p.Nodes) != 0 {
			t.Fatal("ambiguous alias, local file or routing override accepted")
		}
	}
	vmess := imported(t, `{"type":"vmess","name":"VM","server":"example.invalid","port":443,"uuid":"00000000-0000-4000-8000-000000000001","alterId":7,"cipher":"auto","ws-opts":{"headers":{"Host":"example.org"}}}`)
	if len(vmess.Nodes) != 1 || !strings.Contains(vmess.Nodes[0].Config, `"alterId":7`) ||
		!strings.Contains(vmess.Nodes[0].Config, `"Host":"example.org"`) {
		t.Fatal("case-sensitive native tag or header data changed")
	}
}
