import { act, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { App } from "./App";
import { resetEditorTransportForTest } from "./editorTransport";
import { materializeThreeViewDocuments } from "./testBootstrapState";
import type { LinkGraphBootstrapState } from "./types";

vi.mock("./views/fact/FactGraphView", () => ({
  FactGraphView: ({
    onMoveNode,
    onAddNode,
  }: {
    onMoveNode: (nodeId: string, position: { x: number; y: number }) => void;
    onAddNode: (kind: "METHOD" | "DOC_PAGE", position?: { x: number; y: number }) => void;
  }) => (
    <>
      <button
        type="button"
        onClick={() => onMoveNode("method:submit-order", { x: 640, y: 320 })}
      >
        simulate move
      </button>
      <button
        type="button"
        onClick={() => onAddNode("METHOD", { x: 420, y: 240 })}
      >
        simulate add
      </button>
    </>
  ),
}));

vi.mock("./views/flowchart/FlowchartView", () => ({
  FlowchartView: ({
    view,
    onMoveNode,
  }: {
    view: {
      visibleGraph: {
        edges: Array<{ route?: unknown }>;
      };
    };
    onMoveNode: (nodeId: string, position: { x: number; y: number }) => void;
  }) => (
    <>
      <div data-testid="flowchart-route-preserved">{String(Boolean(view.visibleGraph.edges[0]?.route))}</div>
      <button
        type="button"
        onClick={() => onMoveNode("method:submit-order", { x: 640, y: 320 })}
      >
        simulate flowchart move
      </button>
    </>
  ),
}));

const bootstrapState = materializeThreeViewDocuments({
  visibleGraph: {
    nodes: [
      {
        id: "method:submit-order",
        type: "METHOD",
        title: "OrderController.submit",
        inputs: ["java.lang.String"],
        outputs: ["com.example.SubmitResult"],
        certainty: "PROVEN",
        bindingStatus: "DESIGN_ONLY",
        position: { x: 120, y: 96 },
        sourceTag: "DRAFT_MANUAL",
        metadata: {
          "linkGraph.manual": "true",
        },
      },
    ],
    edges: [],
  },
  workingGraph: {
    nodes: [
      {
        id: "method:submit-order",
        type: "METHOD",
        title: "OrderController.submit",
        inputs: ["java.lang.String"],
        outputs: ["com.example.SubmitResult"],
        certainty: "PROVEN",
        bindingStatus: "DESIGN_ONLY",
        position: { x: 120, y: 96 },
        sourceTag: "DRAFT_MANUAL",
        metadata: {
          "linkGraph.manual": "true",
        },
      },
    ],
    edges: [],
  },
  referenceFactGraph: null,
  designBaselineGraph: null,
  mermaidIssues: [],
  diffItems: [],
  syncPreviewItems: [],
  selectedNodeId: "method:submit-order",
} as const satisfies LinkGraphBootstrapState);

const flowchartBootstrapState = materializeThreeViewDocuments({
  visibleGraph: {
    nodes: [
      {
        id: "method:submit-order",
        type: "METHOD",
        title: "OrderController.submit",
        inputs: ["java.lang.String"],
        outputs: ["com.example.SubmitResult"],
        certainty: "PROVEN",
        bindingStatus: "DESIGN_ONLY",
        position: { x: 120, y: 96 },
        metadata: {
          "flowchart.kind": "ENTRY",
          "linkGraph.manual": "true",
        },
        sourceTag: "DRAFT_MANUAL",
      },
      {
        id: "action:write-order",
        type: "FLOW_ACTION",
        title: "writeOrder()",
        inputs: [],
        outputs: [],
        certainty: "PROVEN",
        bindingStatus: "BOUND",
        position: { x: 120, y: 320 },
        metadata: {
          "flowchart.kind": "PROCESS",
        },
      },
    ],
    edges: [
      {
        id: "edge:submit->write-order",
        type: "CONTROL_FLOW",
        source: "method:submit-order",
        target: "action:write-order",
        route: {
          sections: [
            {
              startPoint: { x: 240, y: 156 },
              bendPoints: [
                { x: 240, y: 220 },
              ],
              endPoint: { x: 240, y: 320 },
            },
          ],
        },
      },
    ],
  },
  workingGraph: {
    nodes: [
      {
        id: "method:submit-order",
        type: "METHOD",
        title: "OrderController.submit",
        inputs: ["java.lang.String"],
        outputs: ["com.example.SubmitResult"],
        certainty: "PROVEN",
        bindingStatus: "DESIGN_ONLY",
        position: { x: 120, y: 96 },
        metadata: {
          "flowchart.kind": "ENTRY",
          "linkGraph.manual": "true",
        },
        sourceTag: "DRAFT_MANUAL",
      },
      {
        id: "action:write-order",
        type: "FLOW_ACTION",
        title: "writeOrder()",
        inputs: [],
        outputs: [],
        certainty: "PROVEN",
        bindingStatus: "BOUND",
        position: { x: 120, y: 320 },
        metadata: {
          "flowchart.kind": "PROCESS",
        },
      },
    ],
    edges: [
      {
        id: "edge:submit->write-order",
        type: "CONTROL_FLOW",
        source: "method:submit-order",
        target: "action:write-order",
        route: {
          sections: [
            {
              startPoint: { x: 240, y: 156 },
              bendPoints: [
                { x: 240, y: 220 },
              ],
              endPoint: { x: 240, y: 320 },
            },
          ],
        },
      },
    ],
  },
  referenceFactGraph: null,
  designBaselineGraph: null,
  mermaidIssues: [],
  diffItems: [],
  syncPreviewItems: [],
  selectedNodeId: "method:submit-order",
  analysisDisplayMode: "FLOWCHART",
} as const satisfies LinkGraphBootstrapState);

describe("App layout bridge", () => {
  beforeEach(() => {
    resetEditorTransportForTest();
    window.linkGraphBootstrap = structuredClone(bootstrapState);
    window.linkGraphBridge = {
      graphChanged: vi.fn(),
      layoutChanged: vi.fn(),
      nodeSelected: vi.fn(),
      requestAnalysisDisplayMode: vi.fn(),
    };
  });

  it("publishes layout-only changes without sending a semantic graph mutation", async () => {
    const user = userEvent.setup();
    render(<App />);

    await user.click(screen.getByRole("button", { name: "simulate flowchart move" }));

    expect(window.linkGraphBridge?.layoutChanged).toHaveBeenCalledWith({
      positions: [
        {
          nodeId: "method:submit-order",
          x: 640,
          y: 320,
        },
      ],
    });
    expect(window.linkGraphBridge?.graphChanged).not.toHaveBeenCalled();
  });

  it("publishes semantic graph changes together with layout snapshots", async () => {
    const user = userEvent.setup();
    window.linkGraphBootstrap = structuredClone({
      ...bootstrapState,
      analysisDisplayMode: "FACT_GRAPH",
    });
    render(<App />);

    await user.click(screen.getByRole("button", { name: "simulate add" }));

    expect(window.linkGraphBridge?.graphChanged).toHaveBeenCalledTimes(1);
    expect(window.linkGraphBridge?.layoutChanged).toHaveBeenCalledWith({
      positions: expect.arrayContaining([
        expect.objectContaining({
          nodeId: "design:2",
          x: 420,
          y: 240,
        }),
      ]),
    });
  });

  it("consumes a layout-only transport slice while preserving the current graph semantics", async () => {
    const user = userEvent.setup();
    window.linkGraphBootstrap = structuredClone({
      ...bootstrapState,
      analysisDisplayMode: "FACT_GRAPH",
    });
    render(<App />);

    await user.click(screen.getByRole("button", { name: "simulate add" }));
    expect(window.linkGraphBridge?.graphChanged).toHaveBeenCalledTimes(1);

    act(() => {
      window.dispatchEvent(
        new CustomEvent("link-graph-bootstrap", {
          detail: {
            type: "LAYOUT_SLICE",
            sessionId: "session-layout-slice-1",
            revision: 2,
            state: {
              layoutState: {
                positions: {
                  "method:submit-order": { x: 640, y: 320 },
                },
              },
              layoutRevision: 2,
              snapshotRevision: 2,
              lastMessageType: "layoutChanged",
            },
          },
        }),
      );
    });

    expect(screen.getByRole("button", { name: "simulate add" })).toBeInTheDocument();
  });

  it("keeps the stored flowchart route after a manual node move, instead of clearing it and forcing the edge renderer into a fallback path", async () => {
    const user = userEvent.setup();
    resetEditorTransportForTest();
    window.linkGraphBootstrap = structuredClone(flowchartBootstrapState);
    window.linkGraphBridge = {
      graphChanged: vi.fn(),
      layoutChanged: vi.fn(),
      nodeSelected: vi.fn(),
    };

    render(<App />);

    expect(screen.getByTestId("flowchart-route-preserved")).toHaveTextContent("true");

    await user.click(screen.getByRole("button", { name: "simulate flowchart move" }));

    expect(screen.getByTestId("flowchart-route-preserved")).toHaveTextContent("true");
  });
});
