// SPDX-License-Identifier: GPL-3.0-only
package mobile

import (
	"bytes"
	"context"
	"crypto/tls"
	"encoding/binary"
	"encoding/json"
	"io"
	"net"
	"net/netip"
	"strconv"
	"testing"
	"time"

	"github.com/gofrs/uuid/v5"
	"github.com/metacubex/mihomo/adapter"
	"github.com/metacubex/mihomo/adapter/outbound"
	C "github.com/metacubex/mihomo/constant"
	"github.com/metacubex/mihomo/transport/vless/vision"
	"github.com/metacubex/sing/common/bufio"
	M "github.com/metacubex/sing/common/metadata"
	N "github.com/metacubex/sing/common/network"
)

// Exercise the actual pinned Vision padding writer, not a stub that merely
// advertises headroom. The pipe is the post-TLS wire; TLS is only used by
// Vision's constructor to locate its input fields. No handshake or remote
// server is needed to reproduce a missing-header-space panic on its first write.
func TestOwnedConnectionsPreserveVisionPadding(t *testing.T) {
	for _, size := range []int{1, 517, 8192, 32768, 65536} {
		for _, lease := range []string{"primary", "dns", "dns-primary"} {
			t.Run(lease+"/"+strconv.Itoa(size), func(t *testing.T) {
				client, server := net.Pipe()
				defer client.Close()
				defer server.Close()
				_ = client.SetDeadline(time.Now().Add(5 * time.Second))
				_ = server.SetDeadline(time.Now().Add(5 * time.Second))
				id := uuid.Must(uuid.FromString("01234567-89ab-cdef-0123-456789abcdef"))
				v, err := vision.NewConn(client, tls.Client(client, &tls.Config{}), id)
				if err != nil {
					t.Fatal(err)
				}
				var conn C.Conn = outbound.NewConn(v, outbound.NewDirect())
				if lease == "primary" || lease == "dns-primary" {
					conn = &nativePrimaryConn{Conn: conn, owner: &nativePrimary{}}
				}
				if lease == "dns" || lease == "dns-primary" {
					conn = &dnsLeasedConn{Conn: conn, release: func() {}}
				}
				wire := make(chan []byte, 1)
				go func() { data, _ := io.ReadAll(server); wire <- data }()
				payload := bytes.Repeat([]byte{'x'}, size)
				n, err := bufio.Copy(conn, bytes.NewReader(payload))
				_ = client.Close()
				if err != nil || n != int64(size) {
					t.Fatalf("copy: bytes=%d, err=%v", n, err)
				}
				data := <-wire
				if !bytes.HasPrefix(data, id.Bytes()) {
					t.Fatal("Vision UUID prefix missing")
				}
				data = data[len(id.Bytes()):]
				var decoded []byte
				for len(data) > 0 {
					if len(data) < 5 {
						t.Fatal("truncated padding header")
					}
					content := int(binary.BigEndian.Uint16(data[1:3]))
					padding := int(binary.BigEndian.Uint16(data[3:5]))
					if len(data) < 5+content+padding {
						t.Fatal("truncated padded payload")
					}
					decoded = append(decoded, data[5:5+content]...)
					data = data[5+content+padding:]
				}
				if !bytes.Equal(decoded, payload) {
					t.Fatal("Vision changed the payload")
				}
			})
		}
	}
}

func TestLeaseHeadroomMatchesUnderlyingProtocol(t *testing.T) {
	// Both packet and stream leases must expose the entire nested protocol
	// chain without making themselves replaceable and losing their owner lease.
	base := &headroomFixture{}
	for name, conn := range map[string]any{
		"primary-tcp": &nativePrimaryConn{Conn: base},
		"primary-udp": &nativePrimaryPacket{PacketConn: &packetHeadroomFixture{}},
		"dns-tcp":     &dnsLeasedConn{Conn: base},
		"dns-udp":     &dnsLeasedPacket{PacketConn: &packetHeadroomFixture{}},
	} {
		if N.CalculateFrontHeadroom(conn) != 21 || N.CalculateRearHeadroom(conn) != 1400 {
			t.Errorf("%s hides protocol padding requirements", name)
		}
	}
}

