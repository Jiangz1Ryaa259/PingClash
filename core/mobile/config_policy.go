// SPDX-License-Identifier: GPL-3.0-only
package mobile

// Imported networking semantics are retained; device-level listeners and
// controllers belong to the Android application, never a remote subscription.
func androidOwnedDirective(key string) bool {
	switch key {
	case "port", "socks-port", "mixed-port", "redir-port", "tproxy-port",
		"ss-config", "vmess-config", "external-controller", "external-controller-tls",
		"external-controller-unix", "external-controller-pipe", "external-controller-cors",
		"external-controller-routing-mark", "external-ui", "external-ui-url", "external-ui-name",
		"external-doh-server", "secret", "authentication", "skip-auth-prefixes", "allow-lan",
		"bind-address", "lan-allowed-ips", "lan-disallowed-ips", "listeners", "tunnels", "tun",
		"tuic-server", "iptables", "ntp", "profile", "tls", "geox-url", "geo-auto-update",
		"interface-name", "routing-mark", "log-level":
		return true
	}
	return false
}
