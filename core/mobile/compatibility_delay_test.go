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

	"github.com/metacubex/mihomo/adapter"
)

func TestHY2OptionalBandwidthMatchesMihomoAndKeepsOriginalGroups(t *testing.T) {
	nodes := []any{}
	members := []string{}
	for i := 0; i < 112; i++ {
		n := map[string]any{"name": fmt.Sprintf("node-%d", i), "type": "anytls", "server": "example.invalid", "port": 443, "password": "compatibility-fixture"}
		if i >= 101 {
			n["type"] = "hysteria2"
			n["up"] = nil
			n["down"] = nil
			upstream, e := adapter.ParseProxy(n)
			if e != nil {
				t.Fatal("upstream null bandwidth rejected", e)
			}
			upstream.Close()
			raw, _ := json.Marshal(n)
			if decoded(t, ValidateNode(string(raw))).State != "passed" {
				t.Fatal("single node differs from upstream")
			}
		}
		nodes = append(nodes, n)
		members = append(members, n["name"].(string))
	}
	groups := []any{
		map[string]any{"name": "root", "type": "select", "proxies": append([]string{"auto", "fallback", "DIRECT"}, members...)},
		map[string]any{"name": "auto", "type": "url-test", "proxies": members, "url": "http://www.gstatic.com/generate_204", "interval": 300},
		map[string]any{"name": "fallback", "type": "fallback", "proxies": members, "url": "http://www.gstatic.com/generate_204", "interval": 300},
	}
	raw, _ := json.Marshal(map[string]any{"proxies": nodes, "proxy-groups": groups})
	p := imported(t, string(raw))
	if len(p.Nodes) != 112 || p.Unsupported != 0 || p.GroupsUnsupported != 0 || len(p.Groups) != 3 {
		t.Fatalf("compatibility regression: nodes=%d groups=%d issues=%v", len(p.Nodes), p.GroupsUnsupported, p.FieldIssues)
	}
	if p.Groups[0].Selected != "auto" || len(p.Groups[1].Proxies) != 112 {
		t.Fatal("provider hierarchy/default lost")
	}
	for _, g := range p.Groups {
		if !g.Supported || g.Unavailable != 0 {
			t.Fatal("valid group quarantined")
		}
	}
	for _, values := range []string{`"up":null,"up":2`, `"up":null,"password":null`, `"up":{}`, `"down":[]`, `"skip-cert-verify":null`, `"unknown":null`} {
		input := `{"type":"hysteria2","server":"example.invalid","port":443,"password":"fixture",` + values + `}`
		if decoded(t, ValidateNode(input)).State == "passed" {
			t.Fatal("null exception widened strict boundary")
		}
	}
}

func TestDelayHEADUnifiedTimingAndDiagnosticRemainDistinct(t *testing.T) {
	previous := adapter.UnifiedDelay.Load()
	defer adapter.UnifiedDelay.Store(previous)
	var hits atomic.Int32
	var wrongMethod atomic.Bool
	target := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodHead {
			wrongMethod.Store(true)
		}
		if hits.Add(1)%2 == 1 {
			time.Sleep(250 * time.Millisecond)
		} else {
			time.Sleep(15 * time.Millisecond)
		}
		w.WriteHeader(http.StatusNoContent)
	}))
	defer target.Close()
	config := asJSON(t, anyTLSFixture(t, target.Listener.Addr().String()))
	started := time.Now()
	result := decoded(t, ProbeDelay(config, target.URL, "unified-delay", 2000, true))
	if result.Code != "proxy_delay_measured" || hits.Load() != 2 || wrongMethod.Load() {
		t.Fatal("HEAD pair failed", result)
	}
	if time.Since(started).Milliseconds()-result.ElapsedMs < 200 {
		t.Fatal("cold setup included in unified delay")
	}
	first := decoded(t, ProbeDelay(config, target.URL, "first-delay", 2000, false))
	if first.State != "passed" || hits.Load() != 3 || first.ElapsedMs < 200 {
		t.Fatal("first request timing lost")
	}
	if !validDelayTarget("http://example.invalid/generate_204") || validTarget("http://example.invalid/generate_204") {
		t.Fatal("delay and diagnostic policy mixed")
	}
	for _, u := range []string{"http://user:password@example.invalid", "file:///secret", "https://example.invalid/#secret", "http://example.invalid:0"} {
		if validDelayTarget(u) {
			t.Fatal("invalid delay URL accepted")
		}
	}
	if strings.Contains(ProbeDelay(config, "https://user:secret@example.invalid", "invalid-delay", 1000, true), "secret") {
		t.Fatal("URL leaked")
	}
}

func TestDelayKeepsTargetTLSVerificationAndCancellation(t *testing.T) {
	previous := adapter.UnifiedDelay.Load()
	defer adapter.UnifiedDelay.Store(previous)
	target := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { w.WriteHeader(204) }))
	defer target.Close()
	config := asJSON(t, anyTLSFixture(t, target.Listener.Addr().String()))
	if decoded(t, ProbeDelay(config, target.URL, "delay-untrusted-target", 1000, true)).State == "passed" {
		t.Fatal("untrusted target accepted")
	}
	if !PrepareProbe("delay-precancel", 2000) {
		t.Fatal("reservation failed")
	}
	defer DiscardProbe("delay-precancel")
	Cancel("delay-precancel")
	if decoded(t, ProbeDelay(config, target.URL, "delay-precancel", 1000, true)).State != "cancelled" {
		t.Fatal("delay ignored cancellation")
	}
}
