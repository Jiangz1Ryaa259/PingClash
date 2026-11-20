package mobile

import (
	"context"
	"encoding/json"
	"errors"
	"sync"
	"time"

	"github.com/metacubex/mihomo/adapter/outbound"
	C "github.com/metacubex/mihomo/constant"
)

// A delay batch measures many members of one profile together. Per-member probes each parsed
// their node again (for a relayed node, the whole profile) and the app ran relayed nodes one by
// one; a batch parses the profile at most once, builds each member from it, and measures up to
// its concurrency at once. Results are collected by polling and never persisted or logged.

const (
	maxBatchConcurrency = 64
	maxBatchMembers     = maxImportNodes
	maxBatchRuns        = 4
	// Mihomo's own direct outbound: measured like a node, without the proxy in between.
	directMember = "DIRECT"
	// An abandoned batch is dropped after it ends, even if its results were never collected.
	batchKeep = time.Minute
)

type batchRequest struct {
	Target        string `json:"target"`
	TimeoutMillis int    `json:"timeoutMillis"`
	Concurrency   int    `json:"concurrency"`
	Unified       bool   `json:"unified"`
	// In the order to measure; a member without a configuration is a relayed node built from
	// Definition.
	Members    []batchMember `json:"members"`
	Definition string        `json:"definition"` // the profile, for relayed members
}

// batchMember is one member: its own configuration, or empty for a relayed node.
type batchMember struct {
	Name   string `json:"name"`
	Config string `json:"config"`
}

type batchResult struct {
	Name      string `json:"name"`
	State     string `json:"state"`
	Code      string `json:"code"`
	ElapsedMs int64  `json:"elapsedMs"`
}

type delayBatch struct {
	cancel  context.CancelFunc
	mu      sync.Mutex
	results []batchResult
	done    bool
}

var batches = struct {
	sync.Mutex
	active map[string]*delayBatch
}{active: map[string]*delayBatch{}}

// StartDelayBatch starts measuring the request's members and returns at once with "started", or
// a probe-style failure when the request is invalid or too many batches run.
func StartDelayBatch(runID, request string) (encoded string) {
	defer func() {
		if recover() != nil {
			encoded = encode(runID, "failed", "internal_error", 0, 0)
		}
	}()
	// An id that fails the check is not echoed back; a request too large is a config problem.
	if !validRunID.MatchString(runID) {
		return encode("", "failed", "invalid_run_id", 0, 0)
	}
	if len(request) > maxPrivateBridgeBytes {
		return encode(runID, "failed", "config_invalid", 0, 0)
	}
	var r batchRequest
	if json.Unmarshal([]byte(request), &r) != nil || !validDelayTarget(r.Target) ||
		r.TimeoutMillis < 100 || r.TimeoutMillis > 30000 ||
		len(r.Members) > maxBatchMembers {
		return encode(runID, "failed", "config_invalid", 0, 0)
	}
	ctx, cancel := context.WithCancel(context.Background())
	batch := &delayBatch{cancel: cancel}
	batches.Lock()
	if _, exists := batches.active[runID]; exists || len(batches.active) >= maxBatchRuns {
		batches.Unlock()
		cancel()
		return encode(runID, "not_attempted", "resource_busy", 0, 0)
	}
	batches.active[runID] = batch
	batches.Unlock()
	go batch.run(ctx, runID, r)
	return encode(runID, "started", "batch_started", 0, 0)
}

// DelayBatchResults returns the results measured since the last call, and whether the batch has
// finished. A finished batch is forgotten once its last results are collected.
func DelayBatchResults(runID string) string {
	batches.Lock()
	batch := batches.active[runID]
	batches.Unlock()
	view := struct {
		Done    bool          `json:"done"`
		Results []batchResult `json:"results"`
	}{Done: true, Results: []batchResult{}}
	if batch != nil {
		batch.mu.Lock()
		view.Done = batch.done
		view.Results = append(view.Results, batch.results...)
		batch.results = nil
		batch.mu.Unlock()
		if view.Done {
			forgetBatch(runID, batch)
		}
	}
	b, _ := json.Marshal(view)
	return string(b)
}

// CancelDelayBatch stops a batch and forgets it: members not yet measured report nothing, and
// a caller that stops polling does not leave it holding one of the few batch places.
func CancelDelayBatch(runID string) {
	batches.Lock()
	batch := batches.active[runID]
	delete(batches.active, runID)
	batches.Unlock()
	if batch != nil {
		batch.cancel()
	}
}

func forgetBatch(runID string, batch *delayBatch) {
	batches.Lock()
	if batches.active[runID] == batch {
		delete(batches.active, runID)
	}
	batches.Unlock()
}

func (b *delayBatch) add(result batchResult) {
	b.mu.Lock()
	b.results = append(b.results, result)
	b.mu.Unlock()
}

func (b *delayBatch) run(ctx context.Context, runID string, r batchRequest) {
	defer func() {
		_ = recover()
		b.mu.Lock()
		b.done = true
		b.mu.Unlock()
		b.cancel()
		time.AfterFunc(batchKeep, func() { forgetBatch(runID, b) })
	}()
	var graph *importGraph
	parsed := false
	configs := map[string]string{}
	for _, m := range r.Members {
		if m.Config != "" {
			// The first member of a name keeps its config; a repeat cannot replace it.
			if _, seen := configs[m.Name]; !seen {
				configs[m.Name] = m.Config
			}
		} else if m.Name != directMember && !parsed {
			parsed = true
			graph, _ = parseLocalDefinition(r.Definition)
		}
	}
	// Building is quick and shares the parsed profile, so it runs one at a time; measuring, the
	// slow part, runs in parallel.
	var building sync.Mutex
	build := func(name string) (C.ProxyAdapter, error) {
		building.Lock()
		defer building.Unlock()
		if config, ok := configs[name]; ok {
			return parseNode(config)
		}
		if name == directMember {
			return outbound.NewDirect(), nil
		}
		if graph != nil {
			return graph.runtime(name)
		}
		return nil, errors.New("config_invalid")
	}
	limit := min(max(r.Concurrency, 1), maxBatchConcurrency)
	slots := make(chan struct{}, limit)
	var wg sync.WaitGroup
	defer wg.Wait()
	for _, member := range r.Members {
		name := member.Name
		select {
		case <-ctx.Done():
			return
		case slots <- struct{}{}:
		}
		wg.Add(1)
		go func(name string) {
			defer wg.Done()
			defer func() { <-slots }()
			b.add(measureMember(ctx, name, r, build))
		}(name)
	}
}

// measureMember builds one member (its own configuration, or from the profile for a relayed
// node) and measures it with the request's timeout.
func measureMember(
	ctx context.Context,
	name string,
	r batchRequest,
	build func(string) (C.ProxyAdapter, error),
) (result batchResult) {
	result = batchResult{Name: name, State: "failed", Code: "internal_error"}
	defer func() {
		if recover() != nil {
			result = batchResult{Name: name, State: "failed", Code: "internal_error"}
		}
	}()
	proxy, err := build(name)
	if err != nil {
		result.Code = "config_invalid"
		return result
	}
	defer proxy.Close()
	measured, cancel := context.WithTimeout(ctx, time.Duration(r.TimeoutMillis)*time.Millisecond)
	defer cancel()
	state, code, elapsed := measureDelay(measured, proxy, r.Target, r.TimeoutMillis, r.Unified)
	return batchResult{Name: name, State: state, Code: code, ElapsedMs: elapsed}
}
