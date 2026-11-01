// SPDX-License-Identifier: GPL-3.0-only
package mobile

import (
	"context"
	"encoding/json"
	C "github.com/metacubex/mihomo/constant"
	"net/netip"
	"strings"
	"sync"
	"testing"
)

func TestHotRoutingKeepsTunnelCountersFakeIPAndOldFlows(t *testing.T) {
	s := nativeDNSSession(t, map[string]any{"dns": map[string]any{"enhanced-mode": "fake-ip", "nameserver": []string{"udp://1.1.1.1"}}})
	vpnState.Lock()
	if vpnState.session != nil {
		vpnState.Unlock()
		t.Fatal("unexpected session")
	}
	vpnState.session = s
	vpnState.Unlock()
	defer StopVPN()
	old, release := s.acquireRouting()
	originalDNS := s.nativeDNS
	fake := queryNativeDNS(t, s, "CANARY-FAKE.invalid")
	s.up.Store(123)
	started := s.started
	options := s.options
	options.Mode = "direct"
	raw, _ := json.Marshal(options)
	if decoded(t, UpdateVPNWithOptions(offlineSelected, "1.1.1.1", string(raw))).Code != "vpn_routing_updated" {
		t.Fatal("hot update failed")
	}
	current, done := s.acquireRouting()
	if current == old || current.options.Mode != "direct" || current.nativeDNS != originalDNS || s.up.Load() != 123 || s.started != started || old.ctx.Err() != nil {
		t.Fatal("hot update destroyed logical session or in-use routing")
	}
	if mapped, ok := current.nativeDNS.mapper.FindHostByIP(fake); !ok || mapped != "canary-fake.invalid" {
		t.Fatal("hot switch lost fake-IP mapping")
	}
	done()
	release()
	s.routingMu.Lock()
	retired := len(s.retiredRouting)
	s.routingMu.Unlock()
	if retired != 0 {
		t.Fatal("unused routing generation leaked")
	}
	before, done := s.acquireRouting()
	done()
	if decoded(t, UpdateVPNWithOptions(`{"type":"unsupported"}`, "1.1.1.1", string(raw))).State != "failed" {
		t.Fatal("invalid hot update accepted")
	}
	after, done := s.acquireRouting()
	done()
	if before != after || s.ctx.Err() != nil {
		t.Fatal("failed update changed active routing")
	}
}

func TestHotRoutingConcurrentLeasesAndRepeatedUpdates(t *testing.T) {
	s, e := newSession(offlineSelected, "1.1.1.1")
	if e != nil {
		t.Fatal(e)
	}
	vpnState.Lock()
	vpnState.session = s
	vpnState.Unlock()
	defer StopVPN()
	var workers sync.WaitGroup
	for i := 0; i < 8; i++ {
		workers.Add(1)
		go func() {
			defer workers.Done()
			for j := 0; j < 200; j++ {
				r, release := s.acquireRouting()
				if r.proxy == nil {
					t.Error("missing routing snapshot")
				}
				release()
			}
		}()
	}
	for i := 0; i < 25; i++ {
		mode := "global"
		if i%2 == 0 {
			mode = "direct"
		}
		if decoded(t, UpdateVPNWithOptions(offlineSelected, "1.1.1.1", `{"mode":"`+mode+`"}`)).Code != "vpn_routing_updated" {
			t.Fatal("repeated update failed")
		}
	}
	workers.Wait()
	s.routingMu.Lock()
	n := len(s.retiredRouting)
	s.routingMu.Unlock()
	if n != 0 {
		t.Fatal("retired routing generations retained without users")
	}
}

