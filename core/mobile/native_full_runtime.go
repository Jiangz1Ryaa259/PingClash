// SPDX-License-Identifier: GPL-3.0-only
package mobile

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"net"
	"net/netip"
	"os"
	"runtime"
	"sort"
	"strconv"
	"sync"
	"sync/atomic"

	"github.com/metacubex/mihomo/adapter"
	"github.com/metacubex/mihomo/adapter/outboundgroup"
	"github.com/metacubex/mihomo/component/resolver"
	"github.com/metacubex/mihomo/config"
	C "github.com/metacubex/mihomo/constant"
	P "github.com/metacubex/mihomo/constant/provider"
	MDNS "github.com/metacubex/mihomo/dns"
	"github.com/metacubex/mihomo/hub/executor"
	"github.com/metacubex/mihomo/log"
	"github.com/metacubex/mihomo/tunnel"
	"github.com/metacubex/mihomo/tunnel/statistic"
	M "github.com/metacubex/sing/common/metadata"
	"go.yaml.in/yaml/v3"
)

// Only the live Android VPN installs the singleton native executor. Offline
// import and latency probes continue to use private graphs and never apply a
// profile globally. No raw config, parse error or native snapshot is exported.
type fullNativeRuntime struct {
	cfg       *config.Config
	primary   *nativePrimary
	closeOnce sync.Once
	stopOnce  sync.Once
	stopped   atomic.Bool
	trafficMu sync.Mutex
	users     int
	closing   bool
}
type nativePrimary struct {
	C.ProxyAdapter
	once     sync.Once
	borrowed bool
}

func (p *nativePrimary) closeOwned() {
	p.once.Do(func() {
		runtime.SetFinalizer(p, nil)
		if !p.borrowed {
			_ = p.ProxyAdapter.Close()
		}
	})
}
func (p *nativePrimary) Close() error { return nil } // Flow lifetime is retained by AddRef/owned connection below.
func (p *nativePrimary) DialContext(ctx context.Context, m *C.Metadata) (C.Conn, error) {
	c, e := p.ProxyAdapter.DialContext(ctx, m)
	if e != nil {
		return nil, e
	}
	return &nativePrimaryConn{Conn: c, owner: p}, nil
}
func (p *nativePrimary) ListenPacketContext(ctx context.Context, m *C.Metadata) (C.PacketConn, error) {
	c, e := p.ProxyAdapter.ListenPacketContext(ctx, m)
	if e != nil {
		return nil, e
	}
	return &nativePrimaryPacket{PacketConn: c, owner: p}, nil
}

type nativePrimaryConn struct {
	C.Conn
	owner *nativePrimary
}
type nativePrimaryPacket struct {
	C.PacketConn
	owner *nativePrimary
}

// C.Conn/C.PacketConn do not include Upstream. Re-expose the wrapped
// protocol so sing can discover its front/rear padding and MTU requirements.
// Do not mark these leases replaceable: the copy must retain the owner.
func (c *nativePrimaryConn) Upstream() any   { return c.Conn }
func (c *nativePrimaryPacket) Upstream() any { return c.PacketConn }

// Stop health work at retirement, but let native AutoClose adapters retain
// already-open TCP/UDP connections. Explicit Close would kill multiplexed
// streams that legitimately outlive a configuration reload.
func (n *fullNativeRuntime) retire() {
	n.closeOnce.Do(func() {
		for _, p := range n.cfg.Providers {
			if closer, ok := p.(io.Closer); ok {
				_ = closer.Close()
			}
		}
		for _, p := range n.cfg.RuleProviders {
			if closer, ok := p.(io.Closer); ok {
				_ = closer.Close()
			}
		}
	})
}

// Set while the live VPN has installed the singleton native executor.
var nativeExecutorLive atomic.Bool

type initializedRuleProvider struct{ P.RuleProvider }

func (initializedRuleProvider) Initial() error { return nil }

