// SPDX-License-Identifier: GPL-3.0-only
package mobile

import (
	"context"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/json"
	"encoding/pem"
	"fmt"
	"io"
	"math/big"
	"net"
	"net/http"
	"net/http/httptest"
	"strconv"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"github.com/metacubex/mihomo/component/ca"
	C "github.com/metacubex/mihomo/constant"
	server "github.com/metacubex/mihomo/listener/anytls"
	LC "github.com/metacubex/mihomo/listener/config"
)

func TestInfoDoesNotClaimAndroidOrStageTelemetry(t *testing.T) {
	var info map[string]any
	if err := json.Unmarshal([]byte(Info()), &info); err != nil {
		t.Fatal(err)
	}
	if info["engineVersion"] != engineVersion || info["vpnAvailable"] != false || info["stageTelemetry"] != false {
		t.Fatal("incorrect native capabilities")
	}
}

func TestConfigurationValidationIsOfflineAndStrict(t *testing.T) {
	valid := `{"type":"anytls","server":"127.0.0.1","port":443,"password":"test-password"}`
	if decoded(t, ValidateNode(valid)).Code != "config_valid_not_connected" {
		t.Fatal("valid config rejected")
	}
	for _, input := range []string{
		`{"type":"direct","server":"127.0.0.1","port":443}`,
		`{"type":"anytls","server":"https://secret.invalid","port":443,"password":"secret"}`,
		`{"type":"anytls","server":"127.0.0.1","port":0,"password":"secret"}`,
		`{"type":"anytls","server":"999.1.2.3","port":443,"password":"secret"}`,
		`{"type":"anytls","server":"127.0.0.1","port":443}`,
		`{"type":"anytls","server":"127.0.0.1","port":443,"password":"secret","certificate":"C:/secret.pem"}`,
		`{"type":"anytls","server":"127.0.0.1","port":443,"password":"secret","unrecognized-option":true}`,
		valid + `{}`,
	} {
		reply := decoded(t, ValidateNode(input))
		if reply.Code != "config_invalid" || reply.State != "failed" {
			t.Errorf("invalid config accepted")
		}
	}
}

func TestTargetAndRunIDValidation(t *testing.T) {
	config := `{"type":"anytls","server":"127.0.0.1","port":443,"password":"secret"}`
	for _, target := range []string{"http://outside.invalid/", "https://user:password@outside.invalid/", "ftp://127.0.0.1/"} {
		if decoded(t, Probe(config, target, "target-check", 1000)).Code != "target_invalid" {
			t.Fatal("unsafe target accepted")
		}
	}
	encoded := Probe(config, "https://example.invalid/", "private/password", 1000)
	if decoded(t, encoded).Code != "invalid_run_id" || strings.Contains(encoded, "private/password") {
		t.Fatal("invalid run ID leaked")
	}
	if Cancel("no-such-run") {
		t.Fatal("cancel should not create a run")
	}
}

func TestRealAnyTLSProxyRequestAndRedaction(t *testing.T) {
	target := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { w.WriteHeader(204) }))
	defer target.Close()
	config := anyTLSFixture(t, target.Listener.Addr().String())
	encoded := Probe(asJSON(t, config), target.URL, "local-anytls-success", 3000)
	reply := decoded(t, encoded)
	if reply.State != "passed" || reply.Code != "proxy_request_verified" || reply.HTTPStatus != 204 {
		t.Fatalf("proxy request not verified: %s", encoded)
	}
	for _, sensitive := range []string{config.Password, config.Server, target.URL, "BEGIN CERTIFICATE"} {
		if sensitive != "" && strings.Contains(encoded, sensitive) {
			t.Fatal("sensitive field leaked")
		}
	}
}

func TestAnyTLSWrongPasswordDoesNotInventAnAuthenticationVerdict(t *testing.T) {
	var reached atomic.Int32
	target := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		reached.Add(1)
		w.WriteHeader(204)
	}))
	defer target.Close()
	config := anyTLSFixture(t, target.Listener.Addr().String())
	config.Password = "wrong-private-password"
	encoded := Probe(asJSON(t, config), target.URL, "local-anytls-wrong-password", 1500)
	reply := decoded(t, encoded)
	if reply.State != "failed" || reply.Code != "proxy_or_target_failed" && reply.Code != "proxy_channel_failed" && reply.Code != "proxy_channel_timeout" && reply.Code != "timeout_stage_unknown" {
		t.Fatalf("incorrect confidence for wrong-password observation: %s", encoded)
	}
	if strings.Contains(encoded, config.Password) || strings.Contains(encoded, "password_invalid") {
		t.Fatal("invented or leaked authentication result")
	}
	if reached.Load() != 0 {
		t.Fatal("failed proxy fell back to a direct target request")
	}
}

