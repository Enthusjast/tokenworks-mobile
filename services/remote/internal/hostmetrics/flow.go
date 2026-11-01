package hostmetrics

import (
	"errors"
	"reflect"
)

const FlowCapability = "host.flow.observations.v1"

var ErrFlowReport = errors.New("host flow observations: invalid or nonmonotone report")

type FlowCell struct {
	Stage string `json:"stage"`
	Kind  string `json:"kind"`
	Count uint64 `json:"count"`
}
type FlowReport struct {
	ConnectionID string     `json:"connection_id"`
	Sequence     uint64     `json:"sequence"`
	Dropped      *uint64    `json:"dropped"`
	Cells        []FlowCell `json:"cells"`
}
type FlowDelta struct {
	Cells     []FlowCell
	Dropped   uint64
	Duplicate bool
}

func FlowPair(stage, kind string) bool {
	switch stage {
	case "agent":
		return kind == "start" || kind == "chunk" || kind == "end" || kind == "other"
	case "follow":
		return kind == "snapshot" || kind == "event" || kind == "start" || kind == "chunk" || kind == "replace" || kind == "end" || kind == "other"
	case "projection":
		return kind == "snapshot" || kind == "event" || kind == "checkpoint" || kind == "start" || kind == "chunk" || kind == "replace" || kind == "end" || kind == "question" || kind == "other"
	default:
		return false
	}
}
func flowKey(cell FlowCell) string { return cell.Stage + ":" + cell.Kind }
func (report FlowReport) valid() bool {
	if report.Sequence == 0 || report.Sequence > MaxCount || report.Dropped == nil || *report.Dropped > MaxCount || report.Cells == nil || len(report.Cells) > 20 {
		return false
	}
	seen := map[string]bool{}
	for _, cell := range report.Cells {
		if !FlowPair(cell.Stage, cell.Kind) || cell.Count == 0 || cell.Count > MaxCount || seen[flowKey(cell)] {
			return false
		}
		seen[flowKey(cell)] = true
	}
	return true
}

// FlowDifference never accepts a regression, cell removal or changed duplicate.
// Connection identity and current authority are additionally checked by Relay.
func FlowDifference(previous *FlowReport, current FlowReport) (FlowDelta, error) {
	if !current.valid() {
		return FlowDelta{}, ErrFlowReport
	}
	old := map[string]uint64{}
	if previous != nil {
		if !previous.valid() || previous.ConnectionID != current.ConnectionID || current.Sequence < previous.Sequence || *current.Dropped < *previous.Dropped {
			return FlowDelta{}, ErrFlowReport
		}
		if current.Sequence == previous.Sequence {
			if reflect.DeepEqual(previous, &current) {
				return FlowDelta{Duplicate: true}, nil
			}
			return FlowDelta{}, ErrFlowReport
		}
		for _, cell := range previous.Cells {
			old[flowKey(cell)] = cell.Count
		}
	}
	delta := FlowDelta{Dropped: *current.Dropped}
	if previous != nil {
		delta.Dropped -= *previous.Dropped
	}
	for _, cell := range current.Cells {
		before := old[flowKey(cell)]
		if cell.Count < before {
			return FlowDelta{}, ErrFlowReport
		}
		delete(old, flowKey(cell))
		if cell.Count > before {
			delta.Cells = append(delta.Cells, FlowCell{Stage: cell.Stage, Kind: cell.Kind, Count: cell.Count - before})
		}
	}
	if len(old) != 0 {
		return FlowDelta{}, ErrFlowReport
	}
	return delta, nil
}
