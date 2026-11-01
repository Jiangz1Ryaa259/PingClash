// SPDX-License-Identifier: GPL-3.0-only
package mobile

import (
	"crypto/x509"
	"encoding/json"
	"encoding/pem"
	"github.com/metacubex/mihomo/adapter"
	C "github.com/metacubex/mihomo/constant"
	LC "github.com/metacubex/mihomo/listener/config"
	hyserver "github.com/metacubex/mihomo/listener/sing_hysteria2"
	"net"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
)

func TestDialerDirectEmptyAndFifteenGroupedNodesImport(t *testing.T) {
	for _, value := range []any{"", "DIRECT"} {
		nodes := []any{}
		for i := 0; i < 15; i++ {
			kind := "hysteria2"
			if i >= 9 {
				kind = "trojan"
			}
			nodes = append(nodes, map[string]any{"name": kind + string(rune('A'+i)), "type": kind, "server": "example.invalid", "port": 443, "password": "fixture", "dialer-proxy": value})
		}
		raw, _ := json.Marshal(map[string]any{"proxies": nodes})
		p := imported(t, string(raw))
		if len(p.Nodes) != 15 || p.Unsupported != 0 {
			t.Fatalf("15 nodes not preserved: %v", p.Rejections)
		}
		for _, n := range p.Nodes {
			if strings.Contains(n.Config, "dialer-proxy") {
				t.Fatal("unresolved no-op remained")
			}
		}
		upstream := map[string]any{"name": "fixture", "type": "trojan", "server": "example.invalid", "port": 443, "password": "fixture", "dialer-proxy": value}
		proxy, err := adapter.ParseProxy(upstream)
		if err != nil {
			t.Fatal("pinned parser disagrees with no-op option")
		}
		_ = proxy.Close()
	}
	raw := `{"proxies":[{"name":"exit","type":"trojan","server":"example.invalid","port":443,"password":"fixture","dialer-proxy":"pick"},{"name":"entry","type":"anytls","server":"example.invalid","port":443,"password":"fixture"}],"proxy-groups":[{"name":"pick","type":"select","proxies":["entry","DIRECT"]}]}`
	p := imported(t, raw)
	if len(p.Nodes) != 2 || len(p.Groups) != 1 || !strings.Contains(p.Nodes[0].Config, "dialer-chain") {
		t.Fatalf("group dependency not imported: %v", p.Rejections)
	}
	var def map[string]any
	_ = json.Unmarshal([]byte(p.Definition), &def)
	def["pingclash-selections"] = map[string]any{"pick": "DIRECT"}
	changed, _ := json.Marshal(def)
	q := imported(t, string(changed))
	if len(q.Nodes) != 2 || q.Groups[0].Selected != "DIRECT" || strings.Contains(q.Nodes[0].Config, "dialer-chain") {
		t.Fatal("group selection did not update route")
	}
}

func TestDialerDependenciesRejectCyclesMissingAndUnsupportedGroups(t *testing.T) {
	for _, members := range []string{`"exit"`, `"private-missing"`} {
		raw := `{"proxies":[{"name":"exit","type":"trojan","server":"example.invalid","port":443,"password":"fixture","dialer-proxy":"pick"}],"proxy-groups":[{"name":"pick","type":"select","proxies":[` + members + `]}]}`
		p := imported(t, raw)
		if len(p.Nodes) != 0 || p.Rejections["proxy_chain"] != 1 {
			t.Fatal("invalid chain silently changed route")
		}
	}
	for _, kind := range []string{"url-test", "fallback", "load-balance"} {
		p := imported(t, `{"proxies":[{"name":"exit","type":"trojan","server":"example.invalid","port":443,"password":"fixture","dialer-proxy":"pick"}],"proxy-groups":[{"name":"pick","type":"`+kind+`","proxies":["DIRECT"]}]}`)
		if len(p.Nodes) != 1 || !p.Nodes[0].RequiresProfile || p.GroupsUnsupported != 0 {
			t.Fatal("automatic group dependency was not preserved")
		}
	}
	for _, value := range []string{`null`, `true`, `3`, `[]`, `{}`} {
		if imported(t, `{"type":"trojan","server":"example.invalid","port":443,"password":"fixture","dialer-proxy":`+value+`}`).State == "passed" {
			t.Fatal("malformed reference accepted")
		}
	}
}

func TestGraphBoundariesNeverLoadFilesOrResolveAmbiguousNames(t *testing.T) {
	base := map[string]any{"name": "exit", "type": "trojan", "server": "example.invalid", "port": 443, "password": "private-secret", "dialer-proxy": "entry"}
	parent := map[string]any{"name": "entry", "type": "trojan", "server": "example.invalid", "port": 443, "password": "private-secret"}
	for _, mode := range []string{"duplicate", "file", "cycle", "reserved"} {
		p := map[string]any{}
		for k, v := range parent {
			p[k] = v
		}
		b := map[string]any{}
		for k, v := range base {
			b[k] = v
		}
		nodes := []any{b, p}
		switch mode {
		case "duplicate":
			nodes = append(nodes, p)
		case "file":
			p["certificate"] = "C:/private.pem"
		case "cycle":
			p["dialer-proxy"] = "exit"
		case "reserved":
			p["name"] = "DIRECT"
			b["dialer-proxy"] = "DIRECT"
		}
		raw, _ := json.Marshal(map[string]any{"proxies": nodes})
		profile := imported(t, string(raw))
		for _, n := range profile.Nodes {
			if n.Name == "exit" {
				t.Fatalf("unsafe graph accepted: %s", mode)
			}
		}
	}
	raw := `{"proxies":[{"name":"exit","type":"trojan","server":"example.invalid","port":443,"password":"private-secret","dialer-proxy":"private-missing"}]}`
	result := ImportProfile(raw)
	if strings.Contains(result, "private-secret") || strings.Contains(result, "private-missing") || strings.Contains(result, "definition") {
		t.Fatal("failed import echoed private graph")
	}
	large := []any{}
	for i := 0; i < 7; i++ {
		ref := "DIRECT"
		if i < 6 {
			ref = string(rune('a' + i + 1))
		}
		large = append(large, map[string]any{"name": string(rune('a' + i)), "type": "anytls", "server": "example.invalid", "port": 443, "password": strings.Repeat("x", 1024), "dialer-proxy": ref})
	}
	for i := 0; i < 400; i++ {
		large = append(large, map[string]any{"name": "root" + string(rune(1000+i)), "type": "anytls", "server": "example.invalid", "port": 443, "password": strings.Repeat("x", 1024), "dialer-proxy": "a"})
	}
	encoded, _ := json.Marshal(map[string]any{"proxies": large})
	bounded := imported(t, string(encoded))
	if bounded.Rejections["resource_limit"] == 0 {
		t.Fatal("expanded route budget was not enforced")
	}
}

