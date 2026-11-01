// SPDX-License-Identifier: GPL-3.0-only
package mobile

import (
	"embed"
	"encoding/json"
	"reflect"
	"testing"
)

// Only compiled into the test binary; fixtures must never enter the Android AAR.
//
//go:embed testdata/provider-compatibility
var providerCorpus embed.FS

func TestProviderCompatibilityCorpus(t *testing.T) {
	var manifest struct {
		Cases []struct {
			ID, Plan, Resolve string
			Nodes, Rules      int
			Groups            map[string][]string
			Bodies            map[string]string
		}
	}
	b, err := providerCorpus.ReadFile("testdata/provider-compatibility/manifest.json")
	if err != nil || json.Unmarshal(b, &manifest) != nil || len(manifest.Cases) < 14 {
		t.Fatal("missing compatibility corpus")
	}
	for _, c := range manifest.Cases {
		t.Run(c.ID, func(t *testing.T) {
			text, err := providerCorpus.ReadFile("testdata/provider-compatibility/" + c.ID + ".yaml")
			if err != nil {
				t.Fatal("missing fixture")
			}
			var plan struct{ State string }
			if json.Unmarshal([]byte(PlanRemoteProviders(string(text))), &plan) != nil || plan.State != c.Plan {
				t.Fatal("plan compatibility changed")
			}
			resolved := resolveFixture(t, string(text), c.Bodies)
			if resolved["state"] != c.Resolve {
				t.Fatal("resolution compatibility changed")
			}
			if c.Resolve != "passed" {
				out, _ := json.Marshal(resolved)
				assertNoCanaries(t, string(out))
				return
			}
			var profile importedProfile
			if json.Unmarshal([]byte(ImportProfile(resolved["content"].(string))), &profile) != nil || profile.State != "passed" || len(profile.Nodes) != c.Nodes || len(profile.Rules) != c.Rules || profile.GroupsUnsupported != 0 || profile.Unsupported != 0 {
				t.Fatal("import compatibility changed")
			}
			groups := map[string][]string{}
			for _, g := range profile.Groups {
				groups[g.Name] = g.Proxies
			}
			if !reflect.DeepEqual(groups, c.Groups) {
				t.Fatal("original group membership changed")
			}
			options := defaultRuntimeOptions()
			options.Mode, options.Rules, options.Definition = "rule", profile.Rules, profile.Definition
			opts, _ := json.Marshal(options)
			var validation struct{ State string }
			if json.Unmarshal([]byte(ValidateRuntimeOptions(string(opts))), &validation) != nil || validation.State != "passed" {
				t.Fatal("imported corpus cannot execute in rule mode")
			}
		})
	}
}
