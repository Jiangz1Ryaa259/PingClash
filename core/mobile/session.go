// SPDX-License-Identifier: GPL-3.0-only
package mobile

import (
	"context"

	"encoding/json"
	"errors"
	"io"
	"net"
	"net/http"
	"net/netip"
	"runtime"
	"runtime/metrics"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/metacubex/mihomo/adapter/outbound"
	MN "github.com/metacubex/mihomo/common/net"
	"github.com/metacubex/mihomo/component/sniffer"
	C "github.com/metacubex/mihomo/constant"
	tun "github.com/metacubex/sing-tun"
	"github.com/metacubex/sing/common/buf"
	"github.com/metacubex/sing/common/logger"
	M "github.com/metacubex/sing/common/metadata"
	N "github.com/metacubex/sing/common/network"
	"github.com/miekg/dns"
)

type vpnSession struct {
	traffic        trafficClasses
	owner          *vpnSession
	routingMu      sync.Mutex
	currentRouting *routingEpoch
	retiredRouting map[*routingEpoch]struct{}
	drainingNative map[*fullNativeRuntime]*time.Timer
	dohMu          sync.Mutex
	dohTransport   *http.Transport

	ctx             context.Context
	cancel          context.CancelFunc
	proxy           C.ProxyAdapter
	primaryTarget   string
	liveExecution   bool
	requiredPrimary bool
	dns             netip.AddrPort
	device          tun.Tun
	stack           tun.Stack
	mu              sync.Mutex
	resources       map[io.Closer]struct{}
	udp             map[string]*udpFlow
	queuedUDPBytes  atomic.Int64
	dnsSlots        chan struct{}
	up              atomic.Int64
	down            atomic.Int64
	failures        atomic.Int64
	closeOnce       sync.Once
	options         runtimeOptions
	// The parsed options stay immutable; native mode changes are published atomically.
	liveMode         atomic.Pointer[string]
	rules            []outboundRule
	rulesValidated   bool
	processLookup    bool
	selection        *selectionState
	targets          map[string]C.ProxyAdapter
	direct           C.ProxyAdapter
	hosts            map[netip.Addr]dnsAssociation
	trackDomains     bool
	started          time.Time
	connections      map[uint64]*connectionRecord
	serial           atomic.Uint64
	totalConnections atomic.Int64
	dnsQueries       atomic.Int64
	rejected         atomic.Int64
	nativeDNS        *nativeDNSRuntime
	sniffer          *sniffer.Dispatcher
	native           *fullNativeRuntime
	nativeRestore    func()
	nativePackets    map[string]*nativeUDPBridge
}
type udpFlow struct {
	counted     bool
	queue       chan []byte
	writer      N.PacketWriter
	destination M.Socksaddr
	source      M.Socksaddr
}

var vpnState struct {
	sync.Mutex
	session *vpnSession
}

