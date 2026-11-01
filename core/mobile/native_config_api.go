// SPDX-License-Identifier: GPL-3.0-only
package mobile

import (
	"github.com/metacubex/mihomo/component/resolver"
	"github.com/metacubex/mihomo/component/sniffer"
	"github.com/metacubex/mihomo/component/trie"
	"github.com/metacubex/mihomo/config"
	P "github.com/metacubex/mihomo/constant/provider"
	_ "unsafe" // Pinned, hash-audited native parser bridge; no pointer conversion.
)

// Call the native component parsers without ParseRawConfig's process-global
// temporaryUpdateGeneral/parseIPV6 mutations. These private signatures belong
// to v1.19.32 only; audit-mihomo-compatibility.py pins the entire config source
// hash. An upstream update requires review and the end-to-end DNS regressions.

//go:linkname parseNativeDNS github.com/metacubex/mihomo/config.parseDNS
func parseNativeDNS(*config.RawConfig, map[string]P.RuleProvider) (*config.DNS, error)

//go:linkname parseNativeHosts github.com/metacubex/mihomo/config.parseHosts
func parseNativeHosts(*config.RawConfig) (*trie.DomainTrie[resolver.HostValue], error)

//go:linkname parseNativeRuleProviders github.com/metacubex/mihomo/config.parseRuleProviders
func parseNativeRuleProviders(*config.RawConfig) (map[string]P.RuleProvider, error)

//go:linkname parseNativeSniffer github.com/metacubex/mihomo/config.parseSniffer
func parseNativeSniffer(config.RawSniffer, map[string]P.RuleProvider) (*sniffer.Config, error)

//go:linkname restoreNativeGeneral github.com/metacubex/mihomo/hub/executor.updateGeneral
func restoreNativeGeneral(*config.General, bool)
