// SPDX-License-Identifier: GPL-3.0-only
package mobile

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"net/netip"
	"strings"
	"sync"
	"sync/atomic"

	"github.com/metacubex/mihomo/component/resolver"
	SystemHosts "github.com/metacubex/mihomo/component/resolver/hosts"
	"github.com/metacubex/mihomo/config"
	C "github.com/metacubex/mihomo/constant"
	MDNS "github.com/metacubex/mihomo/dns"
	"github.com/metacubex/mihomo/log"
	"github.com/miekg/dns"
	"go.yaml.in/yaml/v3"
)

// Serialize component construction around the pinned geo loaders. The private
// API avoids the full parser's global mutations and executor.ApplyConfig.
var nativeConfigParse sync.Mutex

type nativeDNSRuntime struct {
	references atomic.Int32

	resolvers      MDNS.Resolvers
	mapper         *MDNS.ResolverEnhancer
	service        *MDNS.Service
	hosts          resolver.Hosts
	useHosts       bool
	ipv6           bool
	useSystemHosts bool
}

// Only DNS/hosts and already-downloaded rule payloads reach the native parser.
// No subscription URL, controller, listener, certificate path, or updater is
// handed to it. Rule-set matchers are rebound to our private immutable registry.
func newNativeDNSRuntime(definition string, session *vpnSession) (*nativeDNSRuntime, error) {
	if definition == "" || !session.options.UseSubscriptionDNS {
		return nil, nil
	}
	var root map[string]json.RawMessage
	if len(definition) > maxPrivateProfileBytes || json.Unmarshal([]byte(definition), &root) != nil {
		return nil, errors.New("dns_invalid")
	}
	input, present := root["dns"]
	if !present {
		return nil, nil
	}
	var dnsMap map[string]any
	if json.Unmarshal(input, &dnsMap) != nil || dnsMap == nil {
		return nil, errors.New("dns_invalid")
	}
	if enabled, ok := dnsMap["enable"].(bool); ok && !enabled {
		return nil, nil
	}
	// Android owns DNS interception; a missing enable flag does not disable an
	// otherwise complete imported DNS configuration.
	dnsMap["enable"] = true
	selected := map[string]any{"dns": dnsMap}
	if h, exists := root["hosts"]; exists {
		var hosts map[string]any
		if json.Unmarshal(h, &hosts) != nil || !boundedNativeHosts(hosts) {
			return nil, errors.New("dns_invalid")
		}
		selected["hosts"] = hosts
	}
	var encoded string
	sets := map[string]storedRuleSet{}
	if data, exists := root[providerRuleDataKey]; exists {
		if json.Unmarshal(data, &encoded) != nil {
			return nil, errors.New("dns_invalid")
		}
		var e error
		sets, e = decodeStoredRuleSets(encoded)
		if e != nil {
			return nil, errors.New("dns_invalid")
		}
	}
	providers := map[string]map[string]any{}
	for name, set := range sets {
		// Native DNS validates names/behaviors; actual matching is rebound to
		// the already bounded private set, avoiding a second 500k-row copy.
		providers[name] = map[string]any{"type": "inline", "behavior": set.Behavior, "payload": []string{}}
	}
	selected["rule-providers"] = providers
	serialized, e := yaml.Marshal(selected)
	if e != nil {
		return nil, errors.New("dns_invalid")
	}
	raw, e := config.UnmarshalRawConfig(serialized)
	if e != nil {
		return nil, errors.New("dns_invalid")
	}
	if saved, exists := root[nativeDNSDocumentKey]; exists {
		var text string
		if json.Unmarshal(saved, &text) != nil || len(text) > 1024*1024 {
			return nil, errors.New("dns_invalid")
		}
		fragment, e := config.UnmarshalRawConfig([]byte(text))
		if e != nil {
			return nil, errors.New("dns_invalid")
		}
		raw.DNS = fragment.DNS
		raw.DNS.Enable = true
	}
	raw.GeoXUrl = config.RawGeoXUrl{}
	if len(raw.DNS.DefaultNameserver) == 0 {
		raw.DNS.DefaultNameserver = []string{session.dns.Addr().String()}
	}
	raw.GeoAutoUpdate = false
	raw.GeodataMode = true
	raw.LogLevel = log.SILENT
	raw.Profile.StoreSelected = false
	raw.Profile.StoreFakeIP = false
	raw.DNS.Listen = ""
	raw.DNS.ListenRoutingMark = 0
	if raw.DNS.CacheMaxSize < 0 || raw.DNS.CacheMaxSize > 16384 {
		return nil, errors.New("dns_invalid")
	}
	// The service's built-in hosts middleware reads global hosts. Keep that
	// disabled; apply the same parsed host trie locally before ServeMsg instead.
	useHosts := raw.DNS.UseHosts
	raw.DNS.UseHosts = false
	if nativeDNSNeedsRuleData(raw) && !ruleDataAvailable() {
		return nil, errors.New("dns_invalid")
	}
	ruleProviders, e := parseNativeRuleProviders(raw)
	if e != nil {
		return nil, errors.New("dns_invalid")
	}
	defer func() {
		for _, p := range ruleProviders {
			if closer, ok := p.(io.Closer); ok {
				_ = closer.Close()
			}
		}
	}()
	nativeConfigParse.Lock()
	c, e := parseNativeDNS(raw, ruleProviders)
	nativeConfigParse.Unlock()
	if e != nil {
		return nil, errors.New("dns_invalid")
	}
	hostTree, e := parseNativeHosts(raw)
	if e != nil {
		return nil, errors.New("dns_invalid")
	}
	matchers := map[string]*privateRuleSet{}
	for name, set := range sets {
		matcher, code := compileProviderRuleSet(set)
		if code != "" {
			return nil, errors.New("dns_invalid")
		}
		matchers[name] = &privateRuleSet{name: name, matcher: matcher, noResolve: true}
	}
	replaceDomain := func(m C.DomainMatcher) C.DomainMatcher {
		if r, ok := m.(C.Rule); ok && r.RuleType() == C.RuleSet {
			return matchers[r.Payload()]
		}
		return m
	}
	serverCount := 0
	bind := func(list []MDNS.NameServer, auto bool) error {
		serverCount += len(list)
		if serverCount > 512 {
			return errors.New("dns_invalid")
		}
		for i := range list {
			name := list[i].ProxyName
			if name == "" {
				if !auto {
					continue
				}
				name = "AUTO"
			}
			var p C.ProxyAdapter
			switch name {
			case "DIRECT", "AUTO":
				p = session.direct
			case "GLOBAL", "PROXY":
				p = session.proxy
			case MDNS.RespectRules:
				p = &dnsRouteAdapter{ProxyAdapter: session.proxy, session: session}
			default:
				p = session.targets[name]
				if p == nil {
					if len(session.targets) >= maxRuleTargets {
						return errors.New("dns_invalid")
					}
					graph, e := parseLocalDefinition(definition)
					if e != nil {
						return errors.New("dns_invalid")
					}
					p, e = graph.runtime(name)
					if e != nil {
						return errors.New("dns_invalid")
					}
					session.targets[name] = p
				}
			}
			list[i].ProxyAdapter = &dnsDynamicAdapter{ProxyAdapter: p, root: session.root(), name: name}
			list[i].ProxyName = ""
		}
		return nil
	}
	for _, list := range [][]MDNS.NameServer{c.NameServer, c.Fallback} {
		if e := bind(list, true); e != nil {
			return nil, e
		}
	}
	for _, list := range [][]MDNS.NameServer{c.DefaultNameserver, c.ProxyServerNameserver, c.DirectNameServer} {
		if e := bind(list, false); e != nil {
			return nil, e
		}
	}
	for kind, policies := range [][]MDNS.Policy{c.NameServerPolicy, c.ProxyServerPolicy} {
		for i := range policies {
			policies[i].Matcher = replaceDomain(policies[i].Matcher)
			if e := bind(policies[i].NameServers, kind == 0); e != nil {
				return nil, e
			}
		}
	}
	for i := range c.FallbackDomainFilter {
		c.FallbackDomainFilter[i] = replaceDomain(c.FallbackDomainFilter[i])
	}
	for i, m := range c.FallbackIPFilter {
		if r, ok := m.(C.Rule); ok && r.RuleType() == C.RuleSet {
			c.FallbackIPFilter[i] = matchers[r.Payload()]
		}
	}
	if f := c.FakeIPSkipper; f != nil {
		for i := range f.Host {
			f.Host[i] = replaceDomain(f.Host[i])
		}
		for i, r := range f.Rules {
			if r.RuleType() == C.RuleSet {
				m := *matchers[r.Payload()]
				m.target = r.Adapter()
				f.Rules[i] = &m
			}
		}
	}
	ipv6 := c.IPv6 && session.options.IPv6
	r := MDNS.NewResolver(MDNS.Config{Main: c.NameServer, Fallback: c.Fallback, Default: c.DefaultNameserver, ProxyServer: c.ProxyServerNameserver, ProxyServerPolicy: c.ProxyServerPolicy, DirectServer: c.DirectNameServer, DirectFollowPolicy: c.DirectFollowPolicy, IPv6: ipv6, IPv6Timeout: c.IPv6Timeout, FallbackIPFilter: c.FallbackIPFilter, FallbackDomainFilter: c.FallbackDomainFilter, FallbackLazyQuery: c.FallbackLazyQuery, Policy: c.NameServerPolicy, CacheAlgorithm: c.CacheAlgorithm, CacheMaxSize: c.CacheMaxSize})
	m := MDNS.NewEnhancer(MDNS.EnhancerConfig{IPv6: ipv6, EnhancedMode: c.EnhancedMode, FakeIPPool: c.FakeIPPool, FakeIPPool6: c.FakeIPPool6, FakeIPSkipper: c.FakeIPSkipper, FakeIPTTL: c.FakeIPTTL, UseHosts: false})
	result := &nativeDNSRuntime{resolvers: r, mapper: m, service: MDNS.NewService(r, m), hosts: resolver.NewHosts(hostTree), useHosts: useHosts, ipv6: ipv6, useSystemHosts: c.UseSystemHosts}
	result.references.Store(1)
	return result, nil
}

