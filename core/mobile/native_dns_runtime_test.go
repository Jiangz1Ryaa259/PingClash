// SPDX-License-Identifier: GPL-3.0-only
package mobile

import (
	"context"
	"encoding/json"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"net/netip"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	C "github.com/metacubex/mihomo/constant"
	"github.com/metacubex/sing/common/buf"
	M "github.com/metacubex/sing/common/metadata"
	N "github.com/metacubex/sing/common/network"
	"github.com/miekg/dns"
)

func nativeDNSFixture(t *testing.T, ip string) (string, *atomic.Int32) {
	t.Helper()
	conn, e := net.ListenPacket("udp", "127.0.0.1:0")
	if e != nil {
		t.Fatal(e)
	}
	count := new(atomic.Int32)
	server := &dns.Server{PacketConn: conn, Handler: dns.HandlerFunc(func(w dns.ResponseWriter, q *dns.Msg) {
		count.Add(1)
		answer := new(dns.Msg)
		answer.SetReply(q)
		if q.Question[0].Qtype == dns.TypeA {
			answer.Answer = []dns.RR{&dns.A{Hdr: dns.RR_Header{Name: q.Question[0].Name, Rrtype: dns.TypeA, Class: dns.ClassINET, Ttl: 30}, A: net.ParseIP(ip)}}
		}
		_ = w.WriteMsg(answer)
	})}
	go func() { _ = server.ActivateAndServe() }()
	t.Cleanup(func() { _ = server.Shutdown(); _ = conn.Close() })
	return "udp://" + conn.LocalAddr().String(), count
}

func TestNativeFakeIPUDPAndSessionCleanup(t *testing.T) {
	echo, e := net.ListenPacket("udp", "127.0.0.1:0")
	if e != nil {
		t.Fatal(e)
	}
	defer echo.Close()
	go func() {
		data := make([]byte, 128)
		n, from, e := echo.ReadFrom(data)
		if e == nil {
			_, _ = echo.WriteTo(data[:n], from)
		}
	}()
	upstream, _ := nativeDNSFixture(t, "127.0.0.1")
	s := nativeDNSSession(t, map[string]any{"dns": map[string]any{"enable": true, "enhanced-mode": "fake-ip", "nameserver": []string{upstream}}})
	ip := queryNativeDNS(t, s, "udp.invalid")
	destination := M.Socksaddr{Addr: ip, Port: uint16(echo.LocalAddr().(*net.UDPAddr).Port)}
	writer := captureWriter{result: make(chan []byte, 1)}
	packet := buf.NewSize(16)
	_, _ = packet.Write([]byte("native-udp-ok"))
	s.NewPacket(s.ctx, netip.MustParseAddrPort("172.29.0.2:42000"), packet, M.Metadata{Source: M.Socksaddr{Addr: netip.MustParseAddr("172.29.0.2"), Port: 42000}, Destination: destination}, func(N.PacketConn) N.PacketWriter { return writer })
	select {
	case result := <-writer.result:
		if string(result) != "native-udp-ok" {
			t.Fatal("UDP payload changed")
		}
	case <-time.After(3 * time.Second):
		t.Fatal("fake-IP UDP forwarding failed")
	}
	old := activeNativeDNS.Swap(s.nativeDNS)
	defer activeNativeDNS.Store(old)
	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()
	ips, e := (runtimeDNSResolver{role: "main"}).LookupIPv4(ctx, "udp.invalid")
	if e != nil || len(ips) != 1 || !ips[0].IsLoopback() {
		t.Fatal("active DNS resolver not used")
	}
	s.close()
	if activeNativeDNS.Load() != nil {
		t.Fatal("closed session left active DNS state")
	}
}

func TestNativeDNSDocumentOrderAliasesAndHostCycles(t *testing.T) {
	raw := `proxies: [{name: n, type: socks5, server: 127.0.0.1, port: 9}]
x-servers: &servers ["1.1.1.1"]
dns:
  enable: true
  nameserver: *servers
  nameserver-policy:
    z.example.invalid: 1.0.0.1
    a.example.invalid: 1.1.1.1
`
	for i := 0; i < 3; i++ {
		var profile importedProfile
		if json.Unmarshal([]byte(ImportProfile(raw)), &profile) != nil || profile.State != "passed" {
			t.Fatal("ordered DNS import failed")
		}
		var root map[string]any
		_ = json.Unmarshal([]byte(profile.Definition), &root)
		fragment, _ := root[nativeDNSDocumentKey].(string)
		if strings.Index(fragment, "z.example.invalid") > strings.Index(fragment, "a.example.invalid") || strings.Contains(fragment, "*servers") {
			t.Fatal("DNS order/alias expansion lost")
		}
		raw = profile.Definition
	}
	for _, hosts := range []map[string]any{{"a.invalid": "a.invalid"}, {"a.invalid": "b.invalid", "b.invalid": "a.invalid"}, {"+.invalid": "a.invalid"}} {
		if boundedNativeHosts(hosts) {
			t.Fatal("cyclic host aliases accepted")
		}
	}
}

