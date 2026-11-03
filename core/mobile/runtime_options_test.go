// SPDX-License-Identifier: GPL-3.0-only
package mobile

import (
	"bufio"
	"context"
	"encoding/json"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"net/netip"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	M "github.com/metacubex/sing/common/metadata"
	"github.com/miekg/dns"
)

const offlineSelected = `{"name":"unused","type":"socks5","server":"127.0.0.1","port":1}`

func optionJSON(t *testing.T, change func(*runtimeOptions)) string {
	t.Helper()
	o := defaultRuntimeOptions()
	change(&o)
	b, e := json.Marshal(o)
	if e != nil {
		t.Fatal(e)
	}
	return string(b)
}

func TestRuntimeOptionsRejectDuplicatesLimitsUnsupportedRulesAndTargets(t *testing.T) {
	for _, raw := range []string{`{"mode":"global","mode":"direct"}`, `{"unknown":true}`, `{"mtu":1}`, `{"ipv6":null}`, `{"ipv6":null,"mode":"bad"}`, `{"maxConnections":513}`, `{"dnsMode":"https","dnsHttps":"http://example.invalid/dns"}`, `{"dnsMode":"https","dnsHttps":"https://user:secret@example.invalid/dns"}`, `{"dnsMode":"https","dnsHttps":"https://example.invalid/dns?token=private"}`} {
		if strings.Contains(ValidateRuntimeOptions(raw), `"state":"passed"`) {
			t.Fatal("invalid options accepted")
		}
	}
	for _, line := range []string{"GEOSITE,private-provider,PROXY", "RULE-SET,private-provider,DIRECT", "IP-CIDR,invalid,PROXY", "DOMAIN-SUFFIX,,PROXY", "MATCH,missing-private-target"} {
		raw := optionJSON(t, func(o *runtimeOptions) { o.Mode = "rule"; o.Rules = []string{line} })
		result := ValidateRuntimeOptions(raw)
		if strings.Contains(result, `"state":"passed"`) || strings.Contains(result, "private-provider") || strings.Contains(result, "missing-private-target") {
			t.Fatal("bad rule accepted or private input reflected")
		}
	}
	raw := optionJSON(t, func(o *runtimeOptions) {
		for i := 0; i < maxRuntimeRules+1; i++ {
			o.Rules = append(o.Rules, "MATCH,PROXY")
		}
	})
	if !strings.Contains(ValidateRuntimeOptions(raw), "rule_limit") {
		t.Fatal("rule count budget escaped")
	}
}

type closeCounter struct{ count *atomic.Int64 }

func (c closeCounter) Close() error { c.count.Add(1); return nil }
func TestClosingFlowRacesWithAttachWithoutLeakingNewSockets(t *testing.T) {
	var count atomic.Int64
	closer := &flowCloser{}
	var tasks sync.WaitGroup
	for i := 0; i < 100; i++ {
		tasks.Add(1)
		go func() { defer tasks.Done(); closer.add(closeCounter{&count}); _ = closer.Close() }()
	}
	tasks.Wait()
	_ = closer.Close()
	if count.Load() != 100 {
		t.Fatal("close/attach race leaked or repeated a socket close")
	}
}

func TestRuleOrderDomainBoundariesAndLocalPolicy(t *testing.T) {
	raw := optionJSON(t, func(o *runtimeOptions) {
		o.Mode = "rule"
		o.Rules = []string{"DOMAIN-SUFFIX,example.test,REJECT", "DST-PORT,8000-9000,DIRECT", "MATCH,PROXY"}
	})
	s, e := newSessionConfigured(offlineSelected, "127.0.0.1:53", raw)
	if e != nil {
		t.Fatal(e)
	}
	defer s.close()
	if _, _, e = s.route(M.ParseSocksaddr("a.example.test:443"), "tcp"); e == nil {
		t.Fatal("subdomain not blocked")
	}
	if _, target, e := s.route(M.ParseSocksaddr("notexample.test:443"), "tcp"); e != nil || target != "PROXY" {
		t.Fatal("domain suffix escaped label boundary")
	}
	if _, target, e := s.route(M.ParseSocksaddr("192.0.2.1:8443"), "tcp"); e != nil || target != "DIRECT" {
		t.Fatal("port range not applied")
	}
	s.options.LAN = "block"
	if _, _, e = s.route(M.ParseSocksaddr("127.0.0.1:8443"), "tcp"); e == nil {
		t.Fatal("LAN policy not prioritized")
	}
	s.options.IPv6 = false
	if _, _, e = s.route(M.ParseSocksaddr("[2001:db8::1]:443"), "tcp"); e == nil {
		t.Fatal("disabled IPv6 escaped")
	}
	if _, _, e = s.route(M.ParseSocksaddr("224.0.0.1:443"), "tcp"); e == nil {
		t.Fatal("multicast escaped")
	}
	s.rules, _, _ = compileRules([]string{"MATCH,PROXY", "DOMAIN,blocked.test,REJECT"})
	if s.dnsBlocked("blocked.test.") {
		t.Fatal("DNS ignored rule order")
	}
}

