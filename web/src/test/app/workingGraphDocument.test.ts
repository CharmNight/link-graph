import { describe, expect, it } from "vitest";
import { resolveWorkingGraphDocument } from "../../app/workingGraphDocument";
import type { LinkGraphBootstrapState } from "../../app/types";

function bootstrapState(): LinkGraphBootstrapState {
  return {
    analysisDisplayMode: "FLOWCHART",
    visibleGraph: {
      nodes: [
        {
          id: "scope:guard",
          type: "FLOW_SCOPE",
          title: "if (!allowed)",
          inputs: [],
          outputs: [],
          certainty: "PROVEN",
          bindingStatus: "BOUND",
          metadata: {
            "flowchart.kind": "DECISION",
          },
        },
      ],
      edges: [],
    },
    workingGraph: {
      nodes: [
        {
          id: "scope:guard",
          type: "FLOW_SCOPE",
          title: "if (!allowed)",
          inputs: [],
          outputs: [],
          certainty: "PROVEN",
          bindingStatus: "BOUND",
          metadata: {
            "flowchart.kind": "DECISION",
          },
        },
      ],
      edges: [],
    },
    flowchartView: {
      visibleGraph: {
        nodes: [
          {
            id: "scope:guard",
            type: "FLOW_SCOPE",
            title: "if (!allowed)",
            inputs: [],
            outputs: [],
            certainty: "PROVEN",
            bindingStatus: "BOUND",
            metadata: {
              "flowchart.kind": "DECISION",
            },
          },
        ],
        edges: [],
      },
      fullGraph: {
        nodes: [
          {
            id: "action:guard-condition",
            type: "FLOW_ACTION",
            title: "!checkAllowDownload(fileName)",
            inputs: [],
            outputs: [],
            certainty: "PROVEN",
            bindingStatus: "BOUND",
            metadata: {
              "flowchart.kind": "PROCESS",
              "flow.kind": "CONDITION",
            },
          },
          {
            id: "scope:guard",
            type: "FLOW_SCOPE",
            title: "if (!allowed)",
            inputs: [],
            outputs: [],
            certainty: "PROVEN",
            bindingStatus: "BOUND",
            metadata: {
              "flowchart.kind": "DECISION",
            },
          },
        ],
        edges: [],
      },
      anchorNodeId: "scope:guard",
      summary: {
        nodeCount: 1,
        branchCount: 1,
        exceptionPathCount: 0,
        fullNodeCount: 2,
        fullEdgeCount: 0,
        hiddenNodeCount: 1,
        hiddenEdgeCount: 0,
        truncated: true,
      },
    },
    mermaidIssues: [],
    diffItems: [],
    syncPreviewItems: [],
  };
}

describe("resolveWorkingGraphDocument", () => {
  it("prefers the top-level working graph over the stale flowchart full graph", () => {
    const resolved = resolveWorkingGraphDocument(bootstrapState());

    expect(resolved.nodes.map((node) => node.id)).toEqual(["scope:guard"]);
  });

  it("falls back to the flowchart full graph only when the top-level working graph is missing", () => {
    const state = bootstrapState();
    delete (state as Partial<LinkGraphBootstrapState>).workingGraph;

    const resolved = resolveWorkingGraphDocument(state);

    expect(resolved.nodes.map((node) => node.id)).toEqual([
      "action:guard-condition",
      "scope:guard",
    ]);
  });
});
