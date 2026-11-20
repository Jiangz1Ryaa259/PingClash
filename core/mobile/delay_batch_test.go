package mobile

import (
	"bufio"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"os"
	"strconv"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

// batchFixture serves a 204 target, an HTTP CONNECT proxy in front of it, a port that refuses
// and a listener that accepts and never answers (a node that times out).
type batchFixture struct {
	target     *httptest.Server
	proxyAddr  string
	silentAddr string
	closedPort int
	stop       func()
}

func newBatchFixture(t testing.TB) *batchFixture {
	target := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusNoContent)
	}))
	connect, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	silent, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	closed, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	closedPort := closed.Addr().(*net.TCPAddr).Port
	_ = closed.Close()
	var held sync.Map
	go func() {
		for {
			c, err := silent.Accept()
			if err != nil {
				return
			}
			held.Store(c, true)
		}
	}()
	go func() {
		for {
			c, err := connect.Accept()
			if err != nil {
				return
			}
			go serveConnect(c)
		}
	}()
	return &batchFixture{
		target:     target,
		proxyAddr:  connect.Addr().String(),
		silentAddr: silent.Addr().String(),
		closedPort: closedPort,
		stop: func() {
			target.Close()
			_ = connect.Close()
			_ = silent.Close()
			held.Range(func(c, _ any) bool { _ = c.(net.Conn).Close(); return true })
		},
	}
}

// serveConnect answers one CONNECT by piping to the requested address.
func serveConnect(c net.Conn) {
	defer c.Close()
	request, err := http.ReadRequest(bufio.NewReader(c))
	if err != nil || request.Method != http.MethodConnect {
		return
	}
	upstream, err := net.Dial("tcp", request.Host)
	if err != nil {
		return
	}
	defer upstream.Close()
	_, _ = io.WriteString(c, "HTTP/1.1 200 Connection established\r\n\r\n")
	go func() { _, _ = io.Copy(upstream, c) }()
	_, _ = io.Copy(c, upstream)
}

func (f *batchFixture) node(name, kind string) map[string]any {
	host, port := "127.0.0.1", 0
	switch kind {
	case "alive":
		_, p, _ := net.SplitHostPort(f.proxyAddr)
		port, _ = strconv.Atoi(p)
	case "silent":
		_, p, _ := net.SplitHostPort(f.silentAddr)
		port, _ = strconv.Atoi(p)
	default:
		port = f.closedPort
	}
	return map[string]any{"name": name, "type": "http", "server": host, "port": port}
}

func collectBatch(t testing.TB, runID string, deadline time.Duration) map[string]batchResult {
	results := map[string]batchResult{}
	end := time.Now().Add(deadline)
	for time.Now().Before(end) {
		var view struct {
			Done    bool          `json:"done"`
			Results []batchResult `json:"results"`
		}
		if err := json.Unmarshal([]byte(DelayBatchResults(runID)), &view); err != nil {
			t.Fatal(err)
		}
		for _, r := range view.Results {
			if _, again := results[r.Name]; again {
				t.Fatalf("%s reported twice", r.Name)
			}
			results[r.Name] = r
		}
		if view.Done {
			return results
		}
		time.Sleep(20 * time.Millisecond)
	}
	t.Fatal("batch did not finish")
	return nil
}

