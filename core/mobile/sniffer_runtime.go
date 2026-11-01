// SPDX-License-Identifier: GPL-3.0-only
package mobile

import (
	"encoding/json"
	"errors"
	"io"
	"strings"

	"github.com/metacubex/mihomo/component/sniffer"
	"github.com/metacubex/mihomo/config"
	"go.yaml.in/yaml/v3"
)

func newNativeSniffer(definition string) (*sniffer.Dispatcher, error) {
	if definition == "" {
		return nil, nil
	}
	invalid := errors.New("options_invalid")
	var root map[string]json.RawMessage
	if json.Unmarshal([]byte(definition), &root) != nil {
		return nil, invalid
	}
	input, ok := root["sniffer"]
	if !ok {
		return nil, nil
	}
	var value map[string]any
	if json.Unmarshal(input, &value) != nil || len(value) > 32 {
		return nil, invalid
	}
	if value["enable"] == false {
		return nil, nil
	}
	selected := map[string]any{"sniffer": value}
	sets := map[string]storedRuleSet{}
	if encoded, ok := root[providerRuleDataKey]; ok {
		var packed string
		if json.Unmarshal(encoded, &packed) != nil {
			return nil, invalid
		}
		var e error
		sets, e = decodeStoredRuleSets(packed)
		if e != nil {
			return nil, invalid
		}
		providers := map[string]map[string]any{}
		for name, set := range sets {
			entries := set.Entries
			if set.Behavior == "classical" {
				entries = []string{}
			} // Rebound below to immutable private matchers, including classical.
			providers[name] = map[string]any{"type": "inline", "behavior": set.Behavior, "payload": entries}
		}
		selected["rule-providers"] = providers
	}
	b, e := yaml.Marshal(selected)
	if e != nil {
		return nil, invalid
	}
	low := strings.ToLower(string(b))
	if (strings.Contains(low, "geoip:") || strings.Contains(low, "geosite:")) && !ruleDataAvailable() {
		return nil, invalid
	}
	raw, e := config.UnmarshalRawConfig(b)
	if e != nil {
		return nil, invalid
	}
	nativeConfigParse.Lock()
	defer nativeConfigParse.Unlock()
	providers, e := parseNativeRuleProviders(raw)
	if e != nil {
		return nil, invalid
	}
	defer func() {
		for _, p := range providers {
			if closer, ok := p.(io.Closer); ok {
				_ = closer.Close()
			}
		}
	}()
	for _, p := range providers {
		if p.Initial() != nil {
			return nil, invalid
		}
	}
	parsed, e := parseNativeSniffer(raw.Sniffer, providers)
	if e != nil {
		return nil, invalid
	}
	if bindPrivateSniffer(parsed, sets) != nil {
		return nil, invalid
	}
	dispatcher, e := sniffer.NewDispatcher(parsed)
	if e != nil {
		return nil, invalid
	}
	return dispatcher, nil
}
