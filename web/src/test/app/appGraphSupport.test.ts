import { describe, expect, it } from "vitest";
import { deriveFlowchartSummary, scopeFlowchartGraphToAnchorMethod } from "../../app/appGraphSupport";
import type { LinkGraphDocument } from "../../app/types";

describe("deriveFlowchartSummary", () => {
  it("counts flow-scope IF nodes as branches even when stale metadata says process", () => {
    const graph: LinkGraphDocument = {
      nodes: [
        {
          id: "scope:delete-if",
          type: "FLOW_SCOPE",
          title: "if (delete)",
          inputs: [],
          outputs: [],
          certainty: "PROVEN",
          bindingStatus: "BOUND",
          metadata: {
            "flow.kind": "IF",
            "flowchart.kind": "PROCESS",
          },
        },
      ],
      edges: [],
    };

    expect(deriveFlowchartSummary(graph).branchCount).toBe(1);
  });
});

describe("scopeFlowchartGraphToAnchorMethod", () => {
  it("keeps invocation expansion nodes attached to the anchored method", () => {
    const graph: LinkGraphDocument = {
      nodes: [
        {
          id: "method:caller",
          type: "METHOD",
          title: "Caller.run",
          signature: "com.example.Caller.run():void",
          inputs: [],
          outputs: [],
          certainty: "PROVEN",
          bindingStatus: "BOUND",
          metadata: { "flowchart.kind": "ENTRY" },
        },
        {
          id: "invoke:create-info",
          type: "FLOW_ACTION",
          title: "systemService.createInfo()",
          signature: "com.example.SystemService.createInfo():void",
          inputs: [],
          outputs: [],
          certainty: "PROVEN",
          bindingStatus: "BOUND",
          metadata: {
            "flow.kind": "INVOCATION",
            "flow.ownerMethod": "com.example.Caller.run():void",
            "flowchart.kind": "SUBROUTINE",
          },
        },
        {
          id: "method:create-info",
          type: "METHOD",
          title: "SystemService.createInfo",
          signature: "com.example.SystemService.createInfo():void",
          inputs: [],
          outputs: [],
          certainty: "PROVEN",
          bindingStatus: "BOUND",
          metadata: {
            "flow.ownerMethod": "com.example.SystemService.createInfo():void",
            "linkGraph.expansion.sourceInvocationNodeId": "invoke:create-info",
            "linkGraph.expansion.id": "invocation:expansion-1",
          },
        },
        {
          id: "action:save-info",
          type: "FLOW_ACTION",
          title: "saveInfo()",
          inputs: [],
          outputs: [],
          certainty: "PROVEN",
          bindingStatus: "BOUND",
          metadata: {
            "flow.kind": "ACTION",
            "flow.ownerMethod": "com.example.SystemService.createInfo():void",
            "linkGraph.expansion.sourceInvocationNodeId": "invoke:create-info",
            "linkGraph.expansion.id": "invocation:expansion-1",
          },
        },
        {
          id: "method:other",
          type: "METHOD",
          title: "Other.run",
          signature: "com.example.Other.run():void",
          inputs: [],
          outputs: [],
          certainty: "PROVEN",
          bindingStatus: "BOUND",
        },
      ],
      edges: [
        {
          id: "control:caller-to-invoke",
          type: "CONTROL_FLOW",
          source: "method:caller",
          target: "invoke:create-info",
        },
        {
          id: "call:invoke-to-target",
          type: "CALL",
          source: "invoke:create-info",
          target: "method:create-info",
        },
        {
          id: "control:target-to-save",
          type: "CONTROL_FLOW",
          source: "method:create-info",
          target: "action:save-info",
        },
        {
          id: "call:caller-to-other",
          type: "CALL",
          source: "method:caller",
          target: "method:other",
        },
      ],
    };

    const scoped = scopeFlowchartGraphToAnchorMethod(graph, "method:caller");

    expect(scoped.nodes.map((node) => node.id).sort()).toEqual([
      "action:save-info",
      "invoke:create-info",
      "method:caller",
      "method:create-info",
    ]);
    expect(scoped.edges.map((edge) => edge.id).sort()).toEqual([
      "call:invoke-to-target",
      "control:caller-to-invoke",
      "control:target-to-save",
    ]);
  });
});
