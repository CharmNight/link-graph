import { afterEach, describe, expect, it, vi } from "vitest";
import { summarizeBootstrapState, traceLinkGraph, traceLinkGraphStartup } from "../../app/debug";
import { materializeThreeViewDocuments } from "../../app/testBootstrapState";

describe("traceLinkGraph", () => {
  const consoleLogSpy = vi.spyOn(console, "log").mockImplementation(() => undefined);

  afterEach(() => {
    window.__linkGraphTraceBuffer = [];
    window.__linkGraphTraceHistory = [];
    window.__linkGraphLastTrace = undefined;
    window.__linkGraphDebugEnabled = undefined;
    window.linkGraphDebugTrace = undefined;
    consoleLogSpy.mockClear();
  });

  it("does not buffer traces by default when debug bridge is absent", () => {
    traceLinkGraph("graph.rendered", { nodes: 2 });

    expect(window.__linkGraphTraceBuffer ?? []).toHaveLength(0);
    expect(consoleLogSpy).not.toHaveBeenCalled();
  });

  it("sends traces directly to bridge when bridge is available", () => {
    const traceSink = vi.fn();
    window.linkGraphDebugTrace = traceSink;

    traceLinkGraph("graph.rendered", { nodes: 3 });

    expect(traceSink).toHaveBeenCalledTimes(1);
    expect(window.__linkGraphTraceBuffer ?? []).toHaveLength(0);
    expect(consoleLogSpy).not.toHaveBeenCalled();
  });

  it("keeps recent trace history even when only the in-page debug flag is enabled", () => {
    window.__linkGraphDebugEnabled = true;

    traceLinkGraph("graph.viewport.apply", { branch: "flowchartAnchor" });

    expect(window.__linkGraphTraceBuffer ?? []).toHaveLength(1);
    expect(window.__linkGraphTraceHistory ?? []).toHaveLength(1);
    expect(window.__linkGraphLastTrace).toContain("\"event\":\"graph.viewport.apply\"");
  });

  it("keeps recent trace history when traces are sent directly to the bridge", () => {
    const traceSink = vi.fn();
    window.linkGraphDebugTrace = traceSink;

    traceLinkGraph("graph.viewport.apply", { branch: "fitView" });

    expect(traceSink).toHaveBeenCalledTimes(1);
    expect(window.__linkGraphTraceHistory ?? []).toHaveLength(1);
    expect(window.__linkGraphLastTrace).toContain("\"branch\":\"fitView\"");
  });

  it("drops noisy routed edge traces before they cross the debug bridge", () => {
    const traceSink = vi.fn();
    window.linkGraphDebugTrace = traceSink;

    traceLinkGraph("routedEdge.render", {
      id: "edge:heavy",
      path: "M 0 0 L 100 0 L 100 100",
    });

    expect(traceSink).not.toHaveBeenCalled();
    expect(window.__linkGraphTraceHistory ?? []).toHaveLength(0);
  });

  it("mirrors startup traces to console warning before the bridge is ready", () => {
    const consoleWarnSpy = vi.spyOn(console, "warn").mockImplementation(() => undefined);
    window.__linkGraphDebugEnabled = true;

    try {
      traceLinkGraphStartup("main.moduleLoaded", { hasBootstrap: true });

      expect(window.__linkGraphTraceBuffer ?? []).toHaveLength(1);
      expect(consoleWarnSpy).toHaveBeenCalledWith(
        "link-graph startup trace",
        expect.stringContaining("\"event\":\"main.moduleLoaded\""),
      );
    } finally {
      consoleWarnSpy.mockRestore();
    }
  });

  it("summarizes the authoritative top-level flowchart working graph instead of the stale full graph view", () => {
    const state = materializeThreeViewDocuments({
      analysisDisplayMode: "FLOWCHART",
      visibleGraph: {
        nodes: [
          {
            id: "decision:guard",
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
            id: "decision:guard",
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
              id: "decision:guard",
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
              id: "condition:guard",
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
              id: "decision:guard",
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
        anchorNodeId: "decision:guard",
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
    });

    const summary = summarizeBootstrapState(state);

    expect(summary.workspaceGraph.nodes).toBe(1);
    expect(summary.workspaceGraph.sampleNodeIds).toEqual(["decision:guard"]);
  });
});
