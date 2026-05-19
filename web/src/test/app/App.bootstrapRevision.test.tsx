import { act, render, screen } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { App } from "../../app/App";
import { resetEditorTransportForTest } from "../../app/editorTransport";
import { materializeThreeViewDocuments, type TestBootstrapState } from "../../app/testBootstrapState";

vi.mock("../../app/views/fact/FactGraphView", () => ({
  FactGraphView: ({
    view,
  }: {
    view: {
      visibleGraph: {
        nodes: Array<{
          id: string;
          position?: { x: number; y: number };
        }>;
      };
    };
  }) => (
    <div>
      {view.visibleGraph.nodes.map((node) => (
        <div key={node.id}>
          {`${node.id}:${node.position?.x ?? "na"},${node.position?.y ?? "na"}`}
        </div>
      ))}
    </div>
  ),
}));

vi.mock("../../app/views/flowchart/FlowchartView", () => ({
  FlowchartView: ({
    view,
  }: {
    view: {
      visibleGraph: {
        nodes: Array<{
          id: string;
          position?: { x: number; y: number };
        }>;
        edges: Array<{
          id: string;
          route?: unknown;
        }>;
      };
    };
  }) => (
    <div>
      {view.visibleGraph.nodes.map((node) => (
        <div key={node.id}>
          {`${node.id}:${node.position?.x ?? "na"},${node.position?.y ?? "na"}`}
        </div>
      ))}
      {view.visibleGraph.edges.map((edge) => (
        <div key={edge.id}>
          {`${edge.id}:${edge.route ? "route" : "no-route"}`}
        </div>
      ))}
    </div>
  ),
}));

vi.mock("../../app/views/architecture/ArchitectureGraphView", () => ({
  ArchitectureGraphView: ({
    view,
  }: {
    view: {
      visibleGraph: {
        nodes: Array<{ id: string }>;
        edges: Array<{ id: string }>;
      };
    };
  }) => (
    <div>
      <div data-testid="architecture-node-count">{view.visibleGraph.nodes.length}</div>
      <div data-testid="architecture-node-ids">{view.visibleGraph.nodes.map((node) => node.id).join("|")}</div>
      <div data-testid="architecture-edge-count">{view.visibleGraph.edges.length}</div>
    </div>
  ),
}));

