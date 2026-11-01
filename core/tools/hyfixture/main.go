// SPDX-License-Identifier: GPL-3.0-only
// Dedicated desktop integration fixture. Never linked into mobile or the APK.
package main

import (
	"context"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/json"
	"encoding/pem"
	"flag"
	"io"
	"math/big"
	"net"
	"net/http"
	"os"
	"strconv"
	"sync/atomic"
	"time"

	C "github.com/metacubex/mihomo/constant"
	LC "github.com/metacubex/mihomo/listener/config"
	hyserver "github.com/metacubex/mihomo/listener/sing_hysteria2"
	"github.com/metacubex/mihomo/log"
	"github.com/miekg/dns"
)

type fixtureTunnel struct{ httpAddr, dnsAddr, peerHost string }

func (t fixtureTunnel) HandleTCPConn(conn net.Conn, metadata *C.Metadata) {
	defer conn.Close()
	var target string
	switch metadata.RemoteAddress() {
	case t.httpAddr, "198.18.0.10:8080", net.JoinHostPort(t.peerHost, "8080"):
		target = t.httpAddr
	case t.dnsAddr:
		target = t.dnsAddr
	default:
		return
	}
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	remote, err := (&net.Dialer{}).DialContext(ctx, "tcp", target)
	if err != nil {
		return
	}
	defer remote.Close()
	_ = conn.SetDeadline(time.Now().Add(12 * time.Second))
	_ = remote.SetDeadline(time.Now().Add(12 * time.Second))
	go func() { _, _ = io.Copy(remote, conn); _ = remote.Close() }()
	_, _ = io.Copy(conn, remote)
}
func (fixtureTunnel) HandleUDPPacket(packet C.UDPPacket, _ *C.Metadata) { packet.Drop() }
func (fixtureTunnel) NatTable() C.NatTable                              { return nil }

func main() {
	ready := flag.String("ready", "", "Ready JSON file")
	stop := flag.String("stop", "", "Stop marker")
	flag.Parse()
	if *ready == "" || *stop == "" {
		panic("fixture arguments missing")
	}
	log.SetLevel(log.SILENT)
	var peerRequests, dnsQueries atomic.Int64
	host := "hy2-" + strconv.FormatInt(time.Now().UnixNano(), 36) + ".fixture.test"
	httpListener, err := net.Listen("tcp", "127.0.0.1:0")
	must(err)
	defer httpListener.Close()
	dnsListener, err := net.Listen("tcp", "127.0.0.1:0")
	must(err)
	defer dnsListener.Close()
	httpServer := &http.Server{ReadHeaderTimeout: 3 * time.Second, ReadTimeout: 5 * time.Second, WriteTimeout: 5 * time.Second, MaxHeaderBytes: 8192,
		Handler: http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			if r.URL.Path == "/stats" {
				w.Header().Set("Content-Type", "application/json")
				_ = json.NewEncoder(w).Encode(map[string]int64{"peerRequests": peerRequests.Load(), "dnsQueries": dnsQueries.Load()})
				return
			}
			if r.URL.Path == "/peer" {
				peerRequests.Add(1)
			}
			w.WriteHeader(http.StatusNoContent)
		})}
	defer httpServer.Close()
	go func() { _ = httpServer.Serve(httpListener) }()
	dnsServer := &dns.Server{Listener: dnsListener, Net: "tcp", Handler: dns.HandlerFunc(func(w dns.ResponseWriter, q *dns.Msg) {
		a := new(dns.Msg)
		a.SetReply(q)
		if len(q.Question) != 1 || q.Question[0].Name != host+"." {
			a.Rcode = dns.RcodeNameError
		} else {
			dnsQueries.Add(1)
			if q.Question[0].Qtype == dns.TypeA {
				a.Answer = []dns.RR{&dns.A{Hdr: dns.RR_Header{Name: q.Question[0].Name, Rrtype: dns.TypeA, Class: dns.ClassINET, Ttl: 1}, A: net.IPv4(198, 18, 0, 10)}}
			}
		}
		_ = w.WriteMsg(a)
	})}
	defer dnsServer.Shutdown()
	go func() { _ = dnsServer.ActivateAndServe() }()
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	must(err)
	cert := &x509.Certificate{SerialNumber: big.NewInt(time.Now().UnixNano()), Subject: pkix.Name{CommonName: "fixture.invalid"}, DNSNames: []string{"fixture.invalid"}, NotBefore: time.Now().Add(-time.Minute), NotAfter: time.Now().Add(time.Hour), KeyUsage: x509.KeyUsageDigitalSignature, ExtKeyUsage: []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth}}
	der, err := x509.CreateCertificate(rand.Reader, cert, cert, &key.PublicKey, key)
	must(err)
	private, err := x509.MarshalPKCS8PrivateKey(key)
	must(err)
	listener, err := hyserver.New(LC.Hysteria2Server{Enable: true, Listen: "127.0.0.1:0", Users: map[string]string{"test": "public-hy2-fixture-password"}, Certificate: string(pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: der})), PrivateKey: string(pem.EncodeToMemory(&pem.Block{Type: "PRIVATE KEY", Bytes: private}))}, new(net.ListenConfig), fixtureTunnel{httpListener.Addr().String(), dnsListener.Addr().String(), host})
	must(err)
	defer listener.Close()
	metadata := map[string]any{"hy2Port": listener.AddrList()[0].(*net.UDPAddr).Port, "httpPort": httpListener.Addr().(*net.TCPAddr).Port, "dnsPort": dnsListener.Addr().(*net.TCPAddr).Port, "host": host}
	encoded, err := json.Marshal(metadata)
	must(err)
	must(os.WriteFile(*ready, encoded, 0600))
	deadline := time.NewTimer(45 * time.Minute)
	defer deadline.Stop()
	tick := time.NewTicker(250 * time.Millisecond)
	defer tick.Stop()
	for {
		select {
		case <-deadline.C:
			return
		case <-tick.C:
			if _, err := os.Stat(*stop); err == nil {
				return
			}
		}
	}
}
func must(err error) {
	if err != nil {
		panic(err)
	}
}
