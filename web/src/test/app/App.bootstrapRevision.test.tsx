import { act, render, screen } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { App } from "../../app/App";
import { resetEditorTransportForTest } from "../../app/editorTransport";
import { materializeThreeViewDocuments, type TestBootstrapState } from "../../app/testBootstrapState";
import type { GraphViewPresentation, LinkGraphBootstrapState } from "../../app/types";
import { installBridgeCommandSpy } from "./bridgeTestUtils";

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

vi.mock("../../app/views/class-diagram/ClassDiagramView", () => ({
  ClassDiagramView: ({
    view,
    selectedNodeId,
  }: {
    selectedNodeId?: string | null;
    view: {
      anchorNodeId?: string | null;
      visibleGraph: {
        nodes: Array<{ id: string }>;
        edges: Array<{ id: string }>;
      };
    };
  }) => (
    <div>
      <div data-testid="class-diagram-anchor">{view.anchorNodeId ?? ""}</div>
      <div data-testid="class-diagram-selected">{selectedNodeId ?? ""}</div>
      <div data-testid="class-diagram-node-count">{view.visibleGraph.nodes.length}</div>
      <div data-testid="class-diagram-node-ids">{view.visibleGraph.nodes.map((node) => node.id).join("|")}</div>
      <div data-testid="class-diagram-edge-count">{view.visibleGraph.edges.length}</div>
    </div>
  ),
}));

const EMPTY_PRESENTATION: GraphViewPresentation = {
  target: {
    nodeId: null,
    title: "",
    subtitle: "",
    location: null,
  },
  lanes: [],
  hiddenBuckets: [],
  controls: {
    primaryScope: "",
    availableScopes: [],
    searchable: true,
    expandable: true,
  },
};

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

