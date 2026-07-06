import { describe, expect, it } from "vitest";
import type { LinkGraphEdge, LinkGraphNode } from "../../../../app/types";
import {
  buildFlowchartInvocationExpansionRegistry,
  buildFlowchartLayoutModel,
} from "../../../../app/views/flowchart/flowchartLayoutModel";

function node(id: string, type: LinkGraphNode["type"] = "FLOW_ACTION", metadata: Record<string, string> = {}): LinkGraphNode {
  return {
    id,
    type,
    title: id,
    inputs: [],
    outputs: [],
    certainty: "PROVEN",
    bindingStatus: "BOUND",
    metadata,
  };
}

function edge(id: string, type: LinkGraphEdge["type"], source: string, target: string, metadata: Record<string, string> = {}): LinkGraphEdge {
  return {
    id,
    type,
    source,
    target,
    metadata,
  };
}

describe("buildFlowchartLayoutModel", () => {
  it("groups invocation expansion nodes by expansion id and keeps the current method nodes as main flow", () => {
    const nodes = [
      node("method:caller", "METHOD", { "flowchart.kind": "ENTRY" }),
      node("invoke:create-info", "FLOW_ACTION", { "flow.kind": "INVOCATION", "flowchart.kind": "SUBROUTINE" }),
      node("method:create-info", "METHOD", {
        "linkGraph.expansion.id": "invocation:1",
        "linkGraph.expansion.sourceInvocationNodeId": "invoke:create-info",
        "linkGraph.expansion.rootNodeId": "method:create-info",
      }),
      node("action:save-info", "FLOW_ACTION", {
        "linkGraph.expansion.id": "invocation:1",
        "linkGraph.expansion.sourceInvocationNodeId": "invoke:create-info",
        "linkGraph.expansion.rootNodeId": "method:create-info",
      }),
    ];
    const edges = [
      edge("caller-invoke", "CONTROL_FLOW", "method:caller", "invoke:create-info"),
      edge("invoke-expanded", "CALL", "invoke:create-info", "method:create-info", {
        "linkGraph.expansion.id": "invocation:1",
      }),
      edge("expanded-save", "CONTROL_FLOW", "method:create-info", "action:save-info", {
        "linkGraph.expansion.id": "invocation:1",
      }),
    ];

    const model = buildFlowchartLayoutModel(nodes, edges);

    expect(model.mainNodeIds).toEqual(["method:caller", "invoke:create-info"]);
    expect(model.expansionGroups).toHaveLength(1);
    expect(model.expansionGroups[0]).toMatchObject({
      expansionId: "invocation:1",
      sourceInvocationNodeId: "invoke:create-info",
      rootNodeId: "method:create-info",
      nodeIds: ["method:create-info", "action:save-info"],
      callEdgeIds: ["invoke-expanded"],
      internalEdgeIds: ["expanded-save"],
    });
  });

  it("falls back to the method node as expansion root when root metadata is missing", () => {
    const nodes = [
      node("invoke:create-info", "FLOW_ACTION", { "flow.kind": "INVOCATION" }),
      node("action:save-info", "FLOW_ACTION", {
        "linkGraph.expansion.id": "invocation:1",
        "linkGraph.expansion.sourceInvocationNodeId": "invoke:create-info",
      }),
      node("method:create-info", "METHOD", {
        "linkGraph.expansion.id": "invocation:1",
        "linkGraph.expansion.sourceInvocationNodeId": "invoke:create-info",
      }),
    ];
    const edges = [
      edge("invoke-expanded", "CALL", "invoke:create-info", "method:create-info", {
        "linkGraph.expansion.id": "invocation:1",
      }),
      edge("expanded-save", "CONTROL_FLOW", "method:create-info", "action:save-info", {
        "linkGraph.expansion.id": "invocation:1",
      }),
    ];

    const model = buildFlowchartLayoutModel(nodes, edges);

    expect(model.expansionGroups[0]?.rootNodeId).toBe("method:create-info");
  });

  it("sorts multiple expansion groups by source invocation order in the input graph", () => {
    const nodes = [
      node("invoke:first", "FLOW_ACTION", { "flow.kind": "INVOCATION" }),
      node("invoke:second", "FLOW_ACTION", { "flow.kind": "INVOCATION" }),
      node("method:second", "METHOD", {
        "linkGraph.expansion.id": "invocation:2",
        "linkGraph.expansion.sourceInvocationNodeId": "invoke:second",
      }),
      node("method:first", "METHOD", {
        "linkGraph.expansion.id": "invocation:1",
        "linkGraph.expansion.sourceInvocationNodeId": "invoke:first",
      }),
    ];
    const edges: LinkGraphEdge[] = [];

    const model = buildFlowchartLayoutModel(nodes, edges);

    expect(model.expansionGroups.map((group) => group.expansionId)).toEqual(["invocation:1", "invocation:2"]);
  });
});

