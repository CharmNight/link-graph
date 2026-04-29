import { afterEach, describe, expect, it, vi } from "vitest";
import type { LinkGraphEdge, LinkGraphNode } from "../../../../app/types";
import {
  factGraphLayoutSizeSignature,
  FACT_GRAPH_LAYOUT_OPTIONS,
  layoutFactGraphView,
  runFactElkOptionExperiments,
} from "../../../../app/views/fact/factGraphLayout";

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

function routeIsOrthogonal(edge: LinkGraphEdge): boolean {
  const sections = edge.route?.sections ?? [];
  if (sections.length === 0) {
    return false;
  }
  return sections.every((section) => {
    const points = [
      section.startPoint,
      ...(section.bendPoints ?? []),
      section.endPoint,
    ];
    return points.slice(1).every((point, index) => {
      const previous = points[index];
      return previous != null && (previous.x === point.x || previous.y === point.y);
    });
  });
}

describe("layoutFactGraphView", () => {
  afterEach(() => {
    delete window.linkGraphDebugTrace;
    delete window.__linkGraphDebugEnabled;
  });

  it("keeps the measurement signature stable when DOM measurements are within conservative fact node bounds", () => {
    const nodes: LinkGraphNode[] = [
      methodNode("method:anchor", "OrderService.place"),
      { ...methodNode("flow:guard", "if (order != null)"), type: "FLOW_SCOPE" },
    ];

    const initialSignature = factGraphLayoutSizeSignature(nodes, new Map());
    const measuredSignature = factGraphLayoutSizeSignature(
      nodes,
      new Map([
        ["method:anchor", { width: 408, height: 132 }],
        ["flow:guard", { width: 368, height: 148 }],
      ]),
    );

    expect(measuredSignature).toBe(initialSignature);
  });

  it("changes the measurement signature when a fact node grows beyond conservative bounds", () => {
    const nodes: LinkGraphNode[] = [
      methodNode("method:anchor", "OrderService.place"),
    ];

    const initialSignature = factGraphLayoutSizeSignature(nodes, new Map());
    const measuredSignature = factGraphLayoutSizeSignature(
      nodes,
      new Map([["method:anchor", { width: 408, height: 220 }]]),
    );

    expect(measuredSignature).not.toBe(initialSignature);
  });

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

  it("keeps low-cost FACT options scoped to model ordering while preserving partitioning and orthogonal routing", () => {
    expect(FACT_GRAPH_LAYOUT_OPTIONS).toMatchObject({
      "elk.algorithm": "layered",
      "org.eclipse.elk.direction": "RIGHT",
      "org.eclipse.elk.partitioning.activate": "true",
      "org.eclipse.elk.edgeRouting": "ORTHOGONAL",
    });
    expect(FACT_GRAPH_LAYOUT_OPTIONS).not.toHaveProperty("org.eclipse.elk.layered.considerModelOrder.strategy");
    expect(FACT_GRAPH_LAYOUT_OPTIONS).not.toHaveProperty(
      "org.eclipse.elk.layered.crossingMinimization.forceNodeModelOrder",
    );
  });

  it("retains every fact node and orthogonal route after dropping model-order constraints", async () => {
    const nodes: LinkGraphNode[] = [
      methodNode("method:callee", "OrderMapper.insert"),
      { ...methodNode("flow:action", "validate(order)"), type: "FLOW_ACTION" },
      methodNode("method:caller", "OrderController.submit"),
      { ...methodNode("flow:if", "if (order != null)"), type: "FLOW_SCOPE", metadata: { "flow.kind": "IF" } },
      methodNode("method:anchor", "OrderService.place"),
    ];
    const edges: LinkGraphEdge[] = [
      { id: "edge:caller->anchor", type: "CALL", source: "method:caller", target: "method:anchor" },
      { id: "edge:anchor->if", type: "CONTROL_FLOW", source: "method:anchor", target: "flow:if" },
      { id: "edge:if->action", type: "CONTROL_FLOW", source: "flow:if", target: "flow:action" },
      { id: "edge:action->callee", type: "CALL", source: "flow:action", target: "method:callee" },
    ];

    const laidOut = await layoutFactGraphView({
      graph: { nodes, edges },
      nodes,
      edges,
      anchorNodeId: "method:anchor",
      sizeSnapshot: new Map(),
      reason: "graph",
    });
    const nodeIndex = new Map(laidOut.nodes.map((node) => [node.id, node]));
    const upstreamNode = nodeIndex.get("method:caller");
    const anchorNode = nodeIndex.get("method:anchor");
    const currentFlowNode = nodeIndex.get("flow:if");
    const downstreamNode = nodeIndex.get("method:callee");

    expect(laidOut.nodes.map((node) => node.id).sort()).toEqual(nodes.map((node) => node.id).sort());
    expect(laidOut.edges.map((edge) => edge.id).sort()).toEqual(edges.map((edge) => edge.id).sort());
    expect(upstreamNode?.metadata?.["layout.direction"]).toBe("UPSTREAM");
    expect(anchorNode?.metadata?.["layout.direction"]).toBe("CURRENT");
    expect(currentFlowNode?.metadata?.["layout.direction"]).toBe("CURRENT");
    expect(downstreamNode?.metadata?.["layout.direction"]).toBe("DOWNSTREAM");
    expect(upstreamNode?.position?.x ?? Number.POSITIVE_INFINITY).toBeLessThan(anchorNode?.position?.x ?? 0);
    expect(downstreamNode?.position?.x ?? 0).toBeGreaterThan(anchorNode?.position?.x ?? Number.POSITIVE_INFINITY);
    expect(laidOut.edges.every(routeIsOrthogonal)).toBe(true);
  });

  it("emits fact preprocessing timing trace before ELK layout", async () => {
    const traceSink = vi.fn();
    window.linkGraphDebugTrace = traceSink;
    const nodes: LinkGraphNode[] = [
      methodNode("method:caller", "OrderController.submit"),
      methodNode("method:anchor", "OrderService.place"),
      methodNode("method:callee", "OrderMapper.insert"),
    ];
    const edges: LinkGraphEdge[] = [
      { id: "edge:caller->anchor", type: "CALL", source: "method:caller", target: "method:anchor" },
      { id: "edge:anchor->callee", type: "CALL", source: "method:anchor", target: "method:callee" },
    ];

    await layoutFactGraphView({
      graph: { nodes, edges },
      nodes,
      edges,
      anchorNodeId: "method:anchor",
      sizeSnapshot: new Map(),
      reason: "graph",
    });

    const preparedTrace = traceSink.mock.calls
      .map(([serialized]) => JSON.parse(String(serialized)))
      .find((entry) => entry.event === "factGraphLayout.prepared");

    expect(preparedTrace?.payload).toMatchObject({
      nodeCount: 3,
      edgeCount: 2,
      anchorNodeId: "method:anchor",
      upstreamReachableCount: 2,
      downstreamReachableCount: 2,
      directionCounts: {
        UPSTREAM: 1,
        CURRENT: 1,
        DOWNSTREAM: 1,
      },
    });
    expect(preparedTrace?.payload.totalPreElkDurationMs).toBeTypeOf("number");
  });

  it("emits ELK option experiment traces without changing the visible layout", async () => {
    const traceSink = vi.fn();
    window.linkGraphDebugTrace = traceSink;
    const nodes: LinkGraphNode[] = [
      methodNode("method:anchor", "OrderService.place"),
      methodNode("method:callee", "OrderMapper.insert"),
    ];
    const edges: LinkGraphEdge[] = [
      { id: "edge:anchor->callee", type: "CALL", source: "method:anchor", target: "method:callee" },
    ];

    await runFactElkOptionExperiments({
      nodes: nodes.map((node) => ({
        node,
        width: 120,
        height: 60,
      })),
      edges: edges.map((edge) => ({ edge })),
      baseOptions: {
        "elk.algorithm": "layered",
        "org.eclipse.elk.direction": "RIGHT",
        "org.eclipse.elk.partitioning.activate": "true",
        "org.eclipse.elk.edgeRouting": "ORTHOGONAL",
        "org.eclipse.elk.layered.considerModelOrder.strategy": "NODES_AND_EDGES",
        "org.eclipse.elk.layered.crossingMinimization.forceNodeModelOrder": "true",
      },
    });

    const experimentTraces = traceSink.mock.calls
      .map(([serialized]) => JSON.parse(String(serialized)))
      .filter((entry) => entry.event === "factGraphLayout.elkOptionExperiment");

    expect(experimentTraces.map((entry) => entry.payload.variant)).toEqual([
      "polyline-routing",
      "no-forced-model-order",
      "no-model-order",
      "no-partitioning",
      "simple-node-placement",
    ]);
    expect(experimentTraces[0]?.payload).toMatchObject({
      nodeCount: 2,
      edgeCount: 1,
    });
    expect(experimentTraces[0]?.payload.durationMs).toBeTypeOf("number");
  });

  it("does not run option experiments as part of the visible FACT layout", async () => {
    const traceSink = vi.fn();
    window.linkGraphDebugTrace = traceSink;
    const nodes: LinkGraphNode[] = [
      methodNode("method:anchor", "OrderService.place"),
      methodNode("method:callee", "OrderMapper.insert"),
    ];
    const edges: LinkGraphEdge[] = [
      { id: "edge:anchor->callee", type: "CALL", source: "method:anchor", target: "method:callee" },
    ];

    await layoutFactGraphView({
      graph: { nodes, edges },
      nodes,
      edges,
      anchorNodeId: "method:anchor",
      sizeSnapshot: new Map(),
      reason: "graph",
    });

    expect(traceSink.mock.calls
      .map(([serialized]) => JSON.parse(String(serialized)))
      .some((entry) => entry.event === "factGraphLayout.elkOptionExperiment")).toBe(false);
  });
});