func TestDeadlineInterruptsARealProxyRequest(t *testing.T) {
	entered := make(chan struct{}, 1)
	release := make(chan struct{})
	target := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		entered <- struct{}{}
		select {
		case <-r.Context().Done():
		case <-release:
		}
	}))
	defer target.Close()
	defer close(release)
	config := anyTLSFixture(t, target.Listener.Addr().String())
	started := time.Now()
	reply := decoded(t, Probe(asJSON(t, config), target.URL, "deadline-request", 500))
	select {
	case <-entered:
	default:
		t.Fatal("timeout did not exercise a real proxied target request")
	}
	if reply.Code != "timeout_stage_unknown" || reply.State != "failed" || time.Since(started) > 2*time.Second {
		t.Fatalf("deadline did not bound the request: %+v", reply)
	}
}

func TestStalledProxyTLSIsClosedOnCancelAndDeadline(t *testing.T) {
	for _, mode := range []string{"cancel", "deadline"} {
		t.Run(mode, func(t *testing.T) {
			listener, err := net.Listen("tcp", "127.0.0.1:0")
			if err != nil {
				t.Fatal(err)
			}
			defer listener.Close()
			accepted := make(chan struct{})
			closed := make(chan error, 1)
			go func() {
				conn, err := listener.Accept()
				if err != nil {
					closed <- err
					return
				}
				defer conn.Close()
				_ = conn.SetReadDeadline(time.Now().Add(3 * time.Second))
				close(accepted)
				_, err = io.Copy(io.Discard, conn) // Consume ClientHello, never answer TLS.
				closed <- err
			}()
			configJSON := asJSON(t, node{Type: "anytls", Server: "127.0.0.1",
				Port: listener.Addr().(*net.TCPAddr).Port, Password: "local-test-password", SNI: "localhost"})
			id := "stalled-tls-" + mode
			budget := 500
			if mode == "cancel" {
				budget = 10000
			}
			finished := make(chan string, 1)
			defer Cancel(id)
			go func() { finished <- Probe(configJSON, "http://127.0.0.1:1/", id, budget) }()
			select {
			case <-accepted:
			case <-time.After(2 * time.Second):
				t.Fatal("stalled proxy not reached")
			}
			if mode == "cancel" && !Cancel(id) {
				t.Fatal("stalled handshake not cancellable")
			}
			select {
			case result := <-finished:
				code := decoded(t, result).Code
				if mode == "cancel" && code != "cancelled" || mode == "deadline" && code != "timeout_stage_unknown" {
					t.Fatalf("incorrect stalled-handshake result: %s", code)
				}
			case <-time.After(2 * time.Second):
				t.Fatal("stalled proxy request did not terminate")
			}
			select {
			case err := <-closed:
				if timeout, ok := err.(net.Error); ok && timeout.Timeout() {
					t.Fatal("proxy socket survived until fixture deadline")
				}
			case <-time.After(time.Second):
				t.Fatal("proxy handshake socket leaked after termination")
			}
		})
	}
}

func TestAnyTLSUntrustedAndWrongNameCertificatesFail(t *testing.T) {
	for _, mode := range []string{"untrusted", "wrong-name"} {
		t.Run(mode, func(t *testing.T) {
			target := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { w.WriteHeader(204) }))
			defer target.Close()
			config := anyTLSFixture(t, target.Listener.Addr().String())
			if mode == "untrusted" {
				ca.ResetCertificate()
			} else {
				config.SNI = "wrong-name.invalid"
			}
			reply := decoded(t, Probe(asJSON(t, config), target.URL, "certificate-"+mode, 2500))
			if reply.State != "failed" || reply.Code != "proxy_certificate_failed" {
				t.Fatalf("certificate unexpectedly accepted: %+v", reply)
			}
		})
	}
}

