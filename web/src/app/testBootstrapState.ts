import { resolveFlowchartKind } from "./flowchartKind";
import type {
  AssistantContextSnapshot,
  AssistantResultStore,
  AssistantSessionState,
  AssistantTurnKind,
  AssistantTurnRef,
  FactGraphViewDocument,
  FlowchartViewDocument,
  ArchitectureGraphViewDocument,
  ClassDiagramViewDocument,
  GraphViewPresentation,
  LinkGraphBootstrapState,
  LinkGraphDocument,
  LinkGraphLayoutState,
  LinkGraphNode,
  ResourceRelationViewDocument,
  ReviewGraphViewDocument,
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

const EMPTY_GRAPH_VIEW_PRESENTATION: GraphViewPresentation = {
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

function hasOwnInputField(
  state: TestBootstrapStateInput,
  field: keyof LinkGraphBootstrapState,
): boolean {
  return Object.prototype.hasOwnProperty.call(state, field);
}

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
    case "ARCHITECTURE_GRAPH":
      return "WORKSPACE_ARCHITECTURE_GRAPH";
    case "CLASS_DIAGRAM":
      return "WORKSPACE_CLASS_DIAGRAM";
    case "REVIEW_GRAPH":
      return "WORKSPACE_REVIEW_GRAPH";
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
  const hiddenCounts = deriveSampleOnlyHiddenCounts(visibleGraph, fullGraph);
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
      hiddenNodeCount: hiddenCounts.hiddenNodeCount,
      hiddenEdgeCount: hiddenCounts.hiddenEdgeCount,
      truncated: hiddenCounts.hiddenNodeCount > 0 || hiddenCounts.hiddenEdgeCount > 0,
    },
    presentation: EMPTY_GRAPH_VIEW_PRESENTATION,
  };
}

function deriveSampleOnlyHiddenCounts(
  visibleGraph: LinkGraphDocument,
  fullGraph: LinkGraphDocument,
): { hiddenNodeCount: number; hiddenEdgeCount: number } {
  const fullNodeIds = new Set(fullGraph.nodes.map((node) => node.id));
  const fullEdgeIds = new Set(fullGraph.edges.map((edge) => edge.id));
  const visibleOriginalNodeIds = new Set(visibleGraph.nodes
    .map((node) => node.id)
    .filter((nodeId) => fullNodeIds.has(nodeId)));
  const visibleOriginalEdgeIds = new Set(visibleGraph.edges
    .map((edge) => edge.id)
    .filter((edgeId) => fullEdgeIds.has(edgeId)));
  return {
    hiddenNodeCount: Math.max(0, fullNodeIds.size - visibleOriginalNodeIds.size),
    hiddenEdgeCount: Math.max(0, fullEdgeIds.size - visibleOriginalEdgeIds.size),
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
  const resourceCount = visibleGraph.nodes.filter(isResourceRelationNode).length;
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
      relationCount: visibleGraph.edges.length,
      resourceCount,
      fallbackReason: visibleGraph.edges.length > 0
        ? "NONE"
        : resourceCount === 0
          ? "NO_RESOURCE_UNITS"
          : "NO_BINDING_RELATIONS",
      laneCounts,
    },
  };
}

function isResourceRelationNode(node: LinkGraphNode): boolean {
  return node.metadata?.["resource.lane"] != null ||
    node.type.includes("RESOURCE") ||
    ["SQL", "HTTP_ENDPOINT", "MQ_TOPIC", "CONFIG_ITEM"].includes(node.type);
}

function buildArchitectureGraphViewDocument(
  visibleGraph: LinkGraphDocument,
  anchorNodeId: string | null,
): ArchitectureGraphViewDocument {
  return {
    visibleGraph,
    fullGraph: visibleGraph,
    anchorNodeId,
    summary: {
      moduleCount: visibleGraph.nodes.filter((node) => node.type === "MODULE").length,
      packageCount: visibleGraph.nodes.filter((node) => node.type === "PACKAGE").length,
      serviceCount: visibleGraph.nodes.filter((node) => node.type === "SERVICE").length,
      componentCount: visibleGraph.nodes.filter((node) => node.type === "COMPONENT").length,
      resourceCount: visibleGraph.nodes.filter((node) => node.type === "RESOURCE").length,
      layerCount: visibleGraph.nodes.filter((node) => node.type === "LAYER").length,
      relationCount: visibleGraph.edges.length,
      classCount: visibleGraph.nodes
        .map((node) => Number(node.metadata?.["architecture.classCount"] ?? node.metadata?.["architecture.package.classCount"] ?? "0"))
        .filter(Number.isFinite)
        .reduce((sum, count) => sum + count, 0),
      truncated: visibleGraph.truncated === true,
    },
    presentation: EMPTY_GRAPH_VIEW_PRESENTATION,
  };
}

