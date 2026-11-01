// SPDX-License-Identifier: GPL-3.0-only
package mobile

import (
	"context"
	C "github.com/metacubex/mihomo/constant"
	MDNS "github.com/metacubex/mihomo/dns"
	"sync"
)

// Cached resolvers borrow the current routing generation for each connection;
// a mode or group change cannot leave a cached DNS client on an old outbound.
type dnsDynamicAdapter struct {
	C.ProxyAdapter
	root *vpnSession
	name string
}

func (p *dnsDynamicAdapter) borrow(ctx context.Context, m *C.Metadata) (C.ProxyAdapter, func(), error) {
	s, release := p.root.acquireRouting()
	target := s.proxy
	switch p.name {
	case "DIRECT":
		target = s.direct
	case "AUTO":
		if s.routingMode() != "global" {
			target = s.direct
		}
	case "GLOBAL", "PROXY":
	case MDNS.RespectRules:
		routed := &dnsRouteAdapter{ProxyAdapter: s.proxy, session: s}
		if e := routed.resolveUpstream(ctx, m); e != nil {
			release()
			return nil, func() {}, e
		}
		var e error
		target, _, e = s.routeFromMetadata(m)
		if e != nil {
			release()
			return nil, func() {}, e
		}
	default:
		target = s.targets[p.name]
	}
	if target == nil {
		release()
		return nil, func() {}, errRoutingUnavailable
	}
	leaf, e := flowProxy(ctx, target, m)
	if e != nil {
		release()
		return nil, func() {}, e
	}
	if leaf.IsL3Protocol(m) && m.DstIP.IsValid() {
		m.Host = ""
	}
	return leaf, release, nil
}
func (p *dnsDynamicAdapter) DialContext(ctx context.Context, m *C.Metadata) (C.Conn, error) {
	target, release, e := p.borrow(ctx, m)
	if e != nil {
		return nil, e
	}
	c, e := target.DialContext(ctx, m)
	if e != nil {
		release()
		return nil, e
	}
	return &dnsLeasedConn{Conn: c, release: release}, nil
}
func (p *dnsDynamicAdapter) ListenPacketContext(ctx context.Context, m *C.Metadata) (C.PacketConn, error) {
	target, release, e := p.borrow(ctx, m)
	if e != nil {
		return nil, e
	}
	c, e := target.ListenPacketContext(ctx, m)
	if e != nil {
		release()
		return nil, e
	}
	return &dnsLeasedPacket{PacketConn: c, release: release}, nil
}
func (p *dnsDynamicAdapter) Close() error { return nil }

type dnsLeasedConn struct {
	C.Conn
	release func()
	once    sync.Once
}

func (c *dnsLeasedConn) Close() error { e := c.Conn.Close(); c.once.Do(c.release); return e }

type dnsLeasedPacket struct {
	C.PacketConn
	release func()
	once    sync.Once
}

// Preserve protocol buffer requirements without unwrapping away the routing
// lease. This also applies to nested DNS -> selected-primary connections.
func (c *dnsLeasedConn) Upstream() any   { return c.Conn }
func (c *dnsLeasedPacket) Upstream() any { return c.PacketConn }

func (c *dnsLeasedPacket) Close() error { e := c.PacketConn.Close(); c.once.Do(c.release); return e }
