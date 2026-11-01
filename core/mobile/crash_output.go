// SPDX-License-Identifier: GPL-3.0-only
package mobile

import (
	"os"
	"runtime/debug"
	"sync"
)

var crashOutputMu sync.Mutex

// SetCrashOutput also writes unrecovered panics and fatal runtime errors to path (they still
// go to standard error), so the app can show the cause after the next launch. The file is
// truncated: the app collects the previous report before arming it. An empty path stops.
func SetCrashOutput(path string) bool {
	crashOutputMu.Lock()
	defer crashOutputMu.Unlock()
	if path == "" {
		return debug.SetCrashOutput(nil, debug.CrashOptions{}) == nil
	}
	f, err := os.OpenFile(path, os.O_CREATE|os.O_TRUNC|os.O_WRONLY, 0o600)
	if err != nil {
		return false
	}
	// SetCrashOutput duplicates the descriptor, so this handle can be closed at once.
	defer f.Close()
	return debug.SetCrashOutput(f, debug.CrashOptions{}) == nil
}
