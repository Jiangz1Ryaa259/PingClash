// SPDX-License-Identifier: GPL-3.0-only
package mobile

import (
	"crypto/x509"
	"encoding/json"
	"encoding/pem"
	"errors"
	"net"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/metacubex/mihomo/component/ca"
	LC "github.com/metacubex/mihomo/listener/config"
	hyserver "github.com/metacubex/mihomo/listener/sing_hysteria2"
	quic "github.com/metacubex/quic-go"
)

func TestFailureCategoriesDoNotEchoPrivateErrors(t *testing.T) {
	for _, item := range []struct {
		err  error
		code string
	}{
		{x509.UnknownAuthorityError{}, "target_certificate_failed"},
		{&proxyConnectError{x509.UnknownAuthorityError{}}, "proxy_certificate_failed"},
		{&proxyConnectError{&quic.HandshakeTimeoutError{}}, "proxy_quic_handshake_timeout"},
		{&proxyConnectError{&quic.TransportError{ErrorCode: 0x12a, ErrorMessage: "private-token"}}, "proxy_quic_tls_failed"},
		{&proxyConnectError{&net.DNSError{Name: "private.invalid", Err: "private-token"}}, "proxy_dns_failed"},
		{&proxyConnectError{errors.New("private-token")}, "proxy_channel_failed"},
		{errors.New("private-token"), "proxy_or_target_failed"},
	} {
		if got := proxyFailureCode(item.err); got != item.code {
			t.Fatalf("classification: want=%s got=%s", item.code, got)
		}
	}
}

func TestHysteria2RealQUICAndCertificateFailureEvidence(t *testing.T) {
	for _, mode := range []string{"trusted", "untrusted", "wrong-name", "untrusted-configured-skip", "wrong-name-configured-skip", "socket-denied"} {
		t.Run(mode, func(t *testing.T) {
			target := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { w.WriteHeader(204) }))
			defer target.Close()
			// Public test-only certificate; no CA changes exist in production paths.
			certSource := httptest.NewTLSServer(nil)
			cert := certSource.TLS.Certificates[0]
			certSource.Close()
			certPEM := string(pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: cert.Certificate[0]}))
			private, err := x509.MarshalPKCS8PrivateKey(cert.PrivateKey)
			if err != nil {
				t.Fatal(err)
			}
			keyPEM := string(pem.EncodeToMemory(&pem.Block{Type: "PRIVATE KEY", Bytes: private}))
			if !strings.HasPrefix(mode, "untrusted") {
				if err := ca.AddCertificate(certPEM); err != nil {
					t.Fatal(err)
				}
			}
			t.Cleanup(ca.ResetCertificate)
			listener, err := hyserver.New(LC.Hysteria2Server{Enable: true, Listen: "127.0.0.1:0", Users: map[string]string{"test": "fixture-password"}, Certificate: certPEM, PrivateKey: keyPEM}, new(net.ListenConfig), loopbackTunnel{allowed: target.Listener.Addr().String()})
			if err != nil {
				t.Fatal(err)
			}
			t.Cleanup(func() { _ = listener.Close() })
			config := map[string]any{"type": "hysteria2", "server": "127.0.0.1", "port": listener.AddrList()[0].(*net.UDPAddr).Port, "password": "fixture-password", "sni": "example.com", "skip-cert-verify": strings.HasSuffix(mode, "configured-skip")}
			if strings.HasPrefix(mode, "wrong-name") {
				config["sni"] = "private-wrong-name.invalid"
			}
			encoded, _ := json.Marshal(config)
			profile := imported(t, string(encoded))
			if len(profile.Nodes) != 1 || profile.VerificationEnabled != 0 {
				t.Fatal("HY2 import failed")
			}
			if mode == "socket-denied" {
				SetPlatform(deniedPlatform{})
				t.Cleanup(func() { SetPlatform(nil) })
			}
			result := Probe(profile.Nodes[0].Config, target.URL, "hy2-"+mode, 3000)
			reply := decoded(t, result)
			want := "proxy_certificate_failed"
			if mode == "trusted" || strings.HasSuffix(mode, "configured-skip") {
				want = "proxy_request_verified"
			}
			if mode == "socket-denied" {
				want = "local_network_restriction"
			}
			if reply.Code != want {
				t.Fatalf("HY2 evidence mode=%s want=%s got=%s", mode, want, reply.Code)
			}
			if strings.Contains(result, "fixture-password") || strings.Contains(result, "127.0.0.1") || strings.Contains(result, "example.com") {
				t.Fatal("private endpoint escaped")
			}
		})
	}
}

func TestTargetCertificateFailureIsNotBlamedOnProxyCertificate(t *testing.T) {
	target := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { w.WriteHeader(204) }))
	defer target.Close()
	config := anyTLSFixture(t, target.Listener.Addr().String())
	config.SkipCertVerify = true
	ca.ResetCertificate()
	if reply := decoded(t, Probe(asJSON(t, config), target.URL, "target-cert", 3000)); reply.Code != "target_certificate_failed" {
		t.Fatalf("wrong certificate stage: %s", reply.Code)
	}
}
