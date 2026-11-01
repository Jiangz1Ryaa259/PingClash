// SPDX-License-Identifier: GPL-3.0-only
package mobile

import (
	"encoding/json"
	"net"
	"strings"
	"testing"
	"time"
)

func TestImportClashYAMLIsOfflineAndReportsUnsupportedNodes(t *testing.T) {
	input := `proxies:
  - {name: "HK 测试", type: anytls, server: example.invalid, port: 443, password: private-test, udp: true}
  - {name: "unsupported", type: made-up-protocol, server: example.invalid, port: 443}
proxy-providers:
  remote: {type: http, url: "https://must-not-fetch.invalid/private-token"}
rules: ["MATCH,DIRECT"]
`
	var profile importedProfile
	if err := json.Unmarshal([]byte(ImportProfile(input)), &profile); err != nil {
		t.Fatal(err)
	}
	if profile.State != "passed" || len(profile.Nodes) != 1 || profile.Unsupported != 1 || !profile.RoutingIgnored {
		t.Fatal("profile import did not preserve capability boundaries")
	}
	if !strings.Contains(profile.Nodes[0].Config, "private-test") || strings.Contains(profile.Nodes[0].Config, "private-token") {
		t.Fatal("node payload missing or remote provider was imported")
	}
}

func TestImportRejectsAliasesDuplicatesMultipleDocumentsAndOversize(t *testing.T) {
	for _, input := range []string{
		"proxies: &nodes []\nother: *nodes",
		"type: anytls\ntype: socks5\nserver: localhost\nport: 443\npassword: test",
		"type: anytls\nserver: localhost\nport: 443\npassword: test\n---\nother: document",
		strings.Repeat("x", 2*1024*1024+1),
		"[" + strings.Repeat("x,", 50001) + "x]",
	} {
		var profile importedProfile
		if json.Unmarshal([]byte(ImportProfile(input)), &profile) != nil || profile.Code != "profile_invalid" {
			t.Fatal("unsafe profile structure accepted")
		}
	}
}

func TestPreparedCancellationPreventsAnyProxyConnection(t *testing.T) {
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer listener.Close()
	if !PrepareProbe("prepared-cancel", 2000) {
		t.Fatal("reservation failed")
	}
	defer DiscardProbe("prepared-cancel")
	if !Cancel("prepared-cancel") {
		t.Fatal("pre-call cancellation failed")
	}
	config := asJSON(t, node{Type: "anytls", Server: "127.0.0.1", Port: listener.Addr().(*net.TCPAddr).Port, Password: "test"})
	reply := decoded(t, Probe(config, "http://127.0.0.1:1/", "prepared-cancel", 1000))
	if reply.State != "cancelled" {
		t.Fatalf("cancelled reservation executed: %+v", reply)
	}
	_ = listener.(*net.TCPListener).SetDeadline(time.Now().Add(100 * time.Millisecond))
	conn, err := listener.Accept()
	if err == nil {
		_ = conn.Close()
		t.Fatal("cancellation allowed a proxy connection")
	}
}

func TestUnusedPreparedRunsCanBeDiscarded(t *testing.T) {
	for i := 0; i < 5; i++ {
		if !PrepareProbe("discard-reservation", 1000) {
			t.Fatal("unused reservations leaked execution slots")
		}
		DiscardProbe("discard-reservation")
	}
	if Cancel("discard-reservation") {
		t.Fatal("discarded token remains active")
	}
}
