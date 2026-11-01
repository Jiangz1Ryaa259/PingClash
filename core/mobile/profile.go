// SPDX-License-Identifier: GPL-3.0-only
package mobile

import (
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"strconv"
	"strings"

	"go.yaml.in/yaml/v3"
)

type importedNode struct {
	Name            string `json:"name"`
	Type            string `json:"type"`
	Config          string `json:"config"`
	RequiresProfile bool   `json:"requiresProfile,omitempty"`
}
type importedProfile struct {
	State                string                `json:"state"`
	Code                 string                `json:"code"`
	Nodes                []importedNode        `json:"nodes"`
	Unsupported          int                   `json:"unsupported"`
	RoutingIgnored       bool                  `json:"routingIgnored"`
	Rejections           map[string]int        `json:"rejections,omitempty"`
	Format               string                `json:"format,omitempty"`
	VerificationEnabled  int                   `json:"verificationEnabled,omitempty"`
	FieldIssues          map[string]int        `json:"fieldIssues,omitempty"`
	Groups               []importedGroup       `json:"groups,omitempty"`
	GroupsUnsupported    int                   `json:"groupsUnsupported,omitempty"`
	GroupsDeclared       bool                  `json:"groupsDeclared"`
	Rules                []string              `json:"rules,omitempty"`
	RulesOmitted         int                   `json:"rulesOmitted,omitempty"`
	ProviderRuleWarnings []providerRuleWarning `json:"providerRuleWarnings,omitempty"`
	ProviderRulesSkipped int                   `json:"providerRulesSkipped,omitempty"`
	Definition           string                `json:"definition,omitempty"`
}

