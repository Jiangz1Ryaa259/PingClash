package mobile

import (
	"context"
	"sync"
	"time"

	"github.com/metacubex/mihomo/adapter/outboundgroup"
	C "github.com/metacubex/mihomo/constant"
)

// Automatic groups choose from whatever their health checks have answered so far. Mihomo checks
// a group's members ten at a time at a five-second timeout, so with hundreds of members the
// choice stays arbitrary for a minute or more after connecting, and groups sharing members test
// them again each. One shared pass right after start tests every member of every automatic group
// once per test URL, many at a time; the groups read the same per-URL results and choose from
// them. Mihomo's own periodic checks continue unchanged afterwards.
const (
	warmConcurrency = 48
	warmTimeout     = 5 * time.Second
	maxWarmJobs     = 4000
)

type warmJob struct {
	proxy C.Proxy
	url   string
}

// warmJobs lists each (member, test URL) pair of the automatic groups once.
func warmJobs(proxies map[string]C.Proxy) []warmJob {
	seen := map[string]bool{}
	jobs := []warmJob{}
	for _, p := range proxies {
		group, ok := p.Adapter().(outboundgroup.ProxyGroup)
		if !ok {
			continue
		}
		switch group.(type) {
		case *outboundgroup.URLTest, *outboundgroup.Fallback:
		default:
			continue
		}
		url := ""
		for _, provider := range group.Providers() {
			if url = provider.HealthCheckURL(); url != "" {
				break
			}
		}
		if url == "" {
			continue
		}
		for _, member := range group.Proxies() {
			key := member.Name() + "\x00" + url
			if seen[key] || len(jobs) >= maxWarmJobs {
				continue
			}
			seen[key] = true
			jobs = append(jobs, warmJob{member, url})
		}
	}
	return jobs
}

// warmAutomaticGroups runs the shared pass until it ends or ctx is cancelled.
func warmAutomaticGroups(ctx context.Context, proxies map[string]C.Proxy) {
	slots := make(chan struct{}, warmConcurrency)
	var wg sync.WaitGroup
	defer wg.Wait()
	for _, job := range warmJobs(proxies) {
		select {
		case <-ctx.Done():
			return
		case slots <- struct{}{}:
		}
		wg.Add(1)
		go func(job warmJob) {
			defer wg.Done()
			defer func() { <-slots }()
			defer func() { _ = recover() }()
			checked, cancel := context.WithTimeout(ctx, warmTimeout)
			defer cancel()
			_, _ = job.proxy.URLTest(checked, job.url, nil)
		}(job)
	}
}