func nativeDNSNeedsRuleData(raw *config.RawConfig) bool {
	if len(raw.DNS.Fallback) > 0 && (raw.DNS.FallbackFilter.GeoIP || len(raw.DNS.FallbackFilter.GeoSite) > 0) {
		return true
	}
	data, _ := json.Marshal(raw.DNS)
	text := strings.ToUpper(string(data))
	for _, tag := range []string{"GEOSITE:", "GEOSITE,", "GEOIP,", "IP-ASN,"} {
		if strings.Contains(text, tag) {
			return true
		}
	}
	return false
}

func (r *nativeDNSRuntime) exchange(ctx context.Context, query *dns.Msg) (*dns.Msg, error) {
	q := query.Question[0]
	if q.Qtype == dns.TypeAAAA && !r.ipv6 {
		answer := new(dns.Msg)
		answer.SetReply(query)
		return answer, nil
	}
	name := strings.TrimSuffix(q.Name, ".")
	if r.useHosts && (q.Qtype == dns.TypeA || q.Qtype == dns.TypeAAAA) {
		canonical, ips := r.realHost(name)
		name = canonical
		if len(ips) > 0 {
			answer := new(dns.Msg)
			answer.SetReply(query)
			for _, ip := range ips {
				header := dns.RR_Header{Name: q.Name, Class: dns.ClassINET, Ttl: 10}
				if ip.Is4() && q.Qtype == dns.TypeA {
					header.Rrtype = dns.TypeA
					answer.Answer = append(answer.Answer, &dns.A{Hdr: header, A: ip.AsSlice()})
				}
				if ip.Is6() && q.Qtype == dns.TypeAAAA {
					header.Rrtype = dns.TypeAAAA
					answer.Answer = append(answer.Answer, &dns.AAAA{Hdr: header, AAAA: ip.AsSlice()})
				}
			}
			return answer, nil
		}
	}
	if name != strings.TrimSuffix(q.Name, ".") {
		forward := query.Copy()
		forward.Question[0].Name = dns.Fqdn(name)
		answer, e := r.service.ServeMsg(ctx, forward)
		if e == nil {
			answer.Question = query.Question
			answer.Answer = append([]dns.RR{&dns.CNAME{Hdr: dns.RR_Header{Name: q.Name, Rrtype: dns.TypeCNAME, Class: dns.ClassINET, Ttl: 10}, Target: dns.Fqdn(name)}}, answer.Answer...)
		}
		return answer, e
	}
	return r.service.ServeMsg(ctx, query)
}

