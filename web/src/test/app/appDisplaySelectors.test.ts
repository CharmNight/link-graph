import { describe, expect, it } from "vitest";
import {
  activeAnchorNodeIdForDisplayMode,
  activeFullGraphForDisplayMode,
  activeIndexedGraphSummary,
  activeProjectionIndex,
  activeVisibleGraphForDisplayMode,
  isProjectStructureDisplay,
  workflowStageToAssistantTarget,
} from "../../app/appDisplaySelectors";
import type { GraphProjectionIndex, IndexedGraphSummary, LinkGraphDocument } from "../../app/types";

function projectionIndex(id: string): GraphProjectionIndex {
  return {
    nodeMappings: {
      [id]: {
        sceneNodeId: id,
        sourceNodeIds: [id],
      },
    },
    edgeMappings: {},
  };
}

function indexedSummary(scopeKind: string): IndexedGraphSummary {
  return {
    view: "ARCHITECTURE",
    scopeKind,
    scopeLabel: scopeKind,
    relationKinds: [],
    depth: 0,
    projectNodeCount: 0,
    projectClassCount: 0,
    canonicalEdgeIds: [],
    canonicalPathNodeIds: [],
    editableCommandKinds: [],
  };
}

function graphDocument(id: string): LinkGraphDocument {
  return {
    nodes: [],
    edges: [],
    nodeCount: id.length,
  };
}

function graphView(id: string, anchorNodeId?: string | null) {
  return {
    visibleGraph: graphDocument(`${id}:visible`),
    fullGraph: graphDocument(`${id}:full`),
    anchorNodeId,
  };
}

