// SPDX-License-Identifier: GPL-3.0-only
package mobile

import (
	"context"
	"encoding/json"
	"errors"
	"strings"
	"testing"
)

func assertNoCanaries(t *testing.T, text string) {
	t.Helper()
	if strings.Contains(strings.ToUpper(text), "CANARY-") || strings.Contains(text, "00000000-0000-4000-8000-000000000001") {
		t.Fatal("synthetic credential escaped a public output")
	}
}

func TestCanaryCredentialsStayOutOfPublicFailureAndProbeOutputs(t *testing.T) {
	config := `{"type":"vless","server":"example.invalid","port":0,"uuid":"00000000-0000-4000-8000-000000000001","password":"CANARY-PASSWORD-1234"}`
	target := "https://example.invalid/CANARY-SUB-TOKEN-1234"
	for _, out := range []string{
		ValidateNode(config), Probe(config, target, "redaction-test", 100),
		ProbeDelay(config, target, "redaction-delay", 100, false),
		ValidateRuntimeOptions(`{"rules":["CANARY-RULE-1234"],"definition":"CANARY-PASSWORD-1234"}`),
		ImportProfile("proxies: [CANARY-PASSWORD-1234"),
		PlanRemoteProviders(`proxy-providers: {CANARY-NAME-1234: {type: file, path: '../CANARY-SUB-TOKEN-1234'}}`),
		ResolveRemoteProviders(`proxy-providers: {x: {type: file, path: CANARY-PASSWORD-1234}}`, "{}"),
		probeError("redaction-test", context.Background(), errors.New(target+" CANARY-PASSWORD-1234"), 0),
		Info(),
	} {
		assertNoCanaries(t, out)
	}
	// Import/resolve success returns PRIVATE credential-bearing configuration,
	// deliberately outside the public-output invariant; never log these strings.
	privatePlan := PlanRemoteProviders(`proxy-providers: {fixture: {type: file, path: CANARY-SUB-TOKEN-1234.yaml}}`)
	if !strings.Contains(privatePlan, `"state":"passed"`) || !strings.Contains(privatePlan, "CANARY-SUB-TOKEN-1234") {
		t.Fatal("private file-provider plan lost its explicitly supplied logical path")
	}
	var got importedProfile
	_ = json.Unmarshal([]byte(ImportProfile(`proxies: [{name: fixture, type: anytls, server: example.invalid, port: 443, password: CANARY-PASSWORD-1234}]`)), &got)
	if got.State != "passed" || !strings.Contains(got.Nodes[0].Config, "CANARY-PASSWORD-1234") {
		t.Fatal("privacy guard broke the private import contract")
	}
}
