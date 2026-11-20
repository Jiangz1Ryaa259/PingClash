package mobile

import (
	"net/netip"
	"testing"

	"github.com/metacubex/mihomo/component/fakeip"
	"github.com/metacubex/mihomo/component/resolver"
	C "github.com/metacubex/mihomo/constant"
	MDNS "github.com/metacubex/mihomo/dns"
)

// A reconnect keeps the fake IPs handed out before it: an app that cached one still reaches its
// domain. Closing a session puts the global mapper back to none; the next start offers the old
// one to ApplyConfig, which copies its pairs.
func TestFakeIPMappingSurvivesAReconnect(t *testing.T) {
	before := resolver.DefaultHostMapper
	defer func() { resolver.DefaultHostMapper = before; carriedHostMapper.Store(nil) }()
	resolver.DefaultHostMapper = nil
	restore := saveNativeGlobals()

	pool, err := fakeip.New(fakeip.Options{IPNet: netip.MustParsePrefix("198.18.0.1/16"), Size: 64})
	if err != nil {
		t.Fatal(err)
	}
	first := MDNS.NewEnhancer(MDNS.EnhancerConfig{EnhancedMode: C.DNSFakeIP, FakeIPPool: pool})
	resolver.DefaultHostMapper = first
	address := pool.Lookup("peer.fixture.test")
	restore()
	if resolver.DefaultHostMapper != nil {
		t.Fatal("closing a session must leave no mapper installed")
	}

	adoptCarriedHostMapper()
	nextPool, _ := fakeip.New(fakeip.Options{IPNet: netip.MustParsePrefix("198.18.0.1/16"), Size: 64})
	next := MDNS.NewEnhancer(MDNS.EnhancerConfig{EnhancedMode: C.DNSFakeIP, FakeIPPool: nextPool})
	if old, ok := resolver.DefaultHostMapper.(*MDNS.ResolverEnhancer); ok && old != nil {
		next.PatchFrom(old) // what ApplyConfig does with the installed mapper
	} else {
		t.Fatal("the next session was not offered the previous mapper")
	}
	if host, ok := next.FindHostByIP(address); !ok || host != "peer.fixture.test" {
		t.Fatalf("fake IP %s lost its domain after a reconnect (%q)", address, host)
	}
}
