package mobile

import (
	"encoding/json"
	"testing"
)

func TestVPNGroupStateIsEmptyWithoutAVPN(t *testing.T) {
	var view groupStateView
	if err := json.Unmarshal([]byte(VPNGroupState()), &view); err != nil {
		t.Fatalf("VPNGroupState is not JSON: %v", err)
	}
	if view.Now == nil || view.Delays == nil || len(view.Now) != 0 || len(view.Delays) != 0 {
		t.Fatalf("VPNGroupState() = %+v, want empty maps", view)
	}
}