func (r *nativeDNSRuntime) resolve(ctx context.Context, host string, direct ...bool) (netip.Addr, error) {
	host, values := r.realHost(host)
	if len(values) > 0 {
		return values[0], nil
	}
	upstream := r.resolvers.Resolver
	if len(direct) > 0 && direct[0] && r.resolvers.DirectResolver.Invalid() {
		upstream = r.resolvers.DirectResolver
	}
	ips, e := upstream.LookupIP(ctx, host)
	if e != nil || len(ips) == 0 {
		return netip.Addr{}, errors.New("dns_exchange_failed")
	}
	return ips[0], nil
}

func (r *nativeDNSRuntime) realHost(host string) (string, []netip.Addr) {
	if !r.useHosts {
		return host, nil
	}
	for i := 0; i < 16; i++ {
		node := r.hosts.DomainTrie.Search(host)
		if node == nil {
			if r.useSystemHosts {
				values, _ := SystemHosts.LookupStaticHost(host)
				ips := []netip.Addr{}
				for _, value := range values {
					if ip, e := netip.ParseAddr(value); e == nil {
						ips = append(ips, ip.Unmap())
					}
				}
				if len(ips) > 0 {
					return host, ips
				}
			}
			return host, nil
		}
		value := node.Data()
		if !value.IsDomain {
			return host, value.IPs
		}
		host = value.Domain
	}
	return host, nil
}

