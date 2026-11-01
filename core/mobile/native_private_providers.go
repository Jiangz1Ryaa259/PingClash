// SPDX-License-Identifier: GPL-3.0-only
package mobile

import (
	"errors"
	"github.com/metacubex/mihomo/component/sniffer"
	C "github.com/metacubex/mihomo/constant"
	P "github.com/metacubex/mihomo/constant/provider"
)

// The parser sees bounded inline descriptors; runtime matchers retain the
// validated payload rather than reparsing classical regexp without a deadline.
type immutableRuleProvider struct {
	P.RuleProvider
	matcher *providerRuleMatcher
	count   int
}

func (p *immutableRuleProvider) Initial() error { return nil }
func (p *immutableRuleProvider) Update() error  { return nil }
func (p *immutableRuleProvider) Count() int     { return p.count }
func (p *immutableRuleProvider) Match(m *C.Metadata, h C.RuleMatchHelper) bool {
	return p.matcher.matchesMetadata(m, h)
}
func (p *immutableRuleProvider) Strategy() any { return nil }

func privateStoredProviders(packed string, providers map[string]P.RuleProvider) error {
	sets, e := decodeStoredRuleSets(packed)
	if e != nil {
		return e
	}
	for name, set := range sets {
		p := providers[name]
		if p == nil {
			return errors.New("rule_provider_invalid")
		}
		matcher, code := compileProviderRuleSet(set)
		if code != "" {
			return errors.New(code)
		}
		providers[name] = &immutableRuleProvider{RuleProvider: p, matcher: matcher, count: len(set.Entries)}
	}
	return nil
}

func bindPrivateSniffer(c *sniffer.Config, sets map[string]storedRuleSet) error {
	matchers := map[string]*privateRuleSet{}
	for name, set := range sets {
		matcher, code := compileProviderRuleSet(set)
		if code != "" {
			return errors.New(code)
		}
		matchers[name] = &privateRuleSet{name: name, matcher: matcher, noResolve: true}
	}
	domain := func(m C.DomainMatcher) C.DomainMatcher {
		if r, ok := m.(C.Rule); ok && r.RuleType() == C.RuleSet {
			return matchers[r.Payload()]
		}
		return m
	}
	ip := func(m C.IpMatcher) C.IpMatcher {
		if r, ok := m.(C.Rule); ok && r.RuleType() == C.RuleSet {
			return matchers[r.Payload()]
		}
		return m
	}
	for i, m := range c.ForceDomain {
		c.ForceDomain[i] = domain(m)
	}
	for i, m := range c.SkipDomain {
		c.SkipDomain[i] = domain(m)
	}
	for i, m := range c.SkipSrcAddress {
		c.SkipSrcAddress[i] = ip(m)
	}
	for i, m := range c.SkipDstAddress {
		c.SkipDstAddress[i] = ip(m)
	}
	return nil
}