func TestImportedCertificatePolicyControlsOnlyProxyVerification(t *testing.T) {
	for _, mode := range []string{"trusted", "untrusted", "wrong-name"} {
		for _, skip := range []bool{false, true} {
			t.Run(mode+strconv.FormatBool(skip), func(t *testing.T) {
				target := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { w.WriteHeader(204) }))
				defer target.Close()
				config := anyTLSFixture(t, target.Listener.Addr().String())
				if mode == "untrusted" {
					ca.ResetCertificate()
				}
				if mode == "wrong-name" {
					config.SNI = "wrong-name.invalid"
				}
				config.SkipCertVerify = skip
				p := imported(t, asJSON(t, config))
				if len(p.Nodes) != 1 || p.VerificationEnabled != 0 {
					t.Fatal("import changed certificate policy")
				}
				reply := decoded(t, Probe(p.Nodes[0].Config, target.URL, "policy-"+mode+strconv.FormatBool(skip), 2500))
				if mode == "trusted" || skip {
					if reply.State != "passed" {
						t.Fatalf("configured proxy policy failed: %s", reply.Code)
					}
				} else if reply.Code != "proxy_certificate_failed" {
					t.Fatalf("strict verification failed open: %s", reply.Code)
				}
			})
		}
	}
}

func TestHTTPStatusAndRedirectAreExplicit(t *testing.T) {
	var redirected atomic.Int32
	destination := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { redirected.Add(1); w.WriteHeader(204) }))
	defer destination.Close()
	for _, status := range []int{302, 503} {
		t.Run(strconv.Itoa(status), func(t *testing.T) {
			target := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				w.Header().Set("Location", destination.URL)
				w.WriteHeader(status)
			}))
			defer target.Close()
			config := anyTLSFixture(t, target.Listener.Addr().String())
			reply := decoded(t, Probe(asJSON(t, config), target.URL, "http-status-"+strconv.Itoa(status), 3000))
			if reply.Code != "target_http_status" || reply.HTTPStatus != status {
				t.Fatalf("HTTP status lost: %+v", reply)
			}
		})
	}
	if redirected.Load() != 0 {
		t.Fatal("probe followed an unexpected redirect")
	}
}

func TestCancellationInterruptsARealProxyRequest(t *testing.T) {
	entered := make(chan struct{}, 1)
	release := make(chan struct{})
	target := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		entered <- struct{}{}
		select {
		case <-r.Context().Done():
		case <-release:
		}
	}))
	defer target.Close()
	defer close(release)
	config := anyTLSFixture(t, target.Listener.Addr().String())
	finished := make(chan string, 1)
	configJSON := asJSON(t, config)
	defer Cancel("cancel-real-request")
	go func() { finished <- Probe(configJSON, target.URL, "cancel-real-request", 10000) }()
	select {
	case <-entered:
	case <-time.After(3 * time.Second):
		t.Fatal("proxy target not reached")
	}
	if !Cancel("cancel-real-request") {
		t.Fatal("active run not cancellable")
	}
	select {
	case encoded := <-finished:
		if decoded(t, encoded).State != "cancelled" {
			t.Fatalf("cancellation misclassified: %s", encoded)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("cancellation did not close request")
	}
}

func TestConcurrencyLimitIsNotANodeFailure(t *testing.T) {
	entered := make(chan struct{}, maxProbeRuns)
	release := make(chan struct{})
	target := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		entered <- struct{}{}
		select {
		case <-r.Context().Done():
		case <-release:
		}
	}))
	defer target.Close()
	defer close(release)
	config := anyTLSFixture(t, target.Listener.Addr().String())
	configJSON := asJSON(t, config)
	finished := make(chan string, maxProbeRuns)
	ids := []string{}
	for i := 0; i < maxProbeRuns; i++ {
		ids = append(ids, fmt.Sprintf("busy-%d", i))
	}
	for _, id := range ids {
		go func(id string) { finished <- Probe(configJSON, target.URL, id, 10000) }(id)
		defer Cancel(id)
	}
	for i := 0; i < maxProbeRuns; i++ {
		select {
		case <-entered:
		case <-time.After(3 * time.Second):
			t.Fatal("requests not active")
		}
	}
	reply := decoded(t, Probe(configJSON, target.URL, "busy-overflow", 1000))
	if reply.State != "not_attempted" || reply.Code != "resource_busy" {
		t.Fatal("busy queue reported as node failure")
	}
	for _, id := range ids {
		Cancel(id)
	}
	for i := 0; i < maxProbeRuns; i++ {
		select {
		case <-finished:
		case <-time.After(2 * time.Second):
			t.Fatal("concurrent cancellation stalled")
		}
	}
}