describe("appDisplaySelectors", () => {
  it("maps workflow stages to assistant composer targets", () => {
    expect(workflowStageToAssistantTarget("understand")).toBe("explanation");
    expect(workflowStageToAssistantTarget("evidence")).toBe("qa");
    expect(workflowStageToAssistantTarget("qa")).toBe("qa");
    expect(workflowStageToAssistantTarget("draft")).toBe("draft");
    expect(workflowStageToAssistantTarget("code")).toBe("code");
  });

  it("selects the active indexed graph summary only for indexed graph views", () => {
    const architecture = indexedSummary("PROJECT");
    const classDiagram = indexedSummary("CLASS");
    const review = indexedSummary("REVIEW");

    expect(activeIndexedGraphSummary("ARCHITECTURE_GRAPH", architecture, classDiagram, review)).toBe(architecture);
    expect(activeIndexedGraphSummary("CLASS_DIAGRAM", architecture, classDiagram, review)).toBe(classDiagram);
    expect(activeIndexedGraphSummary("REVIEW_GRAPH", architecture, classDiagram, review)).toBe(review);
    expect(activeIndexedGraphSummary("FLOWCHART", architecture, classDiagram, review)).toBeNull();
  });

  it("selects the active projection index for every display mode", () => {
    const fact = projectionIndex("fact");
    const flowchart = projectionIndex("flowchart");
    const resource = projectionIndex("resource");
    const architecture = projectionIndex("architecture");
    const classDiagram = projectionIndex("class");
    const review = projectionIndex("review");

    expect(activeProjectionIndex("FACT_GRAPH", fact, flowchart, resource, architecture, classDiagram, review)).toBe(fact);
    expect(activeProjectionIndex("FLOWCHART", fact, flowchart, resource, architecture, classDiagram, review)).toBe(flowchart);
    expect(activeProjectionIndex("RESOURCE_RELATION_VIEW", fact, flowchart, resource, architecture, classDiagram, review)).toBe(resource);
    expect(activeProjectionIndex("ARCHITECTURE_GRAPH", fact, flowchart, resource, architecture, classDiagram, review)).toBe(architecture);
    expect(activeProjectionIndex("CLASS_DIAGRAM", fact, flowchart, resource, architecture, classDiagram, review)).toBe(classDiagram);
    expect(activeProjectionIndex("REVIEW_GRAPH", fact, flowchart, resource, architecture, classDiagram, review)).toBe(review);
  });

  it("selects the visible graph for every display mode", () => {
    const documents = {
      factGraphView: graphView("fact"),
      flowchartView: graphView("flowchart"),
      resourceRelationView: graphView("resource"),
      architectureGraphView: graphView("architecture"),
      classDiagramView: graphView("class"),
      reviewGraphView: graphView("review"),
    };

    expect(activeVisibleGraphForDisplayMode("FACT_GRAPH", documents)).toBe(documents.factGraphView.visibleGraph);
    expect(activeVisibleGraphForDisplayMode("FLOWCHART", documents)).toBe(documents.flowchartView.visibleGraph);
    expect(activeVisibleGraphForDisplayMode("RESOURCE_RELATION_VIEW", documents)).toBe(documents.resourceRelationView.visibleGraph);
    expect(activeVisibleGraphForDisplayMode("ARCHITECTURE_GRAPH", documents)).toBe(documents.architectureGraphView.visibleGraph);
    expect(activeVisibleGraphForDisplayMode("CLASS_DIAGRAM", documents)).toBe(documents.classDiagramView.visibleGraph);
    expect(activeVisibleGraphForDisplayMode("REVIEW_GRAPH", documents)).toBe(documents.reviewGraphView.visibleGraph);
  });

  it("selects the full graph and normalized anchor node id for every display mode", () => {
    const documents = {
      factGraphView: graphView("fact", "fact-anchor"),
      flowchartView: graphView("flowchart", "flow-anchor"),
      resourceRelationView: graphView("resource", "resource-anchor"),
      architectureGraphView: graphView("architecture", "architecture-anchor"),
      classDiagramView: graphView("class", "class-anchor"),
      reviewGraphView: graphView("review", undefined),
    };

    expect(activeFullGraphForDisplayMode("FACT_GRAPH", documents)).toBe(documents.factGraphView.fullGraph);
    expect(activeFullGraphForDisplayMode("FLOWCHART", documents)).toBe(documents.flowchartView.fullGraph);
    expect(activeFullGraphForDisplayMode("RESOURCE_RELATION_VIEW", documents)).toBe(documents.resourceRelationView.fullGraph);
    expect(activeFullGraphForDisplayMode("ARCHITECTURE_GRAPH", documents)).toBe(documents.architectureGraphView.fullGraph);
    expect(activeFullGraphForDisplayMode("CLASS_DIAGRAM", documents)).toBe(documents.classDiagramView.fullGraph);
    expect(activeFullGraphForDisplayMode("REVIEW_GRAPH", documents)).toBe(documents.reviewGraphView.fullGraph);

    expect(activeAnchorNodeIdForDisplayMode("FACT_GRAPH", documents)).toBe("fact-anchor");
    expect(activeAnchorNodeIdForDisplayMode("FLOWCHART", documents)).toBe("flow-anchor");
    expect(activeAnchorNodeIdForDisplayMode("RESOURCE_RELATION_VIEW", documents)).toBe("resource-anchor");
    expect(activeAnchorNodeIdForDisplayMode("ARCHITECTURE_GRAPH", documents)).toBe("architecture-anchor");
    expect(activeAnchorNodeIdForDisplayMode("CLASS_DIAGRAM", documents)).toBe("class-anchor");
    expect(activeAnchorNodeIdForDisplayMode("REVIEW_GRAPH", documents)).toBeNull();
  });

  it("treats only project-scoped architecture graphs as graph-focused layouts", () => {
    expect(isProjectStructureDisplay("ARCHITECTURE_GRAPH", indexedSummary("PROJECT"))).toBe(true);
    expect(isProjectStructureDisplay("ARCHITECTURE_GRAPH", indexedSummary("PACKAGE"))).toBe(false);
    expect(isProjectStructureDisplay("CLASS_DIAGRAM", indexedSummary("PROJECT"))).toBe(false);
  });
});
