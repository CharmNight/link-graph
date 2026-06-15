import { describe, expect, it } from "vitest";
import {
  graphContentBounds,
  graphViewportContentSignature,
  locateAnchorButtonLabel,
  positionsMatch,
  reactFlowPaddingPixels,
  resolveAnchorNode,
  resolveNodePosition,
} from "../../../app/reactflow/graphFlowViewportModel";
import type { LinkGraphEdge, LinkGraphNode } from "../../../app/types";

function node(id: string, position?: { x: number; y: number }, type = "CLASS"): LinkGraphNode {
  return {
    id,
    type,
    title: id,
    inputs: [],
    outputs: [],
    certainty: "PROVEN",
    bindingStatus: "BOUND",
    position,
  };
}

describe("graphFlowViewportModel", () => {
  it("resolves anchor nodes by explicit anchor, selection, method fallback, then first node", () => {
    const nodes = [
      node("class:a"),
      node("method:b", undefined, "METHOD"),
      node("class:c"),
    ];

    expect(resolveAnchorNode(nodes, "class:c", "class:a")?.id).toBe("class:c");
    expect(resolveAnchorNode(nodes, null, "class:a")?.id).toBe("class:a");
    expect(resolveAnchorNode(nodes, null, null)?.id).toBe("method:b");
    expect(resolveAnchorNode([node("class:a")], null, null)?.id).toBe("class:a");
  });

  it("uses deterministic fallback node positions and tolerant position comparison", () => {
    expect(resolveNodePosition(node("missing"), 4)).toEqual({ x: 480, y: 308 });
    expect(resolveNodePosition(node("placed", { x: 10, y: 12 }), 4)).toEqual({ x: 10, y: 12 });
    expect(positionsMatch({ x: 1, y: 1 }, { x: 1.4, y: 1.5 })).toBe(true);
    expect(positionsMatch({ x: 1, y: 1 }, { x: 1.6, y: 1 })).toBe(false);
  });

  it("includes node sizes and routed edge points in content bounds and signatures", () => {
    const nodes = [
      node("a", { x: 10, y: 20 }),
      node("b", { x: 300, y: 80 }),
    ];
    const edges: LinkGraphEdge[] = [{
      id: "edge:a->b",
      source: "a",
      target: "b",
      type: "CALL",
      label: "calls",
      route: {
        sections: [{
          startPoint: { x: 20, y: 30 },
          bendPoints: [{ x: 250, y: 10 }],
          endPoint: { x: 320, y: 90 },
        }],
      },
    }];
    const size = (item: LinkGraphNode) => item.id === "a"
      ? { width: 100, height: 40 }
      : { width: 80, height: 90 };

    expect(graphContentBounds(nodes, edges, size)).toEqual({
      minX: 10,
      minY: 10,
      maxX: 380,
      maxY: 170,
      width: 370,
      height: 160,
    });
    expect(graphViewportContentSignature(nodes, edges, size)).toBe(
      "a:10,20,100x40|b:300,80,80x90|edge:a->b:a->b:20,30;250,10;320,90",
    );
  });

  it("keeps viewport labels and fit padding stable", () => {
    expect(locateAnchorButtonLabel("CLASS_DIAGRAM")).toBe("定位当前类");
    expect(locateAnchorButtonLabel("FACT_GRAPH")).toBe("定位当前方法");
    expect(reactFlowPaddingPixels(1000, 0.16)).toBe(68);
    expect(reactFlowPaddingPixels(1000, 0)).toBe(0);
  });
});
