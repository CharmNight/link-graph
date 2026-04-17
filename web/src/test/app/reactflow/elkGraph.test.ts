import { describe, expect, it } from "vitest";
import { FLOWCHART_DECISION_MIN_HEIGHT, FLOWCHART_DECISION_WIDTH } from "../../../app/graphNodeSizing";
import { executeElkLayout, resolveMeasuredNodeSize } from "../../../app/reactflow/elkGraph";
import type { LinkGraphEdge, LinkGraphNode } from "../../../app/types";

function node(id: string): LinkGraphNode {
  return {
    id,
    type: "METHOD",
    title: id,
    inputs: [],
    outputs: [],
    certainty: "PROVEN",
    bindingStatus: "BOUND",
  };
}

describe("executeElkLayout", () => {
  it("preserves ELK orthogonal edge sections instead of dropping routing information", async () => {
    const nodes = [node("a"), node("b"), node("c")];
    const edges: LinkGraphEdge[] = [
      { id: "a-b", type: "CALL", source: "a", target: "b" },
      { id: "a-c", type: "CALL", source: "a", target: "c" },
    ];

    const laidOut = await executeElkLayout({
      mode: "FLOWCHART",
      layoutOptions: {
        "elk.algorithm": "layered",
        "org.eclipse.elk.direction": "DOWN",
        "org.eclipse.elk.edgeRouting": "ORTHOGONAL",
      },
      nodes: nodes.map((currentNode) => ({
        node: currentNode,
        width: 120,
        height: 60,
      })),
      edges: edges.map((edge) => ({ edge })),
    });

    expect(laidOut.nodes).toHaveLength(3);
    expect(laidOut.edges).toHaveLength(2);
    expect(laidOut.edges.find((edge) => edge.id === "a-c")?.route?.sections[0]?.bendPoints?.length).toBeGreaterThan(0);
  });

  it("does not let measured decision content shrink below the visible diamond shell size", () => {
    const measuredDecision = resolveMeasuredNodeSize(
      {
        ...node("decision"),
        type: "FLOW_SCOPE",
        metadata: {
          "flow.kind": "IF",
          "flowchart.kind": "DECISION",
        },
      },
      new Map([
        [
          "decision",
          {
            width: 180,
            height: 144,
          },
        ],
      ]),
      "FLOWCHART",
    );

    expect(measuredDecision).toEqual({
      width: FLOWCHART_DECISION_WIDTH,
      height: FLOWCHART_DECISION_MIN_HEIGHT,
    });
  });
});
