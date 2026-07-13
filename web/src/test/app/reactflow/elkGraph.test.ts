import { afterEach, describe, expect, it, vi } from "vitest";
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
    confidence: "VERIFIED",
    binding: "CODE_BOUND",
  };
}

describe("executeElkLayout", () => {
  afterEach(() => {
    delete window.linkGraphDebugTrace;
    delete window.__linkGraphDebugEnabled;
  });

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

  it("emits phase timing trace for layout performance diagnosis", async () => {
    const traceSink = vi.fn();
    window.linkGraphDebugTrace = traceSink;
    const nodes = [node("a"), node("b")];
    const edges: LinkGraphEdge[] = [
      { id: "a-b", type: "CALL", source: "a", target: "b" },
    ];

    await executeElkLayout({
      mode: "FACT_GRAPH",
      layoutOptions: {
        "elk.algorithm": "layered",
        "org.eclipse.elk.direction": "RIGHT",
        "org.eclipse.elk.edgeRouting": "ORTHOGONAL",
      },
      nodes: nodes.map((currentNode) => ({
        node: currentNode,
        width: 120,
        height: 60,
      })),
      edges: edges.map((edge) => ({ edge })),
    });

    const layoutTrace = traceSink.mock.calls
      .map(([serialized]) => JSON.parse(String(serialized)))
      .find((entry) => entry.event === "elkLayout.complete");

    expect(layoutTrace?.payload).toMatchObject({
      mode: "FACT_GRAPH",
      nodeCount: 2,
      edgeCount: 1,
      portCount: 0,
    });
    expect(layoutTrace?.payload.buildGraphDurationMs).toBeTypeOf("number");
    expect(layoutTrace?.payload.elkDurationMs).toBeTypeOf("number");
    expect(layoutTrace?.payload.postProcessDurationMs).toBeTypeOf("number");
    expect(layoutTrace?.payload.totalDurationMs).toBeTypeOf("number");
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


  it("keeps stale process metadata on IF scopes from shrinking the decision layout box", () => {
    const measuredDecision = resolveMeasuredNodeSize(
      {
        ...node("stale-decision"),
        type: "FLOW_SCOPE",
        title: "if (delete)",
        metadata: {
          "flow.kind": "IF",
          "flowchart.kind": "PROCESS",
        },
      },
      new Map([
        [
          "stale-decision",
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
