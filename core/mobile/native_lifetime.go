// SPDX-License-Identifier: GPL-3.0-only
package mobile

import (
	"context"
	"net"
	"sync"

	C "github.com/metacubex/mihomo/constant"
)

// TCP epoch users do not cover Mihomo's asynchronous UDP associations. Count
// the actual transport leases as well, so a grace timer cannot cut off a live
// game/QUIC/multiplexed connection during a configuration hot switch.
func (n *fullNativeRuntime) beginUse() bool {
	n.trafficMu.Lock()
	defer n.trafficMu.Unlock()
	if n.closing {
		return false
	}
	n.users++
	return true
}
func (n *fullNativeRuntime) endUse() {
	n.trafficMu.Lock()
	n.users--
	n.trafficMu.Unlock()
}
func (n *fullNativeRuntime) beginIdleStop() bool {
	n.trafficMu.Lock()
	defer n.trafficMu.Unlock()
	if n.users != 0 {
		return false
	}
	n.closing = true
	return true
}

type nativeLeasedAdapter struct {
	C.ProxyAdapter
	generation *fullNativeRuntime
}

func (p *nativeLeasedAdapter) DialContext(ctx context.Context, m *C.Metadata) (C.Conn, error) {
	if !p.generation.beginUse() {
		return nil, net.ErrClosed
	}
	retained := false
	defer func() {
		if !retained {
			p.generation.endUse()
		}
	}()
	c, err := p.ProxyAdapter.DialContext(ctx, m)
	if err != nil {
		return nil, err
	}
	retained = true
	return &nativeGenerationConn{Conn: c, generation: p.generation}, nil
}
func (p *nativeLeasedAdapter) ListenPacketContext(ctx context.Context, m *C.Metadata) (C.PacketConn, error) {
	if !p.generation.beginUse() {
		return nil, net.ErrClosed
	}
	retained := false
	defer func() {
		if !retained {
			p.generation.endUse()
		}
	}()
	c, err := p.ProxyAdapter.ListenPacketContext(ctx, m)
	if err != nil {
		return nil, err
	}
	retained = true
	return &nativeGenerationPacket{PacketConn: c, generation: p.generation}, nil
}

type nativeGenerationConn struct {
	C.Conn
	generation *fullNativeRuntime
	once       sync.Once
}

func (c *nativeGenerationConn) Upstream() any { return c.Conn }
func (c *nativeGenerationConn) Close() (err error) {
	c.once.Do(func() { defer c.generation.endUse(); err = c.Conn.Close() })
	return
}

type nativeGenerationPacket struct {
	C.PacketConn
	generation *fullNativeRuntime
	once       sync.Once
}

func (c *nativeGenerationPacket) Upstream() any { return c.PacketConn }
func (c *nativeGenerationPacket) Close() (err error) {
	c.once.Do(func() { defer c.generation.endUse(); err = c.PacketConn.Close() })
	return
}