func TestLiveTrafficCountsBeforeCloseAndControlClosesBothSockets(t *testing.T) {
	release := make(chan struct{})
	target := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_, _ = io.WriteString(w, "stream!")
		w.(http.Flusher).Flush()
		<-release
	}))
	// Do not Close the server before unblocking its handler.
	defer func() { close(release); target.Close() }()
	raw := optionJSON(t, func(o *runtimeOptions) { o.Mode = "rule"; o.Rules = []string{"IP-CIDR,127.0.0.0/8,DIRECT"} })
	s, e := newSessionConfigured(offlineSelected, "127.0.0.1:53", raw)
	if e != nil {
		t.Fatal(e)
	}
	defer s.close()
	vpnState.Lock()
	if vpnState.session != nil {
		vpnState.Unlock()
		t.Fatal("another test owns VPN")
	}
	vpnState.session = s
	vpnState.Unlock()
	defer func() { vpnState.Lock(); vpnState.session = nil; vpnState.Unlock() }()
	incoming, client := net.Pipe()
	defer client.Close()
	done := make(chan error, 1)
	go func() {
		done <- s.NewConnection(context.Background(), incoming, M.Metadata{Destination: M.ParseSocksaddr(target.Listener.Addr().String())})
	}()
	_ = client.SetDeadline(time.Now().Add(4 * time.Second))
	_, _ = io.WriteString(client, "GET / HTTP/1.1\r\nHost: fixture\r\n\r\n")
	reader := bufio.NewReader(client)
	response, e := http.ReadResponse(reader, nil)
	if e != nil {
		t.Fatal(e)
	}
	payload := make([]byte, 7)
	if _, e = io.ReadFull(response.Body, payload); e != nil || string(payload) != "stream!" {
		t.Fatal("live relay failed")
	}
	// net.Pipe can wake its reader before the writer returns and records bytes.
	// Observe eventual live accounting while both sides are still open.
	deadline := time.Now().Add(time.Second)
	for (s.up.Load() == 0 || s.down.Load() == 0) && time.Now().Before(deadline) {
		time.Sleep(time.Millisecond)
	}
	if s.up.Load() == 0 || s.down.Load() == 0 {
		t.Fatal("live counts wait for close")
	}
	var rows []map[string]any
	_ = json.Unmarshal([]byte(VPNConnections()), &rows)
	if len(rows) != 1 || rows[0]["destination"] != "" {
		t.Fatal("connection metadata enabled without consent")
	}
	id := int64(rows[0]["id"].(float64))
	CloseVPNConnection(id)
	select {
	case <-done:
	case <-time.After(2 * time.Second):
		t.Fatal("control did not close both ends")
	}
	if VPNConnections() != "[]" {
		t.Fatal("closed connection metadata retained")
	}
	if !strings.Contains(VPNStatus(), `"totalConnections":1`) {
		t.Fatal("session status counter missing")
	}
}

