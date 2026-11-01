// SPDX-License-Identifier: GPL-3.0-only
package mobile

// Rule sources remain compact, immutable sets. They never enter Mihomo's
// process-wide provider registry, open paths, or start a downloader.
import (
	"bytes"
	"encoding/json"
	"errors"
	"io"
	"net/netip"
	"strings"

	"github.com/metacubex/mihomo/component/cidr"
	"github.com/metacubex/mihomo/component/trie"
	C "github.com/metacubex/mihomo/constant"
	RC "github.com/metacubex/mihomo/rules/common"
)

const providerRuleDataKey = "pingclash-rule-data"
const maxProviderRules = 500000

type storedRuleSet struct {
	Behavior string   `json:"behavior"`
	Source   int      `json:"index,omitempty"`
	Entries  []string `json:"entries"`
}

type providerRuleMatcher struct {
	domains   *trie.DomainSet
	networks  *cidr.IpCidrSet
	classical []outboundRule
	warnings  []providerRuleWarning
	skipped   int
}

func (m *providerRuleMatcher) matches(host string, ip netip.Addr, port uint16, network string) bool {
	return m.matchesMetadata(ruleMetadata(host, ip, port, network), C.RuleMatchHelper{})
}
func (m *providerRuleMatcher) matchesMetadata(meta *C.Metadata, helper C.RuleMatchHelper) bool {
	if m.domains != nil {
		return meta.RuleHost() != "" && m.domains.Has(meta.RuleHost())
	}
	if m.networks != nil {
		if !meta.DstIP.IsValid() && helper.ResolveIP != nil {
			helper.ResolveIP()
		}
		return meta.DstIP.IsValid() && m.networks.IsContain(meta.DstIP.Unmap())
	}
	for _, r := range m.classical {
		if r.matchesMetadata(meta, helper) {
			return true
		}
	}
	return false
}

func compileProviderRuleSet(s storedRuleSet) (*providerRuleMatcher, string) {
	if len(s.Entries) > maxProviderRules {
		return nil, "provider_rule_limit"
	}
	m := &providerRuleMatcher{}
	var domains trie.DomainSetBuilder
	if s.Behavior == "ipcidr" {
		m.networks = cidr.NewIpCidrSet()
	}
	if s.Behavior != "domain" && s.Behavior != "ipcidr" && s.Behavior != "classical" {
		return nil, "provider_rule_type"
	}
	for ordinal, raw := range s.Entries {
		line := strings.TrimSpace(raw)
		if line == "" || len(line) > 2048 || hasControl(line) {
			if s.Behavior == "classical" {
				m.skip(s.Source, ordinal+1, "UNRECOGNIZED", "rule_invalid")
				continue
			}
			return nil, "provider_rule_syntax"
		}
		switch s.Behavior {
		case "domain":
			// Same builder as Mihomo's domain provider: '+.', '.', and '*' have
			// their native label semantics, rather than a broad regex conversion.
			if domains.Insert(line) != nil {
				return nil, "provider_rule_syntax"
			}
		case "ipcidr":
			if m.networks.AddIpCidrForString(line) != nil {
				return nil, "provider_rule_syntax"
			}
		case "classical":
			tp, payload, _, params := RC.ParseRulePayload(line, false)
			if tp == "MATCH" || tp == "RULE-SET" || tp == "SUB-RULE" {
				m.skip(s.Source, ordinal+1, tp, "classical_forbidden")
				continue
			}
			probe := tp + "," + payload + ",REJECT"
			if len(params) > 0 {
				probe += "," + strings.Join(params, ",")
			}
			r, _, code := compileRules([]string{probe})
			if code != "" {
				m.skip(s.Source, ordinal+1, tp, code)
				continue
			}
			m.classical = append(m.classical, r...)
		}
	}
	if s.Behavior == "domain" {
		m.domains = domains.Build()
	}
	if m.networks != nil && m.networks.Merge() != nil {
		return nil, "provider_rule_syntax"
	}
	return m, ""
}

// Only private resolved data is loaded here. A referenced source must exist;
// an invalid source is never dropped or silently replaced with DIRECT.
func validateStoredRuleSets(encoded string) error {
	_, _, e := analyzeStoredRuleSets(encoded)
	return e
}

func parseStoredRuleSets(raw []byte) (map[string]storedRuleSet, error) {
	invalid := errors.New("provider_payload")
	if len(raw) > maxRuleDataBytes {
		return nil, errors.New("provider_config_limit")
	}
	d := json.NewDecoder(bytes.NewReader(raw))
	token, e := d.Token()
	if e != nil || token != json.Delim('{') {
		return nil, invalid
	}
	sets := map[string]storedRuleSet{}
	total := 0
	for d.More() {
		key, e := d.Token()
		name, ok := key.(string)
		if e != nil || !ok || name == "" || len(name) > 512 || hasControl(name) {
			return nil, invalid
		}
		if _, exists := sets[name]; exists || len(sets) >= 16 {
			return nil, invalid
		}
		token, e = d.Token()
		if e != nil || token != json.Delim('{') {
			return nil, invalid
		}
		s := storedRuleSet{}
		seen := map[string]bool{}
		for d.More() {
			field, e := d.Token()
			key, ok := field.(string)
			if e != nil || !ok || seen[key] {
				return nil, invalid
			}
			seen[key] = true
			switch key {
			case "index":
				if d.Decode(&s.Source) != nil || s.Source < 0 || s.Source > 16 {
					return nil, invalid
				}
			case "behavior":
				if d.Decode(&s.Behavior) != nil || len(s.Behavior) > 16 {
					return nil, invalid
				}
			case "entries":
				token, e = d.Token()
				if e != nil {
					return nil, invalid
				}
				if token == nil {
					continue
				}
				if token != json.Delim('[') {
					return nil, invalid
				}
				for d.More() {
					if total >= maxProviderRules {
						return nil, errors.New("provider_rule_limit")
					}
					var entry string
					if d.Decode(&entry) != nil || len(entry) > 2048 {
						return nil, invalid
					}
					s.Entries = append(s.Entries, entry)
					total++
				}
				if token, e = d.Token(); e != nil || token != json.Delim(']') {
					return nil, invalid
				}
			default:
				return nil, invalid
			}
		}
		if token, e = d.Token(); e != nil || token != json.Delim('}') {
			return nil, invalid
		}
		sets[name] = s
	}
	if token, e = d.Token(); e != nil || token != json.Delim('}') || d.Decode(new(any)) != io.EOF {
		return nil, invalid
	}
	return sets, nil
}
