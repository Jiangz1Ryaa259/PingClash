// SPDX-License-Identifier: GPL-3.0-only
package mobile

import (
	"fmt"
	"io"
	"os"
	"regexp"
	"runtime/debug"
	"strings"
	"sync"
	"time"
)

// A panic while serving one connection must not end the whole app, which also
// tears down the VPN. Entry points and the goroutines PingClash starts for traffic
// defer guard: the connection is closed, the panic is kept (with its stack) for the
// crash report, and the tunnel keeps running. Goroutines started inside Mihomo or
// sing (for example the download half of a relay) cannot be guarded here; their
// panics are still fatal and reach SetCrashOutput.

const (
	maxRecoveredKinds = 6
	maxRecoveredStack = 16 * 1024
)

type recoveredPanic struct {
	first, last time.Time
	where       string
	value       string
	stack       string
	count       int
}

var recovered struct {
	sync.Mutex
	path    string
	entries []*recoveredPanic
}

// SetRecoveredPanicOutput names the file that keeps panics guard contained during
// this run (the app reads it at the next launch). An empty path keeps them in memory.
func SetRecoveredPanicOutput(path string) bool {
	recovered.Lock()
	defer recovered.Unlock()
	recovered.path = path
	recovered.entries = nil
	if path == "" {
		return true
	}
	if err := os.Remove(path); err != nil && !os.IsNotExist(err) {
		return false
	}
	return true
}

// guard is deferred directly (recover only works in the deferred call itself):
//
//	defer guard("tcp", conn)
func guard(where string, closers ...io.Closer) {
	if value := recover(); value != nil {
		contain(where, value, debug.Stack(), closers...)
	}
}

func contain(where string, value any, stack []byte, closers ...io.Closer) {
	for _, c := range closers {
		if c != nil {
			func() {
				defer func() { _ = recover() }()
				_ = c.Close()
			}()
		}
	}
	recordRecovered(where, panicSummary(value), stack)
}

var safeBufferPanic = regexp.MustCompile(`^buffer overflow: capacity [0-9]+,(start|end) [0-9]+, need [0-9]+$`)

func panicSummary(value any) string {
	if text, ok := value.(string); ok && len(text) < 256 && safeBufferPanic.MatchString(text) {
		return text
	}
	// Panic/error strings may contain subscriptions, credentials or raw config.
	// Preserve the type and stack, never invoke a Stringer or persist its value.
	return fmt.Sprintf("panic value of type %T", value)
}

func recordRecovered(where, value string, stack []byte) {
	now := time.Now()
	recovered.Lock()
	defer recovered.Unlock()
	for _, e := range recovered.entries {
		if e.where == where && e.value == value {
			e.count++
			e.last = now
			// Repeats only update the count; write at 10, 100, 1000... so a bug hit
			// by every connection cannot turn into continuous disk writes.
			if isPowerOfTen(e.count) {
				writeRecoveredLocked()
			}
			return
		}
	}
	if len(recovered.entries) >= maxRecoveredKinds {
		return
	}
	if len(stack) > maxRecoveredStack {
		stack = stack[:maxRecoveredStack]
	}
	recovered.entries = append(recovered.entries, &recoveredPanic{now, now, where, value, string(stack), 1})
	writeRecoveredLocked()
}

func isPowerOfTen(n int) bool {
	for n >= 10 && n%10 == 0 {
		n /= 10
	}
	return n == 1
}

func writeRecoveredLocked() {
	if recovered.path == "" {
		return
	}
	var b strings.Builder
	for _, e := range recovered.entries {
		fmt.Fprintf(&b, "recovered panic in %s: %s\ncount %d, first %s, last %s\n\n%s\n",
			e.where, e.value, e.count, e.first.UTC().Format(time.RFC3339), e.last.UTC().Format(time.RFC3339), e.stack)
	}
	tmp := recovered.path + ".tmp"
	if os.WriteFile(tmp, []byte(b.String()), 0o600) == nil {
		_ = os.Rename(tmp, recovered.path)
	}
}

// RecoveredPanicCount reports how many panics guard contained in this run.
func RecoveredPanicCount() int {
	recovered.Lock()
	defer recovered.Unlock()
	total := 0
	for _, e := range recovered.entries {
		total += e.count
	}
	return total
}
