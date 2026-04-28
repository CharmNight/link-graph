import { resolveFlowchartKind } from "./flowchartKind";
import type {
  FactGraphViewDocument,
  FlowchartViewDocument,
  LinkGraphBootstrapState,
  LinkGraphDocument,
  LinkGraphLayoutState,
  LinkGraphNode,
  ResourceRelationViewDocument,
} from "./types";
import {
  EMPTY_STATE,
  resolveActiveViewDocument,
  resolveCurrentSceneState,
} from "./sampleState";

const EMPTY_DOCUMENT: LinkGraphDocument = {
  nodes: [],
  edges: [],
};

export interface LegacyTestBootstrapState {
  visibleGraph?: LinkGraphDocument;
  workingGraph?: LinkGraphDocument;
  referenceWorkingGraph?: LinkGraphDocument | null;
  referenceFactGraph?: LinkGraphDocument | null;
  selectedNodeId?: string | null;
  anchorNodeId?: string | null;
  layoutState?: LinkGraphLayoutState;
  layoutRevision?: number;
}

export type TestBootstrapStateInput = Partial<LinkGraphBootstrapState> & LegacyTestBootstrapState;

export type TestBootstrapState = LinkGraphBootstrapState & {
  visibleGraph: LinkGraphDocument;
  workingGraph: LinkGraphDocument;
  referenceWorkingGraph: LinkGraphDocument | null;
  referenceFactGraph: LinkGraphDocument | null;
  selectedNodeId: string | null;
  anchorNodeId: string | null;
  layoutState: LinkGraphLayoutState;
  layoutRevision: number;
};

function resolveSceneId(
  state: Partial<LinkGraphBootstrapState>,
): LinkGraphBootstrapState["currentSceneId"] {
  if (state.currentSceneId) {
    return state.currentSceneId;
  }
  switch (state.analysisDisplayMode) {
    case "FACT_GRAPH":
      return "WORKSPACE_FACT";
    case "RESOURCE_RELATION_VIEW":
      return "WORKSPACE_RESOURCE_RELATION";
    case "FLOWCHART":
    default:
      return "WORKSPACE_FLOWCHART";
  }
}

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
      branchCount: visibleGraph.nodes.filter((node) => resolveFlowchartKind(node) === "DECISION").length,
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
  state: TestBootstrapStateInput,
): TestBootstrapState {
  const currentSceneId = resolveSceneId(state);
  const baseSceneState = (state.sceneStates ?? EMPTY_STATE.sceneStates)[currentSceneId] ?? EMPTY_STATE.sceneStates[currentSceneId];
  const sceneStates = state.sceneStates ?? {
    ...EMPTY_STATE.sceneStates,
    [currentSceneId]: {
      ...baseSceneState,
      selectedNodeId: state.selectedNodeId ?? baseSceneState.selectedNodeId ?? null,
      anchorNodeId: state.anchorNodeId ?? baseSceneState.anchorNodeId ?? state.selectedNodeId ?? null,
      layoutState: state.layoutState ?? baseSceneState.layoutState,
      layoutRevision: state.layoutRevision ?? baseSceneState.layoutRevision,
    },
  };
  const workingGraph = state.workspaceGraph ?? state.workingGraph ?? EMPTY_DOCUMENT;
  const workspaceBaseGraph = state.workspaceBaseGraph ?? state.referenceWorkingGraph ?? workingGraph;
  const semanticFactGraph = state.semanticFactGraph ?? state.referenceFactGraph ?? workingGraph;
  const normalizedState: LinkGraphBootstrapState = {
    ...EMPTY_STATE,
    ...state,
    currentSceneId,
    sceneStates,
    workspaceGraph: workingGraph,
    workspaceBaseGraph,
    semanticFactGraph,
  };
  const visibleGraph = state.visibleGraph ?? resolveActiveViewDocument(normalizedState).visibleGraph;
  const factFullGraph = semanticFactGraph ?? workingGraph;
  const sceneState = resolveCurrentSceneState(normalizedState);
  const anchorNodeId = resolveAnchorNodeId(
    visibleGraph.nodes,
    sceneState.anchorNodeId ?? sceneState.selectedNodeId ?? null,
  );

  return {
    ...normalizedState,
    workspaceGraph: workingGraph,
    workspaceBaseGraph,
    semanticFactGraph,
    factGraphView: buildFactGraphViewDocument(visibleGraph, factFullGraph, anchorNodeId),
    flowchartView: buildFlowchartViewDocument(visibleGraph, anchorNodeId),
    resourceRelationView: buildResourceRelationViewDocument(visibleGraph, anchorNodeId),
    sceneStates,
    visibleGraph,
    workingGraph,
    referenceWorkingGraph: workspaceBaseGraph,
    referenceFactGraph: semanticFactGraph,
    selectedNodeId: sceneState.selectedNodeId ?? null,
    anchorNodeId,
    layoutState: sceneState.layoutState,
    layoutRevision: sceneState.layoutRevision,
  };
}
