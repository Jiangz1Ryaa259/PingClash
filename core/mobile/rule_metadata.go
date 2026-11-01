// SPDX-License-Identifier: GPL-3.0-only
package mobile

import (
	"encoding/json"
	C "github.com/metacubex/mihomo/constant"
	"net/netip"
	"regexp"
	"strconv"
	"strings"
)

var processRuleKind = regexp.MustCompile(`(?i)(^|[(,])\s*(UID|PROCESS-[A-Z-]+)\s*,`)

// The Android owner lookup is a binder round trip. The complete native tunnel
// only evaluates rules in rule mode, so look up owners only when a top-level
// rule, sub-rule or classical provider entry can read the UID or package.
func rulesNeedProcess(o runtimeOptions) bool {
	if o.Mode != "rule" {
		return false
	}
	match := func(lines []string) bool {
		for _, line := range lines {
			if processRuleKind.MatchString(line) {
				return true
			}
		}
		return false
	}
	if match(o.Rules) {
		return true
	}
	var root map[string]json.RawMessage
	if o.Definition == "" || json.Unmarshal([]byte(o.Definition), &root) != nil {
		return false
	}
	var sections map[string][]string
	if json.Unmarshal(root["sub-rules"], &sections) == nil {
		for _, lines := range sections {
			if match(lines) {
				return true
			}
		}
	}
	var packed string
	if json.Unmarshal(root[providerRuleDataKey], &packed) == nil {
		if sets, e := decodeStoredRuleSets(packed); e == nil {
			for _, set := range sets {
				if set.Behavior == "classical" && match(set.Entries) {
					return true
				}
			}
		}
	}
	return false
}

// Per-connection, lazy and ephemeral. No process identity enters connection
// reports, exported diagnostics or persistent storage.
func connectionRuleHelper(m *C.Metadata) C.RuleMatchHelper {
	attempted := false
	return C.RuleMatchHelper{FindProcess: func() {
		if attempted {
			return
		}
		attempted = true
		if !m.SrcIP.IsValid() || !m.DstIP.IsValid() || m.SrcPort == 0 || m.DstPort == 0 {
			return
		}
		platformState.RLock()
		callback := platformState.callback
		platformState.RUnlock()
		if callback == nil {
			return
		}
		protocol := 6
		if m.NetWork == C.UDP {
			protocol = 17
		}
		result := callback.ConnectionOwner(protocol, m.SourceAddress(), netip.AddrPortFrom(m.DstIP, m.DstPort).String())
		if len(result) > 1024 {
			return
		}
		lines := strings.Split(result, "\n")
		if len(lines) > 2 {
			return
		}
		uid, e := strconv.ParseUint(lines[0], 10, 32)
		if e != nil {
			return
		}
		m.Uid = uint32(uid)
		if len(lines) == 2 && len(lines[1]) <= 255 && !hasControl(lines[1]) {
			m.Process = lines[1]
			m.ProcessPath = lines[1] // Android package identity, not a filesystem path.
		}
	}}
}
