package hostmetrics

import (
	"encoding/json"
	"os"
	"testing"
)

func flowGolden(t *testing.T, name string) FlowReport {
	t.Helper()
	raw, err := os.ReadFile("../../../../contracts/ws/v1/golden/" + name + ".json")
	if err != nil {
		t.Fatal(err)
	}
	var frame struct {
		Payload FlowReport `json:"payload"`
	}
	if json.Unmarshal(raw, &frame) != nil {
		t.Fatal("invalid flow golden")
	}
	return frame.Payload
}
func cloneFlow(t *testing.T, source FlowReport) FlowReport {
	t.Helper()
	raw, _ := json.Marshal(source)
	var target FlowReport
	if json.Unmarshal(raw, &target) != nil {
		t.Fatal("invalid clone")
	}
	return target
}
func TestHostFlowDeltasMatchSharedGoldensAndCannotRewriteCounters(t *testing.T) {
	first, next := flowGolden(t, "host-flow-observations"), flowGolden(t, "host-flow-observations-next")
	initial, err := FlowDifference(nil, first)
	if err != nil || len(initial.Cells) != 4 {
		t.Fatal("initial flow", initial, err)
	}
	delta, err := FlowDifference(&first, next)
	if err != nil || len(delta.Cells) != 4 {
		t.Fatal("flow delta", delta, err)
	}
	for _, cell := range delta.Cells {
		if cell.Count != 1 {
			t.Fatal("cumulative value was counted again", cell)
		}
	}
	if duplicate, err := FlowDifference(&next, cloneFlow(t, next)); err != nil || !duplicate.Duplicate {
		t.Fatal("changed duplicate", err)
	}
	for _, mutate := range []func(*FlowReport){
		func(value *FlowReport) { value.Sequence = first.Sequence },
		func(value *FlowReport) { value.ConnectionID = "other" },
		func(value *FlowReport) { value.Dropped = nil },
		func(value *FlowReport) { value.Cells = nil },
		func(value *FlowReport) { value.Cells = value.Cells[:1] },
		func(value *FlowReport) { value.Cells = append(value.Cells, value.Cells[0]) },
		func(value *FlowReport) { value.Cells[0].Stage = "PRIVATE_PATH" },
		func(value *FlowReport) { value.Cells[0].Kind = "question" },
		func(value *FlowReport) { value.Cells[0].Count = 0 },
		func(value *FlowReport) { value.Cells[0].Count = MaxCount + 1 },
	} {
		bad := cloneFlow(t, next)
		mutate(&bad)
		if _, err := FlowDifference(&first, bad); err == nil {
			t.Fatal("invalid flow accepted")
		}
	}
	bad := cloneFlow(t, next)
	bad.Cells[0].Count++
	if _, err := FlowDifference(&next, bad); err == nil {
		t.Fatal("same sequence changed")
	}
}
