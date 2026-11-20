package mobile

import (
	"encoding/json"
	"testing"
)

// OpenVPN key material must be inline PEM: a path to a file on the device is refused.
func TestOpenVPNKeyFieldsAreInlinePEMOnly(t *testing.T) {
	for _, field := range []string{"ca", "cert", "key", "tls-auth", "tls-crypt", "tls-crypt-v2"} {
		raw, _ := json.Marshal(map[string]any{"name": "fixture", "type": "openvpn", "server": "127.0.0.1",
			"port": 1194, field: "/data/data/dev.pingclash.app/files/secret.pem"})
		if p, e := parseNode(string(raw)); e == nil {
			_ = p.Close()
			t.Fatalf("%s accepted a file path", field)
		}
	}
	if !inlinePEM("-----BEGIN OpenVPN Static key V1-----\n00\n-----END OpenVPN Static key V1-----\n") {
		t.Fatal("an OpenVPN static key is inline PEM")
	}
}
