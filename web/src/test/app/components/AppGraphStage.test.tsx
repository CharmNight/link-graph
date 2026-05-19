import { render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { AppGraphStage } from "../../../app/components/AppGraphStage";
import type { ViewStageProps } from "../../../app/views/viewStageProps";

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

function stageProps(): ViewStageProps {
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

describe("AppGraphStage", () => {
  it("routes the flowchart stage through the presented document while preserving the layout document", () => {
    render(
      <AppGraphStage
        analysisDisplayMode="FLOWCHART"
        stageProps={stageProps()}
        factGraphView={{ visibleGraph: { nodes: [{ id: "fact-node" }], edges: [] } } as never}
        presentedFlowchartView={{ visibleGraph: { nodes: [{ id: "presented-node" }], edges: [] } } as never}
        flowchartView={{ visibleGraph: { nodes: [{ id: "layout-node" }], edges: [] } } as never}
        resourceRelationView={{ visibleGraph: { nodes: [{ id: "resource-node" }], edges: [] } } as never}
        architectureGraphView={{ visibleGraph: { nodes: [{ id: "architecture-node" }], edges: [] } } as never}
        classDiagramView={{ visibleGraph: { nodes: [{ id: "class-node" }], edges: [] } } as never}
        reviewGraphView={{ visibleGraph: { nodes: [{ id: "review-node" }], edges: [] } } as never}
      />,
    );

    expect(screen.getByTestId("flowchart-stage")).toBeInTheDocument();
    expect(screen.getByTestId("flowchart-visible")).toHaveTextContent("presented-node");
    expect(screen.getByTestId("flowchart-layout")).toHaveTextContent("layout-node");
  });

  it("switches to the fact and resource stages for the corresponding analysis modes", () => {
    const props = {
      stageProps: stageProps(),
      factGraphView: { visibleGraph: { nodes: [{ id: "fact-node" }], edges: [] } } as never,
      presentedFlowchartView: { visibleGraph: { nodes: [{ id: "presented-node" }], edges: [] } } as never,
      flowchartView: { visibleGraph: { nodes: [{ id: "layout-node" }], edges: [] } } as never,
      resourceRelationView: { visibleGraph: { nodes: [{ id: "resource-node" }], edges: [] } } as never,
      architectureGraphView: { visibleGraph: { nodes: [{ id: "architecture-node" }], edges: [] } } as never,
      classDiagramView: { visibleGraph: { nodes: [{ id: "class-node" }], edges: [] } } as never,
      reviewGraphView: { visibleGraph: { nodes: [{ id: "review-node" }], edges: [] } } as never,
    };
    const { rerender } = render(
      <AppGraphStage
        analysisDisplayMode="FACT_GRAPH"
        {...props}
      />,
    );

    expect(screen.getByTestId("fact-stage")).toHaveTextContent("fact-node");

    rerender(
      <AppGraphStage
        analysisDisplayMode="RESOURCE_RELATION_VIEW"
        {...props}
      />,
    );

    expect(screen.getByTestId("resource-stage")).toHaveTextContent("resource-node");

    rerender(
      <AppGraphStage
        analysisDisplayMode="REVIEW_GRAPH"
        {...props}
      />,
    );

    expect(screen.getByTestId("review-stage")).toHaveTextContent("review-node");
  });
});