func nativeDNSSession(t *testing.T, root map[string]any) *vpnSession {
	t.Helper()
	root["proxies"] = []any{map[string]any{"name": "server", "type": "socks5", "server": "127.0.0.1", "port": 9}}
	definition, e := json.Marshal(root)
	if e != nil {
		t.Fatal(e)
	}
	options, e := json.Marshal(map[string]any{"mode": "rule", "definition": string(definition), "rules": []string{"MATCH,DIRECT"}})
	if e != nil {
		t.Fatal(e)
	}
	s, e := newSessionConfigured(`{"type":"socks5","server":"127.0.0.1","port":9}`, "1.1.1.1", string(options))
	if e != nil {
		t.Fatal(e)
	}
	t.Cleanup(s.close)
	return s
}

func queryNativeDNS(t *testing.T, s *vpnSession, host string) netip.Addr {
	t.Helper()
	query := new(dns.Msg)
	query.SetQuestion(dns.Fqdn(host), dns.TypeA)
	packet, _ := query.Pack()
	response, e := s.exchangeDNS(packet)
	if e != nil {
		t.Fatal(e)
	}
	var answer dns.Msg
	if answer.Unpack(response) != nil || len(answer.Answer) != 1 {
		t.Fatal("DNS answer missing")
	}
	return netip.MustParseAddr(answer.Answer[0].(*dns.A).A.String())
}

func TestNativeDNSPolicyHostsAndPrivateRuleSet(t *testing.T) {
	main, mainCount := nativeDNSFixture(t, "192.0.2.1")
	policy, policyCount := nativeDNSFixture(t, "192.0.2.2")
	sets, _ := json.Marshal(map[string]storedRuleSet{"local-domains": {Behavior: "domain", Entries: []string{"+.policy.invalid"}}})
	s := nativeDNSSession(t, map[string]any{
		"dns":   map[string]any{"enable": true, "nameserver": []string{main}, "nameserver-policy": map[string]any{"rule-set:local-domains": []string{policy}}},
		"hosts": map[string]any{"host.invalid": "192.0.2.3"}, providerRuleDataKey: string(sets),
		"mixed-port": 12345, "external-controller": "0.0.0.0:12346", "listeners": []any{map[string]any{"type": "http", "port": 12347}},
	})
	for _, tc := range []struct{ host, ip string }{{"a.main.invalid", "192.0.2.1"}, {"a.policy.invalid", "192.0.2.2"}, {"host.invalid", "192.0.2.3"}} {
		if got := queryNativeDNS(t, s, tc.host); got.String() != tc.ip {
			t.Fatalf("native policy result incorrect: %s", got)
		}
	}
	if mainCount.Load() != 1 || policyCount.Load() != 1 {
		t.Fatal("hosts/policy did not use the expected resolver")
	}
	// A private DNS instance must not install a process-global mapper/resolver.
	if s.nativeDNS == nil {
		t.Fatal("native DNS was not installed in session")
	}
}

func TestNativeFakeIPRestoresTCPDestinationAndRejectsUnknownMapping(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { _, _ = io.WriteString(w, "native-dns-ok") }))
	defer server.Close()
	addr := server.Listener.Addr().(*net.TCPAddr)
	upstream, _ := nativeDNSFixture(t, "127.0.0.1")
	s := nativeDNSSession(t, map[string]any{"dns": map[string]any{"enable": true, "enhanced-mode": "fake-ip", "fake-ip-range": "198.18.0.1/16", "nameserver": []string{upstream}, "fallback-filter": map[string]any{"geoip": false}}})
	ip := queryNativeDNS(t, s, "test.invalid")
	if !s.nativeDNS.mapper.IsFakeIP(ip) {
		t.Fatal("native fake IP not generated")
	}
	meta := &C.Metadata{Host: "", DstIP: ip, DstPort: uint16(addr.Port)}
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	if e := s.restoreDNSDestination(ctx, meta, true); e != nil || meta.Host != "test.invalid" || !meta.DstIP.IsLoopback() {
		t.Fatal("fake destination was not restored")
	}
	unknown := &C.Metadata{DstIP: netip.MustParseAddr("198.18.200.201"), DstPort: 443}
	if s.restoreDNSDestination(ctx, unknown, true) == nil {
		t.Fatal("unmapped fake IP was allowed to escape")
	}
	client, inbound := net.Pipe()
	defer client.Close()
	done := make(chan error, 1)
	go func() {
		done <- s.NewConnection(ctx, inbound, M.Metadata{Source: M.Socksaddr{Addr: netip.MustParseAddr("172.29.0.2"), Port: 40500}, Destination: M.Socksaddr{Addr: ip, Port: uint16(addr.Port)}})
	}()
	_ = client.SetDeadline(time.Now().Add(3 * time.Second))
	_, _ = io.WriteString(client, "GET / HTTP/1.1\r\nHost: test.invalid\r\nConnection: close\r\n\r\n")
	data, e := io.ReadAll(client)
	if e != nil || !strings.Contains(string(data), "native-dns-ok") {
		t.Fatal("real TCP flow did not reach server via fake-IP mapping")
	}
	if e := <-done; e != nil {
		t.Fatal(e)
	}
}
