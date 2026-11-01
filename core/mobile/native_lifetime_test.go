// SPDX-License-Identifier: GPL-3.0-only
package mobile

import (
	"context"
	"errors"
	"strings"
	"testing"
	"time"

	"github.com/metacubex/mihomo/config"
	C "github.com/metacubex/mihomo/constant"
)

type lifetimePacket struct{ C.PacketConn }

func (*lifetimePacket) Close() error { return nil }

type lifetimeAdapter struct {
	C.ProxyAdapter
	fail bool
}

func (a *lifetimeAdapter) ListenPacketContext(context.Context, *C.Metadata) (C.PacketConn, error) {
	if a.fail {
		return nil, errors.New("fixture")
	}
	return &lifetimePacket{}, nil
}

func TestRetiredGenerationRetainsAnActivePacketTransport(t *testing.T) {
	root := drainTestRoot(t)
	n := &fullNativeRuntime{cfg: &config.Config{}}
	adapter := &nativeLeasedAdapter{ProxyAdapter: &lifetimeAdapter{}, generation: n}
	packet, err := adapter.ListenPacketContext(context.Background(), &C.Metadata{})
	if err != nil {
		t.Fatal(err)
	}
	root.drainNative(n)
	root.routingMu.Lock()
	timer := root.drainingNative[n]
	root.routingMu.Unlock()
	timer.Reset(time.Millisecond)
	time.Sleep(25 * time.Millisecond)
	if n.stopped.Load() || n.beginIdleStop() {
		t.Fatal("retirement cut off a live packet transport")
	}
	_ = packet.Close()
	_ = packet.Close()
	n.trafficMu.Lock()
	users := n.users
	n.trafficMu.Unlock()
	if users != 0 {
		t.Fatalf("leases after repeated close: %d", users)
	}
	timer.Reset(time.Millisecond)
	deadline := time.Now().Add(3 * time.Second)
	for !n.stopped.Load() && time.Now().Before(deadline) {
		time.Sleep(time.Millisecond)
	}
	if !n.stopped.Load() {
		t.Fatal("idle generation was never released")
	}
	if _, err = adapter.ListenPacketContext(context.Background(), &C.Metadata{}); err == nil {
		t.Fatal("stopped generation accepted a new dial")
	}
	root.stopDrainingNative()
}

func TestFailedTransportDialAndPanicValueDoNotLeak(t *testing.T) {
	n := &fullNativeRuntime{}
	adapter := &nativeLeasedAdapter{ProxyAdapter: &lifetimeAdapter{fail: true}, generation: n}
	_, _ = adapter.ListenPacketContext(context.Background(), &C.Metadata{})
	if !n.beginIdleStop() {
		t.Fatal("failed dial retained a generation lease")
	}
	const secret = "CANARY-PANIC-PASSWORD-020"
	if strings.Contains(panicSummary(errors.New(secret)), secret) || strings.Contains(panicSummary(secret), secret) {
		t.Fatal("panic summary leaked a credential")
	}
	const numeric = "buffer overflow: capacity 32768,start 0, need 2"
	if panicSummary(numeric) != numeric {
		t.Fatal("lost safe numeric panic details")
	}
}