func TestDelayBatchMeasuresNodesAndRelayedMembersOnce(t *testing.T) {
	f := newBatchFixture(t)
	defer f.stop()
	nodes := map[string]string{}
	all := []any{}
	for i, kind := range []string{"alive", "alive", "refused", "silent", "alive", "refused", "silent"} {
		n := f.node(fmt.Sprintf("%s-%d", kind, i), kind)
		all = append(all, n)
		b, _ := json.Marshal(n)
		nodes[n["name"].(string)] = string(b)
	}
	relay := f.node("relay", "alive")
	relay["dialer-proxy"] = "alive-0"
	all = append(all, relay)
	definition, _ := json.Marshal(map[string]any{"proxies": all})
	request, _ := json.Marshal(batchRequest{
		Target: f.target.URL, TimeoutMillis: 600, Concurrency: 8,
		Members: append(members(nodes), batchMember{Name: "relay"}), Definition: string(definition),
	})
	started := decoded(t, StartDelayBatch("batch-correct", string(request)))
	if started.State != "started" {
		t.Fatal("batch not started", started)
	}
	results := collectBatch(t, "batch-correct", 10*time.Second)
	if len(results) != len(nodes)+1 {
		t.Fatalf("got %d results, want %d", len(results), len(nodes)+1)
	}
	for name, r := range results {
		switch {
		case name == "relay" || name[:5] == "alive":
			if r.State != "passed" || r.Code != "proxy_delay_measured" {
				t.Fatalf("%s: %+v", name, r)
			}
		case name[:6] == "silent":
			if r.Code != "timeout_stage_unknown" {
				t.Fatalf("%s should time out: %+v", name, r)
			}
		default:
			if r.State != "failed" || r.Code == "timeout_stage_unknown" {
				t.Fatalf("%s should fail at once: %+v", name, r)
			}
		}
	}
	// A finished batch is forgotten once collected; an unknown run reads as done and empty.
	if got := DelayBatchResults("batch-correct"); got != `{"done":true,"results":[]}` {
		t.Fatal("finished batch kept", got)
	}
}

// DIRECT is measured as a member of its own: no node configuration and no profile to parse.
func TestDelayBatchMeasuresDirect(t *testing.T) {
	f := newBatchFixture(t)
	defer f.stop()
	request, _ := json.Marshal(batchRequest{
		Target: f.target.URL, TimeoutMillis: 2000, Concurrency: 2,
		Members: []batchMember{{Name: "DIRECT"}},
	})
	if started := decoded(t, StartDelayBatch("batch-direct", string(request))); started.State != "started" {
		t.Fatal("batch not started", started)
	}
	results := collectBatch(t, "batch-direct", 10*time.Second)
	if r, ok := results["DIRECT"]; !ok || r.State != "passed" || r.Code != "proxy_delay_measured" {
		t.Fatalf("DIRECT: %+v", results)
	}
}

func TestDelayBatchRejectsInvalidRequestsAndCancels(t *testing.T) {
	if decoded(t, StartDelayBatch("bad id!", "{}")).State != "failed" {
		t.Fatal("invalid run id accepted")
	}
	for _, request := range []string{"", "{", `{"target":"file:///x","timeoutMillis":1000}`, `{"target":"http://example.invalid/","timeoutMillis":5}`} {
		if decoded(t, StartDelayBatch("batch-invalid", request)).State != "failed" {
			t.Fatal("invalid request accepted", request)
		}
	}
	f := newBatchFixture(t)
	defer f.stop()
	nodes := map[string]string{}
	for i := 0; i < 20; i++ {
		b, _ := json.Marshal(f.node(fmt.Sprintf("silent-%d", i), "silent"))
		nodes[fmt.Sprintf("silent-%d", i)] = string(b)
	}
	request, _ := json.Marshal(batchRequest{Target: f.target.URL, TimeoutMillis: 20000, Concurrency: 4, Members: members(nodes)})
	decoded(t, StartDelayBatch("batch-cancel", string(request)))
	time.Sleep(100 * time.Millisecond)
	started := time.Now()
	CancelDelayBatch("batch-cancel")
	results := collectBatch(t, "batch-cancel", 5*time.Second)
	if time.Since(started) > 3*time.Second || len(results) > 4 {
		t.Fatalf("cancel took %v and measured %d", time.Since(started), len(results))
	}
}