func TestImportedDNSCanBeDisabledAndDirectUsesDirectResolver(t *testing.T) {
	primary, pc := nativeDNSFixture(t, "192.0.2.10")
	direct, dc := nativeDNSFixture(t, "192.0.2.20")
	s := nativeDNSSession(t, map[string]any{"dns": map[string]any{"nameserver": []string{primary}, "direct-nameserver": []string{direct}}})
	ip, e := s.nativeDNS.resolve(context.Background(), "direct-resolver.invalid", true)
	if e != nil || ip.String() != "192.0.2.20" || dc.Load() == 0 || pc.Load() != 0 {
		t.Fatal("DIRECT resolver was bypassed")
	}
	options := s.options
	options.UseSubscriptionDNS = false
	raw, _ := json.Marshal(options)
	disabled, e := newSessionConfigured(offlineSelected, "1.1.1.1", string(raw))
	if e != nil {
		t.Fatal(e)
	}
	defer disabled.close()
	if disabled.nativeDNS != nil {
		t.Fatal("explicit DNS setting ignored")
	}
}

func TestIPCIDRProviderResolvesDomainMetadataOnlyWhenAllowed(t *testing.T) {
	matcher, code := compileProviderRuleSet(storedRuleSet{Behavior: "ipcidr", Entries: []string{"192.0.2.0/24"}})
	if code != "" {
		t.Fatal(code)
	}
	meta := &C.Metadata{Host: "fake-ip.invalid"}
	calls := 0
	helper := C.RuleMatchHelper{ResolveIP: func() { calls++; meta.DstIP = netip.MustParseAddr("192.0.2.8") }}
	if !matcher.matchesMetadata(meta, helper) || calls != 1 {
		t.Fatal("ipcidr did not resolve domain metadata")
	}
	meta.DstIP = netip.Addr{}
	if matcher.matchesMetadata(meta, C.RuleMatchHelper{}) || calls != 1 {
		t.Fatal("no-resolve performed DNS")
	}
}

func TestNumericCredentialsKeepLexicalValueAndAndroidOwnsRouting(t *testing.T) {
	p := imported(t, `proxies:
 - {name: n, type: hysteria2, server: node_with_underscore.invalid, port: 443, password: 001234, interface_name: wlan0, routing_mark: 42}
 - {name: r, type: vless, server: example.invalid, port: 443, uuid: 00000000-0000-4000-8000-000000000001, tls: true, reality-opts: {public-key: 'Ih3KhT0bPQ-kcnA30A1BnJ3l7lAJi8Ejx0LOOc3Zx1A', short-id: 001122}}
`)
	if len(p.Nodes) != 2 || !strings.Contains(p.Nodes[0].Config, `"password":"001234"`) || !strings.Contains(p.Nodes[1].Config, `"short-id":"001122"`) {
		t.Fatal("typed numeric credentials changed or rejected")
	}
	if strings.Contains(p.Nodes[0].Config, "interface") || strings.Contains(p.Nodes[0].Config, "routing-mark") {
		t.Fatal("untrusted network binding reached runtime")
	}
}

func TestProviderNamesIntervalsAndLargeHostMappings(t *testing.T) {
	text := `proxy-providers:
 a: {type: file, url: https://example.invalid/a, interval: 0}
proxy-groups: [{name: G, TYPE: SELECT, INCLUDE_ALL_PROVIDERS: true, unknown-extension: true, interval: 86400}]
`
	r := resolveFixture(t, text, map[string]string{"proxy:a": `proxies: [{name: N, type: socks5, server: example.invalid, port: 1080}]`})
	if r["state"] != "passed" {
		t.Fatal("provider template rejected")
	}
	p := imported(t, r["content"].(string))
	if len(p.Groups) != 1 || !p.Groups[0].Supported || strings.Join(p.Groups[0].Proxies, ",") != "N" {
		t.Fatal("normalized group lost provider members")
	}
	var doc strings.Builder
	doc.WriteString("proxies: [{name: N, type: socks5, server: example.invalid, port: 1080}]\nhosts:\n")
	for i := 0; i < 200; i++ {
		doc.WriteString(" h" + strings.Repeat("x", i+1) + ".invalid: 192.0.2.1\n")
	}
	if imported(t, doc.String()).State != "passed" {
		t.Fatal("bounded hosts mapping rejected")
	}
}
