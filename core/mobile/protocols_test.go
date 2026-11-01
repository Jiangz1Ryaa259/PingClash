// SPDX-License-Identifier: GPL-3.0-only
package mobile

import "testing"

func TestCommonProtocolConfigurationsUsePinnedMihomoParser(t *testing.T) {
	for _, config := range []string{
		`{"type":"ss","server":"127.0.0.1","port":443,"cipher":"aes-128-gcm","password":"local-test","udp":true}`,
		`{"type":"trojan","server":"127.0.0.1","port":443,"sni":"localhost","password":"local-test","udp":true}`,
		`{"type":"vmess","server":"127.0.0.1","port":443,"uuid":"ca3ea70c-6845-4e3b-88d4-e4ac64ffbfa8","alterId":0,"cipher":"auto","tls":true,"servername":"localhost","network":"ws","ws-opts":{"path":"/test","headers":{"Host":"localhost"}}}`,
		`{"type":"vless","server":"127.0.0.1","port":443,"uuid":"ca3ea70c-6845-4e3b-88d4-e4ac64ffbfa8","tls":true,"servername":"localhost"}`,
		`{"type":"hysteria2","server":"127.0.0.1","port":443,"password":"local-test","sni":"localhost"}`,
		`{"type":"tuic","server":"127.0.0.1","port":443,"uuid":"ca3ea70c-6845-4e3b-88d4-e4ac64ffbfa8","password":"local-test","sni":"localhost","congestion-controller":"cubic"}`,
	} {
		if decoded(t, ValidateNode(config)).Code != "config_valid_not_connected" {
			t.Errorf("protocol configuration rejected: %s", config)
		}
	}
}

func TestExtendedProtocolsRejectFilesExternalPluginsAndRouting(t *testing.T) {
	for _, config := range []string{
		`{"type":"trojan","server":"127.0.0.1","port":443,"password":"local-test","certificate":"C:/private.pem"}`,
		`{"type":"ss","server":"127.0.0.1","port":443,"cipher":"aes-128-gcm","password":"local-test","plugin":"run-external.exe"}`,
		`{"type":"vless","server":"127.0.0.1","port":443,"uuid":"ca3ea70c-6845-4e3b-88d4-e4ac64ffbfa8","dialer-proxy":"DIRECT"}`,
	} {
		if decoded(t, ValidateNode(config)).Code != "config_invalid" {
			t.Fatal("unsafe configuration accepted")
		}
	}
}
