// SPDX-License-Identifier: GPL-3.0-only
package mobile

import (
	"encoding/json"
	"errors"
	"io"
	"strings"
	"unicode"
	"unicode/utf8"
)

// Decode before handing input to struct decoders or mihomo's weakly typed parser.
// Duplicate names, case aliases, nulls and container type confusion must not gain
// different meanings at the two sides of that boundary.
func strictNodeObject(text string) (map[string]any, error) {
	invalid := errors.New("config_invalid")
	if len(text) == 0 || len(text) > 96*1024 || !utf8.ValidString(text) {
		return nil, invalid
	}
	decoder := json.NewDecoder(strings.NewReader(text))
	decoder.UseNumber()
	count := 0
	var read func(int) (any, error)
	read = func(depth int) (any, error) {
		count++
		if depth > 8 || count > 4096 {
			return nil, invalid
		}
		token, err := decoder.Token()
		if err != nil {
			return nil, invalid
		}
		switch value := token.(type) {
		case json.Delim:
			switch value {
			case '{':
				object := make(map[string]any)
				for decoder.More() {
					keyToken, err := decoder.Token()
					key, ok := keyToken.(string)
					if err != nil || !ok || len(key) > 128 || len(object) >= 100 {
						return nil, invalid
					}
					if _, exists := object[key]; exists {
						return nil, invalid
					}
					child, err := read(depth + 1)
					if err != nil {
						return nil, invalid
					}
					object[key] = child
				}
				end, err := decoder.Token()
				if err != nil || end != json.Delim('}') {
					return nil, invalid
				}
				return object, nil
			case '[':
				array := make([]any, 0)
				for decoder.More() {
					if len(array) >= 512 {
						return nil, invalid
					}
					child, err := read(depth + 1)
					if err != nil {
						return nil, invalid
					}
					array = append(array, child)
				}
				end, err := decoder.Token()
				if err != nil || end != json.Delim(']') {
					return nil, invalid
				}
				return array, nil
			}
		case string:
			if len(value) > 16384 {
				return nil, invalid
			}
			return value, nil
		case nil, bool, json.Number:
			return value, nil
		}
		return nil, invalid
	}
	value, err := read(0)
	object, ok := value.(map[string]any)
	if err != nil || !ok {
		return nil, invalid
	}
	if _, err := decoder.Token(); err != io.EOF {
		return nil, invalid
	}
	// Mihomo treats absent/null HY2 bandwidth as automatic congestion control.
	// Keep this exception at the root and protocol-specific; credentials, TLS
	// switches and nested containers must still reject null/type confusion.
	if object["type"] == "hysteria2" {
		for _, key := range []string{"up", "down"} {
			if object[key] == nil {
				delete(object, key)
			}
		}
	}
	var nonNull func(any) bool
	nonNull = func(v any) bool {
		if v == nil {
			return false
		}
		switch x := v.(type) {
		case map[string]any:
			for _, child := range x {
				if !nonNull(child) {
					return false
				}
			}
		case []any:
			for _, child := range x {
				if !nonNull(child) {
					return false
				}
			}
		}
		return true
	}
	if !nonNull(object) {
		return nil, invalid
	}
	return object, nil
}

func hasControl(text string) bool {
	return strings.IndexFunc(text, func(r rune) bool { return unicode.IsControl(r) || unicode.Is(unicode.Cf, r) }) >= 0
}

func httpToken(text string) bool {
	if text == "" || len(text) > 128 {
		return false
	}
	for _, r := range text {
		if !(r >= 'a' && r <= 'z' || r >= 'A' && r <= 'Z' || r >= '0' && r <= '9' || strings.ContainsRune("!#$%&'*+-.^_`|~", r)) {
			return false
		}
	}
	return true
}

func safeHeaders(value any, listValues bool) bool {
	headers, ok := value.(map[string]any)
	if !ok || len(headers) > 32 {
		return false
	}
	seen := make(map[string]bool)
	total := 0
	for key, value := range headers {
		lower := strings.ToLower(key)
		if !httpToken(key) || seen[lower] {
			return false
		}
		seen[lower] = true
		values := []any{value}
		if listValues {
			var ok bool
			values, ok = value.([]any)
			if !ok || len(values) > 16 {
				return false
			}
		}
		for _, value := range values {
			text, ok := value.(string)
			if !ok || len(text) > 4096 || hasControl(text) {
				return false
			}
			total += len(key) + len(text) + 4
			if total > 8192 {
				return false
			}
		}
	}
	return true
}