func TestNamedOutboundRuleUsesOriginalGroupAndMissingTargetNeverFallsBack(t *testing.T) {
	target := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { _, _ = io.WriteString(w, "named-outbound") }))
	defer target.Close()
	node := anyTLSFixture(t, target.Listener.Addr().String())
	var mapping map[string]any
	_ = json.Unmarshal([]byte(asJSON(t, node)), &mapping)
	mapping["name"] = "named-node"
	definition, _ := json.Marshal(map[string]any{"proxies": []any{mapping}, "proxy-groups": []any{map[string]any{"name": "provider-group", "type": "select", "proxies": []any{"named-node"}}}})
	raw := optionJSON(t, func(o *runtimeOptions) {
		o.Mode = "rule"
		o.Definition = string(definition)
		o.Rules = []string{"MATCH,provider-group"}
	})
	if !strings.Contains(ValidateRuntimeOptions(raw), `"state":"passed"`) {
		t.Fatal("valid original group rule rejected")
	}
	s, e := newSessionConfigured(offlineSelected, "127.0.0.1:53", raw)
	if e != nil {
		t.Fatal(e)
	}
	defer s.close()
	in, client := net.Pipe()
	defer client.Close()
	go func() {
		_ = s.NewConnection(context.Background(), in, M.Metadata{Destination: M.ParseSocksaddr(target.Listener.Addr().String())})
	}()
	_ = client.SetDeadline(time.Now().Add(4 * time.Second))
	_, _ = io.WriteString(client, "GET / HTTP/1.1\r\nHost: fixture\r\nConnection: close\r\n\r\n")
	result, e := io.ReadAll(client)
	if e != nil || !strings.Contains(string(result), "named-outbound") {
		t.Fatal("named outbound did not use original group")
	}
	raw = optionJSON(t, func(o *runtimeOptions) {
		o.Mode = "rule"
		o.Definition = string(definition)
		o.Rules = []string{"MATCH,missing"}
	})
	if bad, e := newSessionConfigured(offlineSelected, "127.0.0.1:53", raw); e == nil {
		bad.close()
		t.Fatal("missing target silently replaced")
	}
}

func TestDNSPolicyBlocksWithoutNetworkAndAssociationsRemainBounded(t *testing.T) {
	raw := optionJSON(t, func(o *runtimeOptions) {
		o.Mode = "rule"
		o.IPv6 = false
		o.Rules = []string{"DOMAIN-SUFFIX,blocked.test,REJECT"}
	})
	s, e := newSessionConfigured(offlineSelected, "127.0.0.1:1", raw)
	if e != nil {
		t.Fatal(e)
	}
	defer s.close()
	for _, qtype := range []uint16{dns.TypeA, dns.TypeAAAA} {
		query := new(dns.Msg)
		host := "blocked.test."
		if qtype == dns.TypeAAAA {
			host = "other.test."
		}
		query.SetQuestion(host, qtype)
		wire, _ := query.Pack()
		response, e := s.exchangeDNS(wire)
		if e != nil {
			t.Fatal("blocked DNS attempted network")
		}
		var answer dns.Msg
		_ = answer.Unpack(response)
		if qtype == dns.TypeA && answer.Rcode != dns.RcodeRefused || qtype == dns.TypeAAAA && len(answer.Answer) != 0 {
			t.Fatal("DNS policy not enforced")
		}
	}
	ip := netip.MustParseAddr("192.0.2.1")
	s.rememberHost(ip, "one.test.", 60)
	if s.hostFor(ip) != "one.test" {
		t.Fatal("DNS association missing")
	}
	s.rememberHost(ip, "two.test.", 60)
	if s.hostFor(ip) != "" {
		t.Fatal("ambiguous shared IP acquired a misleading domain")
	}
	for i := 0; i < 1200; i++ {
		s.rememberHost(netip.AddrFrom4([4]byte{198, 18, byte(i / 256), byte(i % 256)}), "bounded.test", 60)
	}
	if len(s.hosts) > 1024 {
		t.Fatal("DNS association budget escaped")
	}
}

func TestDNSHTTPSRejectsUntrustedCertificateAndDowngrade(t *testing.T) {
	target := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { t.Error("untrusted TLS reached HTTP") }))
	defer target.Close()
	node := anyTLSFixture(t, target.Listener.Addr().String())
	raw := optionJSON(t, func(o *runtimeOptions) { o.DNSMode = "https"; o.DNSHTTPS = target.URL + "/dns-query" })
	s, e := newSessionConfigured(asJSON(t, node), "127.0.0.1:53", raw)
	if e != nil {
		t.Fatal(e)
	}
	defer s.close()
	query := new(dns.Msg)
	query.SetQuestion("example.test.", dns.TypeA)
	wire, _ := query.Pack()
	if _, e = s.exchangeDNS(wire); e == nil {
		t.Fatal("DNS HTTPS accepted untrusted TLS")
	}
	raw = optionJSON(t, func(o *runtimeOptions) {
		o.DNSMode = "https"
		o.DNSHTTPS = strings.Replace(target.URL, "https:", "http:", 1)
	})
	if _, e = decodeRuntimeOptions(raw); e == nil {
		t.Fatal("DNS HTTPS allowed HTTP downgrade")
	}
}

