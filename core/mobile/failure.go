// SPDX-License-Identifier: GPL-3.0-only
package mobile

import (
	"crypto/x509"
	"errors"
	"net"

	"github.com/metacubex/mihomo/component/resolver"
	quic "github.com/metacubex/quic-go"
)

// Preserve the boundary at which an error occurred, never its private message.
type proxyConnectError struct{ cause error }

func (e *proxyConnectError) Error() string { return "proxy_channel_failed" }
func (e *proxyConnectError) Unwrap() error { return e.cause }

func certificateFailure(err error) bool {
	var authority x509.UnknownAuthorityError
	var hostname x509.HostnameError
	var invalid x509.CertificateInvalidError
	return errors.As(err, &authority) || errors.As(err, &hostname) || errors.As(err, &invalid)
}

func proxyFailureCode(err error) string {
	var channel *proxyConnectError
	if errors.As(err, &channel) {
		if certificateFailure(err) {
			return "proxy_certificate_failed"
		}
		var handshake *quic.HandshakeTimeoutError
		if errors.As(err, &handshake) {
			return "proxy_quic_handshake_timeout"
		}
		var transport *quic.TransportError
		if errors.As(err, &transport) && transport.ErrorCode.IsCryptoError() {
			return "proxy_quic_tls_failed"
		}
		var dnsError *net.DNSError
		if errors.As(err, &dnsError) || errors.Is(err, resolver.ErrIPNotFound) {
			return "proxy_dns_failed"
		}
		var networkError net.Error
		if errors.As(err, &networkError) && networkError.Timeout() {
			return "proxy_channel_timeout"
		}
		return "proxy_channel_failed"
	}
	if certificateFailure(err) {
		return "target_certificate_failed"
	}
	return "proxy_or_target_failed"
}