function buildClassDiagramViewDocument(
  visibleGraph: LinkGraphDocument,
  anchorNodeId: string | null,
): ClassDiagramViewDocument {
  return {
    visibleGraph,
    fullGraph: visibleGraph,
    anchorNodeId,
    summary: {
      classCount: visibleGraph.nodes.filter((node) => node.type === "CLASS").length,
      fieldCount: visibleGraph.nodes
        .map((node) => Number(node.metadata?.["uml.field.count"] ?? "0"))
        .filter(Number.isFinite)
        .reduce((sum, count) => sum + count, 0),
      interfaceCount: visibleGraph.nodes.filter((node) => node.type === "INTERFACE").length,
      enumCount: visibleGraph.nodes.filter((node) => node.type === "ENUM").length,
      annotationCount: visibleGraph.nodes.filter((node) => node.type === "ANNOTATION").length,
      recordCount: visibleGraph.nodes.filter((node) => node.type === "RECORD").length,
      objectCount: visibleGraph.nodes.filter((node) => node.type === "OBJECT").length,
      relationCount: visibleGraph.edges.length,
      spiProviderCount: 0,
      reflectionRelationCount: 0,
      relationCompleteness: "COMPLETE",
      scopeTypeCount: visibleGraph.nodes.length,
      projectTypeCount: visibleGraph.nodes.length,
      projectClassCount: visibleGraph.nodes.filter((node) => node.type === "CLASS").length,
      scopeBasis: "CLASS_NEIGHBORHOOD",
      anchorTypeNodeId: visibleGraph.nodes[0]?.id ?? null,
      anchorTypeTitle: visibleGraph.nodes[0]?.title ?? null,
      anchorTypeQualifiedName: visibleGraph.nodes[0]?.signature ?? null,
      neighborhoodLimit: visibleGraph.nodes.length,
      memberLimit: 5,
      neighborhoodCandidateTypeCount: visibleGraph.nodes.length,
      neighborhoodTruncated: false,
    },
    presentation: EMPTY_GRAPH_VIEW_PRESENTATION,
  };
}

function buildReviewGraphViewDocument(
  visibleGraph: LinkGraphDocument,
  anchorNodeId: string | null,
): ReviewGraphViewDocument {
  return {
    visibleGraph,
    fullGraph: visibleGraph,
    anchorNodeId,
    summary: {
      changedSymbolCount: visibleGraph.nodes.filter((node) => node.metadata?.["review.role"] === "CHANGED").length,
      upstreamCount: visibleGraph.nodes.filter((node) => node.metadata?.["review.role"] === "UPSTREAM").length,
      downstreamCount: visibleGraph.nodes.filter((node) => node.metadata?.["review.role"] === "DOWNSTREAM").length,
      relatedTestCount: visibleGraph.nodes.filter((node) => node.metadata?.["review.role"] === "RELATED_TEST").length,
      affectedPackageCount: 0,
      affectedModuleCount: 0,
      evidenceRefCount: visibleGraph.edges.filter((edge) => edge.metadata?.["review.edgeRole"] === "RELATION").length,
      selectedDiffItemIds: [],
      maxChangedNodes: 120,
      maxUpstreamNodes: 40,
      maxDownstreamNodes: 40,
      maxRelatedTestNodes: 40,
    },
  };
}

function assistantContextFromState(
  state: LinkGraphBootstrapState,
  selectedNodeId: string | null,
): AssistantContextSnapshot {
  return {
    selectedNodeIds: selectedNodeId ? [selectedNodeId] : [],
    selectedDiffItemIds: state.diffItems?.map((item) => item.id) ?? [],
    analysisDisplayMode: state.analysisDisplayMode ?? null,
    currentSceneId: state.currentSceneId ?? null,
    selectedMethodSignature: selectedNodeId
      ? state.workspaceGraph.nodes.find((node) => node.id === selectedNodeId)?.signature ?? null
      : null,
    scopeLabel: selectedNodeId
      ? state.workspaceGraph.nodes.find((node) => node.id === selectedNodeId)?.title ?? ""
      : "",
  };
}

function assistantTurnRef(
  kind: AssistantTurnKind,
  resultId: string,
  sourceMessageType: string,
  sequence: number,
  context: AssistantContextSnapshot,
): AssistantTurnRef {
  return {
    turnId: `${sourceMessageType}:${kind.toLowerCase()}:${sequence}`,
    kind,
    sourceMessageType,
    resultId,
    createdAtEpochMillis: sequence,
    context,
  };
}

