// SPDX-License-Identifier: GPL-3.0-only
package mobile

import (
	"net"
	"sync"
	"sync/atomic"
	"time"

	C "github.com/metacubex/mihomo/constant"
	"github.com/metacubex/mihomo/tunnel"
	"github.com/metacubex/mihomo/tunnel/statistic"
	"github.com/metacubex/sing/common/buf"
	M "github.com/metacubex/sing/common/metadata"
	N "github.com/metacubex/sing/common/network"
)

type nativeUDPBridge struct {
	root                *vpnSession
	source, destination M.Socksaddr
	writer              N.PacketWriter
	record              *connectionRecord
	timer               *time.Timer
	closed              atomic.Bool
	once                sync.Once
	ownerOnce           sync.Once
	uid                 uint32
	process             string
}

func (s *vpnSession) nativePacket(data []byte, flow M.Metadata, writer N.PacketWriter) {
	root := s.root()
	m := ruleMetadata(flow.Destination.Fqdn, flow.Destination.Addr, flow.Destination.Port, "udp")
	m.SrcIP = flow.Source.Addr
	m.SrcPort = flow.Source.Port
	if !s.applyDevicePolicy(m) {
		return
	}
	m.DSCP = root.traffic.lookup(flow, "udp")
	key := flow.Source.String()
	root.mu.Lock()
	if root.ctx.Err() != nil {
		root.mu.Unlock()
		return
	}
	if root.nativePackets == nil {
		root.nativePackets = map[string]*nativeUDPBridge{}
	}
	bridge := root.nativePackets[key]
	if bridge == nil {
		if len(root.nativePackets) >= root.options.MaxConnections || len(root.connections) >= root.options.MaxConnections {
			root.mu.Unlock()
			root.failures.Add(1)
			return
		}
		bridge = &nativeUDPBridge{root: root, source: flow.Source, destination: flow.Destination, writer: writer}
		record := &connectionRecord{id: root.serial.Add(1), network: "udp", route: "—", started: time.Now(), closer: bridge,
			nativeKey: nativeFlowKey("udp", flow.Source.String(), "")}
		if root.options.InspectConnections {
			record.destination = flow.Destination.String()
		}
		bridge.record = record
		root.connections[record.id] = record
		root.resources[bridge] = struct{}{}
		root.totalConnections.Add(1)
		root.nativePackets[key] = bridge
		// Native UDP NAT uses a 60s read deadline. Retain the write-back bridge
		// through dialing/sniffing and late responses without an unbounded timer.
		bridge.timer = time.AfterFunc(2*time.Minute, func() { _ = bridge.Close() })
	} else {
		bridge.timer.Reset(2 * time.Minute)
	}
	root.mu.Unlock()
	if bridge.closed.Load() {
		return
	}
	if s.processLookup && s.routingMode() == "rule" {
		// Resolve the owner once per bridge; every later packet reuses it.
		bridge.ownerOnce.Do(func() {
			connectionRuleHelper(m).FindProcess()
			bridge.uid, bridge.process = m.Uid, m.Process
		})
		m.Uid, m.Process, m.ProcessPath = bridge.uid, bridge.process, bridge.process
	}
	root.up.Add(int64(len(data)))
	bridge.record.up.Add(int64(len(data)))
	tunnel.Tunnel.HandleUDPPacket(&nativePacket{payload: data, bridge: bridge}, m)
}
func (b *nativeUDPBridge) Close() error {
	b.once.Do(func() {
		b.closed.Store(true)
		b.root.mu.Lock()
		if b.timer != nil {
			b.timer.Stop()
		}
		if b.root.nativePackets[b.source.String()] == b {
			delete(b.root.nativePackets, b.source.String())
		}
		delete(b.root.connections, b.record.id)
		delete(b.root.resources, b)
		b.root.mu.Unlock()
		statistic.DefaultManager.Range(func(tracker statistic.Tracker) bool {
			m := tracker.Info().Metadata
			if m.Type == C.TUN && m.NetWork == C.UDP && m.SrcIP == b.source.Addr && m.SrcPort == b.source.Port {
				_ = tracker.Close()
			}
			return true
		})
	})
	return nil
}

type nativePacket struct {
	payload []byte
	bridge  *nativeUDPBridge
}

func (p *nativePacket) Data() []byte        { return p.payload }
func (p *nativePacket) Drop()               { p.payload = nil }
func (p *nativePacket) LocalAddr() net.Addr { return p.bridge.source.UDPAddr() }
func (p *nativePacket) WriteBack(data []byte, addr net.Addr) (int, error) {
	b := p.bridge
	if b.closed.Load() {
		return 0, net.ErrClosed
	}
	destination := b.destination
	if addr != nil {
		destination = M.SocksaddrFromNet(addr)
	}
	reply := buf.NewSize(len(data))
	_, _ = reply.Write(data)
	if e := b.writer.WritePacket(reply, destination); e != nil {
		return 0, e
	}
	b.root.down.Add(int64(len(data)))
	b.record.down.Add(int64(len(data)))
	b.root.mu.Lock()
	if !b.closed.Load() {
		b.timer.Reset(2 * time.Minute)
	}
	b.root.mu.Unlock()
	return len(data), nil
}