// ImportProfile returns PRIVATE profile data for local encrypted storage.
// Unlike diagnostic results, its nodes contain credentials. Never log this value.
// It does not fetch providers, rules, scripts, URLs or files referenced by a profile.
func ImportProfile(text string) (result string) {
	invalid := func() string {
		return `{"state":"failed","code":"profile_invalid","nodes":[],"unsupported":0,"routingIgnored":false}`
	}
	defer func() {
		if recover() != nil {
			result = invalid()
		}
	}()
	if len(text) == 0 || len(text) > maxPrivateProfileBytes {
		return invalid()
	}
	text, format := unwrapSubscription(text)
	if strings.HasPrefix(strings.ToLower(text), "<!doctype html") || strings.HasPrefix(strings.ToLower(text), "<html") {
		return `{"state":"failed","code":"subscription_html","nodes":[]}`
	}
	if looksLikeShareList(text) {
		return importShareLinks(text, format)
	}
	// Bound parser work before allocating a YAML AST. This conservative lexical
	// budget also counts punctuation inside scalars; very large legitimate inputs
	// may be rejected. A post-parse node limit alone would allocate the attack first.
	structural := 0
	for _, c := range text {
		if strings.ContainsRune("\n[]{}:,", c) {
			structural++
			if structural > 200000 {
				return invalid()
			}
		}
	}
	decoder := yaml.NewDecoder(strings.NewReader(normalizeJSONStringTokens(text)))
	var document yaml.Node
	if decoder.Decode(&document) != nil || len(document.Content) != 1 {
		return invalid()
	}
	if decoder.Decode(new(yaml.Node)) != io.EOF {
		return invalid()
	}
	count := 0
	if !safeYAML(&document, 0, &count) {
		return invalid()
	}
	normalizeYAMLProtocolScalars(&document, nil, 0)
	var decoded any
	if document.Decode(&decoded) != nil {
		return invalid()
	}
	if !retainNativeDNSDocument(&document, decoded) {
		return invalid()
	}
	profile := importedProfile{State: "passed", Code: "profile_imported", Nodes: make([]importedNode, 0), Rejections: make(map[string]int), FieldIssues: make(map[string]int), Format: format}
	var candidates []any
	switch root := decoded.(type) {
	case []any:
		candidates = root
	case map[string]any:
		if raw, exists := root[providerRuleDataKey]; exists {
			encoded, ok := raw.(string)
			if !ok {
				return invalid()
			}
			var e error
			profile.ProviderRuleWarnings, profile.ProviderRulesSkipped, e = analyzeStoredRuleSets(encoded)
			if e != nil {
				return invalid()
			}
		}
		if raw, exists := root["rules"]; exists {
			if rules, ok := raw.([]any); ok {
				bytes := 0
				for _, item := range rules {
					if rule, ok := item.(string); ok && len(rule) <= 2048 && len(profile.Rules) < 2000 && bytes+len(rule) <= 256*1024 {
						profile.Rules = append(profile.Rules, rule)
						bytes += len(rule)
					} else {
						profile.RulesOmitted++
					}
				}
			} else {
				profile.RulesOmitted++
			}
		}
		if _, exists := root["outbounds"]; exists {
			return `{"state":"failed","code":"singbox_format","nodes":[]}`
		}
		if list, ok := root["proxies"].([]any); ok {
			candidates = list
			for key := range root {
				if key != "proxies" && key != "proxy-groups" && key != "pingclash-selections" {
					profile.RoutingIgnored = true
				}
			}
		} else if _, ok := root["type"]; ok {
			candidates = []any{root}
		} else {
			if _, exists := root["proxy-providers"]; exists {
				return `{"state":"failed","code":"provider_only","nodes":[]}`
			}
			return invalid()
		}
	default:
		return invalid()
	}
	if len(candidates) == 0 || len(candidates) > 500 {
		return invalid()
	}
	graph := newImportGraph(candidates, decoded, &profile)
	if profile.State == "failed" {
		return invalid()
	}
	groupBytes, _ := json.Marshal(profile.Groups)
	configBytes := len(profile.Definition) + len(groupBytes)
	nodeBytes := 0
	for index, candidate := range candidates {
		mapping, ok := candidate.(map[string]any)
		if !ok {
			profile.Unsupported++
			profile.Rejections["invalid_node"]++
			continue
		}
		mapping = normalizedImportNode(mapping)
		name, _ := mapping["name"].(string)
		if strings.TrimSpace(name) == "" {
			name = fmt.Sprintf("节点 %d", index+1)
			mapping["name"] = name
		}
		encoded, err := json.Marshal(mapping)
		if err != nil {
			profile.Unsupported++
			profile.Rejections["invalid_node"]++
			continue
		}
		config, resolveErr := graph.config(mapping)
		requiresProfile := false
		if resolveErr != nil && resolveErr.Error() == "proxy_chain" && profile.Definition != "" {
			if runtime, runtimeErr := graph.runtime(name); runtimeErr == nil {
				_ = runtime.Close()
				config = string(encoded)
				resolveErr = nil
				requiresProfile = true
			} else if runtimeErr.Error() == "resource_limit" {
				resolveErr = runtimeErr
			}
		}
		if resolveErr == nil && (configBytes+len(config) > maxPrivateProfileBytes || nodeBytes+len(config) > 2*1024*1024) {
			profile.Unsupported++
			profile.Rejections["resource_limit"]++
			continue
		}
		var proxy interface{ Close() error }
		if requiresProfile {
			proxy, err = graph.runtime(name)
		} else {
			proxy, err = parseNode(config)
		}
		if resolveErr != nil {
			err = resolveErr
		}
		if err != nil {
			profile.Unsupported++
			issues := importFieldIssues(string(encoded))
			reason := rejectionReason(string(encoded), issues)
			if resolveErr != nil {
				reason = resolveErr.Error()
			}
			profile.Rejections[reason]++
			for _, issue := range issues {
				profile.FieldIssues[issue]++
			}
			continue
		}
		_ = proxy.Close()
		kind, _ := mapping["type"].(string)
		configBytes += len(config)
		nodeBytes += len(config)
		profile.Nodes = append(profile.Nodes, importedNode{Name: name, Type: kind, Config: config, RequiresProfile: requiresProfile})
	}
	for i, group := range profile.Groups {
		for _, ref := range group.Proxies {
			if !graph.duplicate[ref] && graph.nodeUnavailable(ref) {
				profile.Groups[i].Unavailable++
			}
		}
		if group.Supported {
			runtime, e := graph.runtime(group.Name)
			if e != nil {
				profile.Groups[i].Supported = false
				profile.Groups[i].Reason = "group_dependency"
				var failure *groupFailure
				if errors.As(e, &failure) && failure.reason != "" {
					profile.Groups[i].Reason = failure.reason
				}
				if e.Error() == "resource_limit" {
					profile.Groups[i].Reason = "resource_limit"
				}
				profile.GroupsUnsupported++
			} else {
				_ = runtime.Close()
			}
		}
	}
	if len(profile.Nodes) == 0 {
		profile.State = "failed"
		profile.Code = "no_supported_nodes"
		profile.Definition = ""
		profile.Groups = nil
	}
	encoded, _ := json.Marshal(profile)
	if len(encoded) > maxPrivateBridgeBytes {
		return invalid()
	}
	return string(encoded)
}

