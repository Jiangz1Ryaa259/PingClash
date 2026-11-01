// SPDX-License-Identifier: GPL-3.0-only
package mobile

import (
	"go/ast"
	"go/parser"
	"go/token"
	"io"
	"os"
	"path/filepath"
	"strings"
	"sync/atomic"
	"testing"
)

// Every connection wrapper in this package must expose what it wraps. A wrapper
// that hides its connection also hides the protocol's buffer headroom from sing,
// which is how VLESS Vision panicked ("buffer overflow ... need 2") and ended
// the app. New wrappers fail here until they add Upstream().
func TestEveryConnectionWrapperExposesUpstream(t *testing.T) {
	wrapped := map[string]bool{"C.Conn": true, "C.PacketConn": true, "net.Conn": true, "net.PacketConn": true,
		"N.ExtendedConn": true, "N.PacketConn": true, "N.NetPacketConn": true}
	files, _ := filepath.Glob("*.go")
	fset := token.NewFileSet()
	wrappers, upstream := map[string]string{}, map[string]bool{}
	for _, name := range files {
		if strings.HasSuffix(name, "_test.go") {
			continue
		}
		file, err := parser.ParseFile(fset, name, nil, 0)
		if err != nil {
			t.Fatal(err)
		}
		ast.Inspect(file, func(n ast.Node) bool {
			switch node := n.(type) {
			case *ast.TypeSpec:
				if st, ok := node.Type.(*ast.StructType); ok {
					for _, field := range st.Fields.List {
						if sel, ok := field.Type.(*ast.SelectorExpr); ok && len(field.Names) == 0 {
							if pkg, ok := sel.X.(*ast.Ident); ok && wrapped[pkg.Name+"."+sel.Sel.Name] {
								wrappers[node.Name.Name] = name
							}
						}
					}
				}
			case *ast.FuncDecl:
				if node.Recv != nil && node.Name.Name == "Upstream" && len(node.Recv.List) == 1 {
					typ := node.Recv.List[0].Type
					if star, ok := typ.(*ast.StarExpr); ok {
						typ = star.X
					}
					if id, ok := typ.(*ast.Ident); ok {
						upstream[id.Name] = true
					}
				}
			}
			return true
		})
	}
	if len(wrappers) < 8 {
		t.Fatalf("audit found only %d wrappers; the scan is probably broken", len(wrappers))
	}
	for typ, file := range wrappers {
		if !upstream[typ] {
			t.Errorf("%s (%s) embeds a connection but has no Upstream() any", typ, file)
		}
	}
}

type panickingConn struct {
	closed atomic.Bool
}

func (c *panickingConn) Write([]byte) (int, error) { panic("fixture-encoder-panic") }
func (c *panickingConn) Close() error              { c.closed.Store(true); return nil }

func serveGuarded(c *panickingConn) {
	defer guard("tcp", c)
	_, _ = io.WriteString(c, "payload")
}

// A protocol panic while serving one connection closes that connection and is kept
// for the crash report; the process (and every other connection) keeps running.
func TestGuardContainsConnectionPanicsAndCountsRepeats(t *testing.T) {
	path := filepath.Join(t.TempDir(), "core-recovered.txt")
	if !SetRecoveredPanicOutput(path) {
		t.Fatal("cannot arm recovered output")
	}
	defer SetRecoveredPanicOutput("")
	first := &panickingConn{}
	serveGuarded(first)
	if !first.closed.Load() {
		t.Fatal("the failing connection was not closed")
	}
	report, err := os.ReadFile(path)
	if err != nil || !strings.Contains(string(report), "recovered panic in tcp: panic value of type string") ||
		!strings.Contains(string(report), "serveGuarded") {
		t.Fatalf("panic not recorded with its stack: %v %q", err, report)
	}
	for i := 0; i < 9; i++ {
		serveGuarded(&panickingConn{})
	}
	if RecoveredPanicCount() != 10 {
		t.Fatalf("count = %d, want 10", RecoveredPanicCount())
	}
	report, _ = os.ReadFile(path)
	if !strings.Contains(string(report), "count 10") || strings.Count(string(report), "recovered panic") != 1 {
		t.Fatalf("repeats should update one entry: %q", report)
	}
	for _, n := range []int{1, 10, 100, 1000} {
		if !isPowerOfTen(n) {
			t.Fatalf("%d", n)
		}
	}
	for _, n := range []int{2, 11, 20, 110} {
		if isPowerOfTen(n) {
			t.Fatalf("%d", n)
		}
	}
}
