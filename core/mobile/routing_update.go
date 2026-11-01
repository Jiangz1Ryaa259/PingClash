// SPDX-License-Identifier: GPL-3.0-only
package mobile

import (
	"crypto/sha256"
	"encoding/json"
	"errors"
	"sync"
	"time"

	"github.com/metacubex/mihomo/adapter/outboundgroup"
	"github.com/metacubex/mihomo/tunnel"
)

// An outbound generation is retired only after its last TCP/UDP/DNS user
// releases it. Reconfiguration never tears down the TUN or existing flows.
type routingEpoch struct {
	session *vpnSession
	users   int
	retired bool
	once    sync.Once
}

func (e *routingEpoch) close() {
	e.once.Do(func() {
		e.session.closeRouting()
		if e.retired && e.session.native != nil {
			e.session.root().drainNative(e.session.native)
		}
	})
}

// A retired native generation keeps its adapters until UDP associations and
// multiplexed sessions that are not counted as users have idled out.
func (s *vpnSession) nativeStopGrace() time.Duration {
	return time.Duration(max(s.options.UDPTimeout, 60))*time.Second + 30*time.Second
}

// drainNative stops a retired generation after the grace period, or at once
// when the VPN is already stopping. Session close stops whatever remains.
func (s *vpnSession) drainNative(n *fullNativeRuntime) {
	s.routingMu.Lock()
	if s.ctx.Err() != nil || s.drainingNative == nil {
		s.routingMu.Unlock()
		n.stop()
		return
	}
	if _, ok := s.drainingNative[n]; ok {
		s.routingMu.Unlock()
		return
	}
	s.drainingNative[n] = time.AfterFunc(s.nativeStopGrace(), func() {
		s.routingMu.Lock()
		_, pending := s.drainingNative[n]
		if pending && !n.beginIdleStop() {
			s.drainingNative[n].Reset(s.nativeStopGrace())
			s.routingMu.Unlock()
			return
		}
		delete(s.drainingNative, n)
		s.routingMu.Unlock()
		if pending {
			n.stop()
		}
	})
	s.routingMu.Unlock()
}

// stopDrainingNative stops every retired generation still in its grace period.
func (s *vpnSession) stopDrainingNative() {
	s.routingMu.Lock()
	pending := s.drainingNative
	s.drainingNative = nil
	s.routingMu.Unlock()
	for n, timer := range pending {
		timer.Stop()
		n.stop()
	}
}
func (s *vpnSession) root() *vpnSession {
	if s.owner != nil {
		return s.owner
	}
	return s
}

func (s *vpnSession) routingMode() string {
	if mode := s.liveMode.Load(); mode != nil {
		return *mode
	}
	return s.options.Mode
}
func (s *vpnSession) acquireRouting() (*vpnSession, func()) {
	root := s.root()
	root.routingMu.Lock()
	e := root.currentRouting
	if e == nil {
		root.routingMu.Unlock()
		return s, func() {}
	}
	e.users++
	root.routingMu.Unlock()
	return e.session, func() {
		root.routingMu.Lock()
		e.users--
		closeNow := e.retired && e.users == 0
		if closeNow {
			delete(root.retiredRouting, e)
		}
		root.routingMu.Unlock()
		if closeNow {
			e.close()
		}
	}
}
func (s *vpnSession) closeRouting() {
	if s.owner != nil {
		s.cancel()
	}
	s.dohMu.Lock()
	if s.dohTransport != nil {
		s.dohTransport.CloseIdleConnections()
	}
	s.dohMu.Unlock()
	if s.native != nil {
		s.native.retire()
	} else {
		_ = s.proxy.Close()
	}
	if s.direct != nil {
		_ = s.direct.Close()
	}
	for _, p := range s.targets {
		_ = p.Close()
	}
	if s.nativeDNS != nil {
		s.nativeDNS.release()
	}
}
func dnsConfigurationKey(s *vpnSession) [32]byte {
	var root map[string]json.RawMessage
	_ = json.Unmarshal([]byte(s.options.Definition), &root)
	b, _ := json.Marshal(struct {
		DNS, Hosts, Rules, Ordered json.RawMessage
		IPv6, Imported             bool
		Address, Mode, HTTPS       string
	}{
		root["dns"], root["hosts"], root[providerRuleDataKey], root[nativeDNSDocumentKey], s.options.IPv6, s.options.UseSubscriptionDNS, s.dns.String(), s.options.DNSMode, s.options.DNSHTTPS})
	return sha256.Sum256(b)
}

