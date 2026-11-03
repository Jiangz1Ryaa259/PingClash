// SPDX-License-Identifier: GPL-3.0-only
package mobile

import (
	"encoding/json"
	"strings"
	"testing"
)

func TestExitTraceKeepsOnlyAddressCountryAndDataCentre(t *testing.T) {
	info := parseExitTrace("fl=12f\nh=www.cloudflare.com\nip=203.0.113.7\nts=1.2\nvisit_scheme=https\nuag=x\ncolo=HKG\nloc=HK\ntls=TLSv1.3\n")
	if info.IP != "203.0.113.7" || info.Country != "HK" || info.Colo != "HKG" {
		t.Fatalf("%+v", info)
	}
	v6 := parseExitTrace("ip=2001:db8::1\nloc=JP\n")
	if v6.IP != "2001:db8::1" || v6.Country != "JP" {
		t.Fatalf("%+v", v6)
	}
	for _, body := range []string{"", "ip=not-an-ip\nloc=HK", "<html>blocked</html>", "ip=203.0.113.7\nloc=<script>"} {
		bad := parseExitTrace(body)
		if bad.Country == "<script>" || bad.IP == "not-an-ip" {
			t.Fatalf("unvalidated value kept: %+v", bad)
		}
	}
}

func TestExitDetailsReadsTheTraceAndNeedsNoSessionWhileDisconnected(t *testing.T) {
	var info exitInfo
	if json.Unmarshal([]byte(ExitDetails("ip=203.0.113.7\nloc=HK\ncolo=HKG\n")), &info) != nil ||
		info.State != "passed" || info.IP != "203.0.113.7" || info.Country != "HK" ||
		info.Policy != "" || info.Node != "" {
		t.Fatalf("%+v", info)
	}
	for _, body := range []string{"", "<html>blocked</html>", "ip=203.0.113.7\n" + strings.Repeat("x", maxExitTraceBytes)} {
		if json.Unmarshal([]byte(ExitDetails(body)), &info) != nil || info.State != "failed" {
			t.Fatalf("%q: %+v", body, info)
		}
	}
}
