// SPDX-License-Identifier: GPL-3.0-only
package mobile

import (
	"errors"
	"github.com/dlclark/regexp2"
	"regexp"
	"strings"
	"time"
)

type compatPattern struct {
	fast     *regexp.Regexp
	extended *regexp2.Regexp
}
type compatFilters struct {
	patterns []compatPattern
	started  time.Time
	failed   bool
}

func compileFilters(raw string) (*compatFilters, error) {
	out := &compatFilters{}
	if len(raw) > 4096 {
		return nil, errors.New("group_filter")
	}
	for _, pattern := range strings.Split(raw, "`") {
		if pattern == "" {
			continue
		}
		if len(out.patterns) >= 16 {
			return nil, errors.New("group_filter")
		}
		p := compatPattern{}
		if r, e := regexp.Compile(pattern); e == nil {
			p.fast = r
		} else {
			r, e := regexp2.Compile(pattern, regexp2.None)
			if e != nil {
				return nil, errors.New("group_filter")
			}
			r.MatchTimeout = 20 * time.Millisecond
			p.extended = r
		}
		out.patterns = append(out.patterns, p)
	}
	return out, nil
}
func (f *compatFilters) match(s string) bool {
	if f.failed {
		return false
	}
	if f.started.IsZero() {
		f.started = time.Now()
	}
	if len(s) > 512 || time.Since(f.started) > time.Second {
		f.failed = true
		return false
	}
	for _, p := range f.patterns {
		if p.fast != nil {
			if p.fast.MatchString(s) {
				return true
			}
		} else {
			matched, e := p.extended.MatchString(s)
			if e != nil {
				f.failed = true
				return false
			}
			if matched {
				return true
			}
		}
	}
	return false
}
