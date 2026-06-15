import { describe, expect, it } from "vitest";
import {
  collectDraftChangedNodeIds,
  selectDraftWorkbenchEntry,
} from "../../app/workbenchDraftModel";
import type { DraftWorkbenchEntry, DraftWorkbenchState } from "../../app/types";

function draftEntry(entryId: string, kind: DraftWorkbenchEntry["kind"], targetNodeIds: string[]): DraftWorkbenchEntry {
  return {
    entryId,
    kind,
    title: entryId,
    targetStepIds: [],
    targetNodeIds,
    reason: "reason",
    impactSummary: "impact",
    evidence: [],
  };
}

describe("workbenchDraftModel", () => {
  it("selects an explicit draft entry before falling back to the first change then first note", () => {
    const state: DraftWorkbenchState = {
      draftChanges: [
        draftEntry("change:1", "CHANGE", ["node:a"]),
        draftEntry("change:2", "CHANGE", ["node:b"]),
      ],
      draftNotes: [
        draftEntry("note:1", "NOTE", ["node:c"]),
      ],
    };

    expect(selectDraftWorkbenchEntry(state, "note:1")?.entryId).toBe("note:1");
    expect(selectDraftWorkbenchEntry(state, "missing")?.entryId).toBe("change:1");
    expect(selectDraftWorkbenchEntry({
      draftChanges: [],
      draftNotes: state.draftNotes,
    }, null)?.entryId).toBe("note:1");
    expect(selectDraftWorkbenchEntry({
      draftChanges: [],
      draftNotes: [],
    }, null)).toBeNull();
  });

  it("collects changed node ids from draft changes in stable first-seen order", () => {
    const state: DraftWorkbenchState = {
      draftChanges: [
        draftEntry("change:1", "CHANGE", ["node:a", "node:b"]),
        draftEntry("change:2", "CHANGE", ["node:b", "node:c"]),
      ],
      draftNotes: [
        draftEntry("note:1", "NOTE", ["node:ignored"]),
      ],
    };

    expect(collectDraftChangedNodeIds(state, (entry) => entry?.targetNodeIds ?? [])).toEqual([
      "node:a",
      "node:b",
      "node:c",
    ]);
  });
});
