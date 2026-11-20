package mobile

import (
	"encoding/json"
	"fmt"
	"testing"
)

// A profile with more than 100 groups saves a selection for each of them; the runtime must still
// accept it, as the import did.
func TestManyGroupSelectionsStayParseable(t *testing.T) {
	const groups = 150
	proxies := []any{map[string]any{"name": "a", "type": "socks5", "server": "127.0.0.1", "port": 1080}}
	list := make([]any, 0, groups)
	picks := map[string]any{}
	for i := 0; i < groups; i++ {
		name := fmt.Sprintf("g%03d", i)
		list = append(list, map[string]any{"name": name, "type": "select", "proxies": []any{"a"}})
		picks[name] = "a"
	}
	text, err := json.Marshal(map[string]any{
		"proxies":              proxies,
		"proxy-groups":         list,
		"pingclash-selections": picks,
	})
	if err != nil {
		t.Fatal(err)
	}
	if _, err := parseLocalDefinition(string(text)); err != nil {
		t.Fatalf("a profile with %d groups and their selections was refused: %v", groups, err)
	}
}