// Canonicalize JSON string tokens so yaml.v3 accepts Android's slash and UTF-16
// escapes. Preserve every key occurrence: whole-object unmarshal/remarshal would
// erase duplicate keys before the YAML validator can reject them.
func normalizeJSONStringTokens(text string) string {
	if !json.Valid([]byte(text)) {
		return text
	}
	var out strings.Builder
	for i := 0; i < len(text); {
		if text[i] != '"' {
			out.WriteByte(text[i])
			i++
			continue
		}
		end := i + 1
		for end < len(text) {
			if text[end] == '\\' {
				end += 2
				continue
			}
			if text[end] == '"' {
				end++
				break
			}
			end++
		}
		var s string
		if json.Unmarshal([]byte(text[i:end]), &s) != nil {
			return text
		}
		b, _ := json.Marshal(s)
		out.Write(b)
		i = end
		if out.Len() > maxPrivateProfileBytes {
			return text
		}
	}
	return out.String()
}

// Some subscription generators share a VMess/VLESS template. In pinned mihomo
// VlessOption has neither alterId nor cipher: these placeholders do not affect
// VLESS encryption or authentication. Drop only bounded, correctly typed scalar
// placeholders at import; preserve all real VLESS, TLS and routing checks.
func removeUnusedVLESSFields(mapping map[string]any) {
	if mapping["type"] != "vless" {
		return
	}
	if value, exists := mapping["alterId"]; exists {
		valid := false
		switch value := value.(type) {
		case int:
			valid = value >= 0 && value <= 65535
		case string:
			if len(value) <= 16 {
				n, err := strconv.Atoi(value)
				valid = err == nil && n >= 0 && n <= 65535
			}
		case json.Number:
			n, err := value.Int64()
			valid = err == nil && n >= 0 && n <= 65535
		}
		if valid {
			delete(mapping, "alterId")
		}
	}
	if value, ok := mapping["cipher"].(string); ok && len(value) <= 64 && !hasControl(value) {
		delete(mapping, "cipher")
	}
}

// Count the expanded alias graph before decoding. Shared templates are allowed;
// cycles, exponential alias bombs and excessive depth remain bounded.
func safeYAML(node *yaml.Node, depth int, count *int) bool {
	return safeYAMLWithBudget(node, depth, count, 50000)
}
func safeYAMLWithBudget(node *yaml.Node, depth int, count *int, budget int) bool {
	active := map[*yaml.Node]bool{}
	wide := map[*yaml.Node]bool{}
	var visit func(*yaml.Node, int) bool
	visit = func(n *yaml.Node, d int) bool {
		*count++
		if n == nil || d > 24 || *count > budget || active[n] || n.Kind == yaml.MappingNode && (len(n.Content) > 20000 || len(n.Content) > 200 && !wide[n]) {
			return false
		}
		active[n] = true
		if n.Kind == yaml.MappingNode {
			for i := 0; i+1 < len(n.Content); i += 2 {
				switch n.Content[i].Value {
				case "hosts", "nameserver-policy", "proxy-server-nameserver-policy", "proxy-providers", "rule-providers":
					value := n.Content[i+1]
					wide[value] = true
					if value.Kind == yaml.AliasNode {
						wide[value.Alias] = true
					}
				}
			}
		}
		defer delete(active, n)
		if n.Kind == yaml.AliasNode {
			return visit(n.Alias, d+1)
		}
		for _, c := range n.Content {
			if !visit(c, d+1) {
				return false
			}
		}
		return true
	}
	return visit(node, depth)
}
