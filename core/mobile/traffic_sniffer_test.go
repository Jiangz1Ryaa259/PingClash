// SPDX-License-Identifier: GPL-3.0-only
package mobile

import (
	"bytes"
	"context"
	"encoding/binary"
	"encoding/json"
	C "github.com/metacubex/mihomo/constant"
	quic "github.com/metacubex/quic-go"
	M "github.com/metacubex/sing/common/metadata"
	"github.com/metacubex/tls"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"net/netip"
	"strings"
	"testing"
	"time"
)

func TestNativeQUICSnifferUsesRealClientInitialAndPreservesDatagram(t *testing.T) {
	listener, e := net.ListenUDP("udp", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if e != nil {
		t.Fatal(e)
	}
	defer listener.Close()
	ctx, cancel := context.WithTimeout(context.Background(), 4*time.Second)
	defer cancel()
	clientDone := make(chan struct{})
	go func() {
		defer close(clientDone)
		conn, _ := quic.DialAddr(ctx, listener.LocalAddr().String(),
			&tls.Config{ServerName: "quic-sniff.invalid", InsecureSkipVerify: true, NextProtos: []string{"fixture"}},
			&quic.Config{Versions: []quic.Version{quic.Version1}})
		if conn != nil {
			_ = conn.CloseWithError(0, "")
		}
	}()
	packet := make([]byte, 65535)
	_ = listener.SetReadDeadline(time.Now().Add(3 * time.Second))
	n, from, e := listener.ReadFromUDP(packet)
	if e != nil {
		t.Fatal(e)
	}
	packets := [][]byte{append([]byte(nil), packet[:n]...)}
	_ = listener.SetReadDeadline(time.Now().Add(150 * time.Millisecond))
	for len(packets) < 8 {
		n, _, e = listener.ReadFromUDP(packet)
		if e != nil {
			break
		}
		packets = append(packets, append([]byte(nil), packet[:n]...))
	}
	cancel()
	<-clientDone
	dispatcher, e := newNativeSniffer(`{"sniffer":{"enable":true,"parse-pure-ip":true,"sniff":{"QUIC":{"ports":["1-65535"],"override-destination":true}}}}`)
	if e != nil {
		t.Fatal(e)
	}
	s := &vpnSession{sniffer: dispatcher}
	flow := &udpFlow{queue: make(chan []byte, 8), source: M.ParseSocksaddr(from.String()), destination: M.ParseSocksaddr(listener.LocalAddr().String())}
	for _, p := range packets {
		flow.queue <- p
	}
	m := ruleMetadata("", netip.MustParseAddr("127.0.0.1"), uint16(listener.LocalAddr().(*net.UDPAddr).Port), "udp")
	staged := s.sniffUDP(context.Background(), flow, m)
	if m.Host != "quic-sniff.invalid" || len(staged) != len(packets) {
		t.Fatalf("QUIC host or datagrams lost: host=%q input=%d staged=%d", m.Host, len(packets), len(staged))
	}
	for i, p := range packets {
		if !bytes.Equal(staged[i], p) {
			t.Fatal("QUIC datagram changed")
		}
	}
}

func TestTrafficClassUsesIPv4IPv6HeadersAndBoundsExtensionTraversal(t *testing.T) {
	src := netip.MustParseAddr("192.0.2.10")
	dst := netip.MustParseAddr("198.51.100.10")
	ipv4 := make([]byte, 44)
	ipv4[0] = 0x45
	ipv4[1] = 46<<2 | 3
	ipv4[9] = 6
	binary.BigEndian.PutUint16(ipv4[2:4], uint16(len(ipv4)))
	copy(ipv4[12:16], src.AsSlice())
	copy(ipv4[16:20], dst.AsSlice())
	binary.BigEndian.PutUint16(ipv4[20:22], 12345)
	binary.BigEndian.PutUint16(ipv4[22:24], 443)
	var classes trafficClasses
	classes.observe(ipv4)
	flow := M.Metadata{Source: M.Socksaddr{Addr: src, Port: 12345}, Destination: M.Socksaddr{Addr: dst, Port: 443}}
	if classes.lookup(flow, "tcp") != 46 {
		t.Fatal("IPv4 DSCP includes ECN or was lost")
	}
	rule, code := compileNativeRule("DSCP", "46", "REJECT", nil)
	m := ruleMetadata("", dst, 443, "tcp")
	m.DSCP = classes.lookup(flow, "tcp")
	if code != "" {
		t.Fatal(code)
	}
	if ok, _ := rule.Match(m, C.RuleMatchHelper{}); !ok {
		t.Fatal("native DSCP rule did not consume observed header")
	}
	ipv6 := make([]byte, 52)
	ipv6[0] = 0x60 | 0xb
	ipv6[1] = 0xb0
	ipv6[6] = 0
	binary.BigEndian.PutUint16(ipv6[4:6], 12)
	ipv6[40] = 17
	copy(ipv6[8:24], netip.MustParseAddr("2001:db8::1").AsSlice())
	copy(ipv6[24:40], netip.MustParseAddr("2001:db8::2").AsSlice())
	binary.BigEndian.PutUint16(ipv6[48:50], 1111)
	binary.BigEndian.PutUint16(ipv6[50:52], 2222)
	key, dscp, ok := packetTrafficClass(ipv6)
	if !ok || dscp != 46 || key.protocol != 17 || key.destination.Port() != 2222 {
		t.Fatal("IPv6 traffic-class or extension parsing failed")
	}
	ipv6[41] = 255
	if _, _, ok := packetTrafficClass(ipv6); ok {
		t.Fatal("oversized extension accepted")
	}
	ipv4[6] = 0
	ipv4[7] = 1
	if _, _, ok := packetTrafficClass(ipv4); ok {
		t.Fatal("noninitial fragment treated as ports")
	}
	for i := 0; i < len(ipv6); i++ {
		_, _, _ = packetTrafficClass(ipv6[:i])
	}
}

func TestNativeTCPSnifferRoutesPureIPUsingHostWithoutLosingPayload(t *testing.T) {
	target := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { _, _ = w.Write([]byte("native-sniff-payload")) }))
	defer target.Close()
	definition := `{"proxies":[{"name":"unused","type":"socks5","server":"127.0.0.1","port":1}],"sniffer":{"enable":true,"parse-pure-ip":true,"sniff":{"HTTP":{"ports":["1-65535"],"override-destination":true}}}}`
	// Sniffed domain uses private hosts to resolve only to the controlled server.
	var doc map[string]any
	_ = json.Unmarshal([]byte(definition), &doc)
	doc["hosts"] = map[string]any{"sniff.invalid": "127.0.0.1"}
	doc["dns"] = map[string]any{"enable": true, "nameserver": []string{"tcp://127.0.0.1:1"}, "use-hosts": true}
	b, _ := json.Marshal(doc)
	options := defaultRuntimeOptions()
	options.Mode = "rule"
	options.Definition = string(b)
	options.Rules = []string{"DOMAIN,sniff.invalid,DIRECT", "MATCH,REJECT"}
	encoded, _ := json.Marshal(options)
	session, e := newSessionConfigured(`{"type":"socks5","server":"127.0.0.1","port":1}`, "127.0.0.1:53", string(encoded))
	if e != nil {
		t.Fatal(e)
	}
	defer session.close()
	incoming, client := net.Pipe()
	defer client.Close()
	finished := make(chan error, 1)
	go func() {
		finished <- session.NewConnection(context.Background(), incoming, M.Metadata{Destination: M.ParseSocksaddr(target.Listener.Addr().String())})
	}()
	_ = client.SetDeadline(time.Now().Add(4 * time.Second))
	_, e = io.WriteString(client, "GET / HTTP/1.1\r\nHost: sniff.invalid\r\nConnection: close\r\n\r\n")
	if e != nil {
		t.Fatal(e)
	}
	reply, e := io.ReadAll(client)
	if e != nil || !strings.Contains(string(reply), "native-sniff-payload") {
		t.Fatal("sniff routing or buffered first bytes lost")
	}
	select {
	case e := <-finished:
		if e != nil {
			t.Fatal(e)
		}
	case <-time.After(time.Second):
		t.Fatal("sniff connection leaked")
	}
}

