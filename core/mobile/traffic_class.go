// SPDX-License-Identifier: GPL-3.0-only
package mobile

import (
	"encoding/binary"
	tun "github.com/metacubex/sing-tun"
	M "github.com/metacubex/sing/common/metadata"
	"net/netip"
	"sync"
	"time"
)

type trafficKey struct {
	source, destination netip.AddrPort
	protocol            uint8
}
type trafficValue struct {
	dscp  uint8
	until time.Time
}
type trafficClasses struct {
	sync.Mutex
	entries map[trafficKey]trafficValue
}

// Inspect IP headers before the userspace TCP/UDP stack drops traffic class.
// No payload or endpoint data is logged or persisted. Bound both count and age.
func (c *trafficClasses) observe(packet []byte) {
	key, dscp, ok := packetTrafficClass(packet)
	if !ok {
		return
	}
	c.Lock()
	defer c.Unlock()
	if c.entries == nil {
		c.entries = map[trafficKey]trafficValue{}
	}
	now := time.Now()
	if len(c.entries) >= 4096 {
		for key, value := range c.entries {
			if now.After(value.until) {
				delete(c.entries, key)
			}
		}
		if len(c.entries) >= 4096 {
			return
		}
	}
	c.entries[key] = trafficValue{dscp, now.Add(5 * time.Minute)}
}
func (c *trafficClasses) lookup(flow M.Metadata, network string) uint8 {
	protocol := uint8(6)
	if network == "udp" {
		protocol = 17
	}
	c.Lock()
	defer c.Unlock()
	value := c.entries[trafficKey{netip.AddrPortFrom(flow.Source.Addr.Unmap(), flow.Source.Port),
		netip.AddrPortFrom(flow.Destination.Addr.Unmap(), flow.Destination.Port), protocol}]
	if time.Now().After(value.until) {
		return 0
	}
	return value.dscp
}
func packetTrafficClass(p []byte) (key trafficKey, dscp uint8, ok bool) {
	if len(p) < 20 {
		return
	}
	offset := 0
	var src, dst netip.Addr
	switch p[0] >> 4 {
	case 4:
		offset = int(p[0]&15) * 4
		if offset < 20 || len(p) < offset+4 || binary.BigEndian.Uint16(p[6:8])&0x1fff != 0 {
			return
		}
		length := int(binary.BigEndian.Uint16(p[2:4]))
		if length < offset+4 || length > len(p) {
			return
		}
		src = netip.AddrFrom4([4]byte(p[12:16]))
		dst = netip.AddrFrom4([4]byte(p[16:20]))
		key.protocol = p[9]
		dscp = p[1] >> 2
	case 6:
		if len(p) < 44 {
			return
		}
		length := 40 + int(binary.BigEndian.Uint16(p[4:6]))
		if length > len(p) || length < 44 {
			return
		}
		src = netip.AddrFrom16([16]byte(p[8:24]))
		dst = netip.AddrFrom16([16]byte(p[24:40]))
		key.protocol = p[6]
		dscp = ((p[0]&15)<<4 | p[1]>>4) >> 2
		offset = 40
		for extensions := 0; key.protocol != 6 && key.protocol != 17; extensions++ {
			if extensions >= 8 || offset+8 > length {
				return
			}
			next := p[offset]
			size := 0
			switch key.protocol {
			case 0, 43, 60:
				size = (int(p[offset+1]) + 1) * 8
			case 44:
				if binary.BigEndian.Uint16(p[offset+2:offset+4])&0xfff8 != 0 {
					return
				}
				size = 8
			case 51:
				size = (int(p[offset+1]) + 2) * 4
			default:
				return
			}
			offset += size
			key.protocol = next
		}
		if offset+4 > length {
			return
		}
	default:
		return
	}
	if key.protocol != 6 && key.protocol != 17 {
		return
	}
	key.source = netip.AddrPortFrom(src.Unmap(), binary.BigEndian.Uint16(p[offset:]))
	key.destination = netip.AddrPortFrom(dst.Unmap(), binary.BigEndian.Uint16(p[offset+2:]))
	return key, dscp, true
}

type observedTun struct {
	tun.Tun
	classes *trafficClasses
}

func (t observedTun) Read(p []byte) (int, error) {
	n, e := t.Tun.Read(p)
	if n > 0 {
		t.classes.observe(p[:n])
	}
	return n, e
}

type observedLinuxTun struct {
	tun.LinuxTUN
	classes *trafficClasses
}

func (t observedLinuxTun) Read(p []byte) (int, error) {
	n, e := t.LinuxTUN.Read(p)
	if n > 0 {
		t.classes.observe(p[:n])
	}
	return n, e
}
func (t observedLinuxTun) BatchRead(buffers [][]byte, offset int, sizes []int) (int, error) {
	n, e := t.LinuxTUN.BatchRead(buffers, offset, sizes)
	for i := 0; i < n; i++ {
		if i < len(buffers) && i < len(sizes) && sizes[i] > 0 && offset+sizes[i] <= len(buffers[i]) {
			t.classes.observe(buffers[i][offset : offset+sizes[i]])
		}
	}
	return n, e
}
func observeTun(device tun.Tun, classes *trafficClasses) tun.Tun {
	if linux, ok := device.(tun.LinuxTUN); ok {
		return observedLinuxTun{linux, classes}
	}
	return observedTun{device, classes}
}
