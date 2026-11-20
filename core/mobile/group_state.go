package mobile

import (
	"encoding/json"

	"github.com/metacubex/mihomo/adapter/outboundgroup"
	C "github.com/metacubex/mihomo/constant"
)

// groupStateView is what the running core knows about the profile's groups: which member each
// group uses now (an automatic group's own pick) and each proxy's last health-check result in
// milliseconds, 0 for a failed check. Names only; nothing is persisted or logged.
type groupStateView struct {
	Now    map[string]string `json:"now"`
	Delays map[string]int    `json:"delays"`
}

// VPNGroupState reports the running session's group picks and latest delays as JSON, or empty
// maps when no VPN is running.
func VPNGroupState() (result string) {
	view := groupStateView{Now: map[string]string{}, Delays: map[string]int{}}
	defer func() {
		if recover() != nil {
			view = groupStateView{Now: map[string]string{}, Delays: map[string]int{}}
		}
		b, _ := json.Marshal(view)
		result = string(b)
	}()
	vpnState.Lock()
	root := vpnState.session
	vpnState.Unlock()
	if root == nil {
		return
	}
	root.routingMu.Lock()
	defer root.routingMu.Unlock()
	if root.currentRouting == nil || root.currentRouting.session.native == nil {
		return
	}
	collectGroupState(root.currentRouting.session.native.cfg.Proxies, &view)
	return
}

func collectGroupState(proxies map[string]C.Proxy, view *groupStateView) {
	for name, proxy := range proxies {
		if group, ok := proxy.Adapter().(outboundgroup.ProxyGroup); ok {
			if now := group.Now(); now != "" {
				view.Now[name] = now
			}
			continue
		}
		if history := proxy.DelayHistory(); len(history) > 0 {
			view.Delays[name] = int(history[len(history)-1].Delay)
		}
	}
}
