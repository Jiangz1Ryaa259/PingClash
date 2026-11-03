// SPDX-License-Identifier: GPL-3.0-only
package mobile

import (
	"context"
	"encoding/json"
	"errors"
	RC "github.com/metacubex/mihomo/rules/common"
	"io"
	"net/netip"
	"net/url"
	"strconv"
	"strings"
	"time"

	"github.com/metacubex/mihomo/adapter/outbound"
	C "github.com/metacubex/mihomo/constant"
	M "github.com/metacubex/sing/common/metadata"
)

type runtimeOptions struct {
	Mode               string   `json:"mode"`
	MTU                int      `json:"mtu"`
	IPv6               bool     `json:"ipv6"`
	UDP                bool     `json:"udp"`
	LAN                string   `json:"lan"`
	ConnectTimeout     int      `json:"connectTimeout"`
	UDPTimeout         int      `json:"udpTimeout"`
	MaxConnections     int      `json:"maxConnections"`
	InspectConnections bool     `json:"inspectConnections"`
	UseSubscriptionDNS bool     `json:"useSubscriptionDNS"`
	DNSMode            string   `json:"dnsMode"`
	DNSHTTPS           string   `json:"dnsHttps"`
	Rules              []string `json:"rules"`
	Definition         string   `json:"definition"`
}

func defaultRuntimeOptions() runtimeOptions {
	return runtimeOptions{Mode: "global", UseSubscriptionDNS: true, MTU: 1500, IPv6: true, UDP: true, LAN: "proxy", ConnectTimeout: 10, UDPTimeout: 60, MaxConnections: 256, DNSMode: "tcp"}
}

// ValidateRuntimeOptions never opens sockets or starts health checks. Failure
// exposes only a line number and a fixed vocabulary, never a rule or target.
func ValidateRuntimeOptions(raw string) string {
	options, err := decodeRuntimeOptions(raw)
	if err != nil {
		return `{"state":"failed","code":"options_invalid"}`
	}
	rules, line, code := compileRuntimeRules(options.Rules, options.Definition)
	if code != "" {
		b, _ := json.Marshal(map[string]any{"state": "failed", "code": code, "line": line, "ruleType": publicRuleType(options.Rules, line)})
		return string(b)
	}
	targets := map[string]bool{}
	for _, target := range runtimeRuleTargets(rules) {
		if target != "PROXY" && target != "DIRECT" && target != "REJECT" && target != "PASS-RULE" {
			targets[target] = true
		}
	}
	if len(targets) > maxRuleTargets {
		return `{"state":"failed","code":"rule_target_limit"}`
	}
	if len(targets) > 0 {
		graph, e := parseLocalDefinition(options.Definition)
		if e != nil {
			return `{"state":"failed","code":"rule_target_invalid"}`
		}
		for target := range targets {
			p, e := graph.runtime(target)
			if e != nil {
				return `{"state":"failed","code":"rule_target_invalid"}`
			}
			_ = p.Close()
		}
	}
	return `{"state":"passed","code":"options_valid"}`
}
func decodeRuntimeOptions(raw string) (runtimeOptions, error) {
	o := defaultRuntimeOptions()
	if raw == "" {
		return o, nil
	}
	if len(raw) > maxPrivateBridgeBytes {
		return o, errors.New("options_invalid")
	}
	// The options bridge is private, but still reject duplicate/unknown keys.
	d := json.NewDecoder(strings.NewReader(raw))
	token, e := d.Token()
	if e != nil || token != json.Delim('{') {
		return o, errors.New("options_invalid")
	}
	seen := map[string]bool{}
	for d.More() {
		k, e := d.Token()
		if e != nil {
			return o, e
		}
		key, ok := k.(string)
		if !ok || seen[key] {
			return o, errors.New("options_invalid")
		}
		seen[key] = true
		var value json.RawMessage
		if d.Decode(&value) != nil {
			return o, errors.New("options_invalid")
		}
		if string(value) == "null" && key != "rules" {
			return o, errors.New("options_invalid")
		}
	}
	if _, e = d.Token(); e != nil || d.Decode(new(any)) != io.EOF {
		return o, errors.New("options_invalid")
	}
	d = json.NewDecoder(strings.NewReader(raw))
	d.DisallowUnknownFields()
	if e = d.Decode(&o); e != nil {
		return o, e
	}
	if o.Mode != "global" && o.Mode != "rule" && o.Mode != "direct" || o.LAN != "proxy" && o.LAN != "direct" && o.LAN != "block" || o.MTU < 1280 || o.MTU > 9000 || o.ConnectTimeout < 3 || o.ConnectTimeout > 60 || o.UDPTimeout < 10 || o.UDPTimeout > 300 || o.MaxConnections < 16 || o.MaxConnections > 512 || len(o.Definition) > maxPrivateProfileBytes || o.DNSMode != "tcp" && o.DNSMode != "https" {
		return o, errors.New("options_invalid")
	}
	if o.DNSMode == "https" {
		u, e := url.Parse(o.DNSHTTPS)
		if e != nil || u.Scheme != "https" || u.Hostname() == "" || u.User != nil || u.Fragment != "" || u.RawQuery != "" || len(o.DNSHTTPS) > 2048 {
			return o, errors.New("options_invalid")
		}
		if u.Port() != "" {
			p, e := strconv.Atoi(u.Port())
			if e != nil || p < 1 || p > 65535 {
				return o, errors.New("options_invalid")
			}
		}
	}
	return o, nil
}

