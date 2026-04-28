import { describe, expect, it } from "vitest";
import type { LinkGraphLayoutState, LinkGraphNode } from "../../app/types";
import { applyBootstrapNodePositions } from "../../app/graphState";

function semanticNode(id: string): LinkGraphNode {
  return {
    id,
    type: "METHOD",
    title: id,
    inputs: [],
    outputs: [],
    certainty: "PROVEN",
    bindingStatus: "BOUND",
    sourceTag: "FACT",
    metadata: {
      "ui.x": "640",
      "ui.y": "144",
    },
  };
}

function manualNode(id: string): LinkGraphNode {
  return {
    id,
    type: "METHOD",
    title: id,
    inputs: [],
    outputs: [],
    certainty: "PROVEN",
    bindingStatus: "DESIGN_ONLY",
    sourceTag: "DRAFT_MANUAL",
    metadata: {
      "linkGraph.manual": "true",
      "ui.x": "640",
      "ui.y": "144",
    },
  };
}

describe("graphState", () => {
  it("ignores persisted bootstrap positions for semantic fact nodes", () => {
    const nextNodes = applyBootstrapNodePositions(
      [semanticNode("method:anchor")],
      [],
      {
        positions: {
          "method:anchor": { x: 720, y: 180 },
        },
      } satisfies LinkGraphLayoutState,
      true,
    );

    expect(nextNodes[0]?.position).toBeUndefined();
    expect(nextNodes[0]?.metadata?.["ui.x"]).toBeUndefined();
    expect(nextNodes[0]?.metadata?.["ui.y"]).toBeUndefined();
  });

  it("keeps persisted bootstrap positions for manual draft nodes", () => {
    const nextNodes = applyBootstrapNodePositions(
      [manualNode("design:anchor")],
      [],
      {
        positions: {
          "design:anchor": { x: 720, y: 180 },
        },
      } satisfies LinkGraphLayoutState,
      true,
    );

    expect(nextNodes[0]?.position).toEqual({ x: 720, y: 180 });
    expect(nextNodes[0]?.metadata?.["ui.x"]).toBe("720");
    expect(nextNodes[0]?.metadata?.["ui.y"]).toBe("180");
  });
});
