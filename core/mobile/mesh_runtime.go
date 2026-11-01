// SPDX-License-Identifier: GPL-3.0-only
package mobile

import (
	"context"
	"crypto/rand"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"net"
	"path/filepath"
	"runtime"
	"sync"
	"sync/atomic"

	"github.com/metacubex/mihomo/adapter"
	C "github.com/metacubex/mihomo/constant"
	"github.com/metacubex/mihomo/tunnel"
)

// Construction of mesh adapters registers named DNS clients in upstream.
// Give each backend a private namespace, and share equivalent live backends
// across validation/probes/reloads. An uncommitted profile cannot replace the
// DNS transport or lock the state directory of the currently working profile.
var meshPool = struct {
	sync.Mutex
	entries map[[32]byte]*meshEntry
}{entries: map[[32]byte]*meshEntry{}}

type meshEntry struct {
	key       [32]byte
	adapter   C.ProxyAdapter
	namespace string
	users     int
	closed    bool
}
type meshLease struct {
	C.ProxyAdapter
	entry  *meshEntry
	name   string
	closed atomic.Bool
}

func meshKind(kind string) bool {
	return kind == "tailscale" || kind == "easytier" || kind == "zerotier"
}
func acquireMesh(input map[string]any, via C.Dialer) (C.ProxyAdapter, error) {
	material := map[string]any{}
	for k, v := range input {
		if k != "name" && k != "state-dir" {
			material[k] = v
		}
	}
	// Private chained probes have their own dialer context. The full native
	// executor uses named routes through its singleton tunnel instead.
	if via != nil {
		material["route-context"] = fmt.Sprintf("%p", via)
	}
	b, e := json.Marshal(material)
	if e != nil {
		return nil, e
	}
	key := sha256.Sum256(b)
	meshPool.Lock()
	defer meshPool.Unlock()
	entry := meshPool.entries[key]
	if entry == nil {
		mapping := map[string]any{}
		for k, v := range input {
			mapping[k] = v
		}
		nonce := make([]byte, 12)
		if _, e = rand.Read(nonce); e != nil {
			return nil, e
		}
		namespace := "mesh-" + hex.EncodeToString(nonce)
		mapping["name"] = namespace
		mapping["state-dir"] = filepath.Join("runtime-state", stringOption(mapping, "type"), hex.EncodeToString(key[:16]))
		native, e := adapter.ParseProxy(mapping, adapter.WithDialerForAPI(via), adapter.WithTunnelForAPI(tunnel.Tunnel))
		if e != nil {
			return nil, e
		}
		entry = &meshEntry{key: key, adapter: native, namespace: namespace}
		meshPool.entries[key] = entry
	}
	entry.users++
	lease := &meshLease{ProxyAdapter: entry.adapter, entry: entry, name: stringOption(input, "name")}
	runtime.SetFinalizer(lease, func(p *meshLease) { _ = p.Close() })
	return lease, nil
}
func (p *meshLease) Name() string { return p.name }
func (p *meshLease) Close() error {
	if p.closed.CompareAndSwap(false, true) {
		runtime.SetFinalizer(p, nil)
		releaseMesh(p.entry)
	}
	return nil
}
func retainMesh(entry *meshEntry) bool {
	meshPool.Lock()
	defer meshPool.Unlock()
	if entry.closed {
		return false
	}
	entry.users++
	return true
}
func releaseMesh(entry *meshEntry) {
	meshPool.Lock()
	entry.users--
	closeNow := entry.users == 0
	if closeNow {
		entry.closed = true
		delete(meshPool.entries, entry.key)
	}
	meshPool.Unlock()
	if closeNow {
		_ = entry.adapter.Close()
	}
}
func (p *meshLease) DialContext(ctx context.Context, m *C.Metadata) (C.Conn, error) {
	if p.closed.Load() || !retainMesh(p.entry) {
		return nil, net.ErrClosed
	}
	conn, e := p.ProxyAdapter.DialContext(ctx, m)
	if e != nil {
		releaseMesh(p.entry)
		return nil, e
	}
	owned := &meshConn{Conn: conn, entry: p.entry}
	runtime.SetFinalizer(owned, func(c *meshConn) { _ = c.Close() })
	return owned, nil
}
func (p *meshLease) ListenPacketContext(ctx context.Context, m *C.Metadata) (C.PacketConn, error) {
	if p.closed.Load() || !retainMesh(p.entry) {
		return nil, net.ErrClosed
	}
	conn, e := p.ProxyAdapter.ListenPacketContext(ctx, m)
	if e != nil {
		releaseMesh(p.entry)
		return nil, e
	}
	owned := &meshPacketConn{PacketConn: conn, entry: p.entry}
	runtime.SetFinalizer(owned, func(c *meshPacketConn) { _ = c.Close() })
	return owned, nil
}

type meshConn struct {
	C.Conn
	entry *meshEntry
	once  sync.Once
	err   error
}

func (c *meshConn) Close() error {
	c.once.Do(func() { runtime.SetFinalizer(c, nil); c.err = c.Conn.Close(); releaseMesh(c.entry) })
	return c.err
}

type meshPacketConn struct {
	C.PacketConn
	entry *meshEntry
	once  sync.Once
	err   error
}

// Expose the mesh connection for buffer sizing; never replaceable, so the
// mesh lease is released only when this connection closes.
func (c *meshConn) Upstream() any       { return c.Conn }
func (c *meshPacketConn) Upstream() any { return c.PacketConn }

func (c *meshPacketConn) Close() error {
	c.once.Do(func() { runtime.SetFinalizer(c, nil); c.err = c.PacketConn.Close(); releaseMesh(c.entry) })
	return c.err
}