func TestHysteriaDialerGroupReallyUsesAnyTLSUdpParentWithoutDirectFallback(t *testing.T) {
	target := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { w.WriteHeader(204) }))
	defer target.Close()
	certSource := httptest.NewTLSServer(nil)
	cert := certSource.TLS.Certificates[0]
	certSource.Close()
	key, err := x509.MarshalPKCS8PrivateKey(cert.PrivateKey)
	if err != nil {
		t.Fatal(err)
	}
	listener, err := hyserver.New(LC.Hysteria2Server{Enable: true, Listen: "127.0.0.1:0", Users: map[string]string{"test": "fixture"}, Certificate: string(pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: cert.Certificate[0]})), PrivateKey: string(pem.EncodeToMemory(&pem.Block{Type: "PRIVATE KEY", Bytes: key}))}, new(net.ListenConfig), loopbackTunnel{allowed: target.Listener.Addr().String()})
	if err != nil {
		t.Fatal(err)
	}
	defer listener.Close()
	inner := map[string]any{"name": "exit", "type": "hysteria2", "server": "127.0.0.1", "port": listener.AddrList()[0].(*net.UDPAddr).Port, "password": "fixture", "skip-cert-verify": true, "sni": "example.com", "dialer-proxy": "pick"}
	relay := newStableTestRelay(t, listener.AddrList()[0].String())
	parent := anyTLSFixture(t, listener.AddrList()[0].String(), relay)
	parent.Name = "entry"
	parent.UDP = true
	group := map[string]any{"name": "pick", "type": "select", "proxies": []any{"entry", "DIRECT"}}
	raw, _ := json.Marshal(map[string]any{"proxies": []any{inner, parent}, "proxy-groups": []any{group}})
	p := imported(t, string(raw))
	if len(p.Nodes) != 2 {
		t.Fatalf("chain rejected: %v", p.Rejections)
	}
	if got := decoded(t, Probe(p.Nodes[0].Config, target.URL, "hy-chain", 5000)); got.Code != "proxy_request_verified" {
		t.Fatalf("HY2 over AnyTLS UDP failed: %s", got.Code)
	}
	if relay.forwarded.Load() == 0 {
		t.Fatal("HY2 bypassed selected UDP parent")
	}
	parent.Password = "wrong-private-password"
	raw, _ = json.Marshal(map[string]any{"proxies": []any{inner, parent}, "proxy-groups": []any{group}})
	p = imported(t, string(raw))
	if len(p.Nodes) != 2 {
		t.Fatal("wrong credentials should remain syntactically valid")
	}
	if got := decoded(t, Probe(p.Nodes[0].Config, target.URL, "hy-chain-no-fallback", 1000)); got.Code == "proxy_request_verified" {
		t.Fatal("failed parent bypassed via direct connection")
	}
}

// One controlled UDP flow. Keep its source port stable throughout QUIC, and
// retain the first packet until shutdown so its write-back owner isn't pooled.
type stableTestRelay struct {
	loopbackTunnel
	conn      net.Conn
	mu        sync.Mutex
	first     C.UDPPacket
	done      chan struct{}
	forwarded atomic.Int64
}

func newStableTestRelay(t *testing.T, allowed string) *stableTestRelay {
	conn, err := net.Dial("udp", allowed)
	if err != nil {
		t.Fatal(err)
	}
	r := &stableTestRelay{loopbackTunnel: loopbackTunnel{allowed: allowed}, conn: conn, done: make(chan struct{})}
	go func() {
		defer close(r.done)
		b := make([]byte, 65536)
		for {
			n, err := conn.Read(b)
			if err != nil {
				return
			}
			r.mu.Lock()
			first := r.first
			r.mu.Unlock()
			if first != nil {
				_, _ = first.WriteBack(b[:n], conn.RemoteAddr())
			}
		}
	}()
	t.Cleanup(func() {
		_ = conn.Close()
		<-r.done
		r.mu.Lock()
		if r.first != nil {
			r.first.Drop()
		}
		r.mu.Unlock()
	})
	return r
}
func (r *stableTestRelay) HandleUDPPacket(packet C.UDPPacket, metadata *C.Metadata) {
	if metadata.RemoteAddress() != r.allowed {
		packet.Drop()
		return
	}
	r.mu.Lock()
	if r.first == nil {
		r.first = packet
	} else {
		defer packet.Drop()
	}
	r.mu.Unlock()
	r.forwarded.Add(1)
	_, _ = r.conn.Write(packet.Data())
}