type dnsRouteAdapter struct {
	C.ProxyAdapter
	session *vpnSession
}

// Never send a fake IP to an upstream server. TCP proxy protocols may carry
// the restored hostname; direct/L3 and UDP need a real address from native DNS.
func (s *vpnSession) restoreDNSDestination(ctx context.Context, m *C.Metadata, resolve bool, direct ...bool) error {
	if s.nativeDNS == nil || !s.nativeDNS.mapper.IsFakeIP(m.DstIP) {
		return nil
	}
	host, ok := s.nativeDNS.mapper.FindHostByIP(m.DstIP)
	if !ok {
		return errors.New("dns_mapping_missing")
	}
	m.Host = host
	m.DstIP = netip.Addr{}
	return s.resolveRestoredDestination(ctx, m, resolve, len(direct) > 0 && direct[0])
}

func (s *vpnSession) resolveRestoredDestination(ctx context.Context, m *C.Metadata, resolve, direct bool) error {
	if s.nativeDNS == nil || !resolve || m.Host == "" || m.DstIP.IsValid() {
		return nil
	}
	if resolve {
		ip, e := s.nativeDNS.resolve(ctx, m.Host, direct)
		if e != nil {
			return e
		}
		m.DstIP = ip
	}
	return nil
}

func (p *dnsRouteAdapter) DialContext(ctx context.Context, m *C.Metadata) (C.Conn, error) {
	if e := p.resolveUpstream(ctx, m); e != nil {
		return nil, e
	}
	proxy, _, e := p.session.routeFromMetadata(m)
	if e != nil {
		return nil, e
	}
	if proxy.IsL3Protocol(m) {
		m.Host = ""
	}
	return proxy.DialContext(ctx, m)
}
func (p *dnsRouteAdapter) ListenPacketContext(ctx context.Context, m *C.Metadata) (C.PacketConn, error) {
	if e := p.resolveUpstream(ctx, m); e != nil {
		return nil, e
	}
	proxy, _, e := p.session.routeFromMetadata(m)
	if e != nil {
		return nil, e
	}
	if proxy.IsL3Protocol(m) {
		m.Host = ""
	}
	return proxy.ListenPacketContext(ctx, m)
}

func (p *dnsRouteAdapter) resolveUpstream(ctx context.Context, m *C.Metadata) error {
	if m.DstIP.IsValid() || m.Host == "" {
		return nil
	}
	if p.session.nativeDNS == nil {
		return errors.New("dns_invalid")
	}
	r := p.session.nativeDNS.resolvers.ProxyResolver
	if r == nil || !r.Invalid() {
		return errors.New("dns_invalid")
	}
	ips, e := r.LookupIP(ctx, m.Host)
	if e != nil || len(ips) == 0 {
		return errors.New("dns_exchange_failed")
	}
	m.DstIP = ips[0]
	return nil
}

func (r *nativeDNSRuntime) release() {
	if r.references.Add(-1) == 0 {
		r.resolvers.ClearCache()
	}
}