func newSession(configJSON, dnsAddress string) (*vpnSession, error) {
	return newSessionConfigured(configJSON, dnsAddress, "")
}
func newSessionConfigured(configJSON, dnsAddress, options string) (*vpnSession, error) {
	return newSessionOwned(configJSON, dnsAddress, options, nil)
}
func newSessionOwned(configJSON, dnsAddress, options string, owner *vpnSession, live ...bool) (*vpnSession, error) {
	destination, dnsErr := netip.ParseAddrPort(dnsAddress)
	if dnsErr != nil {
		// A bare IP (the app default "223.5.5.5") means port 53. Keep this parse
		// error out of err: a stale value was reported as node_invalid for every
		// profile-target start, which never overwrites err below.
		ip, parseErr := netip.ParseAddr(dnsAddress)
		if parseErr != nil {
			return nil, errors.New("dns_invalid")
		}
		destination = netip.AddrPortFrom(ip, 53)
	}
	var err error
	if destination.Port() == 0 || destination.Addr().IsUnspecified() || destination.Addr().IsMulticast() {
		return nil, errors.New("dns_invalid")
	}
	liveExecution := len(live) > 0 && live[0]
	plan, optionsError := decodeRuntimeOptions(options)
	if optionsError != nil {
		return nil, &vpnStartupError{"options_invalid", optionsError}
	}
	var header struct{ Type, Name, Target string }
	_ = json.Unmarshal([]byte(configJSON), &header)
	var proxy C.ProxyAdapter
	if liveExecution && plan.Definition != "" && header.Type == "profile-target" {
		// Validate/publish the complete graph once in prepareFullNative. No
		// duplicate group health checks or independent DNS/sniffer caches.
		if len(configJSON) > maxPrivateBridgeBytes || header.Target == "" {
			return nil, errors.New("node_invalid")
		}
		proxy = outbound.NewDirect()
	} else {
		proxy, err = parseNode(configJSON)
	}
	if err != nil {
		return nil, &vpnStartupError{"node_invalid", err}
	}
	ctx, cancel := context.WithCancel(func() context.Context {
		if owner != nil {
			return owner.ctx
		}
		return context.Background()
	}())
	s := &vpnSession{liveExecution: liveExecution, owner: owner, ctx: ctx, cancel: cancel, proxy: proxy, dns: destination, started: time.Now(), hosts: map[netip.Addr]dnsAssociation{}, connections: map[uint64]*connectionRecord{},
		resources: make(map[io.Closer]struct{}), udp: make(map[string]*udpFlow), dnsSlots: make(chan struct{}, 16)}
	s.primaryTarget = header.Name
	if header.Type == "profile-target" {
		s.primaryTarget = header.Target
		s.requiredPrimary = liveExecution
	}
	if err := s.configure(options); err != nil {
		s.close()
		code := err.Error()
		switch code {
		case "options_invalid", "dns_invalid", "rule_limit", "rule_invalid", "rule_unsupported", "rule_metadata_unavailable", "rule_provider_invalid", "rule_target_limit", "rule_data_unavailable", "rule_geo_unknown":
		default:
			code = "rule_target_invalid"
		}
		return nil, &vpnStartupError{code, err}
	}
	if owner == nil {
		s.currentRouting = &routingEpoch{session: s}
		s.retiredRouting = map[*routingEpoch]struct{}{}
		s.drainingNative = map[*fullNativeRuntime]*time.Timer{}
	}
	return s, nil
}

