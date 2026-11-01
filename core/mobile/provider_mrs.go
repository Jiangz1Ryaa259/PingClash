// SPDX-License-Identifier: GPL-3.0-only
package mobile

import (
	"bytes"
	"encoding/base64"
	"encoding/binary"
	"errors"
	"io"
	"math/bits"
	"net/netip"
	"strings"

	"github.com/klauspost/compress/zstd"
	RP "github.com/metacubex/mihomo/rules/provider"
)

const mrsBodyPrefix = "pingclash-mrs-v1:base64:"

func unpackProviderBody(body string, mrs bool, maximum int) ([]byte, error) {
	if !mrs {
		return unpackBounded(body, packedProviderPrefix, maximum, maxProviderBridgeBytes)
	}
	if !strings.HasPrefix(body, mrsBodyPrefix) || len(body) > len(mrsBodyPrefix)+base64.StdEncoding.EncodedLen(maximum) {
		return nil, errors.New("provider_rule_document")
	}
	raw, e := base64.StdEncoding.DecodeString(strings.TrimPrefix(body, mrsBodyPrefix))
	if e != nil {
		return nil, errors.New("provider_rule_document")
	}
	if len(raw) > maximum {
		return nil, errors.New("provider_size_limit")
	}
	return raw, nil
}

// The native decoder allocates arrays from serialized lengths. Preflight all
// lengths and the succinct tree before handing bytes to native FromMrs/DumpMrs.
// No filesystem, provider registry, downloader or native config is activated.
func decodeMrsEntries(body []byte, behavior string) (entries []any, failure error) {
	invalid := errors.New("provider_rule_document")
	defer func() {
		if recover() != nil {
			entries = nil
			failure = invalid
		}
	}()
	if len(body) == 0 || len(body) > maxRuleProviderBytes {
		return nil, invalid
	}
	decoder, e := zstd.NewReader(bytes.NewReader(body), zstd.WithDecoderConcurrency(1),
		zstd.WithDecoderMaxMemory(64<<20), zstd.WithDecoderMaxWindow(64<<20))
	if e != nil {
		return nil, invalid
	}
	defer decoder.Close()
	raw, e := io.ReadAll(io.LimitReader(decoder, maxRuleDataBytes+1))
	if e != nil || len(raw) > maxRuleDataBytes || len(raw) < 22 || !bytes.Equal(raw[:4], RP.MrsMagicBytes[:]) {
		return nil, invalid
	}
	byteBehavior := byte(0)
	if behavior == "ipcidr" {
		byteBehavior = 1
	} else if behavior != "domain" {
		return nil, invalid
	}
	if raw[4] != byteBehavior {
		return nil, errors.New("provider_rule_type")
	}
	count := int64(binary.BigEndian.Uint64(raw[5:13]))
	extra := int64(binary.BigEndian.Uint64(raw[13:21]))
	if count < 1 || count > maxProviderRules {
		return nil, errors.New("provider_rule_limit")
	}
	if extra < 0 || extra > 65536 || extra > int64(len(raw)-22) {
		return nil, invalid
	}
	payload := raw[21+int(extra):]
	if behavior == "domain" {
		if !validMrsDomain(payload, int(count)) {
			return nil, invalid
		}
	} else if !validMrsCIDR(payload) {
		return nil, invalid
	}
	var dump interface {
		FromMrs(io.Reader, int) error
		DumpMrs(func(string) bool)
	}
	if behavior == "domain" {
		dump = RP.NewDomainStrategy()
	} else {
		dump = RP.NewIPCidrStrategy()
	}
	reader := bytes.NewReader(payload)
	if dump.FromMrs(reader, int(count)) != nil || reader.Len() != 0 {
		return nil, invalid
	}
	used := 0
	dump.DumpMrs(func(line string) bool {
		used += len(line)
		if len(entries) >= maxProviderRules || used > maxRuleProviderBytes || len(line) > 2048 || hasControl(line) {
			failure = errors.New("provider_rule_limit")
			return false
		}
		entries = append(entries, line)
		return true
	})
	if failure != nil {
		return nil, failure
	}
	if len(entries) == 0 {
		return nil, invalid
	}
	return entries, nil
}

func validMrsCIDR(raw []byte) bool {
	if len(raw) < 9 || raw[0] != 1 {
		return false
	}
	count := int64(binary.BigEndian.Uint64(raw[1:9]))
	if count < 1 || count > maxProviderRules || count*32 != int64(len(raw)-9) {
		return false
	}
	var previous netip.Addr
	for offset := 9; offset < len(raw); offset += 32 {
		from := netip.AddrFrom16([16]byte(raw[offset : offset+16])).Unmap()
		to := netip.AddrFrom16([16]byte(raw[offset+16 : offset+32])).Unmap()
		if from.Is4() != to.Is4() || from.Compare(to) > 0 || previous.IsValid() && previous.Compare(from) >= 0 {
			return false
		}
		previous = to
	}
	return true
}

func validMrsDomain(raw []byte, count int) bool {
	if len(raw) < 25 || raw[0] != 1 {
		return false
	}
	offset := 1
	read := func(width int) ([]byte, bool) {
		if len(raw)-offset < 8 {
			return nil, false
		}
		n := int64(binary.BigEndian.Uint64(raw[offset : offset+8]))
		offset += 8
		if n < 1 || n > int64((len(raw)-offset)/width) {
			return nil, false
		}
		data := raw[offset : offset+int(n)*width]
		offset += len(data)
		return data, true
	}
	leaves, ok := read(8)
	if !ok {
		return false
	}
	bitmap, ok := read(8)
	if !ok {
		return false
	}
	labels, ok := read(1)
	if !ok || offset != len(raw) || len(labels) > maxRuleProviderBytes {
		return false
	}
	nodes := len(labels) + 1
	if len(leaves)/8 != (nodes+63)/64 || len(bitmap)/8 != (2*len(labels)+1+63)/64 {
		return false
	}
	bit := func(data []byte, index int) bool {
		return binary.BigEndian.Uint64(data[index/64*8:])&(uint64(1)<<uint(index%64)) != 0
	}
	terminals := 0
	for i := 0; i < len(leaves); i += 8 {
		terminals += bits.OnesCount64(binary.BigEndian.Uint64(leaves[i:]))
	}
	if terminals < 1 || terminals > count*2 {
		return false
	}
	for i := nodes; i < len(leaves)*8; i++ {
		if bit(leaves, i) {
			return false
		}
	}
	for i := 2*len(labels) + 1; i < len(bitmap)*8; i++ {
		if bit(bitmap, i) {
			return false
		}
	}
	// LOUDS degree sequence is breadth-first. Every child must exist exactly
	// once, and depth is bounded before the native recursive domain dumper runs.
	depth := make([]uint16, nodes)
	next := 1
	position := 0
	expanded := 0
	for node := 0; node < nodes; node++ {
		if node >= next {
			return false
		}
		if bit(leaves, node) {
			if node == 0 {
				return false
			}
			expanded += int(depth[node])
			if expanded > maxRuleProviderBytes {
				return false
			}
		}
		lastLabel := -1
		for position < 2*len(labels)+1 && !bit(bitmap, position) {
			if next >= nodes || depth[node] >= 1024 {
				return false
			}
			label := int(labels[next-1])
			if label <= lastLabel || label < 32 || label == 127 {
				return false
			}
			lastLabel = label
			depth[next] = depth[node] + 1
			next++
			position++
		}
		if position >= 2*len(labels)+1 {
			return false
		}
		position++
	}
	return next == nodes && position == 2*len(labels)+1
}
