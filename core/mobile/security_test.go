// SPDX-License-Identifier: GPL-3.0-only
package mobile

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync/atomic"
	"testing"

	"github.com/miekg/dns"
)

func TestNodeBoundaryRejectsAmbiguityCoercionAndHeaderInjection(t *testing.T) {
	base := `"server":"127.0.0.1","port":443`
	for _, config := range []string{
		`{"type":"anytls","type":"socks5",` + base + `,"password":"x"}`,
		`{"type":"anytls",` + base + `,"password":"x","TLS":false}`,
		`{"type":"anytls",` + base + `,"password":"x","udp":null}`,
		`{"type":"trojan",` + base + `,"password":"x","skip-cert-verify":"false"}`,
		`{"type":"trojan",` + base + `,"password":"x","ws-opts":[{"skip-cert-verify":true}]}`,
		`{"type":"trojan",` + base + `,"password":"x","ws-opts":{"headers":{"Host":"trusted\r\nX-Injected: yes"}}}`,
		`{"type":"trojan",` + base + `,"password":"x","ws-opts":{"headers":{"X\r\nInjected":"yes"}}}`,
		`{"type":"trojan",` + base + `,"password":"x","ws-opts":{"headers":{"Host":"one","host":"two"}}}`,
		`{"type":"trojan",` + base + `,"password":"x","ws-opts":{"headers":{"Host":{"secret":"value"}}}}`,
		`{"type":"trojan",` + base + `,"password":"x","ws-opts":{"max-early-data":2147483647}}`,
		`{"type":"socks5",` + base + `,"username":"` + strings.Repeat("a", 256) + `"}`,
		`{"type":"trojan",` + base + `,"password":"x","name":"trusted\u202eevil"}`,
	} {
		if got := decoded(t, ValidateNode(config)); got.Code != "config_invalid" {
			t.Fatalf("unsafe node accepted, result=%+v", got)
		}
	}
	valid := `{"type":"vmess",` + base + `,"uuid":"ca3ea70c-6845-4e3b-88d4-e4ac64ffbfa8","alterId":0,"cipher":"auto","network":"http","http-opts":{"method":"GET","path":["/"],"headers":{"Host":["example.test"]}}}`
	if decoded(t, ValidateNode(valid)).Code != "config_valid_not_connected" {
		t.Fatal("typed valid HTTP camouflage options rejected")
	}
}

func TestStrictJSONResourceBounds(t *testing.T) {
	for _, input := range []string{
		strings.Repeat(`{"nested":`, 20) + `false` + strings.Repeat(`}`, 20),
		`{"alpn":[` + strings.Repeat(`"x",`, 512) + `"x"]}`,
		`{"type":"anytls"} {"type":"socks5"}`,
		`{"skip-cert-verify":false,"skip-cert-verify":true}`,
		`{"headers":null}`,
	} {
		_, err := strictNodeObject(input)
		if err == nil {
			t.Fatal("unsafe object survived boundary")
		}
	}
}

func TestExplicitTransportPoliciesFollowPinnedCore(t *testing.T) {
	for index, config := range []string{
		`{"type":"tuic","server":"example.invalid","port":443,"uuid":"ca3ea70c-6845-4e3b-88d4-e4ac64ffbfa8","password":"x","disable-sni":true}`,
		`{"type":"tuic","server":"example.invalid","port":443,"uuid":"ca3ea70c-6845-4e3b-88d4-e4ac64ffbfa8","password":"x","reduce-rtt":true}`,
		`{"type":"socks5","server":"example.invalid","port":443,"username":"private","password":"secret"}`,
		`{"type":"socks5","server":"example.invalid","port":443,"tls":true,"udp":true}`,
		`{"type":"socks5","server":"127.0.0.1","port":443,"username":"private"}`,
		`{"type":"vless","server":"example.invalid","port":443,"uuid":"ca3ea70c-6845-4e3b-88d4-e4ac64ffbfa8"}`,
		`{"type":"ss","server":"example.invalid","port":443,"cipher":"none","password":"x"}`,
		`{"type":"ss","server":"example.invalid","port":443,"cipher":"aes-128-cfb","password":"x"}`,
		`{"type":"vmess","server":"example.invalid","port":443,"uuid":"ca3ea70c-6845-4e3b-88d4-e4ac64ffbfa8","cipher":"auto","alterId":64}`,
	} {
		if decoded(t, ValidateNode(config)).Code != "config_valid_not_connected" {
			t.Fatalf("explicit native transport policy rejected at case %d", index)
		}
	}
	if decoded(t, ValidateNode(`{"type":"socks5","server":"example.invalid","port":443,"tls":true}`)).Code != "config_valid_not_connected" {
		t.Fatal("verified SOCKS TLS was rejected")
	}
}

