// SPDX-License-Identifier: GPL-3.0-only
package mobile

import (
	"encoding/json"
	"go/ast"
	"go/parser"
	"go/token"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"testing"
)

// The mobile architecture deliberately has no controller or local proxy server.
// Adding one requires a separate authentication design, not a loopback exception.
func TestMobileBridgeDoesNotInitializeListeners(t *testing.T) {
	entries, err := os.ReadDir(".")
	if err != nil {
		t.Fatal(err)
	}
	for _, entry := range entries {
		name := entry.Name()
		if !strings.HasSuffix(name, ".go") || strings.HasSuffix(name, "_test.go") {
			continue
		}
		file, err := parser.ParseFile(token.NewFileSet(), name, nil, 0)
		if err != nil {
			t.Fatal(err)
		}
		imports := map[string]string{}
		for _, item := range file.Imports {
			path, _ := strconv.Unquote(item.Path.Value)
			parserOnly := (name == "native_dns_runtime.go" || name == "native_config_api.go" || name == "sniffer_runtime.go" || name == "native_full_runtime.go") && path == "github.com/metacubex/mihomo/config"
			ownedExecutor := name == "native_full_runtime.go" && path == "github.com/metacubex/mihomo/hub/executor"
			if !parserOnly && !ownedExecutor && (strings.HasPrefix(path, "github.com/metacubex/mihomo/hub") || strings.HasPrefix(path, "github.com/metacubex/mihomo/listener") || path == "github.com/metacubex/mihomo/config") {
				t.Errorf("%s imports a global configuration/listener entry point: %s", name, path)
			}
			alias := filepath.Base(path)
			if item.Name != nil {
				alias = item.Name.Name
			}
			imports[alias] = path
		}
		ast.Inspect(file, func(node ast.Node) bool {
			call, ok := node.(*ast.CallExpr)
			if !ok {
				return true
			}
			selector, ok := call.Fun.(*ast.SelectorExpr)
			if !ok {
				return true
			}
			pkg, ok := selector.X.(*ast.Ident)
			if !ok {
				return true
			}
			path := imports[pkg.Name]
			if name != "native_full_runtime.go" && path == "github.com/metacubex/mihomo/config" && (selector.Sel.Name == "Parse" || selector.Sel.Name == "ParseRawConfig") {
				t.Errorf("%s calls the process-global configuration parser", name)
			}
			if (path == "net" && (strings.HasPrefix(selector.Sel.Name, "Listen"))) ||
				(path == "net/http" && (selector.Sel.Name == "Serve" || strings.HasPrefix(selector.Sel.Name, "ListenAndServe"))) ||
				((path == "syscall" || path == "golang.org/x/sys/unix") && selector.Sel.Name == "Listen") {
				t.Errorf("%s introduces a listening service: %s.%s", name, pkg.Name, selector.Sel.Name)
			}
			return true
		})
	}
}

func TestSubscriptionCannotEnableGlobalListeners(t *testing.T) {
	raw := `mixed-port: 17890
socks-port: 17891
port: 17892
external-controller: 0.0.0.0:19090
secret: ""
allow-lan: true
bind-address: "*"
proxies:
  - {name: synthetic, type: anytls, server: example.invalid, port: 443, password: test-only}
proxy-groups:
  - {name: Main, type: select, proxies: [synthetic]}
`
	var profile importedProfile
	if err := json.Unmarshal([]byte(ImportProfile(raw)), &profile); err != nil {
		t.Fatal(err)
	}
	if profile.State != "passed" || len(profile.Nodes) != 1 {
		t.Fatal("synthetic profile did not import")
	}
	var definition map[string]json.RawMessage
	if err := json.Unmarshal([]byte(profile.Definition), &definition); err != nil {
		t.Fatal(err)
	}
	for _, key := range []string{"mixed-port", "socks-port", "port", "external-controller", "secret", "allow-lan", "bind-address"} {
		if _, exists := definition[key]; exists {
			t.Fatalf("global listener directive retained: %s", key)
		}
	}
}
