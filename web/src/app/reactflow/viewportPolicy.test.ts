import { describe, expect, it } from "vitest";
import { graphBounds, shouldFocusAnchor, shouldPreserveViewportForIncrementalUpdate } from "./viewportPolicy";

describe("viewportPolicy", () => {
  it("preserves viewport for small downstream expansions under the same anchor", () => {
    expect(shouldPreserveViewportForIncrementalUpdate(
      {
        anchorNodeId: "method:anchor",
        nodeIds: new Set(["method:anchor", "method:callee"]),
        edgeIds: new Set(["edge:anchor->callee"]),
      },
      {
        anchorNodeId: "method:anchor",
        nodeIds: new Set(["method:anchor", "method:callee", "method:tail"]),
        edgeIds: new Set(["edge:anchor->callee", "edge:callee->tail"]),
      },
    )).toBe(true);
  });

  it("does not preserve viewport when the anchor changes", () => {
    expect(shouldPreserveViewportForIncrementalUpdate(
      {
        anchorNodeId: "method:anchor-a",
        nodeIds: new Set(["method:anchor-a", "method:callee"]),
        edgeIds: new Set(["edge:a->callee"]),
      },
      {
        anchorNodeId: "method:anchor-b",
        nodeIds: new Set(["method:anchor-b", "method:callee"]),
        edgeIds: new Set(["edge:b->callee"]),
      },
    )).toBe(false);
  });

  it("focuses the anchor for very wide fact graphs", () => {
    expect(shouldFocusAnchor([
      {
        id: "method:anchor",
        position: { x: 0, y: 0 },
      },
      {
        id: "method:tail",
        position: { x: 3800, y: 120 },
      },
    ])).toBe(true);
  });

  it("reports graph bounds from positioned nodes", () => {
    expect(graphBounds([
      {
        id: "method:anchor",
        position: { x: 120, y: 96 },
      },
      {
        id: "method:tail",
        position: { x: 520, y: 316 },
      },
    ])).toMatchObject({
      minX: 120,
      minY: 96,
    });
  });
});
