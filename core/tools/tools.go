//go:build tools

package tools

// Retain the exact binding runtime used by gomobile in the module lock file.
import _ "golang.org/x/mobile/bind"