// bootstrapNameserver resolves DoH and other domain-named servers. It keeps the user's
// VPN DNS port and the same TCP transport as the main nameserver; Addr() alone sent
// bootstrap queries to UDP port 53 even when the VPN DNS was, say, 127.0.0.1:5353.
func bootstrapNameserver(dns netip.AddrPort) string { return "tcp://" + dns.String() }

func prepareFullNative(s *vpnSession) (*fullNativeRuntime, error) {
	// The complete profile could not be loaded by the native executor; distinct
	// from runtime settings, so the app does not point users at network/DNS.
	invalid := &vpnStartupError{"profile_invalid", errors.New("profile_invalid")}
	var root map[string]any
	if json.Unmarshal([]byte(s.options.Definition), &root) != nil || root == nil {
		return nil, invalid
	}
	graph, e := parseLocalDefinition(s.options.Definition)
	if e != nil {
		return nil, invalid
	}
	// Copy only data. Device listeners, filesystem sources, controller settings
	// and updater directives have a single application-owned policy boundary.
	for key := range root {
		if androidOwnedDirective(key) {
			delete(root, key)
		}
	}
	nodes := []map[string]any{}
	groupCandidates := []any{}
	meshNodes := map[string]map[string]any{}
	for _, item := range root["proxies"].([]any) {
		original, ok := item.(map[string]any)
		if !ok {
			return nil, invalid
		}
		m := normalizedImportNode(original)
		groupCandidates = append(groupCandidates, m)
		name, _ := m["name"].(string)
		if graph.nodeUnavailable(name) {
			m = map[string]any{"name": name, "type": "reject"}
		} else {
			checked := map[string]any{}
			for key, value := range m {
				if key != "dialer-proxy" {
					checked[key] = value
				}
			}
			encoded, _ := json.Marshal(checked)
			strict, e := strictNodeObject(string(encoded))
			if e != nil || !nativeMappingValid(strict, nodeFields(stringOption(m, "type")), 0) {
				return nil, invalid
			}
			if !prepareNativeNode(m) {
				return nil, invalid
			}
			if meshKind(stringOption(m, "type")) {
				meshNodes[name] = m
				m = map[string]any{"type": "direct", "name": name}
			}
		}
		nodes = append(nodes, m)
	}
	// PROXY remains the app's selected primary when no official group uses it.
	aliasPresent := false
	for _, m := range nodes {
		if m["name"] == "PROXY" {
			aliasPresent = true
		}
	}
	for name := range graph.groups {
		if name == "PROXY" {
			aliasPresent = true
		}
	}
	if !aliasPresent {
		// Only rule targets may use the application alias. A missing named
		// dialer/group dependency must not become a synthetic direct or a cycle.
		for _, node := range nodes {
			if node["dialer-proxy"] == "PROXY" {
				return nil, invalid
			}
		}
		for _, group := range graph.groups {
			for _, member := range group.Proxies {
				if member == "PROXY" {
					return nil, invalid
				}
			}
		}
		nodes = append(nodes, map[string]any{"name": "PROXY", "type": "reject"})
	}
	root["proxies"] = nodes
	groups := []map[string]any{}
	if original, ok := root["proxy-groups"].([]any); ok {
		for _, value := range original {
			m, ok := value.(map[string]any)
			if !ok {
				return nil, invalid
			}
			normalized, reason := normalizeGroup(m, groupCandidates)
			if reason != "" {
				return nil, invalid
			}
			groups = append(groups, normalized)
		}
	}
	root["proxy-groups"] = groups
	if hosts, ok := root["hosts"].(map[string]any); ok && !boundedNativeHosts(hosts) {
		return nil, invalid
	}
	providers := map[string]map[string]any{}
	if packed, ok := root[providerRuleDataKey].(string); ok {
		sets, e := decodeStoredRuleSets(packed)
		if e != nil {
			return nil, invalid
		}
		for name, set := range sets {
			entries := set.Entries
			// Classical rules use the bounded native leaf adapters already compiled by
			// configure(). Immutable runtime providers restore classical matchers below.
			if set.Behavior == "classical" {
				entries = []string{}
			}
			providers[name] = map[string]any{"type": "inline", "behavior": set.Behavior, "payload": entries}
		}
	}
	root["rule-providers"] = providers
	delete(root, "proxy-providers")
	root["rules"] = s.options.Rules
	if len(s.options.Rules) == 0 || !s.rulesValidated {
		root["rules"] = []string{"MATCH,PROXY"}
	}
	b, e := yaml.Marshal(root)
	if e != nil {
		return nil, invalid
	}
	raw, e := config.UnmarshalRawConfig(b)
	if e != nil {
		return nil, invalid
	}
	if ordered, ok := root[nativeDNSDocumentKey].(string); ok && s.options.UseSubscriptionDNS {
		fragment, e := config.UnmarshalRawConfig([]byte(ordered))
		if e != nil {
			return nil, invalid
		}
		raw.DNS = fragment.DNS
	}
	if dns, ok := root["dns"].(map[string]any); ok && s.options.UseSubscriptionDNS {
		if _, present := dns["enable"]; !present {
			raw.DNS.Enable = true
		}
	}
	if !s.options.UseSubscriptionDNS || root["dns"] == nil || !raw.DNS.Enable {
		server := "tcp://" + s.dns.String()
		if s.options.DNSMode == "https" {
			server = s.options.DNSHTTPS
		}
		raw.DNS = config.DefaultRawConfig().DNS
		raw.DNS.Enable = true
		raw.DNS.NameServer = []string{server}
		raw.DNS.DefaultNameserver = []string{bootstrapNameserver(s.dns)}
		raw.DNS.Fallback = nil
		raw.DNS.FallbackFilter.GeoIP = false
		raw.DNS.IPv6 = s.options.IPv6
	}
	raw.DNS.Listen = ""
	raw.DNS.ListenRoutingMark = 0
	raw.Profile.StoreSelected = false
	raw.Profile.StoreFakeIP = false
	raw.Tun.Enable = false
	raw.Tun.AutoRoute = false
	raw.Tun.AutoDetectInterface = false
	raw.NTP.Enable = false
	raw.NTP.WriteToSystem = false
	raw.IPTables.Enable = false
	raw.ExternalController = ""
	raw.ExternalControllerTLS = ""
	raw.ExternalControllerUnix = ""
	raw.ExternalControllerPipe = ""
	raw.ExternalDohServer = ""
	raw.ExternalUI = ""
	raw.ExternalUIURL = ""
	raw.Secret = ""
	raw.Port = 0
	raw.SocksPort = 0
	raw.MixedPort = 0
	raw.RedirPort = 0
	raw.TProxyPort = 0
	raw.ShadowSocksConfig = ""
	raw.VmessConfig = ""
	raw.TuicServer.Enable = false
	raw.Listeners = nil
	raw.Tunnels = nil
	raw.Authentication = nil
	raw.AllowLan = false
	raw.BindAddress = "127.0.0.1"
	raw.GeoXUrl = config.RawGeoXUrl{}
	if len(raw.DNS.DefaultNameserver) == 0 {
		raw.DNS.DefaultNameserver = []string{bootstrapNameserver(s.dns)}
	}
	raw.GeoAutoUpdate = false
	raw.GeodataMode = true
	raw.GeodataLoader = "memconservative"
	raw.LogLevel = log.SILENT
	raw.Interface = ""
	raw.RoutingMark = 0
	// The parser temporarily updates global general settings. Hold its mode and
	// IPv6 at the currently installed values until the entire candidate passes.
	nativeConfigParse.Lock()
	previousIPv6 := resolver.DisableIPv6
	raw.Mode = tunnel.Mode()
	raw.IPv6 = !previousIPv6
	cfg, e := func() (*config.Config, error) {
		defer nativeConfigParse.Unlock()
		defer func() { resolver.DisableIPv6 = previousIPv6 }()
		return config.ParseRawConfig(raw)
	}()
	if e != nil {
		return nil, invalid
	}
	n := &fullNativeRuntime{cfg: cfg}
	completed := false
	defer func() {
		if !completed {
			n.retire()
			for _, p := range cfg.Proxies {
				_ = p.Close()
			}
		}
	}()
	meshNamespaces := map[string]string{}
	for name, mapping := range meshNodes {
		// Slots are not published yet; groups already reference these exact
		// Proxy objects, so replacing the adapter preserves all group membership.
		proxy, e := acquireMesh(mapping, nil)
		if e != nil {
			return nil, invalid
		}
		slot, ok := cfg.Proxies[name].(*adapter.Proxy)
		if !ok {
			_ = proxy.Close()
			return nil, invalid
		}
		slot.ProxyAdapter = proxy
		meshNamespaces[name] = proxy.(*meshLease).entry.namespace
	}
	rewriteMeshDNS(cfg.DNS, meshNamespaces)
	for name, p := range cfg.Proxies {
		if _, group := graph.groups[name]; group || name == "GLOBAL" {
			continue
		}
		slot, ok := p.(*adapter.Proxy)
		if !ok {
			return nil, invalid
		}
		slot.ProxyAdapter = &nativeLeasedAdapter{ProxyAdapter: slot.ProxyAdapter, generation: n}
	}
	cfg.General.IPv6 = s.options.IPv6
	switch s.options.Mode {
	case "direct":
		cfg.General.Mode = tunnel.Direct
	case "global":
		cfg.General.Mode = tunnel.Global
	default:
		cfg.General.Mode = tunnel.Rule
	}
	// Keep bounded regexp and private provider wrappers in the complete native
	// tunnel rule engine, including native rematch and named sub-rule selection.
	if len(s.rules) > 0 {
		cfg.Rules = nil
		for _, rule := range s.rules {
			cfg.Rules = append(cfg.Rules, rule.native)
		}
	}
	var sections map[string][]string
	if data, ok := root["sub-rules"]; ok {
		encoded, _ := json.Marshal(data)
		if json.Unmarshal(encoded, &sections) != nil {
			return nil, invalid
		}
	}
	for name, lines := range sections {
		rules, _, code := compileRuntimeRules(lines, s.options.Definition)
		if code != "" {
			return nil, invalid
		}
		cfg.SubRules[name] = nil
		for _, r := range rules {
			cfg.SubRules[name] = append(cfg.SubRules[name], r.native)
		}
	}
	for name, p := range cfg.RuleProviders {
		if p.Initial() != nil {
			return nil, invalid
		}
		cfg.RuleProviders[name] = initializedRuleProvider{p}
	}
	if packed, ok := root[providerRuleDataKey].(string); ok {
		if privateStoredProviders(packed, cfg.RuleProviders) != nil {
			return nil, invalid
		}
	}
	picks, _ := root["pingclash-selections"].(map[string]any)
	names := make([]string, 0, len(picks))
	for name := range picks {
		names = append(names, name)
	}
	sort.Strings(names)
	for _, name := range names {
		if p := cfg.Proxies[name]; p != nil {
			if selectable, ok := p.Adapter().(outboundgroup.SelectAble); ok {
				if pick, ok := picks[name].(string); ok {
					selectable.ForceSet(pick)
				}
			}
		}
	}
	primary := s.proxy
	borrowed := false
	if native := cfg.Proxies[s.primaryTarget]; native != nil {
		primary = native
		borrowed = true
	} else if s.requiredPrimary {
		return nil, &vpnStartupError{"node_invalid", errors.New("primary_missing")}
	}
	if !borrowed {
		primary = &nativeLeasedAdapter{ProxyAdapter: primary, generation: n}
	}
	n.primary = &nativePrimary{ProxyAdapter: primary, borrowed: borrowed}
	runtime.SetFinalizer(n.primary, (*nativePrimary).closeOwned)
	// The global selector is application-owned. Other official groups and their
	// order/membership remain untouched in the stored profile and runtime graph.
	cfg.Proxies["GLOBAL"] = adapter.NewProxy(&namedAdapter{n.primary, "GLOBAL"})
	if !aliasPresent {
		cfg.Proxies["PROXY"].(*adapter.Proxy).ProxyAdapter = &namedAdapter{n.primary, "PROXY"}
	}
	if !nativeHasNoDeviceServices(cfg) {
		return nil, invalid
	}
	completed = true
	return n, nil
}

