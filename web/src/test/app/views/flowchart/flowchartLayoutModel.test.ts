import { describe, expect, it } from "vitest";
import type { InvocationExpansionRegistry, InvocationExpansionRegistryEntry, LinkGraphNode } from "../../../../app/types";
import { buildFlowchartInvocationExpansionRegistry } from "../../../../app/views/flowchart/flowchartLayoutModel";

function node(id: string, type: LinkGraphNode["type"] = "FLOW_ACTION", metadata: Record<string, string> = {}): LinkGraphNode {
  return {
    id,
    type,
    title: id,
    inputs: [],
    outputs: [],
    confidence: "VERIFIED",
    binding: "CODE_BOUND",
    metadata,
  };
}

function expansionEntry(entry: Partial<InvocationExpansionRegistryEntry> & { expansionId: string }): InvocationExpansionRegistryEntry {
  return {
    sourceInvocationNodeId: null,
    targetSignature: null,
    rootNodeId: null,
    createdAt: null,
    parentExpansionId: null,
    depth: 1,
    ownedNodeIds: [],
    borrowedNodeIds: [],
    callEdgeIds: [],
    internalEdgeIds: [],
    childExpansionIds: [],
    warnings: [],
    ...entry,
  };
}

function paymentRegistry(): InvocationExpansionRegistry {
  return {
    entries: [
      expansionEntry({
        expansionId: "invocation:stripe",
        sourceInvocationNodeId: "invoke:pay",
        targetSignature: "Stripe.pay()",
        rootNodeId: "method:stripe",
        createdAt: "2026-07-01T00:00:00Z",
        ownedNodeIds: ["method:stripe"],
        callEdgeIds: ["stripe-call"],
      }),
      expansionEntry({
        expansionId: "invocation:paypal",
        sourceInvocationNodeId: "invoke:pay",
        targetSignature: "Paypal.pay()",
        rootNodeId: "method:paypal",
        createdAt: "2026-07-01T00:00:01Z",
        ownedNodeIds: ["method:paypal"],
        callEdgeIds: ["paypal-call"],
      }),
    ],
  };
}