describe("buildFlowchartInvocationExpansionRegistry", () => {
  it("creates an entry from edge metadata and keeps a reused target entry as borrowed", () => {
    const nodes = [
      node("invoke:load", "FLOW_ACTION", { "flow.kind": "INVOCATION" }),
      node("method:shared-load", "METHOD"),
      node("action:owned", "FLOW_ACTION", {
        "linkGraph.expansion.id": "invocation:load",
        "linkGraph.expansion.sourceInvocationNodeId": "invoke:load",
        "linkGraph.expansion.rootNodeId": "method:shared-load",
      }),
    ];
    const edges = [
      edge("invoke-load", "CALL", "invoke:load", "method:shared-load", {
        "linkGraph.expansion.id": "invocation:load",
        "linkGraph.expansion.sourceInvocationNodeId": "invoke:load",
        "linkGraph.expansion.rootNodeId": "method:shared-load",
      }),
      edge("shared-owned", "CONTROL_FLOW", "method:shared-load", "action:owned", {
        "linkGraph.expansion.id": "invocation:load",
      }),
    ];

    const registry = buildFlowchartInvocationExpansionRegistry({ nodes, edges, anchorNodeId: "method:caller" });

    expect(registry.entriesById["invocation:load"]).toMatchObject({
      expansionId: "invocation:load",
      sourceInvocationNodeId: "invoke:load",
      rootNodeId: "method:shared-load",
      ownedNodeIds: ["action:owned"],
      borrowedNodeIds: ["method:shared-load"],
      ownedEdgeIds: ["invoke-load", "shared-owned"],
      callEdgeIds: ["invoke-load"],
      internalEdgeIds: ["shared-owned"],
      parentExpansionId: null,
      depth: 1,
    });
  });

  it("derives nested parent-child relationships through projected alias source ids", () => {
    const nodes = [
      node("method:caller", "METHOD"),
      node("invoke:outer", "FLOW_ACTION", { "flow.kind": "INVOCATION" }),
      node("method:outer", "METHOD", {
        "linkGraph.expansion.id": "invocation:outer",
        "linkGraph.expansion.sourceInvocationNodeId": "invoke:outer",
        "linkGraph.expansion.rootNodeId": "method:outer",
      }),
      node("alias:inner-invoke", "FLOW_ACTION", {
        "linkGraph.expansion.id": "invocation:outer",
        "flow.kind": "INVOCATION",
        "flowchart.projectedFromNodeIds": "invoke:inner",
      }),
      node("method:inner", "METHOD", {
        "linkGraph.expansion.id": "invocation:inner",
        "linkGraph.expansion.sourceInvocationNodeId": "invoke:inner",
        "linkGraph.expansion.rootNodeId": "method:inner",
      }),
    ];
    const edges = [
      edge("outer-call", "CALL", "invoke:outer", "method:outer", {
        "linkGraph.expansion.id": "invocation:outer",
      }),
      edge("inner-call", "CALL", "invoke:inner", "method:inner", {
        "linkGraph.expansion.id": "invocation:inner",
      }),
    ];

    const registry = buildFlowchartInvocationExpansionRegistry({ nodes, edges, anchorNodeId: "method:caller" });

    expect(registry.entriesById["invocation:inner"]).toMatchObject({
      parentExpansionId: "invocation:outer",
      depth: 2,
    });
    expect(registry.entriesById["invocation:outer"]?.childExpansionIds).toEqual(["invocation:inner"]);
  });

  it("defaults one sibling per parent context to expanded and collapses the rest", () => {
    const nodes = [
      node("method:caller", "METHOD"),
      node("invoke:pay", "FLOW_ACTION", { "flow.kind": "INVOCATION" }),
      node("method:stripe", "METHOD", {
        "linkGraph.expansion.id": "invocation:stripe",
        "linkGraph.expansion.sourceInvocationNodeId": "invoke:pay",
        "linkGraph.expansion.rootNodeId": "method:stripe",
        "linkGraph.expansion.targetSignature": "Stripe.pay()",
        "linkGraph.expansion.createdAt": "2026-07-01T00:00:00Z",
      }),
      node("method:paypal", "METHOD", {
        "linkGraph.expansion.id": "invocation:paypal",
        "linkGraph.expansion.sourceInvocationNodeId": "invoke:pay",
        "linkGraph.expansion.rootNodeId": "method:paypal",
        "linkGraph.expansion.targetSignature": "Paypal.pay()",
        "linkGraph.expansion.createdAt": "2026-07-01T00:00:01Z",
      }),
    ];
    const edges = [
      edge("stripe-call", "CALL", "invoke:pay", "method:stripe", {
        "linkGraph.expansion.id": "invocation:stripe",
      }),
      edge("paypal-call", "CALL", "invoke:pay", "method:paypal", {
        "linkGraph.expansion.id": "invocation:paypal",
      }),
    ];

    const registry = buildFlowchartInvocationExpansionRegistry({ nodes, edges, anchorNodeId: "method:caller" });

    expect(registry.parentContextIdsByExpansionId).toEqual({
      "invocation:stripe": "root:method:caller",
      "invocation:paypal": "root:method:caller",
    });
    expect(registry.entries.map((entry) => [entry.expansionId, entry.state])).toEqual([
      ["invocation:stripe", "expanded"],
      ["invocation:paypal", "collapsed"],
    ]);
    expect(registry.sceneState.activeExpansionPath).toEqual(["invocation:stripe"]);
    expect(registry.sceneState.collapsedExpansionIds).toEqual(["invocation:paypal"]);
    expect(registry.sceneState.activeSiblingByParentContext).toEqual({
      "root:method:caller": "invocation:stripe",
    });
  });

  it("keeps an explicitly collapsed default sibling collapsed and activates the next open sibling", () => {
    const nodes = [
      node("method:caller", "METHOD"),
      node("invoke:pay", "FLOW_ACTION", { "flow.kind": "INVOCATION" }),
      node("method:stripe", "METHOD", {
        "linkGraph.expansion.id": "invocation:stripe",
        "linkGraph.expansion.sourceInvocationNodeId": "invoke:pay",
        "linkGraph.expansion.rootNodeId": "method:stripe",
        "linkGraph.expansion.targetSignature": "Stripe.pay()",
        "linkGraph.expansion.createdAt": "2026-07-01T00:00:00Z",
      }),
      node("method:paypal", "METHOD", {
        "linkGraph.expansion.id": "invocation:paypal",
        "linkGraph.expansion.sourceInvocationNodeId": "invoke:pay",
        "linkGraph.expansion.rootNodeId": "method:paypal",
        "linkGraph.expansion.targetSignature": "Paypal.pay()",
        "linkGraph.expansion.createdAt": "2026-07-01T00:00:01Z",
      }),
    ];
    const edges = [
      edge("stripe-call", "CALL", "invoke:pay", "method:stripe", {
        "linkGraph.expansion.id": "invocation:stripe",
      }),
      edge("paypal-call", "CALL", "invoke:pay", "method:paypal", {
        "linkGraph.expansion.id": "invocation:paypal",
      }),
    ];

    const registry = buildFlowchartInvocationExpansionRegistry({
      nodes,
      edges,
      anchorNodeId: "method:caller",
      sceneState: {
        activeExpansionId: null,
        activeExpansionPath: [],
        collapsedExpansionIds: ["invocation:stripe"],
        activeSiblingByParentContext: {},
      },
    });

    expect(registry.entries.map((entry) => [entry.expansionId, entry.state])).toEqual([
      ["invocation:stripe", "collapsed"],
      ["invocation:paypal", "expanded"],
    ]);
    expect(registry.sceneState.activeExpansionId).toBe("invocation:paypal");
    expect(registry.sceneState.activeExpansionPath).toEqual(["invocation:paypal"]);
    expect(registry.sceneState.collapsedExpansionIds).toEqual(["invocation:stripe"]);
    expect(registry.sceneState.activeSiblingByParentContext).toEqual({
      "root:method:caller": "invocation:paypal",
    });
  });

  it("uses an explicit active expansion id as the active sibling when opening a collapsed sibling", () => {
    const nodes = [
      node("method:caller", "METHOD"),
      node("invoke:pay", "FLOW_ACTION", { "flow.kind": "INVOCATION" }),
      node("method:stripe", "METHOD", {
        "linkGraph.expansion.id": "invocation:stripe",
        "linkGraph.expansion.sourceInvocationNodeId": "invoke:pay",
        "linkGraph.expansion.rootNodeId": "method:stripe",
        "linkGraph.expansion.targetSignature": "Stripe.pay()",
        "linkGraph.expansion.createdAt": "2026-07-01T00:00:00Z",
      }),
      node("method:paypal", "METHOD", {
        "linkGraph.expansion.id": "invocation:paypal",
        "linkGraph.expansion.sourceInvocationNodeId": "invoke:pay",
        "linkGraph.expansion.rootNodeId": "method:paypal",
        "linkGraph.expansion.targetSignature": "Paypal.pay()",
        "linkGraph.expansion.createdAt": "2026-07-01T00:00:01Z",
      }),
    ];
    const edges = [
      edge("stripe-call", "CALL", "invoke:pay", "method:stripe", {
        "linkGraph.expansion.id": "invocation:stripe",
      }),
      edge("paypal-call", "CALL", "invoke:pay", "method:paypal", {
        "linkGraph.expansion.id": "invocation:paypal",
      }),
    ];

    const registry = buildFlowchartInvocationExpansionRegistry({
      nodes,
      edges,
      anchorNodeId: "method:caller",
      sceneState: {
        activeExpansionId: "invocation:paypal",
        activeExpansionPath: ["invocation:paypal"],
        collapsedExpansionIds: [],
        activeSiblingByParentContext: {},
      },
    });

    expect(registry.entries.map((entry) => [entry.expansionId, entry.state])).toEqual([
      ["invocation:stripe", "collapsed"],
      ["invocation:paypal", "expanded"],
    ]);
    expect(registry.sceneState.activeExpansionId).toBe("invocation:paypal");
    expect(registry.sceneState.activeExpansionPath).toEqual(["invocation:paypal"]);
    expect(registry.sceneState.collapsedExpansionIds).toEqual(["invocation:stripe"]);
    expect(registry.sceneState.activeSiblingByParentContext).toEqual({
      "root:method:caller": "invocation:paypal",
    });
  });
});
