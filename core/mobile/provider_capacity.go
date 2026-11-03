// SPDX-License-Identifier: GPL-3.0-only
package mobile

import (
	"bytes"
	"compress/gzip"
	"encoding/base64"
	"errors"
	"io"
	"strings"
)

const (
	maxRemoteProviders     = 128
	maxRuleProviderBytes   = 16 * 1024 * 1024
	maxProviderTotalBytes  = 32 * 1024 * 1024
	maxProviderBridgeBytes = 24 * 1024 * 1024
	maxPrivateProfileBytes = 12 * 1024 * 1024
	maxPrivateBridgeBytes  = 16 * 1024 * 1024
	maxRuleDataBytes       = 48 * 1024 * 1024
	maxPackedRuleDataBytes = 8 * 1024 * 1024
	// Sized for large airport subscriptions as mihomo loads them: regional and
	// service groups, thousands of nodes and rule lists in the tens of thousands.
	// Past a limit only the excess is dropped and counted, never the whole list.
	maxImportNodes     = 5000
	maxImportGroups    = 512
	maxImportRules     = 50000
	maxImportRuleBytes = 4 * 1024 * 1024
	maxImportNodeBytes = 8 * 1024 * 1024
	// The user's own rules come on top of the subscription's.
	maxRuntimeRules     = maxImportRules + 5000
	maxRuntimeRuleBytes = maxImportRuleBytes + 512*1024
	// Parser work bounds: alias expansion and lexical structure, not document size.
	maxYAMLElements      = 1000000
	maxYAMLStructure     = 2000000
	packedProviderPrefix = "pingclash-cache-v1:gzip:"
	packedRuleDataPrefix = "pingclash-rules-v1:gzip:"
)

func unpackBounded(text, prefix string, maximum, packedMaximum int) ([]byte, error) {
	if !strings.HasPrefix(text, prefix) {
		if len(text) > maximum {
			return nil, errors.New("provider_size_limit")
		}
		return []byte(text), nil
	}
	if len(text) > packedMaximum {
		return nil, errors.New("provider_cache_limit")
	}
	raw, e := base64.StdEncoding.DecodeString(strings.TrimPrefix(text, prefix))
	if e != nil {
		return nil, errors.New("provider_payload")
	}
	reader, e := gzip.NewReader(bytes.NewReader(raw))
	if e != nil {
		return nil, errors.New("provider_payload")
	}
	defer reader.Close()
	body, e := io.ReadAll(io.LimitReader(reader, int64(maximum)+1))
	if len(body) > maximum {
		return nil, errors.New("provider_size_limit")
	}
	if e != nil {
		return nil, errors.New("provider_payload")
	}
	return body, nil
}

func packRuleData(raw []byte) (string, error) {
	if len(raw) > maxRuleDataBytes {
		return "", errors.New("provider_config_limit")
	}
	var buffer bytes.Buffer
	writer := gzip.NewWriter(&buffer)
	if _, e := writer.Write(raw); e != nil {
		return "", e
	}
	if e := writer.Close(); e != nil {
		return "", e
	}
	encoded := packedRuleDataPrefix + base64.StdEncoding.EncodeToString(buffer.Bytes())
	if len(encoded) > maxPackedRuleDataBytes {
		return "", errors.New("provider_config_limit")
	}
	return encoded, nil
}

func decodeStoredRuleSets(encoded string) (map[string]storedRuleSet, error) {
	raw, e := unpackBounded(encoded, packedRuleDataPrefix, maxRuleDataBytes, maxPackedRuleDataBytes)
	if e != nil {
		return nil, e
	}
	return parseStoredRuleSets(raw)
}
