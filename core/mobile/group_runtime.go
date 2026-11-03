// SPDX-License-Identifier: GPL-3.0-only
package mobile

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/metacubex/mihomo/adapter"
	"github.com/metacubex/mihomo/adapter/outbound"
	"github.com/metacubex/mihomo/adapter/outboundgroup"
	"github.com/metacubex/mihomo/component/proxydialer"
	C "github.com/metacubex/mihomo/constant"
	P "github.com/metacubex/mihomo/constant/provider"
	"go.yaml.in/yaml/v3"
)

// Subscription groups are local graphs. No global proxy registry, provider URL,
// file, rule or controller is installed. Import and validation do no health checks.
func normalizeGroup(m map[string]any, candidates []any) (map[string]any, string) {
	var aliasesValid bool
	m, aliasesValid = normalizeGroupKeys(m)
	o := map[string]any{}
	for k, v := range m {
		o[k] = v
	}
	reason := ""
	if !aliasesValid {
		reason = "group_options"
	}
	kind, _ := m["type"].(string)
	if kind != "select" && kind != "url-test" && kind != "fallback" && kind != "load-balance" {
		reason = "group_type"
	}
	for k, v := range m {
		valid := true
		switch k {
		case "name", "type", "default-selected", "icon", "expected-status", "filter", "exclude-filter", "exclude-type", "empty-fallback":
			s, ok := v.(string)
			valid = ok && len(s) <= 4096 && !hasControl(s)
		case "url":
			s, ok := v.(string)
			valid = ok && len(s) <= 2048 && (s == "" || validDelayTarget(s))
			if !valid {
				reason = "group_url"
			}
		case "proxies":
			_, valid = v.([]any)
		case "use":
			a, ok := v.([]any)
			valid = ok && len(a) == 0
			if !valid {
				reason = "remote_provider"
			}
		case "hidden", "lazy", "disable-udp", "include-all", "include-all-proxies", "include-all-providers":
			_, valid = v.(bool)
		case "interval", "timeout", "max-failed-times", "tolerance":
			b, _ := json.Marshal(v)
			var n int
			valid = json.Unmarshal(b, &n) == nil && n >= 0 && n <= 31536000
		case "strategy":
			valid = v == "round-robin" || v == "consistent-hashing" || v == "sticky-sessions"
		case "hash-key":
			valid = v == "dst" || v == "src-dst"
		default:
			// The pinned native group decoder ignores unused extensions. This
			// includes group dialer-proxy/interface-name/routing-mark, which the
			// native parser explicitly disallows as group-level routing options.
			delete(o, k)
		}
		if !valid && reason == "" {
			reason = "group_options"
		}
	}
	// RE2 is preferred; native-compatible lookarounds use a timed fallback.
	filters, err := compileFilters(stringOption(m, "filter"))
	if err != nil {
		reason = "group_filter"
		filters, _ = compileFilters("")
	}
	excludes, err := compileFilters(stringOption(m, "exclude-filter"))
	if err != nil {
		reason = "group_filter"
		excludes, _ = compileFilters("")
	}
	members, _ := m["proxies"].([]any)
	members = append([]any{}, members...)
	if m["include-all"] == true || m["include-all-proxies"] == true {
		for _, raw := range candidates {
			if n, ok := raw.(map[string]any); ok {
				s, _ := n["name"].(string)
				if len(filters.patterns) == 0 || filters.match(s) {
					members = append(members, s)
				}
			}
		}
	}
	types := map[string]string{}
	for _, raw := range candidates {
		if n, ok := raw.(map[string]any); ok {
			s, _ := n["name"].(string)
			types[s], _ = n["type"].(string)
		}
	}
	excludedTypes, _ := m["exclude-type"].(string)
	unique := map[string]bool{}
	kept := []any{}
	for _, v := range members {
		s, ok := v.(string)
		if !ok {
			reason = "group_options"
			continue
		}
		if unique[s] || excludes.match(s) {
			continue
		}
		excluded := false
		for _, t := range strings.FieldsFunc(excludedTypes, func(r rune) bool { return r == '|' || r == ',' }) {
			if t != "" && strings.EqualFold(strings.TrimSpace(t), types[s]) {
				excluded = true
			}
		}
		if !excluded {
			kept = append(kept, s)
			unique[s] = true
		}
	}
	if len(kept) == 0 {
		fallback, _ := m["empty-fallback"].(string)
		if fallback == "" {
			fallback = "REJECT"
		}
		kept = append(kept, fallback)
	}
	o["proxies"] = kept
	for _, k := range []string{"use", "filter", "exclude-filter", "exclude-type", "include-all", "include-all-proxies", "include-all-providers"} {
		delete(o, k)
	}
	if kind != "select" {
		if s, _ := o["url"].(string); s == "" {
			o["url"] = "https://www.gstatic.com/generate_204"
		}
		b, _ := json.Marshal(o["timeout"])
		var timeout int
		_ = json.Unmarshal(b, &timeout)
		if timeout == 0 {
			o["timeout"] = 5000
		}
	}
	if filters.failed || excludes.failed {
		reason = "group_filter"
	}
	return o, reason
}

