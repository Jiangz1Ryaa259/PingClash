// SPDX-License-Identifier: GPL-3.0-only
package mobile

import (
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/x509"
	"encoding/base64"
	"encoding/json"
	"encoding/pem"
	"math/big"
	"strings"
	"testing"
	"time"

	"github.com/metacubex/mihomo/adapter"
)

func TestAdditionalNativeOutboundsAndPathIsolation(t *testing.T) {
	rows := []string{
		`{"name":"fixture","type":"direct","udp":true}`,
		`{"name":"fixture","type":"dns"}`,
		`{"name":"fixture","type":"reject"}`,
		`{"name":"fixture","type":"rematch","target-rematch-name":"media"}`,
		`{"name":"fixture","type":"gost-relay","server":"127.0.0.1","port":19090,"udp":true}`,
		`{"name":"fixture","type":"ssh","server":"127.0.0.1","port":22,"username":"fixture","password":"test-only"}`,
		`{"name":"fixture","type":"wireguard","server":"127.0.0.1","port":51820,"ip":"10.0.0.2","private-key":"AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE=","public-key":"AgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgI=","udp":true}`,
		`{"name":"fixture","type":"zerotier","network":"8056c2e21c000001"}`,
		`{"name":"fixture","type":"easytier","network-name":"fixture","network-secret":"test-only","peers":["tcp://127.0.0.1:11010"]}`,
		`{"name":"fixture","type":"shadowquic","server":"127.0.0.1","port":443,"password":"test-only"}`,
		`{"name":"fixture","type":"trusttunnel","server":"127.0.0.1","port":443,"username":"fixture","password":"test-only"}`,
		`{"name":"fixture","type":"sudoku","server":"127.0.0.1","port":443,"key":"0123456789abcdef0123456789abcdef"}`,
	}
	key, e := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if e != nil {
		t.Fatal(e)
	}
	private, e := x509.MarshalECPrivateKey(key)
	if e != nil {
		t.Fatal(e)
	}
	public, e := x509.MarshalPKIXPublicKey(&key.PublicKey)
	if e != nil {
		t.Fatal(e)
	}
	masque, _ := json.Marshal(map[string]any{"name": "fixture", "type": "masque", "server": "127.0.0.1", "port": 443,
		"ip": "10.0.0.2", "private-key": base64.StdEncoding.EncodeToString(private), "public-key": base64.StdEncoding.EncodeToString(public)})
	rows = append(rows, string(masque))
	if nativeTailscaleEnabled {
		rows = append(rows, `{"name":"fixture","type":"tailscale","auth-key":"test-only","ephemeral":true}`)
	}
	certificate := &x509.Certificate{SerialNumber: big.NewInt(1), NotBefore: time.Now().Add(-time.Minute), NotAfter: time.Now().Add(time.Hour), IsCA: true, BasicConstraintsValid: true, KeyUsage: x509.KeyUsageCertSign | x509.KeyUsageDigitalSignature}
	der, e := x509.CreateCertificate(rand.Reader, certificate, certificate, &key.PublicKey, key)
	if e != nil {
		t.Fatal(e)
	}
	openvpn, _ := json.Marshal(map[string]any{"name": "fixture", "type": "openvpn", "server": "127.0.0.1", "port": 1194,
		"ca": string(pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: der})), "username": "fixture", "password": "test-only"})
	rows = append(rows, string(openvpn))
	for _, raw := range rows {
		t.Run(func() string { var m map[string]any; _ = json.Unmarshal([]byte(raw), &m); return m["type"].(string) }(), func(t *testing.T) {
			var m map[string]any
			_ = json.Unmarshal([]byte(raw), &m)
			native, e := adapter.ParseProxy(m)
			if e != nil {
				t.Fatalf("controlled native fixture needs correction: %v", e)
			}
			_ = native.Close()
			p, e := parseNode(raw)
			if e != nil {
				t.Fatalf("native-valid outbound rejected: %v", e)
			}
			_ = p.Close()
		})
	}
	for _, kind := range []string{"tailscale", "easytier", "zerotier"} {
		m := map[string]any{"type": kind, "name": "fixture", "state-dir": "../../private-data"}
		if !prepareNativeNode(m) || !strings.HasPrefix(m["state-dir"].(string), "runtime-state") || strings.Contains(m["state-dir"].(string), "..") {
			t.Fatal("state path not application-owned")
		}
	}
	if safeNativePrivateKey("ssh", "../../private-data") {
		t.Fatal("SSH private key became a file capability")
	}
	if prepareNativeNode(map[string]any{"type": "zerotier", "planet": "../../private-data"}) {
		t.Fatal("planet path became a file capability")
	}
}
