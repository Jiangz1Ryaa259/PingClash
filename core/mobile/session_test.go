// SPDX-License-Identifier: GPL-3.0-only
package mobile

import (
	"context"
	"encoding/binary"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"net/netip"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/metacubex/sing/common/buf"
	M "github.com/metacubex/sing/common/metadata"
	N "github.com/metacubex/sing/common/network"
	"github.com/miekg/dns"
)

func TestVPNTCPForwarderUsesTheSelectedAnyTLSNode(t *testing.T) {
	target := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { _, _ = w.Write([]byte("via-selected-anytls")) }))
	defer target.Close()
	config := anyTLSFixture(t, target.Listener.Addr().String())
	session, err := newSession(asJSON(t, config), "127.0.0.1:53")
	if err != nil {
		t.Fatal(err)
	}
	defer session.close()
	incoming, client := net.Pipe()
	defer client.Close()
	finished := make(chan error, 1)
	go func() {
		finished <- session.NewConnection(context.Background(), incoming, M.Metadata{Destination: M.ParseSocksaddr(target.Listener.Addr().String())})
	}()
	_ = client.SetDeadline(time.Now().Add(3 * time.Second))
	if _, err = io.WriteString(client, "GET / HTTP/1.1\r\nHost: local.test\r\nConnection: close\r\n\r\n"); err != nil {
		t.Fatal(err)
	}
	response, err := io.ReadAll(client)
	if err != nil || !strings.Contains(string(response), "via-selected-anytls") {
		t.Fatal("TCP forwarding failed")
	}
	select {
	case <-finished:
	case <-time.After(time.Second):
		t.Fatal("forwarder failed to clean up")
	}
	if session.up.Load() == 0 || session.down.Load() == 0 {
		t.Fatal("traffic counters not updated")
	}
}

type captureWriter struct{ result chan []byte }

func (w captureWriter) WritePacket(buffer *buf.Buffer, _ M.Socksaddr) error {
	defer buffer.Release()
	w.result <- append([]byte(nil), buffer.Bytes()...)
	return nil
}

func dnsFixture(t *testing.T) net.Listener {
	t.Helper()
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = listener.Close() })
	go func() {
		conn, err := listener.Accept()
		if err != nil {
			return
		}
		defer conn.Close()
		_ = conn.SetDeadline(time.Now().Add(5 * time.Second))
		var header [2]byte
		if _, err = io.ReadFull(conn, header[:]); err != nil {
			return
		}
		queryBytes := make([]byte, int(binary.BigEndian.Uint16(header[:])))
		if _, err = io.ReadFull(conn, queryBytes); err != nil {
			return
		}
		var query dns.Msg
		if query.Unpack(queryBytes) != nil {
			return
		}
		answer := new(dns.Msg)
		answer.SetReply(&query)
		answer.Answer = []dns.RR{&dns.A{Hdr: dns.RR_Header{Name: query.Question[0].Name, Rrtype: dns.TypeA, Class: dns.ClassINET, Ttl: 60}, A: net.IPv4(192, 0, 2, 42)}}
		payload, _ := answer.Pack()
		binary.BigEndian.PutUint16(header[:], uint16(len(payload)))
		_, _ = conn.Write(append(header[:], payload...))
	}()
	return listener
}

func TestVPNDNSIsRelayedOverTCPThroughAnyTLS(t *testing.T) {
	server := dnsFixture(t)
	config := anyTLSFixture(t, server.Addr().String())
	session, err := newSession(asJSON(t, config), server.Addr().String())
	if err != nil {
		t.Fatal(err)
	}
	defer session.close()
	query := new(dns.Msg)
	query.SetQuestion("example.test.", dns.TypeA)
	payload, _ := query.Pack()
	writer := captureWriter{make(chan []byte, 1)}
	session.relayDNS(payload, M.ParseSocksaddr("172.29.0.2:53"), writer)
	select {
	case result := <-writer.result:
		var answer dns.Msg
		if answer.Unpack(result) != nil || answer.Id != query.Id || len(answer.Answer) != 1 {
			t.Fatal("invalid DNS relay response")
		}
		if len(session.hosts) != 0 {
			t.Fatal("global mode retained unnecessary DNS associations without consent")
		}
	case <-time.After(time.Second):
		t.Fatal("DNS not returned through proxy")
	}
}

