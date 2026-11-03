// SPDX-License-Identifier: GPL-3.0-only
package mobile

// This bridge only parses and expands data. Android owns HTTPS, encrypted
// caches, deadlines and refresh scheduling; no Mihomo global service is started.
import (
	"encoding/json"
	"errors"
	RC "github.com/metacubex/mihomo/rules/common"
	"io"
	"net/url"
	"regexp"
	"sort"
	"strconv"
	"strings"
	"unicode/utf8"

	"go.yaml.in/yaml/v3"
)

type remoteSpec struct {
	ID            string            `json:"id"`
	Kind          string            `json:"kind"`
	Index         int               `json:"index"`
	URL           string            `json:"url"`
	Interval      int               `json:"interval"`
	Headers       map[string]string `json:"headers"`
	Name          string            `json:"name"`
	Behavior      string            `json:"behavior,omitempty"`
	Format        string            `json:"format,omitempty"`
	Filter        string            `json:"filter,omitempty"`
	SizeLimit     int               `json:"sizeLimit,omitempty"`
	Mode          string            `json:"mode,omitempty"`
	Path          string            `json:"path,omitempty"`
	Payload       []any             `json:"payload,omitempty"`
	Override      map[string]any    `json:"override,omitempty"`
	ExcludeFilter string            `json:"excludeFilter,omitempty"`
	ExcludeType   string            `json:"excludeType,omitempty"`
	DialerProxy   string            `json:"dialerProxy,omitempty"`
	DownloadProxy string            `json:"downloadProxy,omitempty"`
}
type remoteFailure struct {
	index      int
	kind, code string
}

