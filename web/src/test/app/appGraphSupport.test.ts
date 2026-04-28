import { describe, expect, it } from "vitest";
import { deriveFlowchartSummary } from "../../app/appGraphSupport";
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