func (s *vpnSession) track(c io.Closer) bool {
	if s.owner != nil {
		return s.owner.track(c)
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.ctx.Err() != nil || len(s.resources) >= s.options.MaxConnections*2 {
		_ = c.Close()
		return false
	}
	s.resources[c] = struct{}{}
	return true
}
func (s *vpnSession) release(c io.Closer) {
	if s.owner != nil {
		s.owner.release(c)
		return
	}
	s.mu.Lock()
	delete(s.resources, c)
	s.mu.Unlock()
	_ = c.Close()
}
func (s *vpnSession) close() {
	s.closeOnce.Do(func() {
		s.cancel()
		if s.nativeDNS != nil {
			activeNativeDNS.CompareAndSwap(s.nativeDNS, nil)
		}
		s.mu.Lock()
		resources := make([]io.Closer, 0, len(s.resources))
		for resource := range s.resources {
			resources = append(resources, resource)
		}
		s.mu.Unlock()
		for _, resource := range resources {
			_ = resource.Close()
		}
		if s.device != nil {
			_ = s.device.Close()
		}
		if s.stack != nil {
			_ = s.stack.Close()
		}
		if s.nativeRestore != nil {
			s.nativeRestore()
			s.nativeRestore = nil
		}
		s.routingMu.Lock()
		epochs := make([]*routingEpoch, 0, len(s.retiredRouting)+1)
		if s.currentRouting != nil {
			epochs = append(epochs, s.currentRouting)
			if d := s.currentRouting.session.nativeDNS; d != nil {
				activeNativeDNS.CompareAndSwap(d, nil)
			}
		}
		for e := range s.retiredRouting {
			epochs = append(epochs, e)
		}
		s.routingMu.Unlock()
		if len(epochs) == 0 {
			s.closeRouting()
		} else {
			for _, e := range epochs {
				if d := e.session.nativeDNS; d != nil {
					d.resolvers.ResetConnection()
				}
			}
			for _, e := range epochs {
				e.close()
				if e.session.native != nil {
					e.session.native.stop()
				}
			}
		}
		s.stopDrainingNative()

	})
}

func (s *vpnSession) startDevice(device tun.Tun) error {
	device = observeTun(device, &s.traffic)
	s.device = device
	options := tun.Options{MTU: uint32(s.options.MTU), Inet4Address: []netip.Prefix{netip.MustParsePrefix("172.29.0.1/30")},
		Inet6Address: []netip.Prefix{netip.MustParsePrefix("fdfe:7069:6e67::1/126")}, Logger: logger.NOP()}
	stack, err := tun.NewStack("mips", tun.StackOptions{Context: s.ctx, Tun: device, TunOptions: options,
		Handler: s, Logger: logger.NOP(), UDPTimeout: time.Duration(s.options.UDPTimeout) * time.Second, ICMPTimeout: time.Second})
	if err != nil {
		return err
	}
	s.stack = stack
	return stack.Start()
}

func (s *vpnSession) NewError(context.Context, error) { s.failures.Add(1) }
func (s *vpnSession) PrepareConnection(network string, _, _ M.Socksaddr, _ tun.DirectRouteContext, _ time.Duration) (tun.DirectRouteDestination, error) {
	if network == N.NetworkICMP {
		return nil, tun.ErrDrop
	}
	return nil, nil // Never create a direct bypass route.
}
func (s *vpnSession) NewConnection(_ context.Context, conn net.Conn, metadata M.Metadata) error {
	defer guard("tcp", conn)
	if !s.track(conn) {
		return net.ErrClosed
	}
	defer s.release(conn)
	destination := metadata.Destination
	if destination.Port == 53 {
		return s.serveTCPDNS(conn)
	}
	routing, release := s.acquireRouting()
	defer release()
	if routing.native != nil {
		return routing.nativeTCP(conn, metadata)
	}
	flowMetadata := routing.flowMetadata(metadata, "tcp")
	if routing.sniffer != nil && routing.sniffer.Enable() {
		buffered := MN.NewBufferedConn(conn)
		routing.sniffer.TCPSniff(buffered, flowMetadata)
		conn = buffered
	}
	proxy, route, err := routing.routeMetadata(flowMetadata)
	if err != nil {
		return err
	}
	record, ok := s.openRecord("tcp", route, destination, conn, routing)
	if !ok {
		return net.ErrClosed
	}
	defer s.endRecord(record.id)
	ctx, cancel := context.WithTimeout(s.ctx, time.Duration(s.options.ConnectTimeout)*time.Second)
	actual := M.Socksaddr{Addr: flowMetadata.DstIP, Fqdn: flowMetadata.Host, Port: flowMetadata.DstPort}
	upstream, err := routing.dialWith(ctx, actual, proxy)
	cancel()
	if err != nil {
		s.failures.Add(1)
		return errors.New("proxy_connect_failed")
	}
	if !s.track(upstream) {
		return net.ErrClosed
	}
	defer s.release(upstream)
	record.closer.(*flowCloser).add(upstream)
	finished := make(chan struct{})
	go func() {
		defer close(finished)
		defer guard("tcp-upload", upstream, conn)
		_, _ = io.Copy(countWriter{upstream, &s.up, &record.up}, conn)
		if half, ok := any(upstream).(interface{ CloseWrite() error }); ok {
			_ = half.CloseWrite()
		}
	}()
	_, _ = io.Copy(countWriter{conn, &s.down, &record.down}, upstream)
	_ = conn.Close()
	_ = upstream.Close()
	<-finished
	return nil
}
func (s *vpnSession) dial(ctx context.Context, destination M.Socksaddr) (C.Conn, error) {
	metadata := &C.Metadata{NetWork: C.TCP, Type: C.TUN}
	if err := metadata.SetRemoteAddress(destination.String()); err != nil {
		return nil, err
	}
	proxy := s.proxy
	if s.routingMode() == "direct" {
		proxy = s.direct
	}
	return proxy.DialContext(ctx, metadata)
}

func (s *vpnSession) NewPacket(_ context.Context, key netip.AddrPort, packet *buf.Buffer, metadata M.Metadata, init func(N.PacketConn) N.PacketWriter) {
	defer guard("udp")
	if s.ctx.Err() != nil || packet.Len() > 65507 {
		packet.Release()
		return
	}
	data := append([]byte(nil), packet.Bytes()...)
	packet.Release()
	if metadata.Destination.Port == 53 {
		select {
		case s.dnsSlots <- struct{}{}:
			writer := init(nil)
			go func() {
				defer func() { <-s.dnsSlots }()
				defer guard("dns")
				s.relayDNS(data, metadata.Destination, writer)
			}()
		default:
			s.failures.Add(1)
		}
		return
	}
	if !s.options.UDP {
		s.failures.Add(1)
		return
	}
	routing, releaseRouting := s.acquireRouting()
	native := routing.native != nil
	if native {
		routing.nativePacket(data, metadata, init(nil))
		releaseRouting()
		return
	}
	releaseRouting()
	flowKey := key.String() + "|" + metadata.Destination.String()
	s.mu.Lock()
	flow := s.udp[flowKey]
	if flow == nil {
		if len(s.udp) >= s.options.MaxConnections {
			s.mu.Unlock()
			s.failures.Add(1)
			return
		}
		flow = &udpFlow{counted: true, queue: make(chan []byte, 32), writer: init(nil), destination: metadata.Destination, source: metadata.Source}
		s.udp[flowKey] = flow
		go s.runUDP(flowKey, flow)
	}
	if s.queuedUDPBytes.Add(int64(len(data))) > 8*1024*1024 {
		s.queuedUDPBytes.Add(-int64(len(data)))
		s.mu.Unlock()
		s.failures.Add(1)
		return
	}
	select {
	case flow.queue <- data:
	default:
		s.queuedUDPBytes.Add(-int64(len(data)))
		s.failures.Add(1)
	}
	s.mu.Unlock()
}

func (s *vpnSession) runUDP(key string, flow *udpFlow) {
	defer guard("udp-flow")
	routing, release := s.acquireRouting()
	defer release()
	defer func() {
		s.mu.Lock()
		if s.udp[key] == flow {
			delete(s.udp, key)
		}
		for len(flow.queue) > 0 {
			payload := <-flow.queue
			s.queuedUDPBytes.Add(-int64(len(payload)))
		}
		s.mu.Unlock()
	}()
	ctx, cancel := context.WithTimeout(s.ctx, 10*time.Second)
	metadata := routing.flowMetadata(M.Metadata{Source: flow.source, Destination: flow.destination}, "udp")
	staged := routing.sniffUDP(ctx, flow, metadata)
	proxy, route, err := routing.routeMetadata(metadata)
	if err != nil || !proxy.SupportUDP() {
		cancel()
		s.failures.Add(1)
		return
	}
	if err = routing.restoreDNSDestination(ctx, metadata, false); err != nil {
		cancel()
		s.failures.Add(1)
		return
	}
	proxy, err = flowProxy(ctx, proxy, metadata)
	if err != nil {
		cancel()
		s.failures.Add(1)
		return
	}
	if err = routing.resolveRestoredDestination(ctx, metadata, proxy.IsL3Protocol(metadata), directAdapter(proxy, metadata)); err != nil {
		cancel()
		s.failures.Add(1)
		return
	}
	if metadata.DstIP.IsValid() && proxy.IsL3Protocol(metadata) {
		metadata.Host = ""
	}
	conn, err := proxy.ListenPacketContext(ctx, metadata)
	if err == nil && !metadata.DstIP.IsValid() && metadata.Host != "" {
		err = conn.ResolveUDP(ctx, metadata)
	}
	cancel()
	if err != nil {
		if conn != nil {
			_ = conn.Close()
		}
		s.failures.Add(1)
		return
	}
	actualDestination := M.Socksaddr{Addr: metadata.DstIP, Fqdn: metadata.Host, Port: metadata.DstPort}
	var packetDestination net.Addr = actualDestination
	if metadata.DstIP.IsValid() {
		packetDestination = metadata.UDPAddr()
	}
	if !s.track(conn) {
		return
	}
	defer s.release(conn)
	record, ok := s.openRecord("udp", route, flow.destination, conn, routing)
	if !ok {
		return
	}
	defer s.endRecord(record.id)
	ended := make(chan struct{})
	defer close(ended)
	go func() {
		defer guard("udp-upload", conn)
		for _, payload := range staged {
			_ = conn.SetWriteDeadline(time.Now().Add(10 * time.Second))
			n, err := conn.WriteTo(payload, packetDestination)
			if err != nil {
				s.failures.Add(1)
				_ = conn.Close()
				return
			}
			s.up.Add(int64(n))
			record.up.Add(int64(n))
		}
		for {
			select {
			case payload := <-flow.queue:
				s.queuedUDPBytes.Add(-int64(len(payload)))
				_ = conn.SetWriteDeadline(time.Now().Add(10 * time.Second))
				n, err := conn.WriteTo(payload, packetDestination)
				if err != nil {
					s.failures.Add(1)
					_ = conn.Close()
					return
				}
				s.up.Add(int64(n))
				record.up.Add(int64(n))
			case <-ended:
				return
			case <-s.ctx.Done():
				return
			}
		}
	}()
	buffer := make([]byte, 65535)
	for {
		_ = conn.SetReadDeadline(time.Now().Add(time.Duration(s.options.UDPTimeout) * time.Second))
		n, source, err := conn.ReadFrom(buffer)
		if err != nil {
			return
		}
		actual := M.SocksaddrFromNet(source)
		if actual.Port != flow.destination.Port || actualDestination.Addr.IsValid() && actual.Addr.Unmap() != actualDestination.Addr.Unmap() || !actualDestination.Addr.IsValid() && actual.Fqdn != "" && !strings.EqualFold(actual.Fqdn, actualDestination.Fqdn) || !actual.Addr.IsValid() && actual.Fqdn == "" {
			s.failures.Add(1)
			continue
		}
		s.down.Add(int64(n))
		record.down.Add(int64(n))
		reply := buf.NewSize(n)
		_, _ = reply.Write(buffer[:n])
		if err := flow.writer.WritePacket(reply, flow.destination); err != nil {
			return
		}
	}
}

func (s *vpnSession) relayDNS(payload []byte, destination M.Socksaddr, writer N.PacketWriter) {
	response, err := s.exchangeDNS(payload)
	if err != nil {
		s.failures.Add(1)
		return
	}
	var query, answer dns.Msg
	if query.Unpack(payload) != nil || answer.Unpack(response) != nil {
		return
	}
	limit := dns.MinMsgSize
	if opt := query.IsEdns0(); opt != nil {
		limit = int(opt.UDPSize())
		if limit > 4096 {
			limit = 4096
		}
	}
	answer.Truncate(limit)
	response, err = answer.Pack()
	if err != nil {
		return
	}
	buffer := buf.NewSize(len(response))
	_, _ = buffer.Write(response)
	_ = writer.WritePacket(buffer, destination)
}
func matchingDNSReply(query, answer *dns.Msg) bool {
	if answer.Id != query.Id || !answer.Response || answer.Opcode != query.Opcode || len(query.Question) != 1 || len(answer.Question) != 1 {
		return false
	}
	a, b := query.Question[0], answer.Question[0]
	return strings.EqualFold(a.Name, b.Name) && a.Qtype == b.Qtype && a.Qclass == b.Qclass
}

// StartVPN borrows the caller's original FD. Android duplicates it; only that
// duplicate belongs to Go, including every startup-failure path.
func StartVPN(configJSON string, fd int, dnsAddress string) string {
	return StartVPNWithOptions(configJSON, fd, dnsAddress, "")
}
func StartVPNWithOptions(configJSON string, fd int, dnsAddress, options string) (result string) {
	defer func() {
		if recover() != nil {
			StopVPN()
			result = encode("", "failed", "vpn_start_failed", 0, 0)
		}
	}()
	vpnState.Lock()
	defer vpnState.Unlock()
	if vpnState.session != nil {
		return encode("", "not_attempted", "vpn_already_running", 0, 0)
	}
	if fd <= 0 {
		return encode("", "failed", "tun_fd_invalid", 0, 0)
	}
	session, err := newSessionOwned(configJSON, dnsAddress, options, nil, true)
	if err != nil {
		return encode("", "failed", vpnStartupCode(err), 0, 0)
	}
	defer func() {
		if vpnState.session != session {
			session.close()
		}
	}()
	if err := session.startFullNative(); err != nil {
		return encode("", "failed", vpnStartupCode(err), 0, 0)
	}
	if session.nativeDNS != nil {
		activeNativeDNS.Store(session.nativeDNS)
	}
	if err := startAndroidTun(session, fd); err != nil {
		return encode("", "failed", "vpn_start_failed", 0, 0)
	}
	vpnState.session = session
	if session.native != nil {
		session.selection = newSelectionState(configJSON, dnsAddress, options)
	}
	return encode("", "passed", "tun_established_not_verified", 0, 0)
}

// StopVPN is safe to call repeatedly, including after revoked VPN permission.
func StopVPN() {
	vpnState.Lock()
	defer vpnState.Unlock()
	if session := vpnState.session; session != nil {
		vpnState.session = nil
		session.close()
	}
}

// VPNStatus contains counters and readiness, never endpoints or raw errors.
func VPNStatus() string {
	vpnState.Lock()
	defer vpnState.Unlock()
	status := struct {
		Running           bool   `json:"running"`
		Mode              string `json:"mode"`
		Uploaded          int64  `json:"uploaded"`
		Downloaded        int64  `json:"downloaded"`
		Failures          int64  `json:"failures"`
		Uptime            int64  `json:"uptime"`
		ActiveConnections int    `json:"activeConnections"`
		TotalConnections  int64  `json:"totalConnections"`
		DNSQueries        int64  `json:"dnsQueries"`
		Rejected          int64  `json:"rejected"`
		HeapBytes         uint64 `json:"heapBytes"`
		Goroutines        int    `json:"goroutines"`
	}{}
	if session := vpnState.session; session != nil {
		status.Running = true
		session.routingMu.Lock()
		if session.currentRouting != nil {
			status.Mode = session.currentRouting.session.routingMode()
		}
		session.routingMu.Unlock()
		status.Uploaded = session.up.Load()
		status.Downloaded = session.down.Load()
		status.Failures = session.failures.Load()
		status.Uptime = int64(time.Since(session.started).Seconds())
		session.mu.Lock()
		status.ActiveConnections = len(session.connections)
		session.mu.Unlock()
		status.TotalConnections = session.totalConnections.Load()
		status.DNSQueries = session.dnsQueries.Load()
		status.Rejected = session.rejected.Load()
	}
	samples := []metrics.Sample{{Name: "/memory/classes/heap/objects:bytes"}}
	metrics.Read(samples)
	if samples[0].Value.Kind() == metrics.KindUint64 {
		status.HeapBytes = samples[0].Value.Uint64()
	}
	status.Goroutines = runtime.NumGoroutine()
	encoded, _ := json.Marshal(status)
	return string(encoded)
}

// Preserve only the boundary and fixed vocabulary, never the private adapter error.
type vpnStartupError struct {
	code  string
	cause error
}

func (e *vpnStartupError) Error() string { return e.code }
func (e *vpnStartupError) Unwrap() error { return e.cause }
func vpnStartupCode(err error) string {
	var startup *vpnStartupError
	if errors.As(err, &startup) {
		return "vpn_" + startup.code
	}
	if err != nil && err.Error() == "dns_invalid" {
		return "vpn_dns_invalid"
	}
	return "vpn_options_invalid"
}