// UpdateVPNWithOptions validates an entire replacement before publishing it.
// The old generation and its sockets remain usable on any validation failure.
func UpdateVPNWithOptions(configJSON, dnsAddress, options string) (result string) {
	result = encode("", "failed", "vpn_update_failed", 0, 0)
	defer func() {
		if recover() != nil {
			result = encode("", "failed", "vpn_update_failed", 0, 0)
		}
	}()
	vpnState.Lock()
	root := vpnState.session
	vpnState.Unlock()
	if root == nil {
		return encode("", "not_attempted", "vpn_not_running", 0, 0)
	}
	selection := newSelectionState(configJSON, dnsAddress, options)
	if root.hotSelect(selection) {
		return encode("", "passed", "vpn_routing_updated", 0, 0)
	}
	candidate, err := newSessionOwned(configJSON, dnsAddress, options, root, root.liveExecution)
	if err != nil {
		return encode("", "failed", vpnStartupCode(err), 0, 0)
	}
	// The destination generation decides the execution path. A bare-node
	// profile can also run in the native executor after a full-profile session.
	if candidate.options.Definition == "" && root.nativeRestore != nil {
		var node map[string]any
		if json.Unmarshal([]byte(configJSON), &node) != nil {
			candidate.close()
			return result
		}
		node["name"] = "selected"
		document, _ := json.Marshal(map[string]any{"proxies": []any{node}})
		candidate.options.Definition = string(document)
		candidate.primaryTarget = "selected"
	}
	if candidate.options.Definition != "" && (root.liveExecution || root.nativeRestore != nil) {
		candidate.native, err = prepareFullNative(candidate)
		if err != nil {
			candidate.close()
			return encode("", "failed", vpnStartupCode(err), 0, 0)
		}
		candidate.selection = selection
	}
	committed := false
	defer func() {
		if !committed {
			candidate.close()
		}
	}()
	vpnState.Lock()
	defer vpnState.Unlock()
	if vpnState.session != root || root.ctx.Err() != nil {
		return result
	}
	root.routingMu.Lock()
	previous := root.currentRouting
	if previous == nil {
		root.routingMu.Unlock()
		return result
	}
	// Only routing changes can be hot applied; Android-owned TUN settings require
	// an explicit reconnect instead of silently applying half a configuration.
	if root.options.MTU != candidate.options.MTU || root.options.IPv6 != candidate.options.IPv6 ||
		root.options.UDP != candidate.options.UDP || root.options.MaxConnections != candidate.options.MaxConnections ||
		root.options.UDPTimeout != candidate.options.UDPTimeout || root.options.ConnectTimeout != candidate.options.ConnectTimeout ||
		root.options.InspectConnections != candidate.options.InspectConnections {
		root.routingMu.Unlock()
		return encode("", "failed", "vpn_reconnect_required", 0, 0)
	}
	if previous.session.nativeDNS != nil && dnsConfigurationKey(previous.session) == dnsConfigurationKey(candidate) {
		if candidate.nativeDNS != nil {
			candidate.nativeDNS.release()
		}
		candidate.nativeDNS = previous.session.nativeDNS
		candidate.nativeDNS.references.Add(1)
	}
	// Apply outside the routing lock: resetting native DNS connections may run
	// callbacks that need to borrow the active generation.
	root.routingMu.Unlock()
	if candidate.native != nil {
		if root.nativeRestore == nil {
			root.nativeRestore = saveNativeGlobals()
		}
		if err := candidate.native.apply(); err != nil {
			return encode("", "failed", "vpn_update_failed", 0, 0)
		}
		nativeExecutorLive.Store(true)
		candidate.native.activatePrimary(candidate)
	}
	root.routingMu.Lock()
	root.currentRouting = &routingEpoch{session: candidate}
	previous.retired = true
	closePrevious := previous.users == 0
	if !closePrevious {
		root.retiredRouting[previous] = struct{}{}
	}
	if candidate.nativeDNS != nil {
		activeNativeDNS.Store(candidate.nativeDNS)
	} else if previous.session.nativeDNS != nil {
		activeNativeDNS.CompareAndSwap(previous.session.nativeDNS, nil)
	}
	root.routingMu.Unlock()
	committed = true
	if previous.session.nativeDNS != nil {
		previous.session.nativeDNS.resolvers.ResetConnection()
	}
	if closePrevious {
		previous.close()
	}
	return encode("", "passed", "vpn_routing_updated", 0, 0)
}