const maxRuleTargets = 64

type outboundRule struct {
	native              C.Rule
	set                 *providerRuleMatcher
	kind, value, target string
	prefix              netip.Prefix
	exits               []string
}

func compileRules(lines []string) ([]outboundRule, int, string) {
	return compileRulesUsing(lines, compileNativeRule)
}

type nativeRuleCompiler func(string, string, string, []string) (C.Rule, string)

func compileRulesUsing(lines []string, leaf nativeRuleCompiler) ([]outboundRule, int, string) {
	if len(lines) > maxRuntimeRules {
		return nil, 0, "rule_limit"
	}
	total := 0
	rules := []outboundRule{}
	for i, line := range lines {
		total += len(line)
		if total > maxRuntimeRuleBytes || len(line) > 2048 {
			return nil, i + 1, "rule_limit"
		}
		line = strings.TrimSpace(line)
		if line == "" || strings.HasPrefix(line, "#") {
			continue
		}
		if hasControl(line) {
			return nil, i + 1, "rule_invalid"
		}
		tp, payload, target, params := RC.ParseRulePayload(line, true)
		if target == "" || len(target) > 512 {
			return nil, i + 1, "rule_invalid"
		}
		var native C.Rule
		code := ""
		if tp == "AND" || tp == "OR" || tp == "NOT" {
			budget := 64
			var e error
			native, e = compileLogicalRuleUsing(tp, payload, target, 0, &budget, leaf)
			if e != nil {
				code = "rule_invalid"
				var typed *nativeRuleFailure
				if errors.As(e, &typed) {
					code = typed.code
				}
			}
		} else {
			native, code = leaf(tp, payload, target, params)
		}
		if code != "" {
			return nil, i + 1, code
		}
		rules = append(rules, outboundRule{kind: tp, target: target, native: native})
	}
	return rules, 0, ""
}
func (r outboundRule) matches(host string, ip netip.Addr, port uint16, network string) bool {
	return r.matchesMetadata(ruleMetadata(host, ip, port, network), C.RuleMatchHelper{})
}
func ruleMetadata(host string, ip netip.Addr, port uint16, network string) *C.Metadata {
	m := &C.Metadata{Host: host, DstIP: ip, DstPort: port, Type: C.TUN, InName: "PingClash", NetWork: C.TCP}
	if network == "udp" {
		m.NetWork = C.UDP
	}
	return m
}
func (r outboundRule) matchesMetadata(m *C.Metadata, h C.RuleMatchHelper) bool {
	if r.set != nil {
		return r.set.matchesMetadata(m, h)
	}
	if r.native != nil {
		matched, _ := r.native.Match(m, h)
		return matched
	}
	return false
}

