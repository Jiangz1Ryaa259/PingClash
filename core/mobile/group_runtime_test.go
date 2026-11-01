// SPDX-License-Identifier: GPL-3.0-only
package mobile

import (
	"context"
	"encoding/json"
	"fmt"
	C "github.com/metacubex/mihomo/constant"
	"net"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync/atomic"
	"testing"
	"time"
)

func probeAdapterForTest(p C.ProxyAdapter, target string) error {
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	tr := &http.Transport{DialContext: func(ctx context.Context, _, address string) (net.Conn, error) {
		m := &C.Metadata{NetWork: C.TCP, Type: C.INNER}
		if e := m.SetRemoteAddress(address); e != nil {
			return nil, e
		}
		return p.DialContext(ctx, m)
	}, DisableKeepAlives: true}
	defer tr.CloseIdleConnections()
	req, _ := http.NewRequestWithContext(ctx, "GET", target, nil)
	reply, e := (&http.Client{Transport: tr}).Do(req)
	if e != nil {
		return e
	}
	defer reply.Body.Close()
	if reply.StatusCode != 204 {
		return fmt.Errorf("status %d", reply.StatusCode)
	}
	return nil
}

type remapGroupFixture struct {
	loopbackTunnel
	requested string
}

func (t remapGroupFixture) HandleTCPConn(conn net.Conn, m *C.Metadata) {
	if m.RemoteAddress() != t.requested {
		_ = conn.Close()
		return
	}
	copy := *m
	_ = copy.SetRemoteAddress(t.allowed)
	t.loopbackTunnel.HandleTCPConn(conn, &copy)
}

func TestMainStrategyGroupsReallyForwardAndHealthChecksStayOfflineUntilUse(t *testing.T) {
	var first, second, health atomic.Int32
	fixture := func(counter *atomic.Int32) *httptest.Server {
		s := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			time.Sleep(2 * time.Millisecond) // Avoid upstream's zero-delay/unknown sentinel on loopback.
			if r.Method == "HEAD" {
				health.Add(1)
			} else {
				counter.Add(1)
			}
			w.WriteHeader(204)
		}))
		t.Cleanup(s.Close)
		return s
	}
	a, b := fixture(&first), fixture(&second)
	one, two := anyTLSFixture(t, a.Listener.Addr().String()), anyTLSFixture(t, a.Listener.Addr().String(), remapGroupFixture{loopbackTunnel{b.Listener.Addr().String()}, a.Listener.Addr().String()})
	nodes := []any{}
	for i, n := range []node{one, two, one} {
		var m map[string]any
		_ = json.Unmarshal([]byte(asJSON(t, n)), &m)
		m["name"] = []string{"first", "second", "broken"}[i]
		if i == 2 {
			m["password"] = "wrong-password"
		}
		nodes = append(nodes, m)
	}
	for _, kind := range []string{"select", "url-test", "fallback", "load-balance"} {
		t.Run(kind, func(t *testing.T) {
			first.Store(0)
			second.Store(0)
			health.Store(0)
			members := []any{"broken", "second"}
			if kind == "select" || kind == "load-balance" {
				members = []any{"first", "second"}
			}
			group := map[string]any{"name": "main", "type": kind, "proxies": members, "url": a.URL, "timeout": 500, "interval": 5}
			if kind == "load-balance" {
				group["strategy"] = "round-robin"
			}
			raw, _ := json.Marshal(map[string]any{"proxies": nodes, "proxy-groups": []any{group}})
			imported := imported(t, string(raw))
			if len(imported.Groups) != 1 || !imported.Groups[0].Supported {
				t.Fatal("valid group rejected")
			}
			config := profileTarget(imported.Definition, "main")
			if decoded(t, ValidateNode(config)).State != "passed" {
				t.Fatal("main group invalid")
			}
			if health.Load() != 0 || first.Load() != 0 || second.Load() != 0 {
				t.Fatal("import or validation caused network traffic")
			}
			proxy, e := parseNode(config)
			if e != nil {
				t.Fatal(e)
			}
			// Exercise multiple requests on one runtime, as VPN forwarding does.
			for i := 0; i < 4; i++ {
				if e := probeAdapterForTest(proxy, a.URL); e != nil {
					proxy.Close()
					t.Fatal(e)
				}
			}
			proxy.Close()
			switch kind {
			case "select":
				if first.Load() != 4 || second.Load() != 0 {
					t.Fatal("manual choice not respected")
				}
			case "url-test", "fallback":
				if second.Load() != 4 || first.Load() != 0 {
					t.Fatal("automatic group did not avoid failed member")
				}
			case "load-balance":
				if first.Load() == 0 || second.Load() == 0 {
					t.Fatal("round robin did not distribute requests")
				}
			}
		})
	}
}

