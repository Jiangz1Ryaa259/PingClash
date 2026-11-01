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
	"strings"
	"testing"
	"time"

	M "github.com/metacubex/sing/common/metadata"
)

// New connections must work in global mode, whether a session starts in it or switches
// into it, with a group or a node as the primary target.
func globalModeFreshConnection(t *testing.T, startMode string, profile string, target string) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		_, _ = io.WriteString(w, "fresh-ok\n")
	}))
	defer server.Close()
	p := imported(t, profile)
	config, _ := json.Marshal(map[string]string{"type": "profile-target", "target": target, "definition": p.Definition})
	options := defaultRuntimeOptions()
	options.Mode, options.Definition, options.Rules = startMode, p.Definition, p.Rules
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
	vpnState.session = session
	vpnState.Unlock()
	defer StopVPN()
	session.selection = newSelectionState(string(config), "127.0.0.1:53", string(raw))
	if startMode != "global" {
		options.Mode = "global"
		raw, _ = json.Marshal(options)
		if got := decoded(t, UpdateVPNWithOptions(string(config), "127.0.0.1:53", string(raw))); got.State != "passed" {
			t.Fatal("switch to global failed", got.Code)
		}
	}
	incoming, client := net.Pipe()
	defer client.Close()
	done := make(chan error, 1)
	go func() {
		done <- session.NewConnection(context.Background(), incoming, M.Metadata{
			Source:      M.Socksaddr{Addr: netip.MustParseAddr("192.0.2.2"), Port: 50600},
			Destination: M.ParseSocksaddr(server.Listener.Addr().String()),
		})
	}()
	_ = client.SetDeadline(time.Now().Add(8 * time.Second))
	_, _ = io.WriteString(client, "GET / HTTP/1.1\r\nHost: fixture.invalid\r\nConnection: close\r\n\r\n")
	body, _ := io.ReadAll(client)
	if !strings.Contains(string(body), "fresh-ok") {
		t.Fatalf("fresh connection in global failed: %q", body)
	}
}

const globalModeProfile = `proxies: [{name: A, type: direct}, {name: B, type: direct}]
proxy-groups:
  - {name: Main, type: select, proxies: [Auto, A, B]}
  - {name: Auto, type: url-test, proxies: [A, B], url: 'http://127.0.0.1:1/', interval: 300}
rules: ['MATCH,Main']`

func TestGlobalModeStartServesNewConnections(t *testing.T) {
	globalModeFreshConnection(t, "global", globalModeProfile, "Main")
}
func TestRuleToGlobalServesNewConnections(t *testing.T) {
	globalModeFreshConnection(t, "rule", globalModeProfile, "Main")
}
func TestRuleToGlobalWithNodeTargetServesNewConnections(t *testing.T) {
	globalModeFreshConnection(t, "rule", globalModeProfile, "A")
}
