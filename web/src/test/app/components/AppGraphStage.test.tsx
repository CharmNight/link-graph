import { render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { AppGraphStage } from "../../../app/components/AppGraphStage";
import type {
  ArchitectureGraphViewDocument,
  ClassDiagramViewDocument,
  FactGraphViewDocument,
  FlowchartViewDocument,
  GraphViewPresentation,
  LinkGraphDocument,
  LinkGraphNode,
  ResourceRelationViewDocument,
  ReviewGraphViewDocument,
} from "../../../app/types";
import type { EditableStageProps, IndexedReadonlyStageProps } from "../../../app/views/viewStageProps";

vi.mock("../../../app/views/fact/FactGraphView", () => ({
  FactGraphView: ({ view }: { view: { visibleGraph: { nodes: Array<{ id: string }> } } }) => (
    <div data-testid="fact-stage">{view.visibleGraph.nodes.map((node) => node.id).join(",")}</div>
  ),
}));

vi.mock("../../../app/views/flowchart/FlowchartView", () => ({
  FlowchartView: ({
    view,
    layoutView,
  }: {
    view: { visibleGraph: { nodes: Array<{ id: string }> } };
    layoutView: { visibleGraph: { nodes: Array<{ id: string }> } };
  }) => (
    <div data-testid="flowchart-stage">
      <span data-testid="flowchart-visible">{view.visibleGraph.nodes.map((node) => node.id).join(",")}</span>
      <span data-testid="flowchart-layout">{layoutView.visibleGraph.nodes.map((node) => node.id).join(",")}</span>
    </div>
  ),
}));

vi.mock("../../../app/views/resource/ResourceRelationView", () => ({
  ResourceRelationView: ({ view }: { view: { visibleGraph: { nodes: Array<{ id: string }> } } }) => (
    <div data-testid="resource-stage">{view.visibleGraph.nodes.map((node) => node.id).join(",")}</div>
  ),
}));

vi.mock("../../../app/views/architecture/ArchitectureGraphView", () => ({
  ArchitectureGraphView: ({ view }: { view: { visibleGraph: { nodes: Array<{ id: string }> } } }) => (
    <div data-testid="architecture-stage">{view.visibleGraph.nodes.map((node) => node.id).join(",")}</div>
  ),
}));

vi.mock("../../../app/views/class-diagram/ClassDiagramView", () => ({
  ClassDiagramView: ({ view }: { view: { visibleGraph: { nodes: Array<{ id: string }> } } }) => (
    <div data-testid="class-diagram-stage">{view.visibleGraph.nodes.map((node) => node.id).join(",")}</div>
  ),
}));

vi.mock("../../../app/views/review/ReviewGraphView", () => ({
  ReviewGraphView: ({ view }: { view: { visibleGraph: { nodes: Array<{ id: string }> } } }) => (
    <div data-testid="review-stage">{view.visibleGraph.nodes.map((node) => node.id).join(",")}</div>
  ),
}));

function editableStageProps(): EditableStageProps {
  return {
    selectedNodeId: null,
    onAddNode: vi.fn(),
    onSelectNode: vi.fn(),
    onInspectNode: vi.fn(),
    onDeleteNode: vi.fn(),
    onCreateEdge: vi.fn(),
    onDeleteEdge: vi.fn(),
    onMoveNode: vi.fn(),
    onRequestSourceNavigation: vi.fn(),
    onImportMermaid: vi.fn(),
  };
}

function indexedReadonlyStageProps(): IndexedReadonlyStageProps {
  return {
    selectedNodeId: null,
    onSelectNode: vi.fn(),
    onInspectNode: vi.fn(),
    onMoveNode: vi.fn(),
    onRequestSourceNavigation: vi.fn(),
  };
}

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

function graphWithNode(id: string, type: LinkGraphNode["type"] = "METHOD"): LinkGraphDocument {
  return {
    nodes: [
      {
        id,
        type,
        title: id,
        inputs: [],
        outputs: [],
        confidence: "VERIFIED",
        binding: "CODE_BOUND",
      },
    ],
    edges: [],
  };
}

function factGraphView(nodeId: string): FactGraphViewDocument {
  const graph = graphWithNode(nodeId);
  return {
    visibleGraph: graph,
    fullGraph: graph,
    summary: {
      anchorTitle: nodeId,
      visibleNodeCount: 1,
      fullNodeCount: 1,
    },
    presentation: EMPTY_PRESENTATION,
  };
}

function flowchartView(nodeId: string): FlowchartViewDocument {
  const graph = graphWithNode(nodeId);
  return {
    visibleGraph: graph,
    fullGraph: graph,
    summary: {
      nodeCount: 1,
      branchCount: 0,
      exceptionPathCount: 0,
    },
  };
}

function resourceRelationView(nodeId: string): ResourceRelationViewDocument {
  const graph = graphWithNode(nodeId, "RESOURCE");
  return {
    visibleGraph: graph,
    fullGraph: graph,
    summary: {
      visibleNodeCount: 1,
      relationCount: 0,
      resourceCount: 1,
      fallbackReason: "NO_BINDING_RELATIONS",
      laneCounts: {},
    },
  };
}

function architectureGraphView(nodeId: string): ArchitectureGraphViewDocument {
  const graph = graphWithNode(nodeId, "MODULE");
  return {
    visibleGraph: graph,
    fullGraph: graph,
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
  };
}

function classDiagramView(nodeId: string): ClassDiagramViewDocument {
  const graph = graphWithNode(nodeId, "CLASS");
  return {
    visibleGraph: graph,
    fullGraph: graph,
    summary: {
      classCount: 1,
      interfaceCount: 0,
      enumCount: 0,
      annotationCount: 0,
      recordCount: 0,
      objectCount: 0,
      relationCount: 0,
    },
    presentation: EMPTY_PRESENTATION,
  };
}

function reviewGraphView(nodeId: string): ReviewGraphViewDocument {
  const graph = graphWithNode(nodeId, "METHOD");
  return {
    visibleGraph: graph,
    fullGraph: graph,
    summary: {
      changedSymbolCount: 1,
      upstreamCount: 0,
      downstreamCount: 0,
      relatedTestCount: 0,
      affectedPackageCount: 0,
      affectedModuleCount: 0,
      evidenceRefCount: 0,
    },
  };
}

function stageDocuments() {
  return {
    factGraphView: factGraphView("fact-node"),
    presentedFlowchartView: flowchartView("presented-node"),
    flowchartView: flowchartView("layout-node"),
    resourceRelationView: resourceRelationView("resource-node"),
    architectureGraphView: architectureGraphView("architecture-node"),
    classDiagramView: classDiagramView("class-node"),
    reviewGraphView: reviewGraphView("review-node"),
  };
}

describe("AppGraphStage", () => {
  it("routes the flowchart stage through the presented document while preserving the layout document", () => {
    render(
      <AppGraphStage
        analysisDisplayMode="FLOWCHART"
        stageProps={editableStageProps()}
        {...stageDocuments()}
      />,
    );

    expect(screen.getByTestId("flowchart-stage")).toBeInTheDocument();
    expect(screen.getByTestId("flowchart-visible")).toHaveTextContent("presented-node");
    expect(screen.getByTestId("flowchart-layout")).toHaveTextContent("layout-node");
  });

  it("switches to the fact and resource stages for the corresponding analysis modes", () => {
    const props = stageDocuments();
    const { rerender } = render(
      <AppGraphStage
        analysisDisplayMode="FACT_GRAPH"
        {...props}
        stageProps={editableStageProps()}
      />,
    );

    expect(screen.getByTestId("fact-stage")).toHaveTextContent("fact-node");

    rerender(
      <AppGraphStage
        analysisDisplayMode="RESOURCE_RELATION_VIEW"
        {...props}
        stageProps={editableStageProps()}
      />,
    );

    expect(screen.getByTestId("resource-stage")).toHaveTextContent("resource-node");

    rerender(
      <AppGraphStage
        analysisDisplayMode="REVIEW_GRAPH"
        {...props}
        stageProps={indexedReadonlyStageProps()}
      />,
    );

    expect(screen.getByTestId("review-stage")).toHaveTextContent("review-node");
  });
});
