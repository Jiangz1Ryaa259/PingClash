// SPDX-License-Identifier: GPL-3.0-only
package mobile

import (
	"bufio"
	"encoding/json"
	"net"
	"net/netip"
	"regexp"
	"strconv"
	"strings"

	"github.com/metacubex/mihomo/component/mmdb"
	C "github.com/metacubex/mihomo/constant"
)

// One public echo endpoint answers which address the internet sees. Cloudflare's
// trace page returns only that address, its country and the answering data centre;
// the ASN and organisation come from the bundled DB-IP data, so no IP-intelligence
// service learns the user's address.
const exitTraceHost = "www.cloudflare.com"

const maxExitTraceBytes = 4096

var exitCountry = regexp.MustCompile(`^[A-Z][A-Z0-9]$`)

type exitInfo struct {
	State   string `json:"state"`
	Code    string `json:"code,omitempty"`
	IP      string `json:"ip,omitempty"`
	Country string `json:"country,omitempty"`
	Colo    string `json:"colo,omitempty"`
	ASN     int    `json:"asn,omitempty"`
	Org     string `json:"org,omitempty"`
	Policy  string `json:"policy,omitempty"`
	Node    string `json:"node,omitempty"`
	Type    string `json:"type,omitempty"`
}

// ExitDetails returns PRIVATE data: the address the internet sees right now. The
// app fetches Cloudflare's trace over the phone's current route (through the VPN
// when it runs, directly otherwise); this reads that reply, adds the ASN and
// organisation from the bundled data and, while connected, the policy and node the
// running rules choose for the trace host. Never log this value.
func ExitDetails(trace string) (encoded string) {
	defer func() {
		if recover() != nil {
			encoded = exitResult(exitInfo{State: "failed", Code: "internal_error"})
		}
	}()
	if len(trace) > maxExitTraceBytes {
		return exitResult(exitInfo{State: "failed", Code: "target_reply_invalid"})
	}
	info := parseExitTrace(trace)
	if info.IP == "" {
		return exitResult(exitInfo{State: "failed", Code: "target_reply_invalid"})
	}
	if ruleDataAvailable() {
		asn, org := mmdb.ASNInstance().LookupASN(net.ParseIP(info.IP))
		info.ASN, _ = strconv.Atoi(asn)
		info.Org = boundedLabel(org)
	}
	info.Policy, info.Node, info.Type = currentRoute(exitTraceHost, 443)
	info.State = "passed"
	return exitResult(info)
}

// currentRoute asks the running session which outbound a TCP connection to host
// takes now; empty without a session.
func currentRoute(host string, port uint16) (policy, node, kind string) {
	vpnState.Lock()
	session := vpnState.session
	if session == nil {
		vpnState.Unlock()
		return "", "", ""
	}
	routing, release := session.acquireRouting()
	vpnState.Unlock()
	defer release()
	proxy, target, err := routing.routeMetadata(ruleMetadata(host, netip.Addr{}, port, "tcp"))
	if err != nil || proxy == nil || target == "DIRECT" {
		return boundedLabel(target), "", ""
	}
	node, kind = exitNode(proxy)
	return boundedLabel(target), node, kind
}

// The trace is "key=value" lines; only the address, country and data centre are used.
func parseExitTrace(body string) exitInfo {
	info := exitInfo{}
	scanner := bufio.NewScanner(strings.NewReader(body))
	for scanner.Scan() {
		key, value, ok := strings.Cut(strings.TrimSpace(scanner.Text()), "=")
		if !ok {
			continue
		}
		switch key {
		case "ip":
			if address, err := netip.ParseAddr(value); err == nil {
				info.IP = address.Unmap().String()
			}
		case "loc":
			if exitCountry.MatchString(value) {
				info.Country = value
			}
		case "colo":
			if len(value) <= 8 && exitCountry.MatchString(value[:min(2, len(value))]) {
				info.Colo = value
			}
		}
	}
	return info
}

// exitNode follows a group to the node it currently routes through. Plain nodes
// are parsed under a placeholder name, so only a group's member names are real.
func exitNode(proxy C.ProxyAdapter) (string, string) {
	metadata := &C.Metadata{NetWork: C.TCP, Type: C.INNER}
	current := proxy
	name := ""
	for depth := 0; depth < 16; depth++ {
		next := current.Unwrap(metadata, false)
		if next == nil {
			break
		}
		name = next.Name()
		current = next
	}
	return boundedLabel(name), current.Type().String()
}

func boundedLabel(value string) string {
	value = strings.TrimSpace(value)
	if len(value) > 256 || hasControl(value) {
		return ""
	}
	return value
}

func exitResult(info exitInfo) string {
	b, _ := json.Marshal(info)
	return string(b)
}
