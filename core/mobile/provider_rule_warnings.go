// SPDX-License-Identifier: GPL-3.0-only
package mobile

import (
	"errors"
	"sort"
)

type providerRuleWarning struct {
	Source int    `json:"source"`
	Entry  int    `json:"entry"`
	Type   string `json:"type"`
	Reason string `json:"reason"`
	Count  int    `json:"count"`
}

func (m *providerRuleMatcher) skip(source, entry int, kind, code string) {
	kind = publicNativeRuleType(kind)
	for i := range m.warnings {
		item := &m.warnings[i]
		if item.Source == source && item.Type == kind && item.Reason == code {
			item.Count++
			m.skipped++
			return
		}
	}
	if len(m.warnings) < 64 {
		m.warnings = append(m.warnings, providerRuleWarning{source, entry, kind, code, 1})
	}
	m.skipped++
}

func analyzeStoredRuleSets(encoded string) ([]providerRuleWarning, int, error) {
	sets, e := decodeStoredRuleSets(encoded)
	if e != nil {
		return nil, 0, e
	}
	names := make([]string, 0, len(sets))
	for name := range sets {
		names = append(names, name)
	}
	sort.Strings(names)
	issues := []providerRuleWarning{}
	skipped := 0
	total := 0
	for ordinal, name := range names {
		set := sets[name]
		total += len(set.Entries)
		if name == "" || len(name) > 512 || hasControl(name) || total > maxProviderRules {
			return nil, 0, errors.New("provider_rule_limit")
		}
		if set.Source == 0 {
			set.Source = ordinal + 1
		}
		matcher, code := compileProviderRuleSet(set)
		if code != "" {
			return nil, 0, errors.New(code)
		}
		skipped += matcher.skipped
		for _, issue := range matcher.warnings {
			if len(issues) < 64 {
				issues = append(issues, issue)
			}
		}
	}
	return issues, skipped, nil
}
