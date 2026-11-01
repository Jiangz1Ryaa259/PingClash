// SPDX-License-Identifier: GPL-3.0-only
package mobile

import (
	"context"
	"encoding/json"
	"github.com/metacubex/sing/common/buf"
	M "github.com/metacubex/sing/common/metadata"
	N "github.com/metacubex/sing/common/network"
	"net"
	"net/netip"
	"reflect"
	"strconv"
	"strings"
	"testing"
	"time"
)

func TestLiveNativeHandoffBetweenBareNodeAndProfile(t *testing.T) {
	p := imported(t, `proxies: [{name: live, type: direct}]
proxy-groups: [{name: Main, type: select, proxies: [live]}]
rules: ['MATCH,Main']`)
	o := defaultRuntimeOptions()
	o.Mode = "rule"
	o.Definition = p.Definition
	o.Rules = p.Rules
	encoded, _ := json.Marshal(o)
	bare := `{"name":"bare","type":"direct"}`
	s, e := newSessionOwned(bare, "127.0.0.1:53", "", nil, true)
	if e != nil {
		t.Fatal(e)
	}
	vpnState.Lock()
	vpnState.session = s
	vpnState.Unlock()
	defer StopVPN()
	if decoded(t, UpdateVPNWithOptions(bare, "127.0.0.1:53", string(encoded))).State != "passed" {
		t.Fatal("bare to profile update failed")
	}
	first, done := s.acquireRouting()
	if first.native == nil {
		t.Fatal("profile did not install native runtime")
	}
	done()
	if decoded(t, UpdateVPNWithOptions(bare, "127.0.0.1:53", `{"mode":"direct"}`)).State != "passed" {
		t.Fatal("profile to bare update failed")
	}
	second, done := s.acquireRouting()
	defer done()
	if second.native == nil || second.options.Mode != "direct" || s.ctx.Err() != nil {
		t.Fatal("execution paths split after handoff")
	}
}

func TestNativeUDPSourceSocketCanReachMultipleDestinations(t *testing.T) {
	p := imported(t, `proxies: [{name: live, type: direct}]
rules: ['MATCH,DIRECT']`)
	o := defaultRuntimeOptions()
	o.Mode = "rule"
	o.Definition = p.Definition
	o.Rules = p.Rules
	encoded, _ := json.Marshal(o)
	s, e := newSessionOwned(`{"name":"live","type":"direct"}`, "127.0.0.1:53", string(encoded), nil, true)
	if e != nil {
		t.Fatal(e)
	}
	if e = s.startFullNative(); e != nil {
		t.Fatal(e)
	}
	defer s.close()
	writer := captureWriter{result: make(chan []byte, 2)}
	for _, payload := range []string{"first-destination", "second-destination"} {
		echo, e := net.ListenUDP("udp", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
		if e != nil {
			t.Fatal(e)
		}
		defer echo.Close()
		go func(c *net.UDPConn) {
			b := make([]byte, 64)
			n, peer, e := c.ReadFromUDP(b)
			if e == nil {
				_, _ = c.WriteToUDP(b[:n], peer)
			}
		}(echo)
		packet := buf.NewSize(64)
		_, _ = packet.Write([]byte(payload))
		s.NewPacket(context.Background(), netip.MustParseAddrPort("192.0.2.2:51111"), packet,
			M.Metadata{Source: M.ParseSocksaddr("192.0.2.2:51111"), Destination: M.ParseSocksaddr(echo.LocalAddr().String())},
			func(N.PacketConn) N.PacketWriter { return writer })
		select {
		case reply := <-writer.result:
			if string(reply) != payload {
				t.Fatal("multi-destination UDP payload crossed")
			}
		case <-time.After(3 * time.Second):
			t.Fatal("multi-destination UDP reply lost")
		}
	}
	if s.down.Load() != int64(len("first-destinationsecond-destination")) {
		t.Fatal("UDP accounting mismatch")
	}
}

func TestMeshPoolReusesNamespacedBackendUntilFinalLease(t *testing.T) {
	input := map[string]any{"name": "first", "type": "easytier", "network-name": "fixture", "network-secret": "test-only", "peers": []any{"tcp://127.0.0.1:1"}, "no-listener": true}
	one, e := acquireMesh(input, nil)
	if e != nil {
		t.Fatal(e)
	}
	input["name"] = "second"
	two, e := acquireMesh(input, nil)
	if e != nil {
		one.Close()
		t.Fatal(e)
	}
	a, b := one.(*meshLease), two.(*meshLease)
	if a.entry != b.entry || !strings.HasPrefix(a.entry.namespace, "mesh-") {
		t.Fatal("equivalent backend was recreated")
	}
	_ = one.Close()
	meshPool.Lock()
	alive := !b.entry.closed && b.entry.users == 1
	meshPool.Unlock()
	if !alive {
		t.Fatal("closing one lease stopped another")
	}
	_ = two.Close()
	meshPool.Lock()
	closed := b.entry.closed
	meshPool.Unlock()
	if !closed {
		t.Fatal("final mesh lease leaked backend")
	}
}

func TestFullNativeIncludeAllAndCommaTypeFilterKeepOfficialMembership(t *testing.T) {
	p := imported(t, `proxies:
 - {name: omitted, type: direct}
 - {name: kept, type: socks5, server: 127.0.0.1, port: 9}
proxy-groups: [{name: Main, type: select, include-all: true, exclude-type: 'http, direct'}]
rules: ['MATCH,Main']`)
	o := defaultRuntimeOptions()
	o.Mode = "rule"
	o.Definition = p.Definition
	o.Rules = p.Rules
	encoded, _ := json.Marshal(o)
	s, e := newSessionOwned(`{"type":"profile-target","target":"Main","definition":`+strconv.Quote(p.Definition)+`}`, "127.0.0.1:53", string(encoded), nil, true)
	if e != nil {
		t.Fatal(e)
	}
	defer s.close()
	n, e := prepareFullNative(s)
	if e != nil {
		t.Fatal(e)
	}
	defer n.stop()
	b, e := n.cfg.Proxies["Main"].MarshalJSON()
	if e != nil {
		t.Fatal(e)
	}
	var value struct {
		All []string `json:"all"`
	}
	if json.Unmarshal(b, &value) != nil || !reflect.DeepEqual(value.All, []string{"kept"}) {
		t.Fatal("include-all/type filter changed official membership")
	}
}
