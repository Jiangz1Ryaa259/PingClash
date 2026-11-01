// SPDX-License-Identifier: GPL-3.0-only
package mobile

import (
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync/atomic"
	"testing"
	"time"
)

func Test101ValidAnd11MalformedLeavesKeepThreeOriginalGroups(t *testing.T) {
	nodes := []any{}
	members := []string{}
	for i := 0; i < 112; i++ {
		name := fmt.Sprintf("node-%03d", i)
		n := map[string]any{"name": name, "type": "anytls", "server": "example.invalid", "port": 443, "password": "availability-fixture-secret"}
		if i >= 101 {
			n["udp"] = "invalid-flag"
		}
		nodes = append(nodes, n)
		members = append(members, name)
	}
	groups := []any{
		map[string]any{"name": "main", "type": "select", "proxies": append([]string{"auto", "fallback", "DIRECT"}, members...)},
		map[string]any{"name": "auto", "type": "url-test", "proxies": members},
		map[string]any{"name": "fallback", "type": "fallback", "proxies": members},
	}
	raw, _ := json.Marshal(map[string]any{"proxies": nodes, "proxy-groups": groups})
	p := imported(t, string(raw))
	if p.State != "passed" || len(p.Nodes) != 101 || p.Unsupported != 11 || p.GroupsUnsupported != 0 || len(p.Groups) != 3 {
		t.Fatalf("mixed profile: nodes=%d rejected=%v groups=%d", len(p.Nodes), p.Rejections, p.GroupsUnsupported)
	}
	for i, g := range p.Groups {
		if !g.Supported || (i > 0 && (g.Unavailable != 11 || len(g.Proxies) != 112)) {
			t.Fatal("membership or quarantine metadata lost")
		}
		if decoded(t, ValidateNode(profileTarget(p.Definition, g.Name))).State != "passed" {
			t.Fatal("group unavailable")
		}
	}
	if p.FieldIssues["anytls.udp"] != 11 || p.Groups[0].Selected != "auto" {
		t.Fatal("diagnostic or provider default lost")
	}
	// A leaf can never borrow its pool's reject placeholder as a valid target.
	if decoded(t, ValidateNode(profileTarget(p.Definition, members[111]))).State == "passed" {
		t.Fatal("invalid leaf became usable")
	}
}

func TestQuarantinedMembersForwardHealthyBranchAndNeverFallbackDirect(t *testing.T) {
	var hits atomic.Int32
	target := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		hits.Add(1)
		// Upstream treats a zero-millisecond history as unknown. Model a small
		// real network delay so this fixture tests quarantine, not that sentinel.
		time.Sleep(2 * time.Millisecond)
		w.WriteHeader(204)
	}))
	defer target.Close()
	good := anyTLSFixture(t, target.Listener.Addr().String())
	var healthy map[string]any
	_ = json.Unmarshal([]byte(asJSON(t, good)), &healthy)
	healthy["name"] = "healthy"
	bad := map[string]any{"name": "rejected", "type": "anytls", "server": "example.invalid", "port": 443, "password": nil}
	for _, kind := range []string{"url-test", "fallback", "load-balance"} {
		t.Run(kind, func(t *testing.T) {
			group := map[string]any{"name": "pool", "type": kind, "proxies": []string{"rejected", "healthy"}, "url": target.URL, "timeout": 500}
			if kind == "load-balance" {
				group["strategy"] = "round-robin"
			}
			raw, _ := json.Marshal(map[string]any{"proxies": []any{bad, healthy}, "proxy-groups": []any{group}})
			p := imported(t, string(raw))
			if p.GroupsUnsupported != 0 || p.Groups[0].Unavailable != 1 {
				t.Fatal("mixed pool rejected")
			}
			if hits.Load() != 0 {
				t.Fatal("import caused network activity")
			}
			r, e := parseNode(profileTarget(p.Definition, "pool"))
			if e != nil {
				t.Fatal(e)
			}
			if e = probeAdapterForTest(r, target.URL); e != nil {
				r.Close()
				t.Fatal(e)
			}
			r.Close()
			if hits.Load() == 0 {
				t.Fatal("healthy route did not forward")
			}
			hits.Store(0)
			group["proxies"] = []string{"rejected"}
			raw, _ = json.Marshal(map[string]any{"proxies": []any{bad, healthy}, "proxy-groups": []any{group}})
			p = imported(t, string(raw))
			r, e = parseNode(profileTarget(p.Definition, "pool"))
			if e != nil {
				t.Fatal(e)
			}
			if e = probeAdapterForTest(r, target.URL); e == nil {
				r.Close()
				t.Fatal("rejected pool used a direct fallback")
			}
			r.Close()
			if hits.Load() != 0 {
				t.Fatal("rejected pool reached target")
			}
		})
	}
}

func TestAvailabilityDoesNotMaskMissingDuplicateOrCyclicRoutes(t *testing.T) {
	for _, mode := range []string{"missing", "duplicate", "cycle"} {
		leaf := map[string]any{"name": "leaf", "type": "anytls", "server": "example.invalid", "port": 443, "password": "fixture"}
		nodes := []any{leaf}
		members := []string{"leaf"}
		switch mode {
		case "missing":
			members = []string{"unknown-private-name"}
		case "duplicate":
			nodes = append(nodes, leaf)
		case "cycle":
			leaf["dialer-proxy"] = "pool"
		}
		raw, _ := json.Marshal(map[string]any{"proxies": nodes, "proxy-groups": []any{map[string]any{"name": "pool", "type": "url-test", "proxies": members}}})
		p := imported(t, string(raw))
		if len(p.Groups) > 0 && p.Groups[0].Supported {
			t.Fatalf("unsafe %s route quarantined as a harmless leaf", mode)
		}
		if decoded(t, ValidateNode(profileTarget(string(raw), "pool"))).State == "passed" {
			t.Fatal("unsafe runtime accepted")
		}
	}
}

func TestNullFieldDiagnosticsStayStaticAndStrict(t *testing.T) {
	raw := `{"name":"private-node","type":"anytls","server":"example.invalid","port":443,"password":"private-secret","udp":null,"private-secret-key":null}`
	issues := strings.Join(importFieldIssues(raw), ",")
	if !strings.Contains(issues, "anytls.udp") || !strings.Contains(issues, "anytls.other") || strings.Contains(issues, "private") {
		t.Fatal("null diagnostics omitted schema labels or leaked private data")
	}
	if decoded(t, ValidateNode(raw)).State == "passed" {
		t.Fatal("diagnostic decoding weakened strict validation")
	}
}
