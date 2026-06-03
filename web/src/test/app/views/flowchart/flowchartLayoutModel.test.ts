import { describe, expect, it } from "vitest";
import type { LinkGraphEdge, LinkGraphNode } from "../../../../app/types";
import { buildFlowchartLayoutModel } from "../../../../app/views/flowchart/flowchartLayoutModel";

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