func TestProxyProbeBoundsHeadersAndDoesNotFollowOrReadBody(t *testing.T) {
	var followed atomic.Bool
	target := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/large":
			w.Header().Set("X-Padding", strings.Repeat("a", 64*1024))
			w.WriteHeader(204)
		case "/redirect":
			w.Header().Set("Location", "/private")
			w.WriteHeader(302)
		case "/private":
			followed.Store(true)
			w.WriteHeader(204)
		case "/body":
			w.Header().Set("Content-Length", "104857600")
			w.WriteHeader(200)
			w.(http.Flusher).Flush()
			<-r.Context().Done()
		}
	}))
	defer target.Close()
	config := asJSON(t, anyTLSFixture(t, target.Listener.Addr().String()))
	if reply := decoded(t, Probe(config, target.URL+"/large", "large-response", 3000)); reply.State != "failed" {
		t.Fatalf("oversized headers accepted: %+v", reply)
	}
	if reply := decoded(t, Probe(config, target.URL+"/redirect", "redirect-response", 3000)); reply.HTTPStatus != 302 || followed.Load() {
		t.Fatal("redirect followed or status lost")
	}
	if decoded(t, Probe(config, target.URL+"/body", "small-response", 3000)).State != "passed" {
		t.Fatal("normal request stopped working")
	}
}

func TestDNSResponseMustMatchQuestionAndOpcode(t *testing.T) {
	query := new(dns.Msg)
	query.SetQuestion("private.test.", dns.TypeA)
	answer := new(dns.Msg)
	answer.SetReply(query)
	if !matchingDNSReply(query, answer) {
		t.Fatal("valid reply rejected")
	}
	for _, change := range []func(*dns.Msg){
		func(m *dns.Msg) { m.Id++ }, func(m *dns.Msg) { m.Response = false },
		func(m *dns.Msg) { m.Opcode = dns.OpcodeUpdate }, func(m *dns.Msg) { m.Question = nil },
		func(m *dns.Msg) { m.Question[0].Name = "other.test." },
		func(m *dns.Msg) { m.Question[0].Qtype = dns.TypeAAAA },
		func(m *dns.Msg) { m.Question[0].Qclass = dns.ClassCHAOS },
	} {
		bad := answer.Copy()
		change(bad)
		if matchingDNSReply(query, bad) {
			t.Fatal("unrelated DNS response accepted")
		}
	}
}

func FuzzUntrustedProfile(f *testing.F) {
	f.Add("proxies: [{name: n, type: socks5, server: 127.0.0.1, port: 9}]\nx-servers: &servers [1.1.1.1]\ndns: {enable: true, nameserver: *servers, nameserver-policy: {z.invalid: 1.0.0.1, a.invalid: 1.1.1.1}}\nsub-rules: {local: [MATCH,DIRECT]}\n")
	for _, seed := range []string{
		`{"proxies":[{"name":"leaf","type":"trojan","server":"example.invalid","port":443,"password":"fixture","dialer-proxy":"auto"}],"proxy-groups":[{"name":"auto","type":"url-test","proxies":["DIRECT"],"url":"https:\/\/example.invalid\/"}]}`,
		`{"name":"\ud83d\ude80","type":"anytls","server":"example.invalid","port":443,"password":"fixture"}`,
		`{"type":"anytls","server":"example.invalid","port":443,"password":"private"}`,
		`{"type":"vless","server":"example.invalid","port":443,"uuid":"ca3ea70c-6845-4e3b-88d4-e4ac64ffbfa8","tls":true,"alterId":0,"cipher":"auto"}`,
		`{"proxies":[{"name":"exit","type":"hysteria2","server":"example.invalid","port":443,"password":"fixture","dialer-proxy":"pick"}],"proxy-groups":[{"name":"pick","type":"select","proxies":["DIRECT"]}]}`,
		`{"proxies":[{"name":"exit","type":"trojan","server":"example.invalid","port":443,"password":"fixture","dialer-proxy":"pick"}],"proxy-groups":[{"name":"pick","type":"select","proxies":["exit"]}]}`,
		"proxies: &nodes []\nother: *nodes", "a: [b, c]", "[[]]",
		"anytls://fixture@example.invalid:443?sni=example.invalid#HK",
		"YW55dGxzOi8vZml4dHVyZUBleGFtcGxlLmludmFsaWQ6NDQzI0hL",
		"ss://YWVzLTEyOC1nY206Zml4dHVyZQ@example.invalid:443#SS",
	} {
		f.Add(seed)
	}
	f.Fuzz(func(t *testing.T, input string) {
		if len(input) > 2*1024*1024 {
			return
		}
		var result importedProfile
		if json.Unmarshal([]byte(ImportProfile(input)), &result) != nil {
			t.Fatal("invalid result JSON")
		}
		if len(result.Nodes) > 500 {
			t.Fatal("node limit escaped")
		}
		for _, node := range result.Nodes {
			config := node.Config
			if node.RequiresProfile {
				config = profileTarget(result.Definition, node.Name)
			}
			if decoded(t, ValidateNode(config)).Code != "config_valid_not_connected" {
				t.Fatal("import produced an invalid node")
			}
		}
	})
}