func TestVPNUDPForwarderUsesAnyTLSUOT(t *testing.T) {
	echo, err := net.ListenPacket("udp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer echo.Close()
	go func() {
		buffer := make([]byte, 1024)
		n, from, err := echo.ReadFrom(buffer)
		if err == nil {
			_, _ = echo.WriteTo(buffer[:n], from)
		}
	}()
	config := anyTLSFixture(t, echo.LocalAddr().String())
	config.UDP = true
	session, err := newSession(asJSON(t, config), "127.0.0.1:53")
	if err != nil {
		t.Fatal(err)
	}
	defer session.close()
	payload := []byte("local-udp-echo")
	packet := buf.NewSize(len(payload))
	_, _ = packet.Write(payload)
	writer := captureWriter{make(chan []byte, 1)}
	session.NewPacket(context.Background(), netip.MustParseAddrPort("172.29.0.1:53001"), packet,
		M.Metadata{Source: M.ParseSocksaddr("172.29.0.1:53001"), Destination: M.ParseSocksaddr(echo.LocalAddr().String())},
		func(N.PacketConn) N.PacketWriter { return writer })
	select {
	case result := <-writer.result:
		if string(result) != string(payload) {
			t.Fatal("UDP response corrupted")
		}
	case <-time.After(4 * time.Second):
		t.Fatal("UDP did not traverse AnyTLS")
	}
}

type memoryDevice struct {
	input, output chan []byte
	closed        chan struct{}
	once          sync.Once
}

func newMemoryDevice() *memoryDevice {
	return &memoryDevice{make(chan []byte, 8), make(chan []byte, 8), make(chan struct{}), sync.Once{}}
}
func (d *memoryDevice) Read(buffer []byte) (int, error) {
	select {
	case packet := <-d.input:
		return copy(buffer, packet), nil
	case <-d.closed:
		return 0, net.ErrClosed
	}
}
func (d *memoryDevice) Write(buffer []byte) (int, error) {
	select {
	case d.output <- append([]byte(nil), buffer...):
		return len(buffer), nil
	case <-d.closed:
		return 0, net.ErrClosed
	}
}
func (d *memoryDevice) Close() error { d.once.Do(func() { close(d.closed) }); return nil }

func TestTUNIPPacketReachesDNSThroughSelectedAnyTLS(t *testing.T) {
	server := dnsFixture(t)
	config := anyTLSFixture(t, server.Addr().String())
	session, err := newSession(asJSON(t, config), server.Addr().String())
	if err != nil {
		t.Fatal(err)
	}
	defer session.close()
	device := newMemoryDevice()
	if session.startDevice(device) != nil {
		t.Fatal("packet stack did not start")
	}
	query := new(dns.Msg)
	query.SetQuestion("packet.test.", dns.TypeA)
	payload, _ := query.Pack()
	device.input <- ipv4UDP(netip.MustParseAddr("172.29.0.1"), netip.MustParseAddr("172.29.0.2"), 53000, 53, payload)
	select {
	case packet := <-device.output:
		if len(packet) < 28 || packet[9] != 17 || binary.BigEndian.Uint16(packet[20:22]) != 53 {
			t.Fatal("unexpected TUN response")
		}
		var answer dns.Msg
		if answer.Unpack(packet[28:]) != nil || answer.Id != query.Id || len(answer.Answer) != 1 {
			t.Fatal("TUN DNS answer invalid")
		}
	case <-time.After(4 * time.Second):
		t.Fatal("TUN packet was not forwarded through AnyTLS")
	}
	session.close()
	session.close() // Repeated cleanup must not close a recycled FD.
}

func ipv4UDP(source, destination netip.Addr, sourcePort, destinationPort uint16, payload []byte) []byte {
	packet := make([]byte, 28+len(payload))
	packet[0] = 0x45
	packet[8] = 64
	packet[9] = 17
	binary.BigEndian.PutUint16(packet[2:4], uint16(len(packet)))
	copy(packet[12:16], source.AsSlice())
	copy(packet[16:20], destination.AsSlice())
	var sum uint32
	for i := 0; i < 20; i += 2 {
		sum += uint32(binary.BigEndian.Uint16(packet[i : i+2]))
	}
	for sum>>16 != 0 {
		sum = (sum & 65535) + (sum >> 16)
	}
	binary.BigEndian.PutUint16(packet[10:12], ^uint16(sum))
	binary.BigEndian.PutUint16(packet[20:22], sourcePort)
	binary.BigEndian.PutUint16(packet[22:24], destinationPort)
	binary.BigEndian.PutUint16(packet[24:26], uint16(8+len(payload)))
	copy(packet[28:], payload)
	return packet
}
