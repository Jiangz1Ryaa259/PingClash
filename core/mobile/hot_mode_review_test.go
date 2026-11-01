// SPDX-License-Identifier: GPL-3.0-only
package mobile

import (
	"context"
	"encoding/json"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"net/netip"
	"reflect"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/metacubex/mihomo/component/resolver"
	"github.com/metacubex/mihomo/tunnel"
	M "github.com/metacubex/sing/common/metadata"
)

func TestModeSwitchKeepsNativeGraphResolverAndOpenStream(t *testing.T) {
	release, entered := make(chan struct{}), make(chan struct{})
	finish := sync.OnceFunc(func() { close(release) })
	target := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		_, _ = io.WriteString(w, "before-mode\n")
		w.(http.Flusher).Flush()
		close(entered)
		<-release
		_, _ = io.WriteString(w, "after-mode\n")
	}))
	defer target.Close()
	defer finish()
	p := imported(t, `proxies: [{name: Allowed, type: direct}]
proxy-groups: [{name: Main, type: select, proxies: [Allowed]}]
rules: ['PROCESS-NAME,org.example.fixture,REJECT', 'MATCH,REJECT']`)
	config, _ := json.Marshal(map[string]string{"type": "profile-target", "target": "Main", "definition": p.Definition})
	options := defaultRuntimeOptions()
	options.Mode, options.Definition, options.Rules = "global", p.Definition, p.Rules
	raw, _ := json.Marshal(options)
	session, err := newSessionOwned(string(config), "127.0.0.1:53", string(raw), nil, true)
	if err != nil {
		t.Fatal(err)
	}
	if err = session.startFullNative(); err != nil {
		session.close()
		t.Fatal(err)
	}
	vpnState.Lock()
	if vpnState.session != nil {
		vpnState.Unlock()
		session.close()
		t.Fatal("unexpected VPN fixture")
	}
	vpnState.session = session
	vpnState.Unlock()
	defer StopVPN()
	session.selection = newSelectionState(string(config), "127.0.0.1:53", string(raw))
	if session.selection == nil || !session.processLookup {
		t.Fatal("global start lost retained rule capability")
	}
	epoch, graph, dns := session.currentRouting, session.native.cfg, resolver.DefaultResolver
	primary, rule := graph.Proxies["Main"], graph.Rules[0]
	started := session.started
	incoming, client := net.Pipe()
	defer client.Close()
	completed := make(chan error, 1)
	go func() {
		completed <- session.NewConnection(context.Background(), incoming, M.Metadata{
			Source:      M.Socksaddr{Addr: netip.MustParseAddr("192.0.2.2"), Port: 50507},
			Destination: M.ParseSocksaddr(target.Listener.Addr().String()),
		})
	}()
	_ = client.SetDeadline(time.Now().Add(8 * time.Second))
	if _, err = io.WriteString(client, "GET / HTTP/1.1\r\nHost: fixture.invalid\r\nConnection: close\r\n\r\n"); err != nil {
		t.Fatal(err)
	}
	select {
	case <-entered:
	case <-time.After(3 * time.Second):
		t.Fatal("native stream not established")
	}
	for _, mode := range []string{"direct", "rule", "global", "rule"} {
		options.Mode = mode
		raw, _ = json.Marshal(options)
		if got := decoded(t, UpdateVPNWithOptions(string(config), "127.0.0.1:53", string(raw))); got.State != "passed" {
			t.Fatal("mode update failed", got.Code)
		}
		if session.currentRouting != epoch || session.native.cfg != graph || graph.Proxies["Main"] != primary || graph.Rules[0] != rule || !reflect.DeepEqual(dns, resolver.DefaultResolver) || session.started != started || len(session.retiredRouting) != 0 {
			t.Fatal("mode change rebuilt the graph, DNS or logical session")
		}
		if session.routingMode() != mode || tunnel.Mode() != tunnel.ModeMapping[mode] {
			t.Fatal("published mode differs from native mode")
		}
		var status struct{ Mode string }
		if json.Unmarshal([]byte(VPNStatus()), &status) != nil || status.Mode != mode {
			t.Fatal("status mode is stale")
		}
	}
	for _, mode := range []any{nil, "invalid"} {
		var request map[string]any
		_ = json.Unmarshal(raw, &request)
		request["mode"] = mode
		invalid, _ := json.Marshal(request)
		if decoded(t, UpdateVPNWithOptions(string(config), "127.0.0.1:53", string(invalid))).State == "passed" || session.currentRouting != epoch || session.routingMode() != "rule" {
			t.Fatal("invalid mode changed the live configuration")
		}
	}
	finish()
	body, err := io.ReadAll(client)
	if err != nil || !strings.Contains(string(body), "before-mode") || !strings.Contains(string(body), "after-mode") {
		t.Fatal("hot mode switch broke existing stream", err)
	}
	select {
	case err = <-completed:
		if err != nil {
			t.Fatal(err)
		}
	case <-time.After(time.Second):
		t.Fatal("native stream leaked")
	}
	options.Rules = []string{"MATCH,DIRECT"}
	raw, _ = json.Marshal(options)
	if decoded(t, UpdateVPNWithOptions(string(config), "127.0.0.1:53", string(raw))).State != "passed" || session.currentRouting == epoch {
		t.Fatal("changed rules incorrectly bypassed replacement validation")
	}
}

func TestInactiveInvalidRulesRemainStagedButCannotHotEnterRuleMode(t *testing.T) {
	p := imported(t, `proxies: [{name: Allowed, type: direct}]
proxy-groups: [{name: Main, type: select, proxies: [Allowed]}]`)
	config, _ := json.Marshal(map[string]string{"type": "profile-target", "target": "Main", "definition": p.Definition})
	options := defaultRuntimeOptions()
	options.Mode, options.Definition, options.Rules = "global", p.Definition, []string{"DOMAIN-SUFFIX,,REJECT"}
	raw, _ := json.Marshal(options)
	session, err := newSessionOwned(string(config), "127.0.0.1:53", string(raw), nil, true)
	if err != nil {
		t.Fatal("inactive rules blocked global mode", err)
	}
	if err = session.startFullNative(); err != nil {
		session.close()
		t.Fatal(err)
	}
	vpnState.Lock()
	if vpnState.session != nil {
		vpnState.Unlock()
		session.close()
		t.Fatal("unexpected VPN fixture")
	}
	vpnState.session = session
	vpnState.Unlock()
	defer StopVPN()
	session.selection = newSelectionState(string(config), "127.0.0.1:53", string(raw))
	epoch := session.currentRouting
	if session.rulesValidated {
		t.Fatal("invalid staged rules marked ready")
	}
	for _, mode := range []string{"direct", "global"} {
		options.Mode = mode
		raw, _ = json.Marshal(options)
		if decoded(t, UpdateVPNWithOptions(string(config), "127.0.0.1:53", string(raw))).State != "passed" || session.currentRouting != epoch {
			t.Fatal("inactive rules blocked a non-rule update")
		}
	}
	options.Mode = "rule"
	raw, _ = json.Marshal(options)
	if decoded(t, UpdateVPNWithOptions(string(config), "127.0.0.1:53", string(raw))).State == "passed" || session.routingMode() != "global" || session.currentRouting != epoch {
		t.Fatal("unvalidated rules became active or replaced working graph")
	}
}
