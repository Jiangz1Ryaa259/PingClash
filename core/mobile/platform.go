// SPDX-License-Identifier: GPL-3.0-only
package mobile

import (
	"context"
	"errors"
	"net"
	"net/netip"
	"runtime"
	"strings"
	"sync"
	"syscall"
	"time"

	"github.com/metacubex/mihomo/component/dialer"
	"github.com/metacubex/mihomo/component/resolver"
	"github.com/metacubex/mihomo/log"
	"github.com/miekg/dns"
	"github.com/sirupsen/logrus"
	"io"
)

// Platform binds sockets to an underlying Android network and protects them
// from the VPN. Lookup returns newline-separated literal addresses only.
type Platform interface {
	Protect(fd int) bool
	Lookup(host string, timeoutMillis int) string
	ConnectionOwner(protocol int, sourceAddress, destinationAddress string) string
}

var platformState struct {
	sync.RWMutex
	callback Platform
}

var errSocketPolicy = errors.New("socket_protection_failed")

func init() {
	// Raw mihomo errors can contain private endpoints. Only our result codes leave Go.
	logrus.SetOutput(io.Discard)
	log.SetLevel(log.SILENT)
	resolver.SystemResolver = platformResolver{}
	resolver.DefaultResolver = runtimeDNSResolver{role: "main"}
	resolver.ProxyServerHostResolver = runtimeDNSResolver{role: "proxy"}
	resolver.DirectHostResolver = runtimeDNSResolver{role: "direct"}
	resolver.DisableIPv6 = false
	dialer.DefaultSocketHook = func(_, _ string, conn syscall.RawConn) error {
		platformState.RLock()
		callback := platformState.callback
		platformState.RUnlock()
		if callback == nil {
			if runtime.GOOS == "android" {
				return errSocketPolicy
			}
			return nil // Desktop loopback tests do not install an Android callback.
		}
		accepted := false
		if err := conn.Control(func(fd uintptr) { accepted = callback.Protect(int(fd)) }); err != nil {
			return err
		}
		if !accepted {
			return errSocketPolicy
		}
		return nil
	}
}

// SetPlatform is installed once when the Android application starts.
func SetPlatform(callback Platform) {
	platformState.Lock()
	platformState.callback = callback
	platformState.Unlock()
}

type platformResolver struct{}

func (platformResolver) LookupIP(ctx context.Context, host string) ([]netip.Addr, error) {
	platformState.RLock()
	callback := platformState.callback
	platformState.RUnlock()
	var ips []netip.Addr
	if callback == nil {
		if runtime.GOOS == "android" {
			return nil, errSocketPolicy
		}
		values, err := net.DefaultResolver.LookupNetIP(ctx, "ip", host)
		if err != nil {
			return nil, err
		}
		ips = values
	} else {
		budget := 5000
		if end, ok := ctx.Deadline(); ok {
			remaining := int(time.Until(end).Milliseconds())
			if remaining <= 0 {
				return nil, context.DeadlineExceeded
			}
			if remaining < budget {
				budget = remaining
			}
		}
		for _, text := range strings.Fields(callback.Lookup(host, budget)) {
			if ip, err := netip.ParseAddr(text); err == nil {
				ips = append(ips, ip.Unmap())
			}
		}
	}
	if err := ctx.Err(); err != nil {
		return nil, err
	}
	if len(ips) == 0 {
		return nil, resolver.ErrIPNotFound
	}
	return ips, nil
}
func (r platformResolver) LookupIPv4(ctx context.Context, host string) ([]netip.Addr, error) {
	return r.family(ctx, host, true)
}
func (r platformResolver) LookupIPv6(ctx context.Context, host string) ([]netip.Addr, error) {
	return r.family(ctx, host, false)
}
func (r platformResolver) family(ctx context.Context, host string, ipv4 bool) ([]netip.Addr, error) {
	ips, err := r.LookupIP(ctx, host)
	if err != nil {
		return nil, err
	}
	filtered := make([]netip.Addr, 0, len(ips))
	for _, ip := range ips {
		if ip.Is4() == ipv4 {
			filtered = append(filtered, ip)
		}
	}
	if len(filtered) == 0 {
		return nil, resolver.ErrIPNotFound
	}
	return filtered, nil
}
func (platformResolver) ResolveECH(context.Context, string) ([]byte, error) {
	return nil, resolver.ErrIPNotFound
}
func (platformResolver) ExchangeContext(context.Context, *dns.Msg) (*dns.Msg, error) {
	return nil, resolver.ErrIPNotFound
}
func (platformResolver) Invalid() bool    { return true }
func (platformResolver) ClearCache()      {}
func (platformResolver) ResetConnection() {}
