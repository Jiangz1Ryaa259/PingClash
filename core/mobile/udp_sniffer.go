// SPDX-License-Identifier: GPL-3.0-only
package mobile

import (
	"context"
	"net"
	"net/netip"
	"sync"

	C "github.com/metacubex/mihomo/constant"
)

// The native multi-packet QUIC sniffer sees the original datagrams before the
// outbound is selected. Retained payload is bounded; sending order is preserved.
func (s *vpnSession) sniffUDP(ctx context.Context, flow *udpFlow, metadata *C.Metadata) [][]byte {
	if s.sniffer == nil || !s.sniffer.Enable() {
		return nil
	}
	var first []byte
	select {
	case first = <-flow.queue:
		if flow.counted {
			s.root().queuedUDPBytes.Add(-int64(len(first)))
		}
	case <-ctx.Done():
		return nil
	}
	sink := &sniffPacketSink{}
	packet := &sniffPacket{payload: first, source: flow.source.UDPAddr()}
	sender := s.sniffer.UDPSniff(C.NewPacketAdapter(packet, metadata), sink)
	sender.Send(C.NewPacketAdapter(packet, metadata))
	stop, finished := make(chan struct{}), make(chan struct{})
	go func() {
		defer close(finished)
		defer guard("udp-sniff")
		for {
			select {
			case <-stop:
				return
			default:
			}
			select {
			case b := <-flow.queue:
				if flow.counted {
					s.root().queuedUDPBytes.Add(-int64(len(b)))
				}
				sender.Send(C.NewPacketAdapter(&sniffPacket{payload: b, source: flow.source.UDPAddr()}, metadata))
			case <-stop:
				return
			case <-ctx.Done():
				return
			}
		}
	}()
	_ = sender.DoSniff(metadata)
	close(stop)
	<-finished
	sender.Close()
	return sink.packets
}

type sniffPacketSink struct {
	sync.Mutex
	packets [][]byte
	bytes   int
}

func (s *sniffPacketSink) Send(packet C.PacketAdapter) {
	s.Lock()
	defer s.Unlock()
	defer packet.Drop()
	if s.bytes+len(packet.Data()) > 256*1024 || len(s.packets) >= 64 {
		return
	}
	s.packets = append(s.packets, packet.Data())
	s.bytes += len(packet.Data())
}
func (*sniffPacketSink) Process(C.PacketConn, C.WriteBackProxy)     {}
func (*sniffPacketSink) Close()                                     {}
func (*sniffPacketSink) DoSniff(*C.Metadata) error                  { return nil }
func (*sniffPacketSink) AddMapping(*C.Metadata, *C.Metadata)        {}
func (*sniffPacketSink) RestoreReadFrom(addr netip.Addr) netip.Addr { return addr }

type sniffPacket struct {
	payload []byte
	source  net.Addr
}

func (p *sniffPacket) Data() []byte                                { return p.payload }
func (p *sniffPacket) Drop()                                       { p.payload = nil }
func (p *sniffPacket) LocalAddr() net.Addr                         { return p.source }
func (p *sniffPacket) WriteBack(b []byte, _ net.Addr) (int, error) { return len(b), nil }
