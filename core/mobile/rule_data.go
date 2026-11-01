// SPDX-License-Identifier: GPL-3.0-only
package mobile

import (
	"crypto/sha256"
	"encoding/hex"
	"io"
	"os"
	"path/filepath"
	"regexp"
	"sync"
	"time"

	"github.com/metacubex/mihomo/component/geodata"
	"github.com/metacubex/mihomo/component/mmdb"
	C "github.com/metacubex/mihomo/constant"
)

var geoTagPattern = regexp.MustCompile(`^[a-zA-Z0-9!_-]{1,64}(@[a-zA-Z0-9_-]{1,32})?$`)
var ruleData struct {
	sync.RWMutex
	directory string
	times     map[string]time.Time
}
var ruleDataHashes = map[string]string{
	"GeoIP.dat":   "a6271d3c96361d96156e6d3f7cf1550ef21b00d2f951deb772f464db91e89ac5",
	"GeoSite.dat": "c57333596b44aa061e84c3c224e97a4475c759a5e3baab1fcda002e48b4dc313",
	"ASN.mmdb":    "4384bfcfb594391bc4e0ab37751f44661eaf7cadbeee28a90a6b82dc935fec4c",
}

// InitializeRuleData only accepts the bundled hash-pinned files. A caller
// cannot install a subscription-controlled path, URL, extension or replacement DB.
// The Android caller supplies its private directory, never a provider path.
func InitializeRuleData(directory string) bool {
	nativeConfigParse.Lock()
	defer nativeConfigParse.Unlock()
	ruleData.Lock()
	defer ruleData.Unlock()
	if !filepath.IsAbs(directory) {
		return false
	}
	directory = filepath.Clean(directory)
	if ruleData.directory != "" {
		return directory == ruleData.directory
	}
	times := map[string]time.Time{}
	for name, expected := range ruleDataHashes {
		path := filepath.Join(directory, name)
		stat, err := os.Lstat(path)
		if err != nil || !stat.Mode().IsRegular() || stat.Size() > 20*1024*1024 {
			return false
		}
		f, err := os.Open(path)
		if err != nil {
			return false
		}
		h := sha256.New()
		_, err = io.Copy(h, io.LimitReader(f, 20*1024*1024+1))
		f.Close()
		if err != nil || hex.EncodeToString(h.Sum(nil)) != expected {
			return false
		}
		times[name] = stat.ModTime()
	}
	C.SetHomeDir(directory)
	geodata.SetGeodataMode(true)
	geodata.SetLoader("memconservative")
	// No updater is started, and missing/changed files are rejected before any
	// native loader is called. The app never downloads rule data at runtime.
	geodata.SetGeoIpUrl("")
	geodata.SetGeoSiteUrl("")
	geodata.SetASNUrl("")
	geodata.SetMmdbUrl("")
	// Load only the verified private file before any connection matching starts.
	// The pinned native reader retains its open mapping; no updater is started.
	if geodata.InitASN() != nil {
		return false
	}
	if mmdb.ASNInstance().Metadata.DatabaseType != "DBIP-ASN-Lite (compat=GeoLite2-ASN)" {
		return false
	}
	ruleData.directory = directory
	ruleData.times = times
	return true
}

func ruleDataAvailable() bool {
	ruleData.RLock()
	defer ruleData.RUnlock()
	if ruleData.directory == "" {
		return false
	}
	for name, mtime := range ruleData.times {
		stat, err := os.Lstat(filepath.Join(ruleData.directory, name))
		if err != nil || !stat.Mode().IsRegular() || !stat.ModTime().Equal(mtime) {
			return false
		}
	}
	return true
}