func (e *remoteFailure) Error() string { return e.code }
func remoteResult(value any) string    { b, _ := json.Marshal(value); return string(b) }
func remoteError(e error) string {
	f := &remoteFailure{0, "", "provider_invalid"}
	if !errors.As(e, &f) && e != nil {
		// Preserve only fixed public codes, never an arbitrary parser/transport error.
		switch e.Error() {
		case "provider_payload", "provider_size_limit", "provider_total_limit", "provider_cache_limit",
			"provider_config_limit", "provider_rule_limit", "provider_rule_document",
			"provider_rule_syntax", "provider_rule_type", "provider_count_limit",
			"provider_document_syntax", "provider_document_limit", "provider_dns_document",
			"provider_group_keys", "provider_group_filter", "provider_group_use",
			"provider_group_reference", "provider_rule_reference", "provider_rule_entry",
			"provider_bridge_invalid", "provider_internal":
			f.code = e.Error()
		}
	}
	return remoteResult(map[string]any{"state": "failed", "index": f.index, "kind": f.kind, "code": f.code})
}
func remoteYAML(text string) (any, error) {
	return remoteYAMLBounded(text, maxYAMLElements, maxImportNodeBytes)
}
func remoteYAMLWithBudget(text string, budget int) (any, error) {
	return remoteYAMLBounded(text, budget, maxRuleProviderBytes)
}
func remoteYAMLBounded(text string, budget, maximum int) (any, error) {
	if len(text) == 0 {
		return nil, errors.New("provider_document_syntax")
	}
	// Align the pre-parse lexical allowance with ImportProfile. Punctuation in
	// comments/scalars is not an AST node. The expanded node budget stays bounded.
	lexicalBudget := max(budget, 200000)
	if len(text) > maximum || strings.Count(text, "\n") > lexicalBudget {
		return nil, errors.New("provider_document_limit")
	}
	punct := 0
	for _, c := range text {
		if strings.ContainsRune("\n[]{}:,", c) {
			punct++
			if punct > lexicalBudget {
				return nil, errors.New("provider_document_limit")
			}
		}
	}
	d := yaml.NewDecoder(strings.NewReader(normalizeJSONStringTokens(text)))
	var n yaml.Node
	if d.Decode(&n) != nil || len(n.Content) != 1 || d.Decode(new(yaml.Node)) != io.EOF {
		return nil, errors.New("provider_document_syntax")
	}
	count := 0
	if !safeYAMLWithBudget(&n, 0, &count, budget) {
		return nil, errors.New("provider_document_limit")
	}
	normalizeYAMLProtocolScalars(&n, nil, 0)
	var v any
	if n.Decode(&v) != nil {
		return nil, errors.New("provider_document_syntax")
	}
	if !retainNativeDNSDocument(&n, v) {
		return nil, errors.New("provider_dns_document")
	}
	return v, nil
}
func remotePlan(text string) (map[string]any, []remoteSpec, error) {
	text, _ = unwrapSubscription(text)
	v, e := remoteYAML(text)
	if e != nil {
		return nil, nil, e
	}
	root, ok := v.(map[string]any)
	if !ok {
		return nil, nil, nil
	}
	specs := []remoteSpec{}
	for _, kind := range []string{"proxy", "rule"} {
		raw, exists := root[kind+"-providers"]
		if !exists || raw == nil {
			continue
		}
		providers, ok := raw.(map[string]any)
		if !ok {
			return nil, nil, errors.New("provider_invalid")
		}
		if len(providers) > maxRemoteProviders {
			return nil, nil, &remoteFailure{0, kind, "provider_count_limit"}
		}
		names := []string{}
		for name := range providers {
			names = append(names, name)
		}
		sort.Strings(names)
		for _, name := range names {
			s := remoteSpec{ID: kind + ":" + name, Kind: kind, Name: name, Index: len(specs) + 1, Interval: 3600, Format: "yaml", Headers: map[string]string{}}
			bad := func() (map[string]any, []remoteSpec, error) {
				return nil, nil, &remoteFailure{s.Index, kind, "provider_invalid"}
			}
			m, ok := providers[name].(map[string]any)
			if ok {
				m = canonicalProviderOptions(m, kind)
				providers[name] = m
			}
			if !ok || len(name) == 0 || len(name) > 512 || hasControl(name) || m["type"] != "http" && m["type"] != "inline" && m["type"] != "file" {
				return bad()
			}
			s.Mode, _ = m["type"].(string)
			for k, val := range m {
				switch k {
				case "type", "url", "path", "interval", "header":
				case "behavior", "format":
					if kind != "rule" {
						return bad()
					}
				case "path-in-bundle":
					// Fetch the equivalent URL; never open a subscription archive/path.
					path, typed := val.(string)
					if kind != "rule" || !typed || len(path) > 4096 || hasControl(path) {
						return bad()
					}
				case "payload":
					if s.Mode != "inline" {
						return bad()
					}
					s.Payload, ok = val.([]any)
					if !ok || len(s.Payload) > maxProviderRules {
						return bad()
					}
				case "override":
					if kind != "proxy" {
						return bad()
					}
					s.Override, ok = val.(map[string]any)
					if !ok || !validProviderOverride(s.Override) {
						return bad()
					}
				case "exclude-type", "dialer-proxy":
					value, typed := val.(string)
					if kind != "proxy" || !typed || len(value) > 512 || hasControl(value) {
						return bad()
					}
					if k == "exclude-type" {
						s.ExcludeType = value
					} else {
						s.DialerProxy = value
					}
				case "filter", "exclude-filter":
					if kind != "proxy" {
						return bad()
					}
					value, typed := val.(string)
					if !typed || len(value) > 4096 {
						return bad()
					}
					if _, e := compileFilters(value); e != nil {
						return bad()
					}
					if k == "filter" {
						s.Filter = value
					} else {
						s.ExcludeFilter = value
					}
				case "health-check":
					if kind != "proxy" {
						return bad()
					}
					if _, ok := val.(map[string]any); !ok {
						return bad()
					}
				case "proxy":
					name, ok := val.(string)
					if !ok || len(name) > 512 || hasControl(name) {
						return bad()
					}
					s.DownloadProxy = name
				case "size-limit":
					b, _ := json.Marshal(val)
					n, e := strconv.Atoi(string(b))
					if e != nil || n < 0 || n > 2147483647 {
						return bad()
					}
					s.SizeLimit = n
				default:
					return bad()
				}
			}
			if s.Mode == "http" {
				s.URL, ok = m["url"].(string)
				u, e := url.Parse(s.URL)
				if !ok || e != nil || len(s.URL) > 4096 || u.Scheme != "https" || u.Hostname() == "" || u.User != nil || u.Fragment != "" || hasControl(s.URL) {
					return bad()
				}
				if port := u.Port(); port != "" {
					n, e := strconv.Atoi(port)
					if e != nil || n < 1 || n > 65535 {
						return bad()
					}
				}
			} else if s.Mode == "file" {
				s.Path, ok = m["path"].(string)
				if !ok || !safeProviderBundlePath(s.Path) {
					return bad()
				}
				s.Interval = 0
			} else if s.Payload == nil {
				return bad()
			}
			if val, exists := m["interval"]; exists {
				b, _ := json.Marshal(val)
				n, e := strconv.Atoi(string(b))
				if e != nil || n < 0 || n > 31536000 {
					return bad()
				}
				s.Interval = n
			}
			if s.Mode == "file" {
				s.Interval = 0
			}
			if val, exists := m["header"]; exists {
				h, ok := val.(map[string]any)
				if !ok || len(h) > 16 {
					return bad()
				}
				seenHeaders := map[string]bool{}
				for key, val := range h {
					lower := strings.ToLower(key)
					if seenHeaders[lower] {
						return bad()
					}
					seenHeaders[lower] = true
					if !regexp.MustCompile(`^[A-Za-z0-9-]{1,64}$`).MatchString(key) {
						return bad()
					}
					switch strings.ToLower(key) {
					case "host", "connection", "content-length", "transfer-encoding", "cookie", "proxy-authorization":
						return bad()
					}
					if str, yes := val.(string); yes {
						val = []any{str}
					}
					values, ok := val.([]any)
					if !ok || len(values) != 1 {
						return bad()
					}
					str, ok := values[0].(string)
					if !ok || len(str) > 2048 || hasControl(str) {
						return bad()
					}
					s.Headers[key] = str
				}
			}
			if kind == "rule" {
				s.Behavior, _ = m["behavior"].(string)
				if s.Behavior != "domain" && s.Behavior != "ipcidr" && s.Behavior != "classical" {
					return bad()
				}
				if v, ok := m["format"]; ok {
					s.Format, _ = v.(string)
				}
				if s.Format != "yaml" && s.Format != "text" && s.Format != "mrs" {
					return bad()
				}
				if s.Format == "mrs" && (s.Behavior == "classical" || s.Mode == "inline") {
					return bad()
				}
			}
			specs = append(specs, s)
			if len(specs) > maxRemoteProviders {
				return nil, nil, &remoteFailure{0, kind, "provider_count_limit"}
			}
		}
	}
	return root, specs, nil
}