func TestFileProvidersRequireExplicitBodiesAndNeverReadOperatingSystemPaths(t *testing.T) {
	text := `proxy-providers:
  local: {type: file, path: ./providers/local.yaml}
rule-providers:
  localrules: {type: file, path: ./rules/local.txt, behavior: domain, format: text}
proxy-groups: [{name: Official, type: select, use: [local]}]
rules: ['RULE-SET,localrules,Official','MATCH,DIRECT']`
	if !strings.Contains(PlanRemoteProviders(text), `"mode":"file"`) {
		t.Fatal("file plan rejected")
	}
	denied := resolveFixture(t, text, map[string]string{})
	if denied["state"] != "failed" {
		t.Fatal("missing explicit grant read a path")
	}
	resolved := resolveFixture(t, text, map[string]string{"proxy:local": `proxies: [{name: supplied, type: socks5, server: example.invalid, port: 1080}]`, "rule:localrules": "+.example.invalid"})
	if resolved["state"] != "passed" {
		t.Fatal("explicit local payload did not resolve")
	}
	p := imported(t, resolved["content"].(string))
	if len(p.Nodes) != 1 || p.GroupsUnsupported != 0 {
		t.Fatal("local graph was not preserved")
	}
	for _, path := range []string{"../secret", "/absolute/file", "C:/private", "a\\b", "a/../secret"} {
		if safeProviderBundlePath(path) {
			t.Fatal("unsafe logical bundle path accepted")
		}
	}
}