func normalizeGroupKeys(input map[string]any) (map[string]any, bool) {
	output := map[string]any{}
	for key, value := range input {
		key = strings.ToLower(strings.ReplaceAll(key, "_", "-"))
		if _, exists := output[key]; exists {
			return input, false
		}
		if key == "type" {
			if kind, ok := value.(string); ok {
				value = strings.ToLower(kind)
			}
		}
		output[key] = value
	}
	return output, true
}

func profileTarget(definition, target string) string {
	b, _ := json.Marshal(map[string]string{"type": "profile-target", "target": target, "definition": definition})
	return string(b)
}

func parseProfileTarget(raw string) (C.ProxyAdapter, error) {
	invalid := errors.New("proxy_chain")
	if len(raw) > maxPrivateBridgeBytes {
		return nil, invalid
	}
	d := json.NewDecoder(strings.NewReader(raw))
	token, e := d.Token()
	if e != nil || token != json.Delim('{') {
		return nil, invalid
	}
	fields := map[string]string{}
	for d.More() {
		k, e := d.Token()
		if e != nil {
			return nil, invalid
		}
		key, ok := k.(string)
		if !ok || key != "type" && key != "target" && key != "definition" {
			return nil, invalid
		}
		if _, ok := fields[key]; ok {
			return nil, invalid
		}
		var s string
		if d.Decode(&s) != nil {
			return nil, invalid
		}
		fields[key] = s
	}
	if _, e = d.Token(); e != nil {
		return nil, invalid
	}
	if d.Decode(new(any)) != io.EOF || len(fields) != 3 || fields["type"] != "profile-target" {
		return nil, invalid
	}
	text := fields["definition"]
	if len(fields["target"]) > 512 {
		return nil, invalid
	}
	g, err := parseLocalDefinition(text)
	if err != nil {
		return nil, err
	}
	return g.runtime(fields["target"])
}

func parseLocalDefinition(text string) (*importGraph, error) {
	invalid := errors.New("proxy_chain")
	if len(text) > maxPrivateProfileBytes {
		return nil, invalid
	}
	structural := 0
	for _, c := range text {
		if strings.ContainsRune("\n[]{}:,", c) {
			structural++
			if structural > maxYAMLStructure {
				return nil, invalid
			}
		}
	}
	dec := yaml.NewDecoder(strings.NewReader(normalizeJSONStringTokens(text)))
	var doc yaml.Node
	if dec.Decode(&doc) != nil || len(doc.Content) != 1 || dec.Decode(new(yaml.Node)) != io.EOF {
		return nil, invalid
	}
	count := 0
	if !safeYAML(&doc, 0, &count) {
		return nil, invalid
	}
	var root map[string]any
	if doc.Decode(&root) != nil {
		return nil, invalid
	}
	nodes, ok := root["proxies"].([]any)
	if !ok || len(nodes) > maxImportNodes {
		return nil, invalid
	}
	p := importedProfile{}
	g := newImportGraph(nodes, root, &p)
	return g, nil
}

type namedAdapter struct {
	C.ProxyAdapter
	name string
}

func (p *namedAdapter) Name() string { return p.name }

const (
	// Automatic groups are health-checked eagerly at start, unlike mihomo's lazy
	// default, so this stays below the import limits.
	maxRuntimeHealthGroups = 128
	maxRuntimeAutoRefs     = 50000
)

type groupHealth struct {
	provider P.ProxyProvider
	interval time.Duration
}
type runtimeGraph struct {
	C.ProxyAdapter
	children  []C.ProxyAdapter
	providers map[string]P.ProxyProvider
	health    []groupHealth
	ctx       context.Context
	cancel    context.CancelFunc
	start     sync.Once
	done      chan struct{}
	detached  atomic.Bool // dial through current picks without awaiting the initial pass
	close     sync.Once
}

func (p *runtimeGraph) activate(ctx context.Context) error {
	if e := p.ctx.Err(); e != nil {
		return e
	}
	p.start.Do(func() {
		go func() {
			defer close(p.done)
			for _, h := range p.health {
				if p.ctx.Err() != nil {
					return
				}
				h.provider.HealthCheck()
			}
			for _, h := range p.health {
				go func(h groupHealth) {
					ticker := time.NewTicker(h.interval)
					defer ticker.Stop()
					for {
						select {
						case <-p.ctx.Done():
							return
						case <-ticker.C:
							h.provider.HealthCheck()
						}
					}
				}(h)
			}
		}()
	})
	if p.detached.Load() {
		return nil
	}
	select {
	case <-ctx.Done():
		return ctx.Err()
	case <-p.ctx.Done():
		return p.ctx.Err()
	case <-p.done:
		return p.ctx.Err()
	}
}
func (p *runtimeGraph) DialContext(ctx context.Context, m *C.Metadata) (C.Conn, error) {
	if e := p.activate(ctx); e != nil {
		return nil, e
	}
	return p.ProxyAdapter.DialContext(ctx, m)
}
func (p *runtimeGraph) ListenPacketContext(ctx context.Context, m *C.Metadata) (C.PacketConn, error) {
	if e := p.activate(ctx); e != nil {
		return nil, e
	}
	return p.ProxyAdapter.ListenPacketContext(ctx, m)
}
func (p *runtimeGraph) Close() error {
	p.close.Do(func() {
		p.cancel()
		for _, pd := range p.providers {
			if c, ok := pd.(interface{ Close() error }); ok {
				_ = c.Close()
			}
		}
		for _, child := range p.children {
			_ = child.Close()
		}
	})
	return nil
}

