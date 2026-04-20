import { describe, expect, it } from "vitest";
import { FLOWCHART_DECISION_WIDTH, FLOWCHART_PROCESS_WIDTH, flowchartNodeCardWidth } from "../../app/graphNodeSizing";

describe("flowchartNodeCardWidth", () => {
  it("widens long code-like decision titles so they do not collapse into unreadable wraps", () => {
    const width = flowchartNodeCardWidth({
      type: "FLOW_SCOPE",
      title: "if (Boolean.TRUE.equals(delete) && fileExists(filePath))",
      metadata: { "flowchart.kind": "DECISION" },
    } as any);

    expect(width).toBeGreaterThan(FLOWCHART_DECISION_WIDTH);
  });

  it("widens long code-like process titles so invocation labels keep readable line breaks", () => {
    const width = flowchartNodeCardWidth({
      type: "FLOW_ACTION",
      title: "FileUtils.checkAllowDownload(fileName)",
      metadata: { "flowchart.kind": "PROCESS" },
    } as any);

    expect(width).toBeGreaterThan(FLOWCHART_PROCESS_WIDTH);
  });
});