func TestImportedProviderRulesSurviveWithoutExecutingOrInventingRules(t *testing.T) {
	p := imported(t, `{"proxies":[{"name":"A","type":"trojan","server":"example.invalid","port":443,"password":"fixture"}],"rules":["DOMAIN-SUFFIX,example.test,PROXY","MATCH,DIRECT",123]}`)
	if len(p.Rules) != 2 || p.RulesOmitted != 1 || p.Rules[0] != "DOMAIN-SUFFIX,example.test,PROXY" {
		t.Fatal("provider rule order or omission lost")
	}
}
func FuzzRuntimeOptions(f *testing.F) {
	f.Add(`{"mode":"rule","rules":["SUB-RULE,(NETWORK,tcp),a"],"definition":"{\"sub-rules\":{\"a\":[\"SUB-RULE,(NETWORK,tcp),a\"]}}"}`)
	f.Add(`{"mode":"rule","rules":["SUB-RULE,(DST-PORT,443),local"],"definition":"{\"sub-rules\":{\"local\":[\"MATCH,DIRECT\"]}}"}`)
	f.Add(`{"rules":["DOMAIN-SUFFIX,https://example.invalid/path,PROXY,no-resolve,ignored"]}`)
	f.Add(`{"rules":["DOMAIN-SUFFIX,.,DIRECT"]}`)
	f.Add(`{"rules":["AND,((NETWORK,tcp),(DST-PORT,443/8443)),PROXY"]}`)
	f.Add(`{"rules":["NOT,((AND,((DOMAIN-SUFFIX,example.test),(NETWORK,udp)))),PROXY"]}`)
	for _, seed := range []string{`{}`, `{"mode":"rule","rules":["DOMAIN-SUFFIX,example.test,REJECT","MATCH,PROXY"]}`, `{"rules":["IP-CIDR,127.0.0.0/8,DIRECT,no-resolve"]}`, `{"rules":["DST-PORT,443-8443,PROXY"]}`, `{"mode":"direct","mode":"global"}`} {
		f.Add(seed)
	}
	f.Fuzz(func(t *testing.T, raw string) {
		if len(raw) > 65536 {
			return
		}
		var result map[string]any
		if json.Unmarshal([]byte(ValidateRuntimeOptions(raw)), &result) != nil {
			t.Fatal("invalid public result")
		}
	})
}

func TestDNSAssociationIgnoresUnrelatedAnswersAndAcceptsCNAMEOwner(t *testing.T) {
	s, e := newSession(offlineSelected, "127.0.0.1:53")
	if e != nil {
		t.Fatal(e)
	}
	defer s.close()
	query := new(dns.Msg)
	query.SetQuestion("allowed.test.", dns.TypeA)
	answer := new(dns.Msg)
	answer.SetReply(query)
	answer.Answer = []dns.RR{
		&dns.CNAME{Hdr: dns.RR_Header{Name: "allowed.test.", Rrtype: dns.TypeCNAME, Class: dns.ClassINET, Ttl: 60}, Target: "alias.test."},
		&dns.A{Hdr: dns.RR_Header{Name: "alias.test.", Rrtype: dns.TypeA, Class: dns.ClassINET, Ttl: 60}, A: net.IPv4(192, 0, 2, 10)},
		&dns.A{Hdr: dns.RR_Header{Name: "unrelated.test.", Rrtype: dns.TypeA, Class: dns.ClassINET, Ttl: 60}, A: net.IPv4(192, 0, 2, 11)},
	}
	s.rememberDNSAnswer(query, answer)
	if s.hostFor(netip.MustParseAddr("192.0.2.10")) != "allowed.test" || s.hostFor(netip.MustParseAddr("192.0.2.11")) != "" {
		t.Fatal("unrelated DNS owner poisoned routing association")
	}
}
