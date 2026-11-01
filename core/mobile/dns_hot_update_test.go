// SPDX-License-Identifier: GPL-3.0-only
package mobile

import (
	"encoding/json"
	"testing"
)

func TestHotDNSReplacementClosesCachedSocketsWithoutRetirementDeadlock(t *testing.T) {
	first, _ := nativeDNSFixture(t, "192.0.2.10")
	second, _ := nativeDNSFixture(t, "192.0.2.20")
	s := nativeDNSSession(t, map[string]any{"dns": map[string]any{"enhanced-mode": "redir-host", "nameserver": []string{first}}})
	vpnState.Lock()
	vpnState.session = s
	vpnState.Unlock()
	defer StopVPN()
	if queryNativeDNS(t, s, "before-handoff.invalid").String() != "192.0.2.10" {
		t.Fatal("first DNS unavailable")
	}
	options := s.options
	var definition map[string]any
	_ = json.Unmarshal([]byte(options.Definition), &definition)
	definition["dns"].(map[string]any)["nameserver"] = []string{second}
	encoded, _ := json.Marshal(definition)
	options.Definition = string(encoded)
	raw, _ := json.Marshal(options)
	if decoded(t, UpdateVPNWithOptions(offlineSelected, "1.1.1.1", string(raw))).Code != "vpn_routing_updated" {
		t.Fatal("DNS handoff failed")
	}
	if queryNativeDNS(t, s, "after-handoff.invalid").String() != "192.0.2.20" {
		t.Fatal("cached client stayed on old DNS")
	}
	s.routingMu.Lock()
	retired := len(s.retiredRouting)
	s.routingMu.Unlock()
	if retired != 0 {
		t.Fatal("DNS socket retained an unused generation")
	}
}