function dispatchBootstrapState(state: LinkGraphBootstrapState, revision = state.snapshotRevision ?? 1) {
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
    installBridgeCommandSpy();

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
    installBridgeCommandSpy();

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
        presentation: EMPTY_PRESENTATION,
      },
    };
    installBridgeCommandSpy();

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
          presentation: EMPTY_PRESENTATION,
        },
        operationFeedback: {
          level: "SUCCESS",
          message: "已加载项目结构。",
        },
        semanticRevision: 3,
        workspaceRevision: bootstrapState.workspaceRevision,
        snapshotRevision: 5,
      });
    });

    expect(await screen.findByTestId("architecture-node-count")).toHaveTextContent("1");
    expect(screen.getByTestId("architecture-node-ids")).toHaveTextContent("module:app");
  });

  it("uses the incoming class diagram anchor when a same-revision class diagram result arrives", async () => {
    const staleAnchorNode = {
      id: "class:config",
      type: "CLASS" as const,
      title: "KafkaConfig",
      inputs: [],
      outputs: [],
      certainty: "PROVEN" as const,
      bindingStatus: "BOUND" as const,
    };
    const requestedAnchorNode = {
      id: "class:validator",
      type: "CLASS" as const,
      title: "MetadataVersionConfigValidator",
      inputs: [],
      outputs: [],
      certainty: "PROVEN" as const,
      bindingStatus: "BOUND" as const,
    };
    const relatedNode = {
      id: "class:metadata",
      type: "CLASS" as const,
      title: "MetadataDelta",
      inputs: [],
      outputs: [],
      certainty: "PROVEN" as const,
      bindingStatus: "BOUND" as const,
    };
    const initialState = materializeThreeViewDocuments({
      ...structuredClone(bootstrapState),
      analysisDisplayMode: "CLASS_DIAGRAM",
      currentSceneId: "WORKSPACE_CLASS_DIAGRAM",
      visibleGraph: {
        nodes: [staleAnchorNode],
        edges: [],
      },
      classDiagramView: {
        visibleGraph: { nodes: [staleAnchorNode], edges: [] },
        fullGraph: { nodes: [staleAnchorNode], edges: [] },
        anchorNodeId: staleAnchorNode.id,
        summary: {
          classCount: 1,
          fieldCount: 0,
          interfaceCount: 0,
          enumCount: 0,
          annotationCount: 0,
          recordCount: 0,
          objectCount: 0,
          relationCount: 0,
          spiProviderCount: 0,
          reflectionRelationCount: 0,
          relationCompleteness: "STRUCTURE_ONLY",
          scopeTypeCount: 1,
          projectTypeCount: 1,
          projectClassCount: 1,
          scopeBasis: "CLASS_NEIGHBORHOOD",
          anchorTypeNodeId: staleAnchorNode.id,
          anchorTypeTitle: staleAnchorNode.title,
          anchorTypeQualifiedName: staleAnchorNode.title,
          neighborhoodLimit: 24,
          memberLimit: 5,
          neighborhoodCandidateTypeCount: 1,
          neighborhoodTruncated: false,
        },
        presentation: EMPTY_PRESENTATION,
      },
      sceneStates: {
        ...structuredClone(bootstrapState.sceneStates),
        WORKSPACE_CLASS_DIAGRAM: {
          selectedNodeId: staleAnchorNode.id,
          anchorNodeId: staleAnchorNode.id,
          layoutState: { positions: {} },
          layoutRevision: 0,
          collapsedNodeIds: [],
        },
      },
      semanticRevision: 3,
      workspaceRevision: bootstrapState.workspaceRevision,
      snapshotRevision: 4,
    });
    window.linkGraphBootstrap = initialState;
    installBridgeCommandSpy();

    render(<App />);

    expect(await screen.findByTestId("class-diagram-anchor")).toHaveTextContent(staleAnchorNode.id);

    act(() => {
      dispatchBootstrapState({
        ...structuredClone(initialState),
        classDiagramView: {
          visibleGraph: {
            nodes: [requestedAnchorNode, staleAnchorNode, relatedNode],
            edges: [
              {
                id: "edge:validator->config",
                type: "USES_TYPE",
                source: requestedAnchorNode.id,
                target: staleAnchorNode.id,
              },
            ],
          },
          fullGraph: {
            nodes: [requestedAnchorNode, staleAnchorNode, relatedNode],
            edges: [
              {
                id: "edge:validator->config",
                type: "USES_TYPE",
                source: requestedAnchorNode.id,
                target: staleAnchorNode.id,
              },
            ],
          },
          anchorNodeId: requestedAnchorNode.id,
          summary: {
            classCount: 3,
            fieldCount: 0,
            interfaceCount: 0,
            enumCount: 0,
            annotationCount: 0,
            recordCount: 0,
            objectCount: 0,
            relationCount: 1,
            spiProviderCount: 0,
            reflectionRelationCount: 0,
            relationCompleteness: "COMPLETE",
            scopeTypeCount: 3,
            projectTypeCount: 3,
            projectClassCount: 3,
            scopeBasis: "CLASS_NEIGHBORHOOD",
            anchorTypeNodeId: requestedAnchorNode.id,
            anchorTypeTitle: requestedAnchorNode.title,
            anchorTypeQualifiedName: requestedAnchorNode.title,
            neighborhoodLimit: 24,
            memberLimit: 5,
            neighborhoodCandidateTypeCount: 3,
            neighborhoodTruncated: false,
          },
          presentation: EMPTY_PRESENTATION,
        },
        sceneStates: {
          ...structuredClone(initialState.sceneStates),
          WORKSPACE_CLASS_DIAGRAM: {
            selectedNodeId: requestedAnchorNode.id,
            anchorNodeId: requestedAnchorNode.id,
            layoutState: { positions: {} },
            layoutRevision: 0,
            collapsedNodeIds: [],
          },
        },
        operationFeedback: {
          level: "SUCCESS",
          message: "已加载类图。",
        },
        semanticRevision: 3,
        workspaceRevision: bootstrapState.workspaceRevision,
        snapshotRevision: 5,
      });
    });

    expect(await screen.findByTestId("class-diagram-anchor")).toHaveTextContent(requestedAnchorNode.id);
    expect(screen.getByTestId("class-diagram-node-count")).toHaveTextContent("3");
    expect(screen.getByTestId("class-diagram-node-ids")).toHaveTextContent("class:validator|class:config|class:metadata");
  });

  it("keeps the current class diagram visible while a same-revision class usage request is running", async () => {
    const anchorNode = {
      id: "class:accessor",
      type: "CLASS" as const,
      title: "AbstractNestablePropertyAccessor",
      inputs: [],
      outputs: [],
      certainty: "PROVEN" as const,
      bindingStatus: "BOUND" as const,
    };
    const callerNode = {
      id: "class:bean-wrapper",
      type: "CLASS" as const,
      title: "BeanWrapperImpl",
      inputs: [],
      outputs: [],
      certainty: "PROVEN" as const,
      bindingStatus: "BOUND" as const,
    };
    const initialState = materializeThreeViewDocuments({
      ...structuredClone(bootstrapState),
      analysisDisplayMode: "CLASS_DIAGRAM",
      currentSceneId: "WORKSPACE_CLASS_DIAGRAM",
      visibleGraph: {
        nodes: [anchorNode, callerNode],
        edges: [
          {
            id: "edge:caller->anchor",
            type: "USES_TYPE",
            source: callerNode.id,
            target: anchorNode.id,
          },
        ],
      },
      classDiagramView: {
        visibleGraph: {
          nodes: [anchorNode, callerNode],
          edges: [
            {
              id: "edge:caller->anchor",
              type: "USES_TYPE",
              source: callerNode.id,
              target: anchorNode.id,
            },
          ],
        },
        fullGraph: {
          nodes: [anchorNode, callerNode],
          edges: [
            {
              id: "edge:caller->anchor",
              type: "USES_TYPE",
              source: callerNode.id,
              target: anchorNode.id,
            },
          ],
        },
        anchorNodeId: anchorNode.id,
        summary: {
          classCount: 2,
          fieldCount: 0,
          interfaceCount: 0,
          enumCount: 0,
          annotationCount: 0,
          recordCount: 0,
          objectCount: 0,
          relationCount: 1,
          spiProviderCount: 0,
          reflectionRelationCount: 0,
          relationCompleteness: "STRUCTURE_ONLY",
          scopeTypeCount: 2,
          projectTypeCount: 2,
          projectClassCount: 2,
          scopeBasis: "CLASS_NEIGHBORHOOD",
          anchorTypeNodeId: anchorNode.id,
          anchorTypeTitle: anchorNode.title,
          anchorTypeQualifiedName: anchorNode.title,
          neighborhoodLimit: 24,
          memberLimit: 5,
          neighborhoodCandidateTypeCount: 2,
          neighborhoodTruncated: false,
        },
        presentation: EMPTY_PRESENTATION,
      },
      sceneStates: {
        ...structuredClone(bootstrapState.sceneStates),
        WORKSPACE_CLASS_DIAGRAM: {
          selectedNodeId: anchorNode.id,
          anchorNodeId: anchorNode.id,
          layoutState: { positions: {} },
          layoutRevision: 0,
          collapsedNodeIds: [],
        },
      },
      semanticRevision: 3,
      workspaceRevision: bootstrapState.workspaceRevision,
      snapshotRevision: 4,
    });
    window.linkGraphBootstrap = initialState;
    installBridgeCommandSpy();

    render(<App />);

    expect(await screen.findByTestId("class-diagram-node-ids")).toHaveTextContent("class:accessor|class:bean-wrapper");

    act(() => {
      dispatchBootstrapState({
        ...structuredClone(initialState),
        classDiagramView: {
          visibleGraph: { nodes: [], edges: [] },
          fullGraph: { nodes: [], edges: [] },
          anchorNodeId: null,
          summary: {
            classCount: 0,
            fieldCount: 0,
            interfaceCount: 0,
            enumCount: 0,
            annotationCount: 0,
            recordCount: 0,
            objectCount: 0,
            relationCount: 0,
            spiProviderCount: 0,
            reflectionRelationCount: 0,
            relationCompleteness: "STRUCTURE_ONLY",
            scopeTypeCount: 0,
            projectTypeCount: 0,
            projectClassCount: 0,
            scopeBasis: "CLASS_NEIGHBORHOOD",
            anchorTypeNodeId: null,
            anchorTypeTitle: null,
            anchorTypeQualifiedName: null,
            neighborhoodLimit: 24,
            memberLimit: 5,
            neighborhoodCandidateTypeCount: 0,
            neighborhoodTruncated: false,
          },
          presentation: EMPTY_PRESENTATION,
        },
        indexedGraphRequestStates: {
          CLASS_DIAGRAM: {
            phase: "RUNNING",
            statusMessage: "正在查找类使用处。",
          },
        },
        operationFeedback: {
          level: "INFO",
          message: "正在查找类使用处。",
        },
        semanticRevision: 3,
        workspaceRevision: bootstrapState.workspaceRevision,
        snapshotRevision: 5,
      });
    });

    expect(screen.getByTestId("class-diagram-anchor")).toHaveTextContent(anchorNode.id);
    expect(screen.getByTestId("class-diagram-node-count")).toHaveTextContent("2");
    expect(screen.getByTestId("class-diagram-node-ids")).toHaveTextContent("class:accessor|class:bean-wrapper");
  });

  it("does not let a stale class diagram scene anchor override the incoming view anchor", async () => {
    const staleAnchorNode = {
      id: "class:config",
      type: "CLASS" as const,
      title: "KafkaConfig",
      inputs: [],
      outputs: [],
      certainty: "PROVEN" as const,
      bindingStatus: "BOUND" as const,
    };
    const requestedAnchorNode = {
      id: "class:validator",
      type: "CLASS" as const,
      title: "MetadataVersionConfigValidator",
      inputs: [],
      outputs: [],
      certainty: "PROVEN" as const,
      bindingStatus: "BOUND" as const,
    };
    const initialState = materializeThreeViewDocuments({
      ...structuredClone(bootstrapState),
      analysisDisplayMode: "CLASS_DIAGRAM",
      currentSceneId: "WORKSPACE_CLASS_DIAGRAM",
      visibleGraph: {
        nodes: [staleAnchorNode],
        edges: [],
      },
      classDiagramView: {
        visibleGraph: { nodes: [staleAnchorNode], edges: [] },
        fullGraph: { nodes: [staleAnchorNode], edges: [] },
        anchorNodeId: staleAnchorNode.id,
        summary: {
          classCount: 1,
          fieldCount: 0,
          interfaceCount: 0,
          enumCount: 0,
          annotationCount: 0,
          recordCount: 0,
          objectCount: 0,
          relationCount: 0,
          spiProviderCount: 0,
          reflectionRelationCount: 0,
          relationCompleteness: "COMPLETE",
          scopeTypeCount: 1,
          projectTypeCount: 1,
          projectClassCount: 1,
          scopeBasis: "CLASS_NEIGHBORHOOD",
          anchorTypeNodeId: staleAnchorNode.id,
          anchorTypeTitle: staleAnchorNode.title,
          anchorTypeQualifiedName: staleAnchorNode.title,
          neighborhoodLimit: 24,
          memberLimit: 5,
          neighborhoodCandidateTypeCount: 1,
          neighborhoodTruncated: false,
        },
        presentation: EMPTY_PRESENTATION,
      },
      sceneStates: {
        ...structuredClone(bootstrapState.sceneStates),
        WORKSPACE_CLASS_DIAGRAM: {
          selectedNodeId: staleAnchorNode.id,
          anchorNodeId: staleAnchorNode.id,
          layoutState: { positions: {} },
          layoutRevision: 0,
          collapsedNodeIds: [],
        },
      },
      semanticRevision: 3,
      workspaceRevision: bootstrapState.workspaceRevision,
      snapshotRevision: 4,
    });
    window.linkGraphBootstrap = initialState;
    installBridgeCommandSpy();

    render(<App />);

    expect(await screen.findByTestId("class-diagram-anchor")).toHaveTextContent(staleAnchorNode.id);

    act(() => {
      dispatchBootstrapState({
        ...structuredClone(initialState),
        classDiagramView: {
          visibleGraph: {
            nodes: [staleAnchorNode, requestedAnchorNode],
            edges: [
              {
                id: "edge:validator->config",
                type: "USES_TYPE",
                source: requestedAnchorNode.id,
                target: staleAnchorNode.id,
              },
            ],
          },
          fullGraph: {
            nodes: [staleAnchorNode, requestedAnchorNode],
            edges: [
              {
                id: "edge:validator->config",
                type: "USES_TYPE",
                source: requestedAnchorNode.id,
                target: staleAnchorNode.id,
              },
            ],
          },
          anchorNodeId: requestedAnchorNode.id,
          summary: {
            classCount: 2,
            fieldCount: 0,
            interfaceCount: 0,
            enumCount: 0,
            annotationCount: 0,
            recordCount: 0,
            objectCount: 0,
            relationCount: 1,
            spiProviderCount: 0,
            reflectionRelationCount: 0,
            relationCompleteness: "STRUCTURE_ONLY",
            scopeTypeCount: 2,
            projectTypeCount: 2,
            projectClassCount: 2,
            scopeBasis: "CLASS_NEIGHBORHOOD",
            anchorTypeNodeId: requestedAnchorNode.id,
            anchorTypeTitle: requestedAnchorNode.title,
            anchorTypeQualifiedName: requestedAnchorNode.title,
            neighborhoodLimit: 24,
            memberLimit: 5,
            neighborhoodCandidateTypeCount: 2,
            neighborhoodTruncated: false,
          },
          presentation: EMPTY_PRESENTATION,
        },
        sceneStates: {
          ...structuredClone(initialState.sceneStates),
          WORKSPACE_CLASS_DIAGRAM: {
            selectedNodeId: staleAnchorNode.id,
            anchorNodeId: staleAnchorNode.id,
            layoutState: { positions: {} },
            layoutRevision: 0,
            collapsedNodeIds: [],
          },
        },
        operationFeedback: {
          level: "INFO",
          message: "已加载类图结构，正在补齐完整关系。",
        },
        semanticRevision: 3,
        workspaceRevision: bootstrapState.workspaceRevision,
        snapshotRevision: 5,
      });
    });

    expect(await screen.findByTestId("class-diagram-anchor")).toHaveTextContent(requestedAnchorNode.id);
    expect(screen.getByTestId("class-diagram-selected")).toHaveTextContent(requestedAnchorNode.id);
    expect(screen.getByTestId("class-diagram-node-ids")).toHaveTextContent("class:config|class:validator");
  });
});
