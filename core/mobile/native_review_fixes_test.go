// SPDX-License-Identifier: GPL-3.0-only
package mobile

import (
	"context"
	"net/netip"
	"testing"
	"time"

	"github.com/metacubex/mihomo/config"
)

func TestBootstrapNameserverKeepsTheConfiguredPort(t *testing.T) {
	for dns, want := range map[string]string{
		"1.1.1.1:53":         "tcp://1.1.1.1:53",
		"9.9.9.9:5353":       "tcp://9.9.9.9:5353",
		"[2606:4700::1]:853": "tcp://[2606:4700::1]:853",
	} {
		if got := bootstrapNameserver(netip.MustParseAddrPort(dns)); got != want {
			t.Fatalf("%s: got %s, want %s", dns, got, want)
		}
	}
}

func TestNativeHostLabelsAddressOnlyDestinations(t *testing.T) {
	cases := []struct{ destination, host, want string }{
		{"93.184.216.34:443", "example.com", "example.com (93.184.216.34:443)"},
		{"93.184.216.34:443", "", "93.184.216.34:443"},
		{"example.com (93.184.216.34:443)", "example.com", "example.com (93.184.216.34:443)"},
		{"", "example.com", ""},
	}
	for _, c := range cases {
		if got := withNativeHost(c.destination, c.host); got != c.want {
			t.Fatalf("withNativeHost(%q, %q) = %q, want %q", c.destination, c.host, got, c.want)
		}
	}
	if !isAddress("10.0.0.1") || !isAddress("::1") || isAddress("example.com") {
		t.Fatal("isAddress misclassifies")
	}
}

func drainTestRoot(t *testing.T) *vpnSession {
	ctx, cancel := context.WithCancel(context.Background())
	t.Cleanup(cancel)
	return &vpnSession{ctx: ctx, cancel: cancel, options: runtimeOptions{UDPTimeout: 60},
		drainingNative: map[*fullNativeRuntime]*time.Timer{}}
}

func TestRetiredNativeGenerationsAreStoppedAfterGraceOrAtVPNStop(t *testing.T) {
	root := drainTestRoot(t)
	if grace := root.nativeStopGrace(); grace < 60*time.Second {
		t.Fatalf("grace %v is shorter than the UDP idle timeout", grace)
	}
	n := &fullNativeRuntime{cfg: &config.Config{}}
	root.drainNative(n)
	root.drainNative(n) // Retiring twice keeps one timer.
	if n.stopped.Load() || len(root.drainingNative) != 1 {
		t.Fatal("a retired generation must wait for its grace period")
	}
	// The VPN stops before the grace period ends: everything is stopped at once.
	root.cancel()
	root.stopDrainingNative()
	if !n.stopped.Load() || root.drainingNative != nil {
		t.Fatal("VPN stop must stop every draining generation")
	}
	n.stop() // Idempotent.

	// Retiring after the VPN stopped stops immediately instead of arming a timer.
	late := &fullNativeRuntime{cfg: &config.Config{}}
	root.drainNative(late)
	if !late.stopped.Load() {
		t.Fatal("a generation retired during shutdown must stop at once")
	}
}

func TestRetiredNativeGenerationStopsWhenItsTimerFires(t *testing.T) {
	root := drainTestRoot(t)
	n := &fullNativeRuntime{cfg: &config.Config{}}
	root.drainNative(n)
	root.routingMu.Lock()
	timer := root.drainingNative[n]
	root.routingMu.Unlock()
	timer.Reset(time.Millisecond)
	deadline := time.Now().Add(5 * time.Second)
	for !n.stopped.Load() && time.Now().Before(deadline) {
		time.Sleep(5 * time.Millisecond)
	}
	if !n.stopped.Load() {
		t.Fatal("the grace timer never stopped the generation")
	}
	root.routingMu.Lock()
	left := len(root.drainingNative)
	root.routingMu.Unlock()
	if left != 0 {
		t.Fatal("a stopped generation must leave the draining set")
	}
}