func rewriteMeshDNS(d *config.DNS, names map[string]string) {
	bind := func(servers []MDNS.NameServer) {
		for i := range servers {
			if servers[i].Net == "tailscale" || servers[i].Net == "easytier" {
				if namespace, ok := names[servers[i].Addr]; ok {
					servers[i].Addr = namespace
				}
			}
		}
	}
	bind(d.NameServer)
	bind(d.Fallback)
	bind(d.DefaultNameserver)
	bind(d.ProxyServerNameserver)
	bind(d.DirectNameServer)
	for i := range d.NameServerPolicy {
		bind(d.NameServerPolicy[i].NameServers)
	}
	for i := range d.ProxyServerPolicy {
		bind(d.ProxyServerPolicy[i].NameServers)
	}
}

func nativeHasNoDeviceServices(c *config.Config) bool {
	g := c.General
	return g.Port == 0 && g.SocksPort == 0 && g.MixedPort == 0 && g.RedirPort == 0 && g.TProxyPort == 0 &&
		g.ShadowSocksConfig == "" && g.VmessConfig == "" && !g.Tun.Enable && !g.TuicServer.Enable &&
		len(c.Listeners) == 0 && len(c.Tunnels) == 0 && c.DNS.Listen == "" && !c.NTP.Enable && !c.IPTables.Enable &&
		c.Controller.ExternalController == "" && c.Controller.ExternalControllerTLS == "" && c.Controller.ExternalControllerUnix == "" &&
		c.Controller.ExternalControllerPipe == "" && c.Controller.ExternalUI == "" && c.Controller.ExternalUIURL == "" &&
		c.TLS.Certificate == "" && c.TLS.PrivateKey == "" && len(c.TLS.CustomTrustCert) == 0 && !c.General.GeoAutoUpdate
}
func (n *fullNativeRuntime) apply() error {
	if !nativeHasNoDeviceServices(n.cfg) {
		return errors.New("options_invalid")
	}
	nativeConfigParse.Lock()
	defer nativeConfigParse.Unlock()
	_ = os.Setenv("QUIC_GO_DISABLE_GSO", strconv.FormatBool(n.cfg.Experimental.QUICGoDisableGSO))
	_ = os.Setenv("QUIC_GO_DISABLE_ECN", strconv.FormatBool(n.cfg.Experimental.QUICGoDisableECN))
	executor.ApplyConfig(n.cfg, true)
	return nil
}
func closeNativeFlows() {
	statistic.DefaultManager.Range(func(tracker statistic.Tracker) bool { _ = tracker.Close(); return true })
}

