package mobile

import (
	"strings"
	"testing"
)

func TestPublicRulePayloadKeepsShortPrintableValues(t *testing.T) {
	for _, value := range []string{"youtube", "example.com", "192.0.2.0/24", "Global ads"} {
		if got := publicRulePayload(value); got != value {
			t.Fatalf("publicRulePayload(%q) = %q", value, got)
		}
	}
	for _, value := range []string{strings.Repeat("a", 257), "bad\nline", "tab\there"} {
		if got := publicRulePayload(value); got != "" {
			t.Fatalf("publicRulePayload(%q) = %q, want it withheld", value, got)
		}
	}
}
