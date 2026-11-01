// SPDX-License-Identifier: GPL-3.0-only
package mobile

import (
	"bytes"
	"encoding/json"
	"fmt"
	MN "github.com/metacubex/mihomo/common/net"
	C "github.com/metacubex/mihomo/constant"
	"go.yaml.in/yaml/v3"
	"io"
	"net"
	"net/netip"
	"reflect"
	"strings"
	"testing"
	"time"
)

func TestDNSNodeExportPreservesSixteenYAMLForms(t *testing.T) {
	forms := []string{
		"dns:\n  enable: true\n  nameserver: [1.1.1.1]\n",
		"dns: {enable: true, nameserver: [1.1.1.1]}\n",
		"'dns': {enable: true, nameserver: ['1.1.1.1']}\n",
		"\"dns\": {enable: true, nameserver: [\"1.1.1.1\"]}\n",
		"? dns\n: {enable: true, nameserver: [1.1.1.1]}\n",
		"dns: !!map {enable: true, nameserver: [1.1.1.1]}\n",
		"x-dns: &dns {enable: true, nameserver: [1.1.1.1]}\ndns: *dns\n",
		"x-ns: &ns [1.1.1.1, 1.0.0.1]\ndns: {enable: true, nameserver: *ns}\n",
		"x-default: &default {enable: true, nameserver: [1.1.1.1]}\ndns: {<<: *default, ipv6: false}\n",
		"x-a: &a {enable: true}\nx-b: &b {nameserver: [1.1.1.1]}\ndns: {<<: [*a, *b]}\n",
		"dns: # inline comment\n  nameserver:\n    - 1.1.1.1 # comment\n  enable: true\n",
		"dns:\n  enable: true\n  nameserver:\n  - >-\n    https://dns.example.invalid/dns-query\n",
		"dns:\n  enable: true\n  nameserver: [1.1.1.1]\n  nameserver-policy:\n    ? '+.z.invalid'\n    : 1.1.1.1\n    '+.a.invalid': 1.0.0.1\n",
		"dns: {enable: true, nameserver: [1.1.1.1], nameserver-policy: {'+.z.invalid': 1.1.1.1, '+.a.invalid': 1.0.0.1}}\n",
		"x-policy: &policy {'+.z.invalid': [1.1.1.1], '+.a.invalid': 1.0.0.1}\ndns: {enable: true, nameserver: [1.1.1.1], nameserver-policy: *policy}\n",
		"dns: {enable: true, nameserver: [1.1.1.1], fake-ip-ttl: 9007199254740993}\n",
	}
	for i, form := range forms {
		t.Run(fmt.Sprintf("form-%02d", i+1), func(t *testing.T) {
			text := "proxies: [{name: n, type: socks5, server: 127.0.0.1, port: 1}]\n" + form
			var original map[string]any
			if yaml.Unmarshal([]byte(text), &original) != nil {
				t.Fatal("invalid fixture")
			}
			p := imported(t, text)
			export := ExportConfiguration(p.Definition)
			if !json.Valid([]byte(export)) || strings.Contains(export, "pingclash-") {
				t.Fatal("export contains private metadata or invalid JSON")
			}
			var actual map[string]any
			if yaml.Unmarshal([]byte(export), &actual) != nil {
				t.Fatal("export decoding failed")
			}
			if !reflect.DeepEqual(original["dns"], actual["dns"]) {
				t.Fatal("DNS semantics changed")
			}
			if strings.Contains(form, "z.invalid") && strings.Index(export, "z.invalid") > strings.Index(export, "a.invalid") {
				t.Fatal("policy priority changed")
			}
		})
	}
}

func TestExportRebuildsInlineRulesWithoutPrivateBridgeFields(t *testing.T) {
	packed, _ := json.Marshal(map[string]storedRuleSet{"policy": {Behavior: "classical", Entries: []string{"DOMAIN-SUFFIX,example.invalid", "IP-CIDR,192.0.2.0/24"}}})
	root := map[string]any{"proxies": []any{}, providerRuleDataKey: string(packed), "pingclash-selections": map[string]string{"Main": "n"}}
	b, _ := json.Marshal(root)
	export := ExportConfiguration(string(b))
	if strings.Contains(export, "pingclash-") || !strings.Contains(export, "DOMAIN-SUFFIX,example.invalid") || !strings.Contains(export, "\"type\":\"inline\"") {
		t.Fatal("standard inline export lost provider data")
	}
}

