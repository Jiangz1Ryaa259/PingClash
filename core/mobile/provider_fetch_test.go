// SPDX-License-Identifier: GPL-3.0-only
package mobile

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync/atomic"
	"testing"
	"time"
)

func TestProviderNamedDownloadUsesNativeAdapterAndKeepsTLS(t *testing.T) {
	var requests atomic.Int32
	target := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		requests.Add(1)
		if r.Header.Get("Authorization") != "CANARY-HEADER-1234" || r.Header.Get("User-Agent") != "Fixture-UA" {
			t.Error("provider negotiation headers lost")
		}
		_, _ = w.Write([]byte("payload: ['+.example.invalid']"))
	}))
	defer target.Close()
	native := anyTLSFixture(t, target.Listener.Addr().String())
	var node map[string]any
	_ = json.Unmarshal([]byte(asJSON(t, native)), &node)
	node["name"] = "Hop"
	root := map[string]any{"proxies": []any{node}, "proxy-groups": []any{map[string]any{"name": "Download", "type": "select", "proxies": []any{"Hop"}}}}
	proxy, e := providerBootstrap(root, nil, map[string]string{}, "Download")
	if e != nil {
		t.Fatal("native bootstrap unavailable")
	}
	defer proxy.Close()
	transport := providerHTTPTransport(proxy)
	defer transport.CloseIdleConnections()
	spec := remoteSpec{URL: target.URL, Kind: "rule", Headers: map[string]string{"Authorization": "CANARY-HEADER-1234"}}
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	if _, code := fetchProviderBody(ctx, transport, spec, "Fixture-UA"); code != "provider_tls" {
		t.Fatal("untrusted source certificate accepted or mislabeled")
	}
	if requests.Load() != 0 {
		t.Fatal("untrusted TLS reached provider HTTP")
	}
	// Test-only CA injection: production transport never receives fixture trust.
	trusted := providerHTTPTransport(proxy)
	trusted.TLSClientConfig = target.Client().Transport.(*http.Transport).TLSClientConfig.Clone()
	defer trusted.CloseIdleConnections()
	body, code := fetchProviderBody(ctx, trusted, spec, "Fixture-UA")
	if code != "" || !strings.Contains(string(body), "payload:") || requests.Load() != 1 {
		t.Fatal("named native adapter fetch failed")
	}
}

func TestProviderRedirectStripsSecretsAndLimitsResponse(t *testing.T) {
	var leaked atomic.Bool
	destination := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("Authorization") != "" || r.Header.Get("X-Private") != "" {
			leaked.Store(true)
		}
		_, _ = w.Write([]byte(strings.Repeat("x", 30)))
	}))
	defer destination.Close()
	origin := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { http.Redirect(w, r, destination.URL, http.StatusFound) }))
	defer origin.Close()
	transport := origin.Client().Transport.(*http.Transport).Clone()
	defer transport.CloseIdleConnections()
	spec := remoteSpec{URL: origin.URL, Kind: "proxy", SizeLimit: 10, Headers: map[string]string{"Authorization": "CANARY-SUB-TOKEN-1234", "X-Private": "CANARY-PASSWORD-1234"}}
	_, code := fetchProviderBody(context.Background(), transport, spec, "Fixture-UA")
	if code != "provider_declared_size_limit" || leaked.Load() {
		t.Fatal("redirect leaked credentials or size cap ignored")
	}
}

func TestProviderUnresolvedDownloadRouteNeverFallsBackToDirect(t *testing.T) {
	var hits atomic.Int32
	target := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { hits.Add(1) }))
	defer target.Close()
	text := `proxies: [{name: N, type: socks5, server: example.invalid, port: 1080}]
rule-providers:
 source: {type: http, behavior: domain, proxy: missing, url: '` + target.URL + `?token=CANARY-SUB-TOKEN-1234'}
`
	reply := FetchRemoteProvider(text, `{}`, "rule:source", "Fixture-UA", 1000)
	if !strings.Contains(reply, `"code":"provider_proxy_unavailable"`) || hits.Load() != 0 {
		t.Fatal("missing route was substituted or failure lost")
	}
	assertNoCanaries(t, reply)
}

func TestRenamedProviderChainsKeepTheirOwnHop(t *testing.T) {
	text := `proxy-providers:
 a: {type: http, url: https://example.invalid/a}
 b: {type: http, url: https://example.invalid/b}
proxy-groups:
 - {name: A, type: select, use: [a]}
 - {name: B, type: select, use: [b]}
`
	body := `proxies:
 - {name: Hop, type: socks5, server: example.invalid, port: 1080}
 - {name: Leaf, type: socks5, server: example.invalid, port: 1082, dialer-proxy: Hop}
`
	r := resolveFixture(t, text, map[string]string{"proxy:a": body, "proxy:b": strings.Replace(body, "port: 1080", "port: 1081", 1)})
	if r["state"] != "passed" {
		t.Fatal("provider chains rejected")
	}
	p := imported(t, r["content"].(string))
	if len(p.Nodes) != 4 || p.GroupsUnsupported != 0 {
		t.Fatal("provider-specific chain was deduplicated")
	}
	var config map[string]any
	if json.Unmarshal([]byte(p.Nodes[3].Config), &config) != nil {
		t.Fatal("chain config missing")
	}
	chain, ok := config["dialer-chain"].([]any)
	if !ok || len(chain) != 1 {
		t.Fatal("chain hop missing")
	}
	hop, ok := chain[0].(string)
	var decodedHop map[string]any
	if !ok || json.Unmarshal([]byte(hop), &decodedHop) != nil || decodedHop["port"] != float64(1081) {
		t.Fatal("renamed leaf used another provider's hop")
	}
}