// PlanRemoteProviders returns PRIVATE URLs and headers. Never log this value.
func PlanRemoteProviders(text string) (result string) {
	defer func() {
		if recover() != nil {
			result = remoteError(errors.New("provider_internal"))
		}
	}()
	unwrapped, _ := unwrapSubscription(text)
	if looksLikeShareList(unwrapped) {
		return remoteResult(map[string]any{"state": "passed", "providers": []remoteSpec{}})
	}
	_, specs, e := remotePlan(text)
	if e != nil {
		return remoteError(e)
	}
	return remoteResult(map[string]any{"state": "passed", "providers": specs})
}

// ResolveRemoteProviders expands only the explicitly supplied provider bodies.
// No URL or caller-controlled path is opened. Failure returns a safe ordinal.
func ResolveRemoteProviders(text, bodiesJSON string) (result string) {
	defer func() {
		if recover() != nil {
			result = remoteError(errors.New("provider_internal"))
		}
	}()
	root, specs, e := remotePlan(text)
	if e != nil {
		return remoteError(e)
	}
	if len(specs) == 0 {
		return remoteResult(map[string]any{"state": "passed", "content": text})
	}
	if len(bodiesJSON) > maxProviderBridgeBytes {
		return remoteError(errors.New("provider_cache_limit"))
	}
	var bodies map[string]string
	if json.Unmarshal([]byte(bodiesJSON), &bodies) != nil {
		return remoteError(errors.New("provider_bridge_invalid"))
	}
	nodes, _ := root["proxies"].([]any)
	nodes = append([]any{}, nodes...)
	localNames := []string{}
	for _, raw := range nodes {
		if n, ok := raw.(map[string]any); ok {
			if name, ok := n["name"].(string); ok {
				localNames = append(localNames, name)
			}
		}
	}
	sort.Strings(localNames)
	members := map[string][]any{}
	ruleSets := map[string]storedRuleSet{}
	totalRuleEntries := 0
	totalProviderBytes := 0
	seen := map[string]map[string]any{}
	for _, raw := range nodes {
		if m, ok := raw.(map[string]any); ok {
			n, _ := m["name"].(string)
			seen[n] = m
		}
	}
	for _, s := range specs {
		bad := func() string { return remoteError(&remoteFailure{s.Index, s.Kind, "provider_payload"}) }
		body, ok := bodies[s.ID]
		if s.Mode == "inline" {
			b, _ := json.Marshal(map[string]any{"proxies": s.Payload, "payload": s.Payload})
			body = string(b)
			ok = true
		}
		if ok && body == "" && s.Kind == "rule" && s.Mode != "inline" {
			// Deferred by the app: unreachable before the VPN is up. Match nothing
			// until a later refresh supplies the body; never stand in for a proxy list.
			ruleSets[s.Name] = storedRuleSet{Behavior: s.Behavior, Source: s.Index, Entries: []string{}}
			continue
		}
		if !ok {
			return bad()
		}
		maximum := 2 * 1024 * 1024
		if s.Kind == "rule" {
			maximum = maxRuleProviderBytes
		}
		rawBody, decodeErr := unpackProviderBody(body, s.Format == "mrs", maximum)
		if decodeErr != nil {
			return remoteError(&remoteFailure{s.Index, s.Kind, decodeErr.Error()})
		}
		body = string(rawBody)
		totalProviderBytes += len(rawBody)
		if totalProviderBytes > maxProviderTotalBytes {
			return remoteError(&remoteFailure{s.Index, s.Kind, "provider_total_limit"})
		}
		if s.SizeLimit > 0 && len(rawBody) > s.SizeLimit {
			return remoteError(&remoteFailure{s.Index, s.Kind, "provider_declared_size_limit"})
		}
		if s.Kind == "proxy" {
			list, e := providerProxyNodes(body, s.Index)
			if e != nil {
				return bad()
			}
			members[s.Name] = []any{}
			aliases := map[string]string{}
			prepared := []map[string]any{}
			filter, _ := compileFilters(s.Filter)
			exclude, _ := compileFilters(s.ExcludeFilter)
			for _, raw := range list {
				n, ok := raw.(map[string]any)
				if !ok {
					n = map[string]any{"type": "unsupported"}
				}
				n = normalizedImportNode(n)
				name, ok := n["name"].(string)
				if !ok || name == "" || len(name) > 512 || hasControl(name) {
					name = "Provider " + strconv.Itoa(s.Index) + " / node " + strconv.Itoa(len(prepared)+1)
					n["name"] = name
				}
				if kind, ok := n["type"].(string); !ok || kind == "" {
					n["type"] = "unsupported"
				}
				if providerExcludesType(s.ExcludeType, stringOption(n, "type")) {
					continue
				}
				if s.Filter != "" && !filter.match(name) || s.ExcludeFilter != "" && exclude.match(name) {
					continue
				}
				if s.DialerProxy != "" {
					n["dialer-proxy"] = s.DialerProxy
				}
				if !applyProviderOverride(n, s.Override) {
					return bad()
				}
				name, _ = n["name"].(string)
				if old, exists := seen[name]; exists {
					if !sameProviderNode(old, n) || stringOption(n, "dialer-proxy") != "" {
						base := name
						for len(base) > 440 {
							_, width := utf8.DecodeLastRuneInString(base)
							base = base[:len(base)-width]
						}
						for suffix := 1; ; suffix++ {
							candidate := base + " [Provider " + strconv.Itoa(s.Index) + "." + strconv.Itoa(suffix) + "]"
							if _, used := seen[candidate]; !used {
								name = candidate
								n["name"] = name
								seen[name] = n
								nodes = append(nodes, n)
								break
							}
						}
					}
				} else {
					seen[name] = n
					nodes = append(nodes, n)
				}
				original := name
				if source, ok := raw.(map[string]any); ok {
					original = stringOption(source, "name")
				}
				if _, exists := aliases[original]; !exists {
					aliases[original] = name
				}
				prepared = append(prepared, n)
				members[s.Name] = append(members[s.Name], name)
				if len(nodes) > maxImportNodes {
					return bad()
				}
			}
			for _, n := range prepared {
				if ref, ok := n["dialer-proxy"].(string); ok {
					if replacement, exists := aliases[ref]; exists {
						n["dialer-proxy"] = replacement
					}
				}
			}
			if filter.failed || exclude.failed {
				return bad()
			}
		} else {
			fail := func(code string) string { return remoteError(&remoteFailure{s.Index, s.Kind, code}) }
			trimmed := strings.TrimSpace(strings.TrimPrefix(body, "\ufeff"))
			if strings.HasPrefix(strings.ToLower(trimmed), "<!doctype html") || strings.HasPrefix(strings.ToLower(trimmed), "<html") {
				return fail("provider_html")
			}
			var entries []any
			format := s.Format
			if format != "mrs" {
				format = ruleBodyFormat(trimmed)
			}
			if format == "mrs" {
				var e error
				entries, e = decodeMrsEntries(rawBody, s.Behavior)
				if e != nil {
					return fail(e.Error())
				}
			} else if format == "yaml" {
				v, e := remoteYAMLWithBudget(trimmed, maxProviderRules*3)
				if e != nil {
					return fail("provider_rule_document")
				}
				m, ok := v.(map[string]any)
				if !ok {
					return fail("provider_rule_document")
				}
				entries, ok = m["payload"].([]any)
				if len(entries) == 0 {
					if rules, typed := m["rules"].([]any); typed {
						entries, ok = rules, true
					}
				}
				if !ok {
					return fail("provider_rule_document")
				}
			} else {
				for _, line := range strings.Split(trimmed, "\n") {
					line = strings.TrimSpace(line)
					if line != "" && !strings.HasPrefix(line, "#") && !strings.HasPrefix(line, "//") {
						entries = append(entries, line)
					}
				}
			}
			totalRuleEntries += len(entries)
			if totalRuleEntries > maxProviderRules {
				return fail("provider_rule_limit")
			}
			set := storedRuleSet{Behavior: s.Behavior, Source: s.Index, Entries: make([]string, 0, len(entries))}
			for _, raw := range entries {
				line, ok := raw.(string)
				if !ok {
					return fail("provider_rule_syntax")
				}
				line = strings.TrimSpace(line)
				if len(line) > 2048 {
					line = ""
				} // Preserve the entry position; skip instead of retaining oversized invalid data.
				set.Entries = append(set.Entries, line)
			}
			if _, code := compileProviderRuleSet(set); code != "" {
				return fail(code)
			}
			ruleSets[s.Name] = set
		}
	}

	groups, _ := root["proxy-groups"].([]any)
	for gi, raw := range groups {
		g, ok := raw.(map[string]any)
		if !ok {
			continue
		}
		normalized, valid := normalizeGroupKeys(g)
		if !valid {
			return remoteError(errors.New("provider_group_keys"))
		}
		g = normalized
		groups[gi] = g
		list, _ := g["proxies"].([]any)
		list = append([]any{}, list...)
		// A group's filter applies to provider-derived members, but must not
		// remove explicitly named nested policy groups such as an auto selector.
		filters, e := compileFilters(stringOption(g, "filter"))
		if e != nil {
			return remoteError(errors.New("provider_group_filter"))
		}
		appendProvider := func(values []any) {
			for _, value := range values {
				name, _ := value.(string)
				if len(filters.patterns) == 0 || filters.match(name) {
					list = append(list, value)
				}
			}
		}
		use, exists := g["use"]
		if exists && use != nil {
			u, ok := use.([]any)
			if !ok {
				return remoteError(errors.New("provider_group_use"))
			}
			for _, raw := range u {
				name, ok := raw.(string)
				m, exists := members[name]
				if !ok || !exists {
					return remoteError(errors.New("provider_group_reference"))
				}
				appendProvider(m)
			}
		}
		if g["include-all-providers"] == true || g["include-all"] == true {
			for _, s := range specs {
				if s.Kind == "proxy" {
					appendProvider(members[s.Name])
				}
			}
		}
		// After expansion, the candidates also contain provider nodes. Resolve
		// include-all-proxies against the original local pool now, otherwise a
		// later normalization incorrectly mixes every provider into this group.
		if g["include-all-proxies"] == true || g["include-all"] == true {
			for _, name := range localNames {
				if len(filters.patterns) == 0 || filters.match(name) {
					list = append(list, name)
				}
			}
		}
		if filters.failed {
			return remoteError(errors.New("provider_group_filter"))
		}
		g["proxies"] = list
		delete(g, "use")
		delete(g, "include-all-providers")
		delete(g, "include-all-proxies")
		delete(g, "include-all")
	}
	rawRules, _ := root["rules"].([]any)
	rules := []any{}
	for _, raw := range rawRules {
		line, ok := raw.(string)
		if !ok {
			return remoteError(errors.New("provider_rule_entry"))
		}
		kind, payload, target, params := RC.ParseRulePayload(line, true)
		if kind == "RULE-SET" {
			if target == "" || len(target) > 512 || len(params) > 32 {
				return remoteError(errors.New("provider_rule_entry"))
			}
			if _, exists := ruleSets[payload]; !exists {
				return remoteError(errors.New("provider_rule_reference"))
			}
		}
		rules = append(rules, line)
	}
	encoded, _ := json.Marshal(ruleSets)
	packed, packErr := packRuleData(encoded)
	if packErr != nil {
		return remoteError(&remoteFailure{0, "rule", "provider_config_limit"})
	}
	root[providerRuleDataKey] = packed

	root["proxies"] = nodes
	root["rules"] = rules
	delete(root, "proxy-providers")
	delete(root, "rule-providers")
	b, e := json.Marshal(root)
	if e != nil || len(b) > maxPrivateProfileBytes {
		return remoteError(&remoteFailure{0, "rule", "provider_config_limit"})
	}
	return remoteResult(map[string]any{"state": "passed", "content": string(b)})
}

// A rule source's body says what it is: a YAML document opens with a key
// ("payload:", or any other so a wrong document is still reported), a flow
// mapping or a list item; anything else is one entry per line. Domains, CIDRs
// (including IPv6) and classical rules never look like a YAML key. The declared
// or default format is not trusted for this, because subscriptions routinely
// serve YAML lists as ".txt" and plain lists without a format, and reading one
// as the other turns every entry into an invalid rule.
var yamlKeyLine = regexp.MustCompile(`^[A-Za-z][A-Za-z0-9_-]*:(\s|$)`)

func ruleBodyFormat(body string) string {
	for _, line := range strings.Split(body, "\n") {
		line = strings.TrimSpace(line)
		if line == "" || strings.HasPrefix(line, "#") || strings.HasPrefix(line, "//") {
			continue
		}
		if yamlKeyLine.MatchString(line) || strings.HasPrefix(line, "{") || strings.HasPrefix(line, "- ") {
			return "yaml"
		}
		return "text"
	}
	return "text"
}
