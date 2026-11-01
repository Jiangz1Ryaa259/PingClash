// SPDX-License-Identifier: GPL-3.0-only
package mobile

import (
	"encoding/json"
	C "github.com/metacubex/mihomo/constant"
	"github.com/metacubex/mihomo/tunnel/statistic"
	M "github.com/metacubex/sing/common/metadata"
	"io"
	"net/netip"
	"strings"
	"sync"
	"sync/atomic"
	"time"
)

type countWriter struct {
	writer      io.Writer
	total, flow *atomic.Int64
}

func (w countWriter) Write(p []byte) (int, error) {
	n, e := w.writer.Write(p)
	w.total.Add(int64(n))
	w.flow.Add(int64(n))
	return n, e
}

type connectionRecord struct {
	nativeKey                   string
	id                          uint64
	network, route, destination string
	started                     time.Time
	closer                      io.Closer
	up, down                    atomic.Int64
}
type flowCloser struct {
	mu     sync.Mutex
	closed bool
	items  []io.Closer
}

func (f *flowCloser) add(c io.Closer) {
	f.mu.Lock()
	if f.closed {
		f.mu.Unlock()
		_ = c.Close()
		return
	}
	f.items = append(f.items, c)
	f.mu.Unlock()
}
func (f *flowCloser) Close() error {
	f.mu.Lock()
	if f.closed {
		f.mu.Unlock()
		return nil
	}
	f.closed = true
	items := f.items
	f.items = nil
	f.mu.Unlock()
	for _, c := range items {
		_ = c.Close()
	}
	return nil
}

func (s *vpnSession) openRecord(network, route string, destination M.Socksaddr, closer io.Closer, runtimes ...*vpnSession) (*connectionRecord, bool) {
	routing := s
	if len(runtimes) > 0 {
		routing = runtimes[0]
	}
	display := ""
	if s.options.InspectConnections {
		display = destination.String()
		host := s.hostFor(destination.Addr)
		if routing.nativeDNS != nil {
			if mapped, ok := routing.nativeDNS.mapper.FindHostByIP(destination.Addr); ok {
				host = mapped
			}
		}
		if host != "" {
			display = host + " (" + display + ")"
		}
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.ctx.Err() != nil || len(s.connections) >= s.options.MaxConnections {
		return nil, false
	}
	f := &flowCloser{}
	f.add(closer)
	r := &connectionRecord{id: s.serial.Add(1), network: network, route: route, started: time.Now(), closer: f}
	if s.options.InspectConnections {
		r.destination = display
	} else if route != "DIRECT" && route != "REJECT" {
		r.route = "PROXY"
	}
	s.connections[r.id] = r
	s.totalConnections.Add(1)
	return r, true
}
func (s *vpnSession) endRecord(id uint64) { s.mu.Lock(); delete(s.connections, id); s.mu.Unlock() }

// The snapshot is never persisted or logged. Targets are absent unless explicitly enabled.
func VPNConnections() string {
	vpnState.Lock()
	defer vpnState.Unlock()
	rows := []map[string]any{}
	if s := vpnState.session; s != nil {
		native := nativeConnectionRoutes(s.options.InspectConnections)
		s.mu.Lock()
		defer s.mu.Unlock()
		for _, r := range s.connections {
			route, rule, destination := r.route, "", r.destination
			if actual, ok := native[r.nativeKey]; ok {
				route = actual.route
				rule = actual.rule
				destination = withNativeHost(destination, actual.host)
			}
			rows = append(rows, map[string]any{"id": r.id, "network": r.network, "route": route, "ruleType": rule, "destination": destination, "seconds": int64(time.Since(r.started).Seconds()), "uploaded": r.up.Load(), "downloaded": r.down.Load()})
		}
	}
	b, _ := json.Marshal(rows)
	return string(b)
}

type nativeRouteView struct{ route, rule, host string }

// withNativeHost labels an address-only destination with the domain the native runtime
// knows for the flow. In full-native mode fake-IP and sniffing live in Mihomo's own
// resolver, so the record opened at the TUN saw only an address (e.g. 198.18.0.7:443).
func withNativeHost(destination, host string) string {
	if host == "" || destination == "" || strings.Contains(destination, " (") {
		return destination
	}
	return host + " (" + destination + ")"
}

func nativeFlowKey(network string, source, destination string) string {
	if network == "udp" {
		destination = ""
	} // Native SNAT deliberately multiplexes one local socket.
	return network + "|" + source + "|" + destination
}
func nativeConnectionRoutes(inspect bool) map[string]nativeRouteView {
	result := map[string]nativeRouteView{}
	statistic.DefaultManager.Range(func(tracker statistic.Tracker) bool {
		info := tracker.Info()
		m := info.Metadata
		if m.Type != C.TUN {
			return true
		}
		network := "tcp"
		if m.NetWork == C.UDP {
			network = "udp"
		}
		chain := info.Chain
		route := "PROXY"
		if len(chain) > 0 && (chain[0] == "DIRECT" || chain[0] == "REJECT") {
			route = chain[0]
		}
		rule, host := "", ""
		if inspect {
			names := []string{}
			for i := len(chain) - 1; i >= 0 && len(names) < 8; i-- {
				name := chain[i]
				if len(name) <= 512 && !hasControl(name) {
					names = append(names, name)
				}
			}
			if len(names) > 0 {
				route = strings.Join(names, " → ")
			}
			rule = publicTrackerRule(info.Rule)
			if h := m.Host; len(h) <= 253 && !hasControl(h) && !strings.ContainsAny(h, " ()") && !isAddress(h) {
				host = h
			}
		}
		destination := netip.AddrPortFrom(m.DstIP.Unmap(), m.DstPort).String()
		if m.RawDstAddr != nil {
			destination = m.RawDstAddr.String()
		}
		key := nativeFlowKey(network, netip.AddrPortFrom(m.SrcIP.Unmap(), m.SrcPort).String(), destination)
		result[key] = nativeRouteView{route, rule, host}
		return true
	})
	return result
}

func publicTrackerRule(value string) string {
	// Native TrackerInfo uses RuleType.String() (e.g. DomainSuffix, SubRules),
	// while the public vocabulary uses config tokens. Never publish an unknown payload.
	a := strings.NewReplacer("-", "", "_", "").Replace(strings.ToUpper(value))
	if a == "SUBRULES" {
		return "SUB-RULE"
	}
	for _, kind := range nativeRuleKinds {
		if a == strings.ReplaceAll(kind, "-", "") {
			return kind
		}
	}
	return "UNRECOGNIZED"
}

// ID 0 closes all current flows, without changing policy or opening new listeners.
func CloseVPNConnection(id int64) {
	vpnState.Lock()
	defer vpnState.Unlock()
	if id < 0 {
		return
	}
	if s := vpnState.session; s != nil {
		closers := []io.Closer{}
		s.mu.Lock()
		for key, r := range s.connections {
			if id == 0 || uint64(id) == key {
				closers = append(closers, r.closer)
			}
		}
		s.mu.Unlock()
		for _, c := range closers {
			_ = c.Close()
		}
	}
}

func isAddress(text string) bool { _, err := netip.ParseAddr(text); return err == nil }