// Run the actual singleton tunnel in both modes. Only the outer TLS wire is
// synthetic: the selected group's connection is the real Vision writer.
// This catches losses of headroom at any point between nativeTCP and Relay.
func TestNativeTunnelVisionPreservesPaddingInBothModes(t *testing.T) {
	for _, mode := range []string{"global", "rule"} {
		t.Run(mode, func(t *testing.T) {
			p := imported(t, "proxies: [{name: A, type: direct}]\nproxy-groups: [{name: Main, type: select, proxies: [A]}]\nrules: ['MATCH,Main']")
			config, _ := json.Marshal(map[string]string{"type": "profile-target", "target": "Main", "definition": p.Definition})
			o := defaultRuntimeOptions()
			o.Mode, o.Definition, o.Rules = mode, p.Definition, p.Rules
			raw, _ := json.Marshal(o)
			s, err := newSessionOwned(string(config), "127.0.0.1:53", string(raw), nil, true)
			if err != nil {
				t.Fatal(err)
			}
			if err = s.startFullNative(); err != nil {
				s.close()
				t.Fatal(err)
			}
			vpnState.Lock()
			vpnState.session = s
			vpnState.Unlock()
			defer StopVPN()
			wire, peer := net.Pipe()
			defer wire.Close()
			defer peer.Close()
			_ = wire.SetDeadline(time.Now().Add(5 * time.Second))
			_ = peer.SetDeadline(time.Now().Add(5 * time.Second))
			v, err := vision.NewConn(wire, tls.Client(wire, &tls.Config{}), uuid.Nil)
			if err != nil {
				t.Fatal(err)
			}
			slot := s.native.cfg.Proxies["A"].(*adapter.Proxy)
			slot.ProxyAdapter = &visionWireAdapter{ProxyAdapter: slot.ProxyAdapter, connection: outbound.NewConn(v, outbound.NewDirect())}
			incoming, client := net.Pipe()
			defer client.Close()
			_ = client.SetDeadline(time.Now().Add(5 * time.Second))
			done := make(chan error, 1)
			go func() {
				done <- s.NewConnection(context.Background(), incoming, M.Metadata{
					Source:      M.Socksaddr{Addr: netip.MustParseAddr("192.0.2.2"), Port: 50123},
					Destination: M.Socksaddr{Addr: netip.MustParseAddr("198.51.100.2"), Port: 443},
				})
			}()
			payload := bytes.Repeat([]byte{'x'}, 517)
			if _, err = client.Write(payload); err != nil {
				t.Fatal(err)
			}
			header := make([]byte, 16+5)
			if _, err = io.ReadFull(peer, header); err != nil {
				t.Fatal(err)
			}
			content := int(binary.BigEndian.Uint16(header[17:19]))
			padding := int(binary.BigEndian.Uint16(header[19:21]))
			body := make([]byte, content+padding)
			if _, err = io.ReadFull(peer, body); err != nil {
				t.Fatal(err)
			}
			if content != len(payload) || !bytes.Equal(body[:content], payload) {
				t.Fatal("tunnel corrupted Vision payload")
			}
			_ = peer.Close()
			select {
			case <-done:
			case <-time.After(5 * time.Second):
				t.Fatal("tunnel copy did not exit")
			}
		})
	}
}

type visionWireAdapter struct {
	C.ProxyAdapter
	connection C.Conn
}

func (p *visionWireAdapter) DialContext(context.Context, *C.Metadata) (C.Conn, error) {
	return p.connection, nil
}

type headroomFixture struct{ C.Conn }

func (*headroomFixture) FrontHeadroom() int { return 21 }
func (*headroomFixture) RearHeadroom() int  { return 1400 }

type packetHeadroomFixture struct{ C.PacketConn }

func (*packetHeadroomFixture) FrontHeadroom() int { return 21 }
func (*packetHeadroomFixture) RearHeadroom() int  { return 1400 }