// The way the app measured before: one probe per node, at most ten at a time, a 2.5 s pass then
// every failure again at 5 s. Against a batch at 32 that retries only timeouts. Set
// PINGCLASH_BENCH=1 to run it; it takes about two minutes.
func TestDelayBatchTimingAgainstPerNodeProbes(t *testing.T) {
	if os.Getenv("PINGCLASH_BENCH") == "" {
		t.Skip("set PINGCLASH_BENCH=1")
	}
	f := newBatchFixture(t)
	defer f.stop()
	const count = 300
	nodes := map[string]string{}
	kinds := []string{"alive", "alive", "alive", "alive", "refused", "silent"}
	for i := 0; i < count; i++ {
		kind := kinds[i%len(kinds)]
		b, _ := json.Marshal(f.node(fmt.Sprintf("%s-%d", kind, i), kind))
		nodes[fmt.Sprintf("%s-%d", kind, i)] = string(b)
	}
	before := time.Now()
	var serial atomic.Int64
	perNode := func(names []string, timeout int) []string {
		var failed []string
		var mu sync.Mutex
		work := make(chan string)
		var wg sync.WaitGroup
		for w := 0; w < 10; w++ {
			wg.Add(1)
			go func() {
				defer wg.Done()
				for name := range work {
					r := decoded(t, ProbeDelay(nodes[name], f.target.URL, "bench-"+strconv.FormatInt(serial.Add(1), 10), timeout, false))
					if r.State != "passed" {
						mu.Lock()
						failed = append(failed, name)
						mu.Unlock()
					}
				}
			}()
		}
		for _, name := range names {
			work <- name
		}
		close(work)
		wg.Wait()
		return failed
	}
	names := make([]string, 0, count)
	for name := range nodes {
		names = append(names, name)
	}
	perNode(perNode(names, 2500), 5000)
	old := time.Since(before)
	before = time.Now()
	run := func(id string, group map[string]string, timeout int) map[string]batchResult {
		request, _ := json.Marshal(batchRequest{Target: f.target.URL, TimeoutMillis: timeout, Concurrency: 32, Members: members(group)})
		decoded(t, StartDelayBatch(id, string(request)))
		return collectBatch(t, id, 3*time.Minute)
	}
	retry := map[string]string{}
	for name, r := range run("bench-fast", nodes, 2500) {
		if r.Code == "timeout_stage_unknown" {
			retry[name] = nodes[name]
		}
	}
	run("bench-retry", retry, 5000)
	t.Logf("%d nodes (1/6 refused, 1/6 silent): per-node %v, batch %v", count, old, time.Since(before))
}

func members(nodes map[string]string) []batchMember {
	list := make([]batchMember, 0, len(nodes))
	for name, config := range nodes {
		list = append(list, batchMember{Name: name, Config: config})
	}
	return list
}

func TestDelayBatchMeasuresInTheGivenOrder(t *testing.T) {
	f := newBatchFixture(t)
	defer f.stop()
	order := []batchMember{}
	for i := 0; i < 6; i++ {
		b, _ := json.Marshal(f.node(fmt.Sprintf("alive-%d", i), "alive"))
		order = append(order, batchMember{Name: fmt.Sprintf("alive-%d", i), Config: string(b)})
	}
	request, _ := json.Marshal(batchRequest{Target: f.target.URL, TimeoutMillis: 2000, Concurrency: 1, Members: order})
	decoded(t, StartDelayBatch("batch-order", string(request)))
	seen := []string{}
	end := time.Now().Add(10 * time.Second)
	for time.Now().Before(end) {
		var view struct {
			Done    bool          `json:"done"`
			Results []batchResult `json:"results"`
		}
		_ = json.Unmarshal([]byte(DelayBatchResults("batch-order")), &view)
		for _, r := range view.Results {
			seen = append(seen, r.Name)
		}
		if view.Done {
			break
		}
		time.Sleep(10 * time.Millisecond)
	}
	for i, name := range seen {
		if name != order[i].Name {
			t.Fatalf("measured %v, want the given order", seen)
		}
	}
	if len(seen) != len(order) {
		t.Fatalf("measured %d of %d", len(seen), len(order))
	}
}