func (n *fullNativeRuntime) stop() {
	n.stopOnce.Do(func() {
		n.trafficMu.Lock()
		n.closing = true
		n.trafficMu.Unlock()
		n.retire()
		for _, p := range n.cfg.Proxies {
			_ = p.Close()
		}
		if n.primary != nil {
			n.primary.closeOwned()
		}
		n.stopped.Store(true)
	})
}
func saveNativeGlobals() func() {
	general := executor.GetGeneral()
	ip4p := resolver.GetIP4PEnable()
	env := map[string]*string{}
	for _, key := range []string{"QUIC_GO_DISABLE_GSO", "QUIC_GO_DISABLE_ECN"} {
		if value, ok := os.LookupEnv(key); ok {
			copy := value
			env[key] = &copy
		} else {
			env[key] = nil
		}
	}
	main, proxy, direct := resolver.DefaultResolver, resolver.ProxyServerHostResolver, resolver.DirectHostResolver
	mapper, service, hosts := resolver.DefaultHostMapper, resolver.DefaultService, resolver.DefaultHosts
	ipv6, systemHosts := resolver.DisableIPv6, resolver.UseSystemHosts
	return func() {
		nativeConfigParse.Lock()
		defer nativeConfigParse.Unlock()
		nativeExecutorLive.Store(false)
		tunnel.OnSuspend()
		closeNativeFlows()
		resolver.ResetConnection()
		tunnel.UpdateRules(nil, nil, map[string]P.RuleProvider{})
		tunnel.UpdateProxies(map[string]C.Proxy{}, map[string]P.ProxyProvider{})
		restoreNativeGeneral(general, false)
		tunnel.SetSniffing(general.Sniffing)
		resolver.SetIP4PEnable(ip4p)
		for key, value := range env {
			if value == nil {
				_ = os.Unsetenv(key)
			} else {
				_ = os.Setenv(key, *value)
			}
		}
		resolver.DefaultResolver = main
		resolver.ProxyServerHostResolver = proxy
		resolver.DirectHostResolver = direct
		resolver.DefaultHostMapper = mapper
		resolver.DefaultService = service
		resolver.DefaultHosts = hosts
		resolver.DisableIPv6 = ipv6
		resolver.UseSystemHosts = systemHosts
	}
}
func (s *vpnSession) startFullNative() error {
	if s.options.Definition == "" {
		return nil
	}
	n, e := prepareFullNative(s)
	if e != nil {
		return e
	}
	s.native = n
	s.nativeRestore = saveNativeGlobals()
	if e = n.apply(); e != nil {
		s.nativeRestore()
		s.nativeRestore = nil
		n.stop()
		s.native = nil
		return e
	}
	nativeExecutorLive.Store(true)
	n.activatePrimary(s)
	return nil
}

