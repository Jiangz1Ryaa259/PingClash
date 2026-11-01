// SPDX-License-Identifier: GPL-3.0-only
package mobile

import (
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"
)

// A fatal panic in any goroutine reaches the crash file, so the app can report it later.
func TestCrashOutputCapturesUnrecoveredPanic(t *testing.T) {
	if os.Getenv("PINGCLASH_CRASH_CHILD") != "" {
		if !SetCrashOutput(os.Getenv("PINGCLASH_CRASH_CHILD")) {
			os.Exit(3)
		}
		done := make(chan struct{})
		go func() { panic("fixture-core-panic") }()
		<-done
	}
	path := filepath.Join(t.TempDir(), "core-live.txt")
	command := exec.Command(os.Args[0], "-test.run=^TestCrashOutputCapturesUnrecoveredPanic$")
	command.Env = append(os.Environ(), "PINGCLASH_CRASH_CHILD="+path)
	if err := command.Run(); err == nil {
		t.Fatal("child did not crash")
	}
	report, err := os.ReadFile(path)
	if err != nil || !strings.Contains(string(report), "fixture-core-panic") || !strings.Contains(string(report), "goroutine") {
		t.Fatalf("crash file missing the panic: %v %q", err, report)
	}
	if !SetCrashOutput(path) {
		t.Fatal("re-arming failed")
	}
	if info, _ := os.Stat(path); info.Size() != 0 {
		t.Fatal("arming did not start a fresh file")
	}
	if !SetCrashOutput("") {
		t.Fatal("disarming failed")
	}
}