func (g *importGraph) runtime(target string) (result C.ProxyAdapter, err error) {
	invalid := errors.New("proxy_chain")
	ctx, cancel := context.WithCancel(context.Background())
	r := &runtimeGraph{ctx: ctx, cancel: cancel, providers: map[string]P.ProxyProvider{}, done: make(chan struct{})}
	defer func() {
		if err != nil || result == nil {
			r.Close()
		}
	}()
	built := map[string]C.Proxy{}
	visiting := map[string]bool{}
	autoRefs := 0
	work := 0
	var build func(string, int, bool) (C.Proxy, error)
	build = func(name string, depth int, quarantine bool) (C.Proxy, error) {
		if depth > 8 {
			return nil, &groupFailure{"group_depth"}
		}
		if g.duplicate[name] {
			return nil, &groupFailure{"group_duplicate"}
		}
		if visiting[name] {
			return nil, &groupFailure{"group_cycle"}
		}
		badLeaf := g.nodeUnavailable(name)
		if badLeaf && !quarantine {
			return nil, &groupFailure{"group_node_unavailable"}
		}
		if p := built[name]; p != nil {
			return p, nil
		}
		work++
		if work > 10000 {
			return nil, errors.New("resource_limit")
		}
		visiting[name] = true
		defer delete(visiting, name)
		var p C.ProxyAdapter
		if badLeaf {
			// Preserve membership and order, but give a rejected leaf no network
			// capability. Health checks fail locally; no DIRECT substitute exists.
			p = outbound.NewReject()
		}
		switch name {
		case "DIRECT":
			p = outbound.NewDirect()
		case "REJECT", "COMPATIBLE":
			p = outbound.NewReject()
		}
		if p == nil {
			if group, ok := g.groups[name]; ok {
				if !group.Supported {
					return nil, &groupFailure{group.Reason}
				}
				o := map[string]any{}
				for k, v := range g.options[name] {
					o[k] = v
				}
				// Select constructs only the selected branch. Unselected groups retain
				// their configuration but cannot trigger probes or block an unrelated path.
				members := group.Proxies
				if group.Type == "select" {
					members = []string{group.Selected}
					o["proxies"] = []any{group.Selected}
					o["default-selected"] = group.Selected
				}
				for _, ref := range members {
					if _, e := build(ref, depth+1, group.Type != "select"); e != nil {
						return nil, e
					}
				}
				fallback, _ := o["empty-fallback"].(string)
				if fallback == "" {
					fallback = "COMPATIBLE"
				}
				if _, e := build(fallback, depth+1, false); e != nil {
					return nil, e
				}
				native, e := outboundgroup.ParseProxyGroup(o, built, r.providers, nil, nil)
				if e != nil {
					return nil, invalid
				}
				p = native
				if group.Type != "select" && group.Selected != "" {
					if selectable, ok := native.(outboundgroup.SelectAble); ok {
						selectable.ForceSet(group.Selected)
					}
				}
				if group.Type != "select" {
					// Subconverter-style templates nest auto, fallback, load-balance
					// and a dozen region groups. Bound work without rejecting them.
					autoRefs += len(members)
					if len(r.health) >= maxRuntimeHealthGroups || autoRefs > maxRuntimeAutoRefs {
						return nil, errors.New("resource_limit")
					}
					interval := 300
					data, _ := json.Marshal(o["interval"])
					_ = json.Unmarshal(data, &interval)
					if interval < 5 {
						interval = 5
					}
					r.health = append(r.health, groupHealth{r.providers[name], time.Duration(interval) * time.Second})
				}
			} else {
				n, ok := g.nodes[name]
				if !ok {
					return nil, &groupFailure{"group_missing"}
				}
				m := normalizedImportNode(n)
				var via C.Dialer
				if value, ok := m["dialer-proxy"]; ok {
					ref, ok := value.(string)
					if !ok {
						return nil, invalid
					}
					parent, e := build(ref, depth+1, false)
					if e != nil {
						return nil, e
					}
					via = proxydialer.New(parent, false)
				}
				delete(m, "dialer-proxy")
				if _, ok := m["dialer-chain"]; ok {
					return nil, invalid
				}
				b, _ := json.Marshal(m)
				var e error
				p, e = parseNodeWithDialer(string(b), via)
				if e != nil {
					return nil, e
				}
			}
		}
		r.children = append(r.children, p)
		wrapped := adapter.NewProxy(&namedAdapter{p, name})
		built[name] = wrapped
		return wrapped, nil
	}
	p, e := build(target, 0, false)
	if e != nil {
		return nil, e
	}
	r.ProxyAdapter = p
	return r, nil
}

func stringOption(m map[string]any, key string) string { v, _ := m[key].(string); return v }