func (n *fullNativeRuntime) activatePrimary(s *vpnSession) {
	if n.primary.borrowed {
		old := s.proxy
		s.proxy = n.primary.ProxyAdapter
		_ = old.Close()
	}
	// The complete executor owns DNS/fake-IP state. Do not retain a second
	// component-only preflight cache for the same live profile.
	if s.nativeDNS != nil {
		s.nativeDNS.release()
		s.nativeDNS = nil
	}
}
func (s *vpnSession) nativeTCP(conn net.Conn, flow M.Metadata) error {
	m := ruleMetadata(flow.Destination.Fqdn, flow.Destination.Addr, flow.Destination.Port, "tcp")
	m.SrcIP = flow.Source.Addr
	m.SrcPort = flow.Source.Port
	m.RawDstAddr = flow.Destination.TCPAddr()
	m.DSCP = s.root().traffic.lookup(flow, "tcp")
	if !s.applyDevicePolicy(m) {
		return errors.New("destination_blocked")
	}
	if s.processLookup && s.routingMode() == "rule" {
		connectionRuleHelper(m).FindProcess()
	}
	root := s.root()
	record, ok := root.openRecord("tcp", "—", flow.Destination, conn, s)
	if !ok {
		return net.ErrClosed
	}
	root.mu.Lock()
	record.nativeKey = nativeFlowKey("tcp", flow.Source.String(), flow.Destination.String())
	root.mu.Unlock()
	defer root.endRecord(record.id)
	tunnel.Tunnel.HandleTCPConn(&nativeInbound{Conn: conn, root: root, record: record}, m)
	return nil
}

// The complete tunnel never sees the application LAN/IPv6 switches. Pin local
// destinations to DIRECT ahead of mode and rules, or refuse the flow.
func (s *vpnSession) applyDevicePolicy(m *C.Metadata) bool {
	switch s.devicePolicy(m.DstIP) {
	case "BLOCKED", "REJECT":
		s.root().rejected.Add(1)
		return false
	case "DIRECT":
		m.SpecialProxy = "DIRECT"
	}
	return true
}

type nativeInbound struct {
	net.Conn
	root   *vpnSession
	record *connectionRecord
}

// Expose the TUN connection for buffer sizing and half-close. Not replaceable:
// reads and writes must keep going through the counters above.
func (c *nativeInbound) Upstream() any { return c.Conn }

func (c *nativeInbound) Read(p []byte) (int, error) {
	n, e := c.Conn.Read(p)
	c.root.up.Add(int64(n))
	c.record.up.Add(int64(n))
	return n, e
}
func (c *nativeInbound) Write(p []byte) (int, error) {
	n, e := c.Conn.Write(p)
	c.root.down.Add(int64(n))
	c.record.down.Add(int64(n))
	return n, e
}