func (s *vpnSession) configure(raw string) error {
	o, err := decodeRuntimeOptions(raw)
	if err != nil {
		return err
	}
	rules, _, code := compileRuntimeRules(o.Rules, o.Definition)
	if code != "" {
		if o.Mode == "rule" {
			return errors.New(code)
		}
		rules = nil // Keep invalid inactive rules staged; never install a partial table.
	}
	s.rulesValidated = code == ""
	s.options = o
	s.targets = map[string]C.ProxyAdapter{}
	s.direct = outbound.NewDirect()
	s.rules = rules
	ownerRules := o
	ownerRules.Mode = "rule"
	s.processLookup = rulesNeedProcess(ownerRules)
	if s.liveExecution && o.Definition != "" {
		return nil
	}
	s.sniffer, err = newNativeSniffer(o.Definition)
	if err != nil {
		return err
	}
	s.rules = rules
	s.trackDomains = o.InspectConnections
	if o.Mode == "rule" {
		for _, r := range rules {
			if strings.HasPrefix(r.kind, "DOMAIN") || r.kind == "GEOSITE" || r.kind == "RULE-SET" || r.kind == "AND" || r.kind == "OR" || r.kind == "NOT" {
				s.trackDomains = true
				break
			}
		}
	}
	if o.Mode != "rule" {
		s.nativeDNS, err = newNativeDNSRuntime(o.Definition, s)
		return err
	}
	var graph *importGraph
	for _, target := range runtimeRuleTargets(rules) {
		if target == "DIRECT" || target == "REJECT" || target == "PROXY" || target == "PASS-RULE" || s.targets[target] != nil {
			continue
		}
		if len(s.targets) >= maxRuleTargets {
			return errors.New("rule_target_limit")
		}
		if graph == nil {
			graph, err = parseLocalDefinition(o.Definition)
			if err != nil {
				return err
			}
		}
		p, e := graph.runtime(target)
		if e != nil {
			return e
		}
		s.targets[target] = p
	}
	s.nativeDNS, err = newNativeDNSRuntime(o.Definition, s)
	return err
}
func localDestination(ip netip.Addr) bool {
	return ip.IsValid() && (ip.IsPrivate() || ip.IsLoopback() || ip.IsLinkLocalUnicast())
}

// Application-owned destination switches. Both the component router and the
// complete native tunnel apply them before any profile mode or rule:
// "BLOCKED" (unspecified, multicast, IPv6 while disabled), "DIRECT" or
// "REJECT" for local networks, or "" when profile routing decides.
func (s *vpnSession) devicePolicy(ip netip.Addr) string {
	ip = ip.Unmap()
	if ip.IsValid() && (ip.IsUnspecified() || ip.IsMulticast() || !s.options.IPv6 && ip.Is6()) {
		return "BLOCKED"
	}
	if localDestination(ip) && s.options.LAN != "proxy" {
		if s.options.LAN == "direct" {
			return "DIRECT"
		}
		return "REJECT"
	}
	return ""
}
func (s *vpnSession) route(destination M.Socksaddr, network string) (C.ProxyAdapter, string, error) {
	return s.routeFrom(M.Metadata{Destination: destination}, network)
}
func (s *vpnSession) routeFromMetadata(m *C.Metadata) (C.ProxyAdapter, string, error) {
	return s.routeMetadata(m)
}
func (s *vpnSession) routeFrom(flow M.Metadata, network string) (C.ProxyAdapter, string, error) {
	return s.routeMetadata(s.flowMetadata(flow, network))
}
func (s *vpnSession) flowMetadata(flow M.Metadata, network string) *C.Metadata {
	destination := flow.Destination
	ip := destination.Addr.Unmap()
	host := strings.ToLower(strings.TrimSuffix(destination.Fqdn, "."))
	dnsMode := C.DNSNormal
	if host == "" {
		host = s.hostFor(ip)
		if host != "" {
			dnsMode = C.DNSMapping
		}
	}
	if s.nativeDNS != nil {
		if name, ok := s.nativeDNS.mapper.FindHostByIP(ip); ok {
			host = name
			dnsMode = C.DNSMapping
		}
		if s.nativeDNS.mapper.IsFakeIP(ip) {
			ip = netip.Addr{}
			dnsMode = C.DNSFakeIP
		}
	}
	m := ruleMetadata(host, ip, destination.Port, network)
	m.DNSMode = dnsMode
	m.SrcIP = flow.Source.Addr.Unmap()
	m.SrcPort = flow.Source.Port
	m.DSCP = s.root().traffic.lookup(flow, network)
	return m
}
func (s *vpnSession) routeMetadata(metadata *C.Metadata) (C.ProxyAdapter, string, error) {
	destination := M.Socksaddr{Addr: metadata.DstIP, Fqdn: metadata.Host, Port: metadata.DstPort}
	policy := s.devicePolicy(destination.Addr)
	if policy == "BLOCKED" {
		s.root().rejected.Add(1)
		return nil, "REJECT", errors.New("destination_blocked")
	}
	target := "PROXY"
	if policy != "" {
		target = policy
	} else if s.routingMode() == "direct" {
		target = "DIRECT"
	} else if s.routingMode() == "rule" {
		helper := connectionRuleHelper(metadata)
		if s.nativeDNS != nil {
			helper.ResolveIP = func() {
				if metadata.DstIP.IsValid() || metadata.Host == "" {
					return
				}
				ctx, cancel := context.WithTimeout(s.ctx, 8*time.Second)
				defer cancel()
				metadata.DstIP, _ = s.nativeDNS.resolve(ctx, metadata.Host)
			}
		}
		for _, r := range s.rules {
			if matched, actual := r.native.Match(metadata, helper); matched {
				target = actual
				break
			}
		}
	}
	switch target {
	case "REJECT":
		s.root().rejected.Add(1)
		return nil, target, errors.New("rule_rejected")
	case "DIRECT":
		return s.direct, target, nil
	case "PROXY":
		return s.proxy, target, nil
	default:
		if p := s.targets[target]; p != nil {
			return p, target, nil
		}
		return nil, "REJECT", errors.New("rule_target_unavailable")
	}
}
func (s *vpnSession) dnsBlocked(host string) bool {
	if s.routingMode() != "rule" {
		return false
	}
	for _, r := range s.rules {
		if matched, actual := r.native.Match(ruleMetadata(strings.ToLower(strings.TrimSuffix(host, ".")), netip.Addr{}, 53, "udp"), C.RuleMatchHelper{}); matched {
			return actual == "REJECT"
		}
	}
	return false
}