type loopbackTunnel struct {
	allowed string
}

func (t loopbackTunnel) HandleTCPConn(conn net.Conn, metadata *C.Metadata) {
	defer conn.Close()
	if metadata.RemoteAddress() != t.allowed {
		return
	}
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	remote, err := (&net.Dialer{}).DialContext(ctx, "tcp", t.allowed)
	if err != nil {
		return
	}
	defer remote.Close()
	go func() { _, _ = io.Copy(remote, conn); _ = remote.Close() }()
	_, _ = io.Copy(conn, remote)
}
func (t loopbackTunnel) HandleUDPPacket(packet C.UDPPacket, metadata *C.Metadata) {
	defer packet.Drop()
	if metadata.RemoteAddress() != t.allowed {
		return
	}
	conn, err := net.DialTimeout("udp", t.allowed, time.Second)
	if err != nil {
		return
	}
	defer conn.Close()
	_ = conn.SetDeadline(time.Now().Add(2 * time.Second))
	if _, err = conn.Write(packet.Data()); err != nil {
		return
	}
	buffer := make([]byte, 4096)
	n, err := conn.Read(buffer)
	if err != nil {
		return
	}
	_, _ = packet.WriteBack(buffer[:n], conn.RemoteAddr())
}
func (loopbackTunnel) NatTable() C.NatTable { return nil }

func anyTLSFixture(t *testing.T, target string, override ...C.Tunnel) node {
	t.Helper()
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	certificate := &x509.Certificate{
		SerialNumber: big.NewInt(time.Now().UnixNano()), Subject: pkix.Name{CommonName: "localhost"},
		DNSNames: []string{"localhost"}, NotBefore: time.Now().Add(-time.Minute), NotAfter: time.Now().Add(time.Hour),
		IsCA: true, BasicConstraintsValid: true,
		KeyUsage:    x509.KeyUsageDigitalSignature | x509.KeyUsageCertSign,
		ExtKeyUsage: []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth},
	}
	der, err := x509.CreateCertificate(rand.Reader, certificate, certificate, &key.PublicKey, key)
	if err != nil {
		t.Fatal(err)
	}
	private, err := x509.MarshalPKCS8PrivateKey(key)
	if err != nil {
		t.Fatal(err)
	}
	certPEM := string(pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: der}))
	keyPEM := string(pem.EncodeToMemory(&pem.Block{Type: "PRIVATE KEY", Bytes: private}))
	// Only this test process trusts its ephemeral CA. Production never mutates roots.
	if err := ca.AddCertificate(certPEM); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(ca.ResetCertificate)
	var tunnel C.Tunnel = loopbackTunnel{allowed: target}
	if len(override) > 0 {
		tunnel = override[0]
	}
	listener, err := server.New(LC.AnyTLSServer{
		Enable: true, Listen: "127.0.0.1:0", Users: map[string]string{"test": "local-test-password"},
		Certificate: certPEM, PrivateKey: keyPEM,
	}, new(net.ListenConfig), tunnel)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = listener.Close() })
	_, portText, err := net.SplitHostPort(listener.AddrList()[0].String())
	if err != nil {
		t.Fatal(err)
	}
	port, _ := strconv.Atoi(portText)
	return node{Type: "anytls", Server: "127.0.0.1", Port: port,
		Password: "local-test-password", SNI: "localhost"}
}

func asJSON(t *testing.T, config node) string {
	t.Helper()
	value, err := json.Marshal(config)
	if err != nil {
		t.Fatal(err)
	}
	return string(value)
}

func decoded(t *testing.T, encoded string) response {
	t.Helper()
	var result response
	if err := json.Unmarshal([]byte(encoded), &result); err != nil {
		t.Fatal(err)
	}
	return result
}
