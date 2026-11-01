// SPDX-License-Identifier: GPL-3.0-only
package mobile

import (
	"context"
	"net/netip"
	"sync/atomic"

	"github.com/metacubex/mihomo/component/resolver"
)

// The public Mihomo resolver interfaces stay constant for the process lifetime.
// Only the session pointer is atomically published/cleared. Parallel delay tests
// do not observe unsynchronized global resolver replacements during VPN start.
var activeNativeDNS atomic.Pointer[nativeDNSRuntime]

type runtimeDNSResolver struct {
	platformResolver
	role string
}

func (r runtimeDNSResolver) LookupIP(ctx context.Context, host string) ([]netip.Addr, error) {
	if ip, e := netip.ParseAddr(host); e == nil {
		return []netip.Addr{ip.Unmap()}, nil
	}
	if engine := activeNativeDNS.Load(); engine != nil {
		host, value := engine.realHost(host)
		if value != nil {
			return value, nil
		}
		native := engine.resolvers.Resolver
		if r.role == "proxy" && engine.resolvers.ProxyResolver.Invalid() {
			native = engine.resolvers.ProxyResolver
		}
		if r.role == "direct" && engine.resolvers.DirectResolver.Invalid() {
			native = engine.resolvers.DirectResolver
		}
		if native != nil && native.Invalid() {
			return native.LookupIP(ctx, host)
		}
	}
	return (platformResolver{}).LookupIP(ctx, host)
}
func (r runtimeDNSResolver) LookupIPv4(ctx context.Context, host string) ([]netip.Addr, error) {
	return r.family(ctx, host, true)
}
func (r runtimeDNSResolver) LookupIPv6(ctx context.Context, host string) ([]netip.Addr, error) {
	return r.family(ctx, host, false)
}
func (r runtimeDNSResolver) family(ctx context.Context, host string, v4 bool) ([]netip.Addr, error) {
	ips, e := r.LookupIP(ctx, host)
	if e != nil {
		return nil, e
	}
	result := []netip.Addr{}
	for _, ip := range ips {
		if ip.Is4() == v4 {
			result = append(result, ip)
		}
	}
	if len(result) == 0 {
		return nil, resolver.ErrIPNotFound
	}
	return result, nil
}

var _ resolver.Resolver = runtimeDNSResolver{}