func TestClassicalDNSAndSnifferKeepPrivateMatchersAlive(t *testing.T) {
	packed, _ := json.Marshal(map[string]storedRuleSet{"domains": {Behavior: "classical", Entries: []string{"DOMAIN-SUFFIX,policy.invalid"}}})
	main, _ := nativeDNSFixture(t, "192.0.2.1")
	policy, _ := nativeDNSFixture(t, "192.0.2.2")
	s := nativeDNSSession(t, map[string]any{providerRuleDataKey: string(packed), "dns": map[string]any{"enable": true, "nameserver": []string{main}, "nameserver-policy": map[string]any{"rule-set:domains": policy}}})
	if queryNativeDNS(t, s, "a.policy.invalid").String() != "192.0.2.2" {
		t.Fatal("classical DNS policy was emptied")
	}
	if e := s.startFullNative(); e != nil {
		t.Fatal(e)
	}
	if queryNativeDNS(t, s, "native.policy.invalid").String() != "192.0.2.2" {
		t.Fatal("native executor lost classical DNS policy")
	}
	definition := map[string]any{providerRuleDataKey: string(packed), "sniffer": map[string]any{"enable": true, "force-domain": []string{"rule-set:domains"}, "sniff": map[string]any{"HTTP": map[string]any{"ports": []string{"80"}}}}}
	b, _ := json.Marshal(definition)
	dispatcher, e := newNativeSniffer(string(b))
	if e != nil {
		t.Fatal(e)
	}
	// The dispatcher is used after its temporary native providers were closed.
	conn, peer := net.Pipe()
	defer conn.Close()
	defer peer.Close()
	_ = conn.SetDeadline(time.Now().Add(time.Second))
	go func() { _, _ = io.WriteString(peer, "GET / HTTP/1.1\r\nHost: sniffed.invalid\r\n\r\n") }()
	m := &C.Metadata{Host: "a.policy.invalid", DstIP: netip.MustParseAddr("192.0.2.1"), DstPort: 80, NetWork: C.TCP}
	if !dispatcher.TCPSniff(MN.NewBufferedConn(conn), m) || m.SniffHost != "sniffed.invalid" {
		t.Fatal("classical sniffer policy was lost")
	}
}

func TestASNRejectsInvalidUnsignedPayloadBeforeDatabaseLookup(t *testing.T) {
	for _, value := range []string{"-1", "not-a-number", "4294967296", "1-3", ""} {
		if _, code := compileNativeRule("IP-ASN", value, "PROXY", nil); code != "rule_invalid" {
			t.Fatal("ASN validation bypass", value, code)
		}
	}
}

func TestNativeNodeLargeArraysRetainGlobalBudget(t *testing.T) {
	good := `{"type":"direct","ip-version":"dual","test":[` + strings.Repeat(`"x",`, 63) + `"x"]}`
	if _, e := strictNodeObject(good); e != nil {
		t.Fatal("legitimate bounded array rejected")
	}
	paths := []string{}
	for i := 0; i < 64; i++ {
		paths = append(paths, fmt.Sprintf("/path-%d", i))
	}
	node := map[string]any{"type": "vmess", "server": "127.0.0.1", "port": 443, "uuid": "ca3ea70c-6845-4e3b-88d4-e4ac64ffbfa8", "alterId": 0, "cipher": "auto", "network": "http", "http-opts": map[string]any{"method": "GET", "path": paths, "headers": map[string]any{"Host": []any{"example.test"}}}}
	encoded, _ := json.Marshal(node)
	if decoded(t, ValidateNode(string(encoded))).State != "passed" {
		t.Fatal("native transport rejected valid bounded path list")
	}
	bad := `{"type":"direct","test":[` + strings.Repeat(`"x",`, 512) + `"x"]}`
	if _, e := strictNodeObject(bad); e == nil {
		t.Fatal("array budget removed")
	}
	if bytes.Contains([]byte(ExportConfiguration(`not JSON CANARY-PASSWORD`)), []byte("CANARY")) {
		t.Fatal("export failure leaked input")
	}
}
