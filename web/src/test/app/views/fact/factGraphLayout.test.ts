import { describe, expect, it } from "vitest";
import type { LinkGraphEdge, LinkGraphNode } from "../../../../app/types";
import { layoutFactGraphView } from "../../../../app/views/fact/factGraphLayout";

function methodNode(id: string, title: string): LinkGraphNode {
  return {
    id,
    type: "METHOD",
    title,
    inputs: [],
    outputs: [],
    certainty: "PROVEN",
    bindingStatus: "BOUND",
  };
}

describe("layoutFactGraphView", () => {
  it("keeps upstream nodes on the left of the anchor and downstream nodes on the right", async () => {
    const nodes: LinkGraphNode[] = [
      methodNode("method:caller", "OrderController.submit"),
      methodNode("method:anchor", "OrderService.place"),
      methodNode("method:callee", "OrderMapper.insert"),
    ];
    const edges: LinkGraphEdge[] = [
      { id: "edge:caller->anchor", type: "CALL", source: "method:caller", target: "method:anchor" },
      { id: "edge:anchor->callee", type: "CALL", source: "method:anchor", target: "method:callee" },
    ];

    const laidOut = await layoutFactGraphView({
      graph: { nodes, edges },
      nodes,
      edges,
      anchorNodeId: "method:anchor",
      sizeSnapshot: new Map(),
      reason: "graph",
    });
    const index = new Map(laidOut.nodes.map((node) => [node.id, node]));

    expect(index.get("method:caller")?.position?.x).toBeLessThan(index.get("method:anchor")?.position?.x ?? 0);
    expect(index.get("method:callee")?.position?.x).toBeGreaterThan(index.get("method:anchor")?.position?.x ?? 0);
    expect(index.get("method:caller")?.metadata?.["layout.direction"]).toBe("UPSTREAM");
    expect(index.get("method:anchor")?.metadata?.["layout.direction"]).toBe("CURRENT");
    expect(index.get("method:callee")?.metadata?.["layout.direction"]).toBe("DOWNSTREAM");
    expect(laidOut.edges[0]?.route?.sections[0]?.startPoint.x).toBeTypeOf("number");
  });
});