var errRoutingUnavailable = errors.New("vpn_routing_unavailable")

// Picks and mode may change in place only when the remaining configuration,
// including the complete rule table and DNS options, is unchanged.
type selectionState struct {
	key   [32]byte
	picks map[string]string
	mode  string
}

func newSelectionState(configJSON, dnsAddress, options string) *selectionState {
	strip := func(document string) ([]byte, map[string]string, bool) {
		var root map[string]json.RawMessage
		if json.Unmarshal([]byte(document), &root) != nil || root == nil {
			return nil, nil, false
		}
		picks := map[string]string{}
		if raw, present := root["pingclash-selections"]; present {
			if json.Unmarshal(raw, &picks) != nil {
				return nil, nil, false
			}
			delete(root, "pingclash-selections")
		}
		encoded, e := json.Marshal(root)
		return encoded, picks, e == nil
	}
	var config, request map[string]json.RawMessage
	var configDefinition, optionDefinition string
	if json.Unmarshal([]byte(configJSON), &config) != nil || json.Unmarshal(config["definition"], &configDefinition) != nil ||
		json.Unmarshal([]byte(options), &request) != nil || json.Unmarshal(request["definition"], &optionDefinition) != nil {
		return nil
	}
	head, picks, ok := strip(configDefinition)
	if !ok {
		return nil
	}
	body, optionPicks, ok := strip(optionDefinition)
	if !ok || len(optionPicks) != len(picks) {
		return nil
	}
	for name, pick := range picks {
		if other, present := optionPicks[name]; !present || other != pick {
			return nil
		}
	}
	delete(config, "definition")
	delete(request, "definition")
	mode := defaultRuntimeOptions().Mode
	if raw, present := request["mode"]; present {
		var selected *string
		if json.Unmarshal(raw, &selected) != nil || selected == nil {
			return nil
		}
		mode = *selected
	}
	if _, valid := tunnel.ModeMapping[mode]; !valid {
		return nil
	}
	delete(request, "mode")
	configRest, e1 := json.Marshal(config)
	requestRest, e2 := json.Marshal(request)
	if e1 != nil || e2 != nil {
		return nil
	}
	h := sha256.New()
	for _, part := range [][]byte{configRest, head, requestRest, body, []byte(dnsAddress)} {
		var size [8]byte
		for i, n := 0, len(part); i < 8; i, n = i+1, n>>8 {
			size[i] = byte(n)
		}
		h.Write(size[:])
		h.Write(part)
	}
	state := &selectionState{picks: picks, mode: mode}
	h.Sum(state.key[:0])
	return state
}

func (root *vpnSession) hotSelect(next *selectionState) bool {
	if next == nil {
		return false
	}
	mode, valid := tunnel.ModeMapping[next.mode]
	if !valid {
		return false
	}
	vpnState.Lock()
	defer vpnState.Unlock()
	if vpnState.session != root || root.ctx.Err() != nil {
		return false
	}
	// Match the executor's VPN -> parser -> routing lock order. Parsing an
	// unrelated candidate must not temporarily overwrite a just-selected mode.
	nativeConfigParse.Lock()
	defer nativeConfigParse.Unlock()
	root.routingMu.Lock()
	defer root.routingMu.Unlock()
	if root.currentRouting == nil {
		return false
	}
	current := root.currentRouting.session
	if current.native == nil || current.selection == nil || current.selection.key != next.key {
		return false
	}
	if next.mode == "rule" && !current.rulesValidated {
		return false // Revalidate staged rules before permitting them to run.
	}
	type change struct {
		group outboundgroup.SelectAble
		pick  string
	}
	changes := []change{}
	for name := range current.selection.picks {
		if _, kept := next.picks[name]; !kept {
			return false // A cleared pick needs the constructor default.
		}
	}
	for name, pick := range next.picks {
		if current.selection.picks[name] == pick {
			continue
		}
		p := current.native.cfg.Proxies[name]
		if p == nil {
			return false
		}
		group, ok := p.Adapter().(outboundgroup.SelectAble)
		if !ok {
			return false
		}
		changes = append(changes, change{group, pick})
	}
	for _, c := range changes {
		c.group.ForceSet(c.pick)
	}
	tunnel.SetMode(mode)
	selectedMode := next.mode
	current.liveMode.Store(&selectedMode)
	current.selection = next
	return true
}