type dnsAssociation struct {
	host      string
	expires   time.Time
	ambiguous bool
}

func (s *vpnSession) rememberHost(ip netip.Addr, host string, ttl uint32) {
	if s.owner != nil {
		s.owner.rememberHost(ip, host, ttl)
		return
	}
	if !ip.IsValid() {
		return
	}
	ip = ip.Unmap()
	if ttl > 300 {
		ttl = 300
	}
	if ttl == 0 {
		return
	}
	host = strings.ToLower(strings.TrimSuffix(host, "."))
	s.mu.Lock()
	defer s.mu.Unlock()
	if len(s.hosts) >= 1024 {
		for key, item := range s.hosts {
			if time.Now().After(item.expires) {
				delete(s.hosts, key)
			}
		}
		if len(s.hosts) >= 1024 {
			return
		}
	}
	old := s.hosts[ip]
	ambiguous := time.Now().Before(old.expires) && (old.ambiguous || old.host != host)
	s.hosts[ip] = dnsAssociation{host, time.Now().Add(time.Duration(ttl) * time.Second), ambiguous}
}
func (s *vpnSession) hostFor(ip netip.Addr) string {
	if s.owner != nil {
		return s.owner.hostFor(ip)
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	item := s.hosts[ip.Unmap()]
	if item.ambiguous || time.Now().After(item.expires) {
		return ""
	}
	return item.host
}
func (s *vpnSession) dialWith(ctx context.Context, destination M.Socksaddr, proxy C.ProxyAdapter) (C.Conn, error) {
	metadata := &C.Metadata{NetWork: C.TCP, Type: C.TUN}
	if err := metadata.SetRemoteAddress(destination.String()); err != nil {
		return nil, err
	}
	if err := s.restoreDNSDestination(ctx, metadata, false); err != nil {
		return nil, err
	}
	var err error
	proxy, err = flowProxy(ctx, proxy, metadata)
	if err != nil {
		return nil, err
	}
	direct := directAdapter(proxy, metadata)
	if err := s.resolveRestoredDestination(ctx, metadata, direct || proxy.IsL3Protocol(metadata), direct); err != nil {
		return nil, err
	}
	if metadata.DstIP.IsValid() && proxy.IsL3Protocol(metadata) {
		metadata.Host = ""
	}
	return proxy.DialContext(ctx, metadata)
}

func flowProxy(ctx context.Context, p C.ProxyAdapter, m *C.Metadata) (C.ProxyAdapter, error) {
	if graph, ok := p.(*runtimeGraph); ok {
		if e := graph.activate(ctx); e != nil {
			return nil, e
		}
	}
	for depth := 0; depth < 16; depth++ {
		next := p.Unwrap(m, false)
		if next == nil {
			return p, nil
		}
		p = next.Adapter()
	}
	return nil, errors.New("proxy_chain")
}

func directAdapter(p C.ProxyAdapter, m *C.Metadata) bool {
	for depth := 0; depth < 16; depth++ {
		if p.Type() == C.Direct || p.Type() == C.Compatible {
			return true
		}
		child := p.Unwrap(m, false)
		if child == nil {
			return false
		}
		p = child.Adapter()
	}
	return false
}