const bootstrapState = materializeThreeViewDocuments({
  analysisDisplayMode: "FACT_GRAPH",
  visibleGraph: {
    nodes: [
      {
        id: "method:submit-order",
        type: "METHOD",
        title: "OrderController.submit",
        inputs: ["java.lang.String"],
        outputs: ["com.example.SubmitResult"],
        certainty: "PROVEN",
        bindingStatus: "BOUND",
        position: { x: 120, y: 96 },
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
        bindingStatus: "BOUND",
        position: { x: 120, y: 96 },
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
  semanticRevision: 3,
  layoutRevision: 1,
  snapshotRevision: 4,
});

const flowchartBootstrapState = materializeThreeViewDocuments({
  analysisDisplayMode: "FLOWCHART",
  visibleGraph: {
    nodes: [
      {
        id: "flow:entry",
        type: "METHOD",
        title: "entry",
        inputs: [],
        outputs: [],
        certainty: "PROVEN",
        bindingStatus: "BOUND",
        position: { x: 120, y: 96 },
        metadata: {
          "flowchart.kind": "ENTRY",
        },
      },
      {
        id: "flow:decision",
        type: "FLOW_ACTION",
        title: "decision",
        inputs: [],
        outputs: [],
        certainty: "PROVEN",
        bindingStatus: "BOUND",
        position: { x: 420, y: 280 },
        metadata: {
          "flowchart.kind": "PROCESS",
        },
      },
    ],
    edges: [
      {
        id: "edge:entry->decision",
        type: "CONTROL_FLOW",
        source: "flow:entry",
        target: "flow:decision",
        route: {
          sections: [
            {
              startPoint: { x: 240, y: 156 },
              bendPoints: [{ x: 240, y: 220 }],
              endPoint: { x: 420, y: 220 },
            },
          ],
        },
      },
    ],
  },
  workingGraph: {
    nodes: [
      {
        id: "flow:entry",
        type: "METHOD",
        title: "entry",
        inputs: [],
        outputs: [],
        certainty: "PROVEN",
        bindingStatus: "BOUND",
        position: { x: 120, y: 96 },
        metadata: {
          "flowchart.kind": "ENTRY",
        },
      },
      {
        id: "flow:decision",
        type: "FLOW_ACTION",
        title: "decision",
        inputs: [],
        outputs: [],
        certainty: "PROVEN",
        bindingStatus: "BOUND",
        position: { x: 420, y: 280 },
        metadata: {
          "flowchart.kind": "PROCESS",
        },
      },
    ],
    edges: [
      {
        id: "edge:entry->decision",
        type: "CONTROL_FLOW",
        source: "flow:entry",
        target: "flow:decision",
        route: {
          sections: [
            {
              startPoint: { x: 240, y: 156 },
              bendPoints: [{ x: 240, y: 220 }],
              endPoint: { x: 420, y: 220 },
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
  selectedNodeId: "flow:entry",
  semanticRevision: 3,
  layoutRevision: 1,
  snapshotRevision: 4,
});

function dispatchBootstrapState(state: TestBootstrapState, revision = state.snapshotRevision ?? 1) {
  window.dispatchEvent(
    new CustomEvent("link-graph-bootstrap", {
      detail: {
        sessionId: "test-session-bootstrap-revision",
        revision,
        state,
      },
    }),
  );
}

describe("App bootstrap revisions", () => {
  beforeEach(() => {
    resetEditorTransportForTest();
  });

  it("applies layout-only bootstrap updates without needing a semantic graph refresh", async () => {
    window.linkGraphBootstrap = structuredClone(bootstrapState);
    window.linkGraphBridge = {
      nodeSelected: vi.fn(),
    };

    render(<App />);

    expect(await screen.findByText("method:submit-order:120,96")).toBeInTheDocument();

    act(() => {
      dispatchBootstrapState(materializeThreeViewDocuments({
        ...structuredClone(bootstrapState),
        visibleGraph: {
          nodes: [
            {
              ...structuredClone(bootstrapState.visibleGraph.nodes[0]),
              position: { x: 640, y: 320 },
            },
          ],
          edges: [],
        },
        workingGraph: {
          nodes: [
            {
              ...structuredClone(bootstrapState.workingGraph.nodes[0]),
              position: { x: 640, y: 320 },
            },
          ],
          edges: [],
        },
        layoutState: {
          positions: {
            "method:submit-order": { x: 640, y: 320 },
          },
        },
        semanticRevision: 3,
        layoutRevision: 2,
        snapshotRevision: 5,
      }));
    });

    expect(await screen.findByText("method:submit-order:640,320")).toBeInTheDocument();
  });

  it("keeps the existing flowchart edge route when a semantic bootstrap refresh re-sends the same edge without route geometry", async () => {
    window.linkGraphBootstrap = structuredClone(flowchartBootstrapState);
    window.linkGraphBridge = {
      nodeSelected: vi.fn(),
    };

    render(<App />);

    expect(await screen.findByText("edge:entry->decision:route")).toBeInTheDocument();

    act(() => {
      dispatchBootstrapState(materializeThreeViewDocuments({
        ...structuredClone(flowchartBootstrapState),
        visibleGraph: {
          nodes: structuredClone(flowchartBootstrapState.visibleGraph.nodes),
          edges: [
            {
              id: "edge:entry->decision",
              type: "CONTROL_FLOW",
              source: "flow:entry",
              target: "flow:decision",
            },
          ],
        },
        workingGraph: {
          nodes: structuredClone(flowchartBootstrapState.workingGraph.nodes),
          edges: [
            {
              id: "edge:entry->decision",
              type: "CONTROL_FLOW",
              source: "flow:entry",
              target: "flow:decision",
            },
          ],
        },
        semanticRevision: 4,
        layoutRevision: 1,
        snapshotRevision: 5,
      }));
    });

    expect(await screen.findByText("edge:entry->decision:route")).toBeInTheDocument();
  });

  it("does not reuse an empty current architecture graph when a same-revision architecture result arrives", async () => {
    window.linkGraphBootstrap = {
      ...structuredClone(bootstrapState),
      analysisDisplayMode: "FACT_GRAPH",
      currentSceneId: "WORKSPACE_FACT",
      architectureGraphView: {
        visibleGraph: { nodes: [], edges: [] },
        fullGraph: { nodes: [], edges: [] },
        anchorNodeId: null,
        summary: {
          moduleCount: 0,
          packageCount: 0,
          serviceCount: 0,
          resourceCount: 0,
          layerCount: 0,
          relationCount: 0,
          classCount: 0,
        },
      },
    };
    window.linkGraphBridge = {
      nodeSelected: vi.fn(),
    };

    render(<App />);

    act(() => {
      dispatchBootstrapState({
        ...structuredClone(window.linkGraphBootstrap!),
        analysisDisplayMode: "ARCHITECTURE_GRAPH",
        currentSceneId: "WORKSPACE_ARCHITECTURE_GRAPH",
        sceneStates: {
          ...structuredClone(window.linkGraphBootstrap!.sceneStates),
          WORKSPACE_ARCHITECTURE_GRAPH: {
            selectedNodeId: null,
            anchorNodeId: null,
            layoutState: { positions: {} },
            layoutRevision: 0,
            collapsedNodeIds: [],
          },
        },
        architectureGraphView: {
          visibleGraph: {
            nodes: [
              {
                id: "module:app",
                type: "MODULE",
                title: "app",
                inputs: [],
                outputs: [],
                certainty: "PROVEN",
                bindingStatus: "BOUND",
              },
            ],
            edges: [],
          },
          fullGraph: {
            nodes: [
              {
                id: "module:app",
                type: "MODULE",
                title: "app",
                inputs: [],
                outputs: [],
                certainty: "PROVEN",
                bindingStatus: "BOUND",
              },
            ],
            edges: [],
          },
          anchorNodeId: "module:app",
          summary: {
            moduleCount: 1,
            packageCount: 0,
            serviceCount: 0,
            resourceCount: 0,
            layerCount: 0,
            relationCount: 0,
            classCount: 0,
          },
        },
        operationFeedback: {
          level: "SUCCESS",
          message: "已加载架构图。",
        },
        semanticRevision: 3,
        workspaceRevision: bootstrapState.workspaceRevision,
        snapshotRevision: 5,
      });
    });

    expect(await screen.findByTestId("architecture-node-count")).toHaveTextContent("1");
    expect(screen.getByTestId("architecture-node-ids")).toHaveTextContent("module:app");
  });
});