func TestGroupOptionsSelectionsAndEnvelopeBounds(t *testing.T) {
	base := `{"proxies":[{"name":"HK-one","type":"trojan","server":"example.invalid","port":443,"password":"fixture"},{"name":"US-two","type":"trojan","server":"example.invalid","port":443,"password":"fixture"}],"proxy-groups":[{"name":"main","type":"select","include-all":true,"filter":"^HK","default-selected":"missing","use":[],"disable-udp":true}]}`
	p := imported(t, base)
	if len(p.Groups) != 1 || !p.Groups[0].Supported || p.Groups[0].Selected != "HK-one" || len(p.Groups[0].Proxies) != 1 {
		t.Fatal("common group options rejected")
	}
	r, e := parseNode(profileTarget(p.Definition, "main"))
	if e != nil {
		t.Fatal(e)
	}
	if r.SupportUDP() {
		t.Fatal("disable-udp ignored")
	}
	r.Close()
	for _, change := range []struct{ old, new, reason string }{{`"use":[]`, `"use":["remote"]`, "remote_provider"}, {`"^HK"`, `"(?=HK"`, "group_filter"}, {`"select"`, `"relay"`, "group_type"}} {
		q := imported(t, strings.Replace(base, change.old, change.new, 1))
		if q.Groups[0].Supported || q.Groups[0].Reason != change.reason {
			t.Fatal("unsupported option did not have an accurate reason")
		}
	}
	for _, raw := range []string{`{"type":"profile-target","type":"profile-target","target":"DIRECT","definition":"{}"}`, `{"type":"profile-target","target":"DIRECT","definition":"{}","extra":true}`, profileTarget(`{"proxies":[],"proxies":[]}`, "DIRECT"), profileTarget(p.Definition, "missing"), profileTarget(strings.Repeat("x", 2*1024*1024+1), "main")} {
		if decoded(t, ValidateNode(raw)).State == "passed" {
			t.Fatal("malformed or ambiguous envelope accepted")
		}
	}
	cycle := strings.Replace(base, `"include-all":true,"filter":"^HK"`, `"proxies":["main"]`, 1)
	q := imported(t, cycle)
	if q.Groups[0].Supported {
		t.Fatal("group cycle accepted")
	}
}

func TestStrategyGroupClosedRuntimeDoesNotDial(t *testing.T) {
	p := imported(t, `{"proxies":[{"name":"one","type":"trojan","server":"example.invalid","port":443,"password":"fixture"}],"proxy-groups":[{"name":"main","type":"select","proxies":["DIRECT"]}]}`)
	r, e := parseNode(profileTarget(p.Definition, "main"))
	if e != nil {
		t.Fatal(e)
	}
	r.Close()
	started := time.Now()
	var hits atomic.Int32
	target := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { hits.Add(1); w.WriteHeader(204) }))
	defer target.Close()
	if e = probeAdapterForTest(r, target.URL); e == nil || time.Since(started) > time.Second || hits.Load() != 0 {
		t.Fatal(fmt.Sprintf("closed runtime dialled: %v", e))
	}
}

func Test234NodesWithTwoGroups(t *testing.T) {
	nodes := []any{}
	for i := 0; i < 234; i++ {
		kind := "hysteria2"
		if i >= 228 {
			kind = "trojan"
		}
		n := map[string]any{"name": fmt.Sprintf("合成节点%d", i), "type": kind, "server": "example.invalid", "port": 443, "password": "private-group-fixture", "skip-cert-verify": true}
		if i >= 219 {
			if i%2 == 0 {
				n["dialer-proxy"] = "DIRECT"
			} else {
				n["dialer-proxy"] = "拨号选择"
			}
		}
		nodes = append(nodes, n)
	}
	raw, _ := json.Marshal(map[string]any{"proxies": nodes, "proxy-groups": []any{map[string]any{"name": "拨号选择", "type": "select", "proxies": []string{"DIRECT", "合成节点0"}}, map[string]any{"name": "自动测试", "type": "url-test", "proxies": []string{"合成节点0", "DIRECT"}, "url": "https://www.gstatic.com/generate_204", "tolerance": 50}}})
	p := imported(t, string(raw))
	if p.State != "passed" || len(p.Nodes) != 234 || p.GroupsUnsupported != 0 {
		t.Fatalf("large import: state=%s code=%s nodes=%d groups=%d rejected=%v", p.State, p.Code, len(p.Nodes), p.GroupsUnsupported, p.Rejections)
	}
	q := imported(t, strings.ReplaceAll(string(raw), "/", `\/`))
	if q.State != "passed" || len(q.Nodes) != 234 {
		t.Fatalf("Android escaped JSON: state=%s code=%s", q.State, q.Code)
	}
}

func TestJSONStringNormalizationPreservesCredentialsAndDuplicateRejection(t *testing.T) {
	raw := `{"name":"\ud83d\ude80","type":"anytls","server":"example.invalid","port":443,"password":"backslash\\\/quote\"end"}`
	p := imported(t, raw)
	if len(p.Nodes) != 1 || p.Nodes[0].Name != "🚀" {
		t.Fatal("valid JSON surrogate pair rejected")
	}
	var expected, actual map[string]any
	_ = json.Unmarshal([]byte(raw), &expected)
	_ = json.Unmarshal([]byte(p.Nodes[0].Config), &actual)
	if expected["password"] != actual["password"] {
		t.Fatal("string normalization changed credentials")
	}
	duplicate := strings.Replace(raw, `"port":443`, `"port":443,"\u0070ort":8443`, 1)
	if imported(t, duplicate).State == "passed" {
		t.Fatal("escaped duplicate key accepted")
	}
	group := imported(t, `{"proxies":[`+raw+`],"proxy-groups":[{"name":"main","type":"select","proxies":["\ud83d\ude80"]}]}`)
	if decoded(t, ValidateNode(profileTarget(strings.ReplaceAll(group.Definition, "/", `\/`), "main"))).State != "passed" {
		t.Fatal("runtime definition rejects valid escaped JSON")
	}
}