describe("buildFlowchartInvocationExpansionRegistry", () => {
  it("uses server edge and borrowed-node ownership without re-deriving it from metadata", () => {
    const nodes = [
      node("invoke:load", "FLOW_ACTION", { "flow.kind": "INVOCATION" }),
      node("method:shared-load", "METHOD"),
      node("action:owned", "FLOW_ACTION"),
    ];

    const registry = buildFlowchartInvocationExpansionRegistry({
      nodes,
      anchorNodeId: "method:caller",
      serverRegistry: {
        entries: [
          expansionEntry({
            expansionId: "invocation:load",
            sourceInvocationNodeId: "invoke:load",
            rootNodeId: "method:shared-load",
            ownedNodeIds: ["action:owned"],
            borrowedNodeIds: ["method:shared-load"],
            callEdgeIds: ["invoke-load"],
            internalEdgeIds: ["shared-owned"],
          }),
        ],
      },
    });

    expect(registry.entriesById["invocation:load"]).toMatchObject({
      expansionId: "invocation:load",
      sourceInvocationNodeId: "invoke:load",
      rootNodeId: "method:shared-load",
      ownedNodeIds: ["action:owned"],
      borrowedNodeIds: ["method:shared-load"],
      callEdgeIds: ["invoke-load"],
      internalEdgeIds: ["shared-owned"],
      parentExpansionId: null,
      depth: 1,
    });
  });

  it("uses server nested parent-child relationships", () => {
    const nodes = [
      node("method:caller", "METHOD"),
      node("invoke:outer", "FLOW_ACTION", { "flow.kind": "INVOCATION" }),
      node("method:outer", "METHOD"),
      node("alias:inner-invoke", "FLOW_ACTION", { "flow.kind": "INVOCATION" }),
      node("method:inner", "METHOD"),
    ];

    const registry = buildFlowchartInvocationExpansionRegistry({
      nodes,
      anchorNodeId: "method:caller",
      serverRegistry: {
        entries: [
          expansionEntry({
            expansionId: "invocation:outer",
            sourceInvocationNodeId: "invoke:outer",
            rootNodeId: "method:outer",
            ownedNodeIds: ["method:outer", "alias:inner-invoke"],
            callEdgeIds: ["outer-call"],
            childExpansionIds: ["invocation:inner"],
          }),
          expansionEntry({
            expansionId: "invocation:inner",
            sourceInvocationNodeId: "invoke:inner",
            rootNodeId: "method:inner",
            parentExpansionId: "invocation:outer",
            depth: 2,
            ownedNodeIds: ["method:inner"],
            callEdgeIds: ["inner-call"],
          }),
        ],
      },
    });

    expect(registry.entriesById["invocation:inner"]).toMatchObject({
      parentExpansionId: "invocation:outer",
      depth: 2,
    });
    expect(registry.entriesById["invocation:outer"]?.childExpansionIds).toEqual(["invocation:inner"]);
  });

  it("uses the server invocation expansion registry instead of re-deriving hierarchy from metadata", () => {
    const nodes = [
      node("invoke:outer", "FLOW_ACTION", { "flow.kind": "INVOCATION" }),
      node("method:outer", "METHOD"),
      node("invoke:inner", "FLOW_ACTION", { "flow.kind": "INVOCATION" }),
      node("method:inner", "METHOD"),
    ];

    const registry = buildFlowchartInvocationExpansionRegistry({
      nodes,
      anchorNodeId: "method:caller",
      serverRegistry: {
        entries: [
          {
            expansionId: "invocation:inner",
            sourceInvocationNodeId: "invoke:inner",
            targetSignature: "Inner.run():void",
            rootNodeId: "method:inner",
            createdAt: "2026-07-01T00:00:01Z",
            parentExpansionId: null,
            depth: 1,
            ownedNodeIds: ["method:inner"],
            borrowedNodeIds: [],
            callEdgeIds: ["inner-call"],
            internalEdgeIds: [],
            childExpansionIds: [],
            warnings: ["server-authoritative"],
          },
          {
            expansionId: "invocation:outer",
            sourceInvocationNodeId: "invoke:outer",
            targetSignature: "Outer.run():void",
            rootNodeId: "method:outer",
            createdAt: "2026-07-01T00:00:00Z",
            parentExpansionId: null,
            depth: 1,
            ownedNodeIds: ["method:outer", "invoke:inner"],
            borrowedNodeIds: [],
            callEdgeIds: ["outer-call"],
            internalEdgeIds: [],
            childExpansionIds: [],
            warnings: [],
          },
        ],
      },
    });

    expect(registry.entries.map((entry) => entry.expansionId)).toEqual(["invocation:outer", "invocation:inner"]);
    expect(registry.entriesById["invocation:inner"]).toMatchObject({
      parentExpansionId: null,
      depth: 1,
      childExpansionIds: [],
      warnings: ["server-authoritative"],
    });
    expect(registry.entriesById["invocation:outer"]?.childExpansionIds).toEqual([]);
  });

  it("defaults one sibling per parent context to expanded and collapses the rest", () => {
    const nodes = [
      node("method:caller", "METHOD"),
      node("invoke:pay", "FLOW_ACTION", { "flow.kind": "INVOCATION" }),
      node("method:stripe", "METHOD"),
      node("method:paypal", "METHOD"),
    ];

    const registry = buildFlowchartInvocationExpansionRegistry({
      nodes,
      anchorNodeId: "method:caller",
      serverRegistry: paymentRegistry(),
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
      node("method:stripe", "METHOD"),
      node("method:paypal", "METHOD"),
    ];

    const registry = buildFlowchartInvocationExpansionRegistry({
      nodes,
      anchorNodeId: "method:caller",
      serverRegistry: paymentRegistry(),
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
      node("method:stripe", "METHOD"),
      node("method:paypal", "METHOD"),
    ];

    const registry = buildFlowchartInvocationExpansionRegistry({
      nodes,
      anchorNodeId: "method:caller",
      serverRegistry: paymentRegistry(),
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
