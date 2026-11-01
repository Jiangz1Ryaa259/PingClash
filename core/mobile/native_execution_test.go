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
	"reflect"
	"strings"
	"testing"
	"time"

	"github.com/metacubex/mihomo/component/resolver"
	"github.com/metacubex/mihomo/listener"
	"github.com/metacubex/sing/common/buf"
	M "github.com/metacubex/sing/common/metadata"
	N "github.com/metacubex/sing/common/network"
	"github.com/miekg/dns"
)

func TestFullNativeUDPAndFakeIPDNSUseNativeRuntime(t *testing.T) {
	upstream, _ := nativeDNSFixture(t, "192.0.2.55")
	root := map[string]any{
		"proxies": []any{map[string]any{"name": "unused", "type": "socks5", "server": "127.0.0.1", "port": 1}},
		"rules":   []string{"MATCH,DIRECT"},
		"dns": map[string]any{"enable": true, "nameserver": []string{"tcp://" + upstream},
			"default-nameserver": []string{"127.0.0.1"}, "enhanced-mode": "fake-ip", "fake-ip-range": "198.18.0.1/16"},
	}
	b, _ := json.Marshal(root)
	p := imported(t, string(b))
	options := defaultRuntimeOptions()
	options.Mode = "rule"
	options.Rules = p.Rules
	options.Definition = p.Definition
	encoded, _ := json.Marshal(options)
	s, e := newSessionConfigured(`{"type":"socks5","server":"127.0.0.1","port":1}`, "127.0.0.1:53", string(encoded))
	if e != nil {
		t.Fatal(e)
	}
	if e = s.startFullNative(); e != nil {
		t.Fatal(e)
	}
	defer s.close()
	query := new(dns.Msg)
	query.SetQuestion("native-fake.example.invalid.", dns.TypeA)
	wire, _ := query.Pack()
	answer, e := s.exchangeDNS(wire)
	if e != nil {
		t.Fatal(e)
	}
	var reply dns.Msg
	if reply.Unpack(answer) != nil || len(reply.Answer) == 0 {
		t.Fatal("native fake-IP response missing")
	}
	ip := netip.AddrFrom4([4]byte(reply.Answer[0].(*dns.A).A.To4()))
	if !netip.MustParsePrefix("198.18.0.0/16").Contains(ip) {
		t.Fatal("DNS did not use native fake-IP service")
	}
	echo, e := net.ListenUDP("udp", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if e != nil {
		t.Fatal(e)
	}
	defer echo.Close()
	go func() {
		packet := make([]byte, 1024)
		n, peer, e := echo.ReadFromUDP(packet)
		if e == nil {
			_, _ = echo.WriteToUDP(packet[:n], peer)
		}
	}()
	writer := captureWriter{result: make(chan []byte, 1)}
	packet := buf.NewSize(64)
	_, _ = packet.Write([]byte("native-udp-return"))
	s.NewPacket(context.Background(), netip.MustParseAddrPort("192.0.2.2:50502"), packet,
		M.Metadata{Source: M.ParseSocksaddr("192.0.2.2:50502"), Destination: M.ParseSocksaddr(echo.LocalAddr().String())},
		func(N.PacketConn) N.PacketWriter { return writer })
	select {
	case result := <-writer.result:
		if string(result) != "native-udp-return" {
			t.Fatal("UDP payload changed")
		}
	case <-time.After(4 * time.Second):
		t.Fatal("native UDP forwarding failed")
	}
	if s.up.Load() == 0 || s.down.Load() == 0 {
		t.Fatal("native traffic counters did not update")
	}
}

func TestFullNativePipelineUsesRematchSniffAndPreservesOpenStreamAcrossReload(t *testing.T) {
	release := make(chan struct{})
	entered := make(chan struct{})
	target := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "text/plain")
		_, _ = io.WriteString(w, "native-before\n")
		w.(http.Flusher).Flush()
		close(entered)
		<-release
		_, _ = io.WriteString(w, "native-after\n")
	}))
	defer target.Close()
	originalResolver := resolver.DefaultResolver
	text := `proxies:
 - {name: unused, type: socks5, server: 127.0.0.1, port: 1}
 - {name: actual, type: direct, udp: true}
 - {name: rematch, type: rematch, target-sub-rule: media}
proxy-groups: [{name: Main, type: select, proxies: [unused]}]
rules: ['DOMAIN,native.full.invalid,rematch','MATCH,REJECT']
sub-rules: {media: ['DOMAIN,native.full.invalid,actual','MATCH,REJECT']}
hosts: {native.full.invalid: 127.0.0.1}
dns: {enable: true, nameserver: ['tcp://127.0.0.1:1'], use-hosts: true}
sniffer: {enable: true, parse-pure-ip: true, sniff: {HTTP: {ports: ['1-65535'], override-destination: true}}}
`
	p := imported(t, text)
	var doc map[string]any
	_ = json.Unmarshal([]byte(p.Definition), &doc)
	// Test the application-owned boundary even if a caller bypasses import.
	doc["mixed-port"] = 45678
	doc["external-controller"] = "0.0.0.0:45679"
	doc["dns"].(map[string]any)["listen"] = "0.0.0.0:45680"
	b, _ := json.Marshal(doc)
	options := defaultRuntimeOptions()
	options.Mode = "rule"
	options.Rules = p.Rules
	options.Definition = string(b)
	options.InspectConnections = true
	encoded, _ := json.Marshal(options)
	config := `{"type":"socks5","server":"127.0.0.1","port":1}`
	s, e := newSessionConfigured(config, "127.0.0.1:53", string(encoded))
	if e != nil {
		t.Fatal(e)
	}
	if e = s.startFullNative(); e != nil {
		t.Fatal(e)
	}
	vpnState.Lock()
	if vpnState.session != nil {
		vpnState.Unlock()
		t.Fatal("unexpected live test session")
	}
	vpnState.session = s
	vpnState.Unlock()
	defer StopVPN()
	if !nativeHasNoDeviceServices(s.native.cfg) {
		t.Fatal("untrusted directive escaped platform ownership")
	}
	ports := listener.GetPorts()
	if ports.MixedPort != 0 || ports.SocksPort != 0 || ports.Port != 0 || listener.GetTunConf().Enable {
		t.Fatal("native executor opened an extra inbound")
	}
	incoming, client := net.Pipe()
	defer client.Close()
	completed := make(chan error, 1)
	go func() {
		completed <- s.NewConnection(context.Background(), incoming, M.Metadata{Source: M.Socksaddr{Addr: netip.MustParseAddr("192.0.2.2"), Port: 50501}, Destination: M.ParseSocksaddr(target.Listener.Addr().String())})
	}()
	_ = client.SetDeadline(time.Now().Add(8 * time.Second))
	_, e = io.WriteString(client, "GET / HTTP/1.1\r\nHost: native.full.invalid\r\nConnection: close\r\n\r\n")
	if e != nil {
		t.Fatal(e)
	}
	select {
	case <-entered:
	case <-time.After(4 * time.Second):
		close(release)
		t.Fatal("native rematch/sniff path did not reach controlled server")
	}
	if rows := VPNConnections(); !strings.Contains(rows, "actual") || !strings.Contains(rows, "DOMAIN") {
		close(release)
		t.Fatal("native connection route/rule was not synchronized")
	}
	// A mode change must not kill this already-open stream or reset its root.
	options.Mode = "direct"
	updated, _ := json.Marshal(options)
	result := decoded(t, UpdateVPNWithOptions(config, "127.0.0.1:53", string(updated)))
	if result.State != "passed" {
		close(release)
		t.Fatal("native reload failed", result.Code)
	}
	close(release)
	body, e := io.ReadAll(client)
	if e != nil || !strings.Contains(string(body), "native-before") || !strings.Contains(string(body), "native-after") {
		t.Fatal("reload killed the native stream")
	}
	select {
	case e := <-completed:
		if e != nil {
			t.Fatal(e)
		}
	case <-time.After(time.Second):
		t.Fatal("native TCP handler leaked")
	}
	StopVPN()
	if !reflect.DeepEqual(originalResolver, resolver.DefaultResolver) {
		t.Fatal("stop retained subscription DNS in global probe resolver")
	}
}
