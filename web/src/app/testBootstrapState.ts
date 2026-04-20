import type {
  FactGraphViewDocument,
  FlowchartViewDocument,
  LinkGraphBootstrapState,
  LinkGraphDocument,
  LinkGraphNode,
  ResourceRelationViewDocument,
} from "./types";
import { resolveWorkingGraphDocument } from "./workingGraphDocument";

const EMPTY_DOCUMENT: LinkGraphDocument = {
  nodes: [],
  edges: [],
};

function resolveAnchorNodeId(
  nodes: LinkGraphNode[],
  preferredNodeId?: string | null,
): string | null {
  if (preferredNodeId && nodes.some((node) => node.id === preferredNodeId)) {
    return preferredNodeId;
  }
  return nodes.find((node) => node.type === "METHOD")?.id ?? nodes[0]?.id ?? null;
}

function buildFactGraphViewDocument(
  visibleGraph: LinkGraphDocument,
  fullGraph: LinkGraphDocument,
  anchorNodeId: string | null,
): FactGraphViewDocument {
  return {
    visibleGraph,
    fullGraph,
    anchorNodeId,
    summary: {
      anchorTitle: fullGraph.nodes.find((node) => node.id === anchorNodeId)?.title
        ?? visibleGraph.nodes.find((node) => node.id === anchorNodeId)?.title
        ?? null,
      visibleNodeCount: visibleGraph.nodes.length,
      fullNodeCount: fullGraph.nodes.length,
    },
  };
}

function buildFlowchartViewDocument(
  visibleGraph: LinkGraphDocument,
  anchorNodeId: string | null,
): FlowchartViewDocument {
  const fullGraph = visibleGraph;
  const incompleteNodeCount = visibleGraph.nodes.filter((node) => node.metadata?.["flow.incomplete"] === "true").length;
  const incompleteEdgeCount = visibleGraph.edges.filter((edge) => edge.metadata?.["flow.incomplete"] === "true").length;
  return {
    visibleGraph,
    fullGraph,
    anchorNodeId,
    summary: {
      nodeCount: visibleGraph.nodes.length,
      branchCount: visibleGraph.nodes.filter((node) => node.metadata?.["flowchart.kind"] === "DECISION").length,
      exceptionPathCount: visibleGraph.edges.filter((edge) => edge.label?.trim().toUpperCase() === "EXCEPTION").length,
      fullNodeCount: fullGraph.nodes.length,
      fullEdgeCount: fullGraph.edges.length,
      hiddenNodeCount: 0,
      hiddenEdgeCount: 0,
      truncated: false,
      incompleteNodeCount,
      incompleteEdgeCount,
      semanticallyIncomplete: incompleteNodeCount > 0 || incompleteEdgeCount > 0,
      syntheticEdgeCount: visibleGraph.edges.filter((edge) => edge.metadata?.["flow.synthetic"] === "true").length,
      syntheticEntryEdgeCount: 0,
    },
  };
}

function buildResourceRelationViewDocument(
  visibleGraph: LinkGraphDocument,
  anchorNodeId: string | null,
): ResourceRelationViewDocument {
  const laneCounts = visibleGraph.nodes.reduce<Record<string, number>>((counts, node) => {
    const lane = node.metadata?.["resource.lane"] ?? "CODE";
    counts[lane] = (counts[lane] ?? 0) + 1;
    return counts;
  }, {});
  return {
    visibleGraph,
    fullGraph: visibleGraph,
    anchorNodeId,
    summary: {
      visibleNodeCount: visibleGraph.nodes.length,
      laneCounts,
    },
  };
}

export function materializeThreeViewDocuments(
  state: LinkGraphBootstrapState,
): LinkGraphBootstrapState {
  const visibleGraph = state.visibleGraph ?? state.workingGraph ?? EMPTY_DOCUMENT;
  const workingGraph = resolveWorkingGraphDocument({
    ...state,
    visibleGraph,
    workingGraph: state.workingGraph ?? visibleGraph,
  });
  const referenceWorkingGraph = state.referenceWorkingGraph
    ?? state.workingGraph
    ?? visibleGraph;
  const referenceFactGraph = state.referenceFactGraph ?? null;
  const factFullGraph = referenceFactGraph ?? visibleGraph;
  const anchorNodeId = resolveAnchorNodeId(visibleGraph.nodes, state.selectedNodeId ?? null);

  return {
    ...state,
    visibleGraph,
    workingGraph,
    referenceWorkingGraph,
    factGraphView: buildFactGraphViewDocument(visibleGraph, factFullGraph, anchorNodeId),
    flowchartView: buildFlowchartViewDocument(visibleGraph, anchorNodeId),
    resourceRelationView: buildResourceRelationViewDocument(visibleGraph, anchorNodeId),
  };
}
