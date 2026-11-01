// SPDX-License-Identifier: GPL-3.0-only
package mobile

import (
	"net"
	"testing"
	"time"
)

type deniedPlatform struct{}

func (deniedPlatform) ConnectionOwner(int, string, string) string { return "" }

func (deniedPlatform) Protect(int) bool          { return false }
func (deniedPlatform) Lookup(string, int) string { return "" }

func TestSocketProtectionFailureIsALocalRestriction(t *testing.T) {
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer listener.Close()
	SetPlatform(deniedPlatform{})
	defer SetPlatform(nil)
	config := asJSON(t, node{Type: "anytls", Server: "127.0.0.1", Port: listener.Addr().(*net.TCPAddr).Port, Password: "private-test"})
	reply := decoded(t, Probe(config, "http://127.0.0.1:1/", "protection-denied", 1000))
	if reply.State != "blocked" || reply.Code != "local_network_restriction" {
		t.Fatalf("local policy failure blamed on node: %+v", reply)
	}
	_ = listener.(*net.TCPListener).SetDeadline(time.Now().Add(100 * time.Millisecond))
	if conn, err := listener.Accept(); err == nil {
		_ = conn.Close()
		t.Fatal("unprotected connection was allowed")
	}
}