function materializeAssistantHistory(
  state: LinkGraphBootstrapState,
  selectedNodeId: string | null,
): {
  assistantSessionState: AssistantSessionState;
  assistantResultStore: AssistantResultStore;
} {
  const context = assistantContextFromState(state, selectedNodeId);
  const turns: AssistantTurnRef[] = [];
  const assistantResultStore: AssistantResultStore = {};
  let sequence = 1;

  if (state.graphBeautificationResult) {
    const resultId = "test-explanation:current";
    assistantResultStore[resultId] = {
      kind: "EXPLANATION",
      explanation: state.graphBeautificationResult,
    };
    turns.push(assistantTurnRef("EXPLANATION", resultId, "graphBeautificationResult", sequence++, context));
  }
  if (state.qaResult) {
    const resultId = "test-qa:current";
    assistantResultStore[resultId] = {
      kind: "QA",
      qa: state.qaResult,
    };
    turns.push(assistantTurnRef("QA", resultId, "qaResult", sequence++, context));
  }
  if (state.diffReviewResult) {
    const resultId = "test-check:current";
    assistantResultStore[resultId] = {
      kind: "CHECK_RESULT",
      check: state.diffReviewResult,
    };
    turns.push(assistantTurnRef("CHECK_RESULT", resultId, "diffReviewResult", sequence++, context));
  }
  if (state.generationPlan || state.generationPlanDiscussionSession) {
    const resultId = "test-generation-plan:current";
    assistantResultStore[resultId] = {
      kind: "GENERATION_PLAN",
      generationPlan: state.generationPlan ?? null,
      generationDiscussionSession: state.generationPlanDiscussionSession ?? null,
    };
    turns.push(assistantTurnRef("GENERATION_PLAN", resultId, "generationPlanResult", sequence++, context));
  }
  if ((state.generatedCodeDrafts ?? []).length > 0) {
    const resultId = "test-code-draft:current";
    assistantResultStore[resultId] = {
      kind: "CODE_DRAFT",
      generationPlan: state.generationPlan ?? null,
      generationDiscussionSession: state.generationPlanDiscussionSession ?? null,
      codeDrafts: state.generatedCodeDrafts ?? [],
      codeDraftWarnings: state.generatedCodeDraftWarnings ?? [],
    };
    turns.push(assistantTurnRef("CODE_DRAFT", resultId, "codeDraftResult", sequence++, context));
  }

  return {
    assistantSessionState: {
      sessionId: "assistant-session-test",
      activeIntent: state.assistantSessionState?.activeIntent ?? "EXPLAIN_CODE",
      contextLocked: false,
      context,
      composer: state.assistantSessionState?.composer ?? {
        draft: "",
        target: {
          kind: "NewTask",
        },
      },
      nextResultSequence: state.assistantSessionState?.nextResultSequence ?? 1,
      turns,
    },
    assistantResultStore,
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
  const selectedNodeId = sceneState.selectedNodeId ?? null;
  const assistantHistory = hasOwnInputField(state, "assistantSessionState") || hasOwnInputField(state, "assistantResultStore")
    ? {
        assistantSessionState: normalizedState.assistantSessionState,
        assistantResultStore: normalizedState.assistantResultStore,
      }
    : materializeAssistantHistory(normalizedState, selectedNodeId);

  return {
    ...normalizedState,
    assistantSessionState: assistantHistory.assistantSessionState,
    assistantResultStore: assistantHistory.assistantResultStore,
    workspaceGraph: workingGraph,
    workspaceBaseGraph,
    semanticFactGraph,
    factGraphView: buildFactGraphViewDocument(visibleGraph, factFullGraph, anchorNodeId),
    flowchartView: buildFlowchartViewDocument(visibleGraph, anchorNodeId),
    resourceRelationView: buildResourceRelationViewDocument(visibleGraph, anchorNodeId),
    architectureGraphView: buildArchitectureGraphViewDocument(visibleGraph, anchorNodeId),
    classDiagramView: buildClassDiagramViewDocument(visibleGraph, anchorNodeId),
    reviewGraphView: buildReviewGraphViewDocument(visibleGraph, anchorNodeId),
    sceneStates,
    visibleGraph,
    workingGraph,
    referenceWorkingGraph: workspaceBaseGraph,
    referenceFactGraph: semanticFactGraph,
    selectedNodeId,
    anchorNodeId,
    layoutState: sceneState.layoutState,
    layoutRevision: sceneState.layoutRevision,
  };
}
