import { useRef, type Dispatch, type MutableRefObject, type SetStateAction } from "react";
import { measureDuration, measureStart, summarizeBootstrapState, summarizeGraph, traceLinkGraph } from "../debug";
import { applyLayoutOnlyNodePositions, resolveNodePosition, syncNodePosition } from "../graphState";
import type {
  AnalysisDisplayMode,
  ArchitectureGraphViewDocument,
  AsyncRequestState,
  ClassDiagramViewDocument,
  FactGraphViewDocument,
  FlowchartViewDocument,
  LinkGraphBootstrapState,
  LinkGraphDocument,
  LinkGraphLayoutState,
  LinkGraphNode,
  LinkGraphSceneId,
  LinkGraphSceneState,
  ResourceRelationViewDocument,
  ReviewGraphViewDocument,
  SourceNavigationState,
} from "../types";
import type {
  WorkbenchCanvasState,
  WorkbenchProjectionState,
} from "./useWorkbenchState";
import { resolveIndexedGraphRequestStates } from "./useWorkbenchState";

const DEFAULT_ANALYSIS_DISPLAY_MODE: AnalysisDisplayMode = "FLOWCHART";

type GraphViewDocumentLike = {
  visibleGraph: LinkGraphDocument;
  fullGraph: LinkGraphDocument;
  anchorNodeId?: string | null;
};

type SceneGraphViews = {
  factGraphView: FactGraphViewDocument;
  flowchartView: FlowchartViewDocument;
  resourceRelationView: ResourceRelationViewDocument;
  architectureGraphView: ArchitectureGraphViewDocument;
  classDiagramView: ClassDiagramViewDocument;
  reviewGraphView: ReviewGraphViewDocument;
};

interface UseBootstrapProjectionStateArgs {
  nodesRef: MutableRefObject<LinkGraphNode[]>;
  edgesRef: MutableRefObject<LinkGraphDocument["edges"]>;
  draftGraphRef: MutableRefObject<LinkGraphDocument | null>;
  anchorNodeIdRef: MutableRefObject<string | null>;
  analysisDisplayModeRef: MutableRefObject<AnalysisDisplayMode>;
  semanticRevisionRef: MutableRefObject<number | null>;
  layoutRevisionRef: MutableRefObject<number | null>;
  canvasState: WorkbenchCanvasState;
  setCanvasState: Dispatch<SetStateAction<WorkbenchCanvasState>>;
  projectionState: WorkbenchProjectionState;
  setProjectionState: Dispatch<SetStateAction<WorkbenchProjectionState>>;
  explanationLocalOverrideRef: MutableRefObject<boolean>;
  setSelectionGroupNodeIds: Dispatch<SetStateAction<string[]>>;
  setDiffTargetItemIds: Dispatch<SetStateAction<string[]>>;
  syncManualNodeIdCounters: (nextNodes: Array<{ id: string }>) => void;
  resolveSourceNavigationState: (state: LinkGraphBootstrapState) => SourceNavigationState;
  resolveFactGraphView: (state: LinkGraphBootstrapState) => FactGraphViewDocument;
  resolveFlowchartView: (state: LinkGraphBootstrapState) => FlowchartViewDocument;
  resolveResourceRelationView: (state: LinkGraphBootstrapState) => ResourceRelationViewDocument;
  resolveArchitectureGraphView: (state: LinkGraphBootstrapState) => ArchitectureGraphViewDocument;
  resolveClassDiagramView: (state: LinkGraphBootstrapState) => ClassDiagramViewDocument;
  resolveReviewGraphView: (state: LinkGraphBootstrapState) => ReviewGraphViewDocument;
  resolveWorkingGraph: (state: LinkGraphBootstrapState) => LinkGraphDocument;
  resolveActiveViewDocument: (
    state: LinkGraphBootstrapState & {
      factGraphView: FactGraphViewDocument;
      flowchartView: FlowchartViewDocument;
      resourceRelationView: ResourceRelationViewDocument;
      architectureGraphView: ArchitectureGraphViewDocument;
      classDiagramView: ClassDiagramViewDocument;
      reviewGraphView: ReviewGraphViewDocument;
    },
    displayMode: AnalysisDisplayMode,
  ) => { visibleGraph: LinkGraphDocument };
  applyBootstrapRoutesToViewDocument: <T extends GraphViewDocumentLike>(
    nextView: T,
    currentView: T,
  ) => T;
  reuseCurrentViewGraphs: <T extends GraphViewDocumentLike>(
    nextView: T,
    currentView: T,
    reuseCurrentGraphs: boolean,
  ) => T;
  resolveAnchorNodeId: (nodes: LinkGraphNode[], preferredNodeId: string | null) => string | null;
  resolveWorkspaceBaseGraph: (state: LinkGraphBootstrapState) => LinkGraphDocument | null;
  resolveSemanticFactGraph: (state: LinkGraphBootstrapState) => LinkGraphDocument | null;
  resolveDesignBaselineGraph: (state: LinkGraphBootstrapState) => LinkGraphDocument | null;
  resolveRequestState: (state?: AsyncRequestState | null) => AsyncRequestState;
}

function createEmptySceneState(): LinkGraphSceneState {
  return {
    selectedNodeId: null,
    anchorNodeId: null,
    layoutState: {
      positions: {},
    },
    layoutRevision: 0,
    collapsedNodeIds: [],
  };
}

function hasOwnBootstrapField(
  state: LinkGraphBootstrapState,
  field: keyof LinkGraphBootstrapState,
): boolean {
  return Object.prototype.hasOwnProperty.call(state, field);
}

function filterLayoutState(
  layoutState: LinkGraphLayoutState | null | undefined,
  nodes: LinkGraphNode[],
): LinkGraphLayoutState {
  const positions = layoutState?.positions ?? {};
  return {
    positions: Object.fromEntries(
      nodes.flatMap((node) => {
        const position = positions[node.id];
        return position ? [[node.id, position]] : [];
      }),
    ),
  };
}

function normalizeSceneState(
  sceneState: LinkGraphSceneState | null | undefined,
  nodes: LinkGraphNode[],
  resolveAnchorNodeId: (nodes: LinkGraphNode[], preferredNodeId: string | null) => string | null,
  authoritativeAnchorNodeId?: string | null,
): LinkGraphSceneState {
  const baseSceneState = sceneState ?? createEmptySceneState();
  const nodeIds = new Set(nodes.map((node) => node.id));
  const resolvedAuthoritativeAnchorNodeId = authoritativeAnchorNodeId && nodeIds.has(authoritativeAnchorNodeId)
    ? authoritativeAnchorNodeId
    : null;
  const sceneAnchorNodeId = baseSceneState.anchorNodeId && nodeIds.has(baseSceneState.anchorNodeId)
    ? baseSceneState.anchorNodeId
    : null;
  const sceneSelectedNodeId = baseSceneState.selectedNodeId && nodeIds.has(baseSceneState.selectedNodeId)
    ? baseSceneState.selectedNodeId
    : null;
  const sceneSelectionMirrorsStaleAnchor = resolvedAuthoritativeAnchorNodeId != null
    && sceneAnchorNodeId != null
    && sceneAnchorNodeId !== resolvedAuthoritativeAnchorNodeId
    && sceneSelectedNodeId === sceneAnchorNodeId;
  const selectedNodeId = sceneSelectionMirrorsStaleAnchor
    ? resolvedAuthoritativeAnchorNodeId
    : sceneSelectedNodeId ?? resolvedAuthoritativeAnchorNodeId ?? nodes[0]?.id ?? null;
  const preferredAnchorNodeId = resolvedAuthoritativeAnchorNodeId
    ?? sceneAnchorNodeId
    ?? selectedNodeId;

  return {
    ...baseSceneState,
    selectedNodeId,
    anchorNodeId: resolveAnchorNodeId(nodes, preferredAnchorNodeId),
    layoutState: filterLayoutState(baseSceneState.layoutState, nodes),
    collapsedNodeIds: (baseSceneState.collapsedNodeIds ?? []).filter((nodeId) => nodeIds.has(nodeId)),
  };
}

function applySceneLayoutToGraph(
  graph: LinkGraphDocument,
  currentGraph: LinkGraphDocument,
  sceneState: LinkGraphSceneState,
): LinkGraphDocument {
  const currentNodeById = new Map(currentGraph.nodes.map((node) => [node.id, node]));
  let changed = false;
  const nextNodes = graph.nodes.map((node) => {
    const layoutPosition = sceneState.layoutState.positions[node.id];
    if (layoutPosition) {
      const currentPosition = resolveNodePosition(node);
      if (currentPosition?.x === layoutPosition.x && currentPosition?.y === layoutPosition.y) {
        return node;
      }
      changed = true;
      return syncNodePosition(node, layoutPosition);
    }
    if (resolveNodePosition(node)) {
      return node;
    }
    const currentPosition = resolveNodePosition(currentNodeById.get(node.id));
    if (!currentPosition) {
      return node;
    }
    changed = true;
    return syncNodePosition(node, currentPosition);
  });
  return !changed
    ? graph
    : {
        ...graph,
        nodes: nextNodes,
      };
}

function applyBootstrapLayoutToGraph(
  graph: LinkGraphDocument,
  bootstrapGraph: LinkGraphDocument,
): LinkGraphDocument {
  const nextNodes = applyLayoutOnlyNodePositions(graph.nodes, bootstrapGraph.nodes);
  return nextNodes === graph.nodes
    ? graph
    : {
        ...graph,
        nodes: nextNodes,
      };
}

function applyBootstrapLayoutToViewDocument<T extends GraphViewDocumentLike>(
  view: T,
  bootstrapView: T,
): T {
  const nextVisibleGraph = applyBootstrapLayoutToGraph(view.visibleGraph, bootstrapView.visibleGraph);
  const nextFullGraph = applyBootstrapLayoutToGraph(view.fullGraph, bootstrapView.fullGraph);

  if (nextVisibleGraph === view.visibleGraph && nextFullGraph === view.fullGraph) {
    return view;
  }

  return {
    ...view,
    visibleGraph: nextVisibleGraph,
    fullGraph: nextFullGraph,
  };
}

function applySceneStateToViewDocument<T extends GraphViewDocumentLike>(
  view: T,
  currentView: T,
  sceneState: LinkGraphSceneState,
): T {
  const nextVisibleGraph = applySceneLayoutToGraph(
    view.visibleGraph,
    currentView.visibleGraph,
    sceneState,
  );
  const nextFullGraph = applySceneLayoutToGraph(
    view.fullGraph,
    currentView.fullGraph,
    sceneState,
  );

  if (
    nextVisibleGraph === view.visibleGraph
    && nextFullGraph === view.fullGraph
    && (view.anchorNodeId ?? null) === sceneState.anchorNodeId
  ) {
    return view;
  }

  return {
    ...view,
    visibleGraph: nextVisibleGraph,
    fullGraph: nextFullGraph,
    anchorNodeId: sceneState.anchorNodeId,
  };
}

function resolveSceneNodes(
  sceneId: LinkGraphSceneId,
  views: SceneGraphViews,
  fallbackVisibleGraph: LinkGraphDocument,
  currentSceneId: LinkGraphSceneId,
): LinkGraphNode[] {
  switch (sceneId) {
    case "WORKSPACE_FACT":
      return views.factGraphView.visibleGraph.nodes;
    case "WORKSPACE_FLOWCHART":
      return views.flowchartView.visibleGraph.nodes;
    case "WORKSPACE_RESOURCE_RELATION":
      return views.resourceRelationView.visibleGraph.nodes;
    case "WORKSPACE_ARCHITECTURE_GRAPH":
      return views.architectureGraphView.visibleGraph.nodes;
    case "WORKSPACE_CLASS_DIAGRAM":
      return views.classDiagramView.visibleGraph.nodes;
    case "WORKSPACE_REVIEW_GRAPH":
      return views.reviewGraphView.visibleGraph.nodes;
    case "DIFF":
      return currentSceneId === "DIFF" ? fallbackVisibleGraph.nodes : [];
    default:
      return [];
  }
}

function resolveSceneViewAnchorNodeId(
  sceneId: LinkGraphSceneId,
  views: SceneGraphViews,
): string | null {
  switch (sceneId) {
    case "WORKSPACE_FACT":
      return views.factGraphView.anchorNodeId ?? null;
    case "WORKSPACE_FLOWCHART":
      return views.flowchartView.anchorNodeId ?? null;
    case "WORKSPACE_RESOURCE_RELATION":
      return views.resourceRelationView.anchorNodeId ?? null;
    case "WORKSPACE_ARCHITECTURE_GRAPH":
      return views.architectureGraphView.anchorNodeId ?? null;
    case "WORKSPACE_CLASS_DIAGRAM":
      return views.classDiagramView.anchorNodeId ?? null;
    case "WORKSPACE_REVIEW_GRAPH":
      return views.reviewGraphView.anchorNodeId ?? null;
    case "DIFF":
    default:
      return null;
  }
}

function mergeSceneState(args: {
  nextSceneState: LinkGraphSceneState | undefined;
  currentSceneState: LinkGraphSceneState | undefined;
  nodes: LinkGraphNode[];
  authoritativeAnchorNodeId?: string | null;
  preserveLocalSceneUi: boolean;
  resolveAnchorNodeId: (nodes: LinkGraphNode[], preferredNodeId: string | null) => string | null;
}): LinkGraphSceneState {
  const bootstrapSceneState = args.nextSceneState ?? createEmptySceneState();
  const currentSceneState = args.currentSceneState ?? bootstrapSceneState;
  const nodeIds = new Set(args.nodes.map((node) => node.id));
  const authoritativeAnchorNodeId = args.authoritativeAnchorNodeId && nodeIds.has(args.authoritativeAnchorNodeId)
    ? args.authoritativeAnchorNodeId
    : null;
  const incomingAnchorChanged = Boolean(
    authoritativeAnchorNodeId
      ? currentSceneState.anchorNodeId && authoritativeAnchorNodeId !== currentSceneState.anchorNodeId
      : bootstrapSceneState.anchorNodeId
        && currentSceneState.anchorNodeId
        && bootstrapSceneState.anchorNodeId !== currentSceneState.anchorNodeId,
  );
  const preserveLocalSceneUi = args.preserveLocalSceneUi && !incomingAnchorChanged;
  const preserveLocalLayout = preserveLocalSceneUi
    && currentSceneState.layoutRevision >= bootstrapSceneState.layoutRevision;

  const mergedSceneState = preserveLocalSceneUi
    ? {
        ...bootstrapSceneState,
        selectedNodeId: currentSceneState.selectedNodeId,
        anchorNodeId: currentSceneState.anchorNodeId,
        collapsedNodeIds: currentSceneState.collapsedNodeIds,
        layoutState: preserveLocalLayout ? currentSceneState.layoutState : bootstrapSceneState.layoutState,
        layoutRevision: preserveLocalLayout ? currentSceneState.layoutRevision : bootstrapSceneState.layoutRevision,
    }
    : bootstrapSceneState;

  return normalizeSceneState(
    mergedSceneState,
    args.nodes,
    args.resolveAnchorNodeId,
    authoritativeAnchorNodeId,
  );
}

export function useBootstrapProjectionState(args: UseBootstrapProjectionStateArgs) {
  const canvasStateRef = useRef(args.canvasState);
  canvasStateRef.current = args.canvasState;

  function applyBootstrapState(nextState: LinkGraphBootstrapState) {
    const startedAt = measureStart();
    const currentCanvasState = canvasStateRef.current;
    const nextAnalysisDisplayMode = nextState.analysisDisplayMode
      ?? args.analysisDisplayModeRef.current
      ?? currentCanvasState.analysisDisplayMode
      ?? DEFAULT_ANALYSIS_DISPLAY_MODE;
    const currentSemanticRevision = args.semanticRevisionRef.current;
    const reuseCurrentProjectionGraphs = nextState.workspaceRevision === currentCanvasState.workspaceRevision
      && nextState.semanticRevision === currentSemanticRevision;
    const nextSourceNavigationState = args.resolveSourceNavigationState(nextState);

    const bootstrapFactGraphView = args.applyBootstrapRoutesToViewDocument(
      args.resolveFactGraphView(nextState),
      currentCanvasState.factGraphView,
    );
    const bootstrapFlowchartView = args.applyBootstrapRoutesToViewDocument(
      args.resolveFlowchartView(nextState),
      currentCanvasState.flowchartView,
    );
    const bootstrapResourceRelationView = args.applyBootstrapRoutesToViewDocument(
      args.resolveResourceRelationView(nextState),
      currentCanvasState.resourceRelationView,
    );
    const bootstrapArchitectureGraphView = args.applyBootstrapRoutesToViewDocument(
      args.resolveArchitectureGraphView(nextState),
      currentCanvasState.architectureGraphView,
    );
    const bootstrapClassDiagramView = args.applyBootstrapRoutesToViewDocument(
      args.resolveClassDiagramView(nextState),
      currentCanvasState.classDiagramView,
    );
    const bootstrapReviewGraphView = args.applyBootstrapRoutesToViewDocument(
      args.resolveReviewGraphView(nextState),
      currentCanvasState.reviewGraphView,
    );

    const projectedFactGraphView = applyBootstrapLayoutToViewDocument(
      args.reuseCurrentViewGraphs(
        bootstrapFactGraphView,
        currentCanvasState.factGraphView,
        reuseCurrentProjectionGraphs,
      ),
      bootstrapFactGraphView,
    );
    const projectedFlowchartView = applyBootstrapLayoutToViewDocument(
      args.reuseCurrentViewGraphs(
        bootstrapFlowchartView,
        currentCanvasState.flowchartView,
        reuseCurrentProjectionGraphs,
      ),
      bootstrapFlowchartView,
    );
    const projectedResourceRelationView = applyBootstrapLayoutToViewDocument(
      args.reuseCurrentViewGraphs(
        bootstrapResourceRelationView,
        currentCanvasState.resourceRelationView,
        reuseCurrentProjectionGraphs,
      ),
      bootstrapResourceRelationView,
    );
    const projectedArchitectureGraphView = applyBootstrapLayoutToViewDocument(
      args.reuseCurrentViewGraphs(
        bootstrapArchitectureGraphView,
        currentCanvasState.architectureGraphView,
        reuseCurrentProjectionGraphs,
      ),
      bootstrapArchitectureGraphView,
    );
    const projectedClassDiagramView = applyBootstrapLayoutToViewDocument(
      args.reuseCurrentViewGraphs(
        bootstrapClassDiagramView,
        currentCanvasState.classDiagramView,
        reuseCurrentProjectionGraphs,
      ),
      bootstrapClassDiagramView,
    );
    const projectedReviewGraphView = applyBootstrapLayoutToViewDocument(
      args.reuseCurrentViewGraphs(
        bootstrapReviewGraphView,
        currentCanvasState.reviewGraphView,
        reuseCurrentProjectionGraphs,
      ),
      bootstrapReviewGraphView,
    );

    const provisionalVisibleGraph = args.resolveActiveViewDocument({
      ...nextState,
      factGraphView: projectedFactGraphView,
      flowchartView: projectedFlowchartView,
      resourceRelationView: projectedResourceRelationView,
      architectureGraphView: projectedArchitectureGraphView,
      classDiagramView: projectedClassDiagramView,
      reviewGraphView: projectedReviewGraphView,
    }, nextAnalysisDisplayMode).visibleGraph;

    const sceneIds = Array.from(new Set([
      ...Object.keys(nextState.sceneStates),
      ...Object.keys(currentCanvasState.sceneStates),
    ])) as LinkGraphSceneId[];

    const mergedSceneStates = Object.fromEntries(
      sceneIds.map((sceneId) => {
        const projectedViews = {
          factGraphView: projectedFactGraphView,
          flowchartView: projectedFlowchartView,
          resourceRelationView: projectedResourceRelationView,
          architectureGraphView: projectedArchitectureGraphView,
          classDiagramView: projectedClassDiagramView,
          reviewGraphView: projectedReviewGraphView,
        };
        const nodes = resolveSceneNodes(
          sceneId,
          projectedViews,
          provisionalVisibleGraph,
          nextState.currentSceneId,
        );
        return [
          sceneId,
          mergeSceneState({
            nextSceneState: nextState.sceneStates[sceneId],
            currentSceneState: currentCanvasState.sceneStates[sceneId],
            nodes,
            authoritativeAnchorNodeId: resolveSceneViewAnchorNodeId(sceneId, projectedViews),
            preserveLocalSceneUi: reuseCurrentProjectionGraphs,
            resolveAnchorNodeId: args.resolveAnchorNodeId,
          }),
        ];
      }),
    ) as Record<LinkGraphSceneId, LinkGraphSceneState>;

    let nextFactGraphView = applySceneStateToViewDocument(
      projectedFactGraphView,
      currentCanvasState.factGraphView,
      mergedSceneStates.WORKSPACE_FACT ?? createEmptySceneState(),
    );
    let nextFlowchartView = applySceneStateToViewDocument(
      projectedFlowchartView,
      currentCanvasState.flowchartView,
      mergedSceneStates.WORKSPACE_FLOWCHART ?? createEmptySceneState(),
    );
    let nextResourceRelationView = applySceneStateToViewDocument(
      projectedResourceRelationView,
      currentCanvasState.resourceRelationView,
      mergedSceneStates.WORKSPACE_RESOURCE_RELATION ?? createEmptySceneState(),
    );
    let nextArchitectureGraphView = applySceneStateToViewDocument(
      projectedArchitectureGraphView,
      currentCanvasState.architectureGraphView,
      mergedSceneStates.WORKSPACE_ARCHITECTURE_GRAPH ?? createEmptySceneState(),
    );
    let nextClassDiagramView = applySceneStateToViewDocument(
      projectedClassDiagramView,
      currentCanvasState.classDiagramView,
      mergedSceneStates.WORKSPACE_CLASS_DIAGRAM ?? createEmptySceneState(),
    );
    let nextReviewGraphView = applySceneStateToViewDocument(
      projectedReviewGraphView,
      currentCanvasState.reviewGraphView,
      mergedSceneStates.WORKSPACE_REVIEW_GRAPH ?? createEmptySceneState(),
    );

    const resolvedVisibleGraph = args.resolveActiveViewDocument({
      ...nextState,
      factGraphView: nextFactGraphView,
      flowchartView: nextFlowchartView,
      resourceRelationView: nextResourceRelationView,
      architectureGraphView: nextArchitectureGraphView,
      classDiagramView: nextClassDiagramView,
      reviewGraphView: nextReviewGraphView,
      sceneStates: mergedSceneStates,
    }, nextAnalysisDisplayMode).visibleGraph;
    const visibleGraph = resolvedVisibleGraph;
    const nextSceneState = mergedSceneStates[nextState.currentSceneId] ?? createEmptySceneState();
    const nextNodes = visibleGraph.nodes;
    const nextEdges = visibleGraph.edges;
    const nextSelectedNodeId = nextSceneState.selectedNodeId ?? nextNodes[0]?.id ?? null;
    const nextAnchorNodeId = nextSceneState.anchorNodeId ?? args.resolveAnchorNodeId(nextNodes, nextSelectedNodeId);
    const nextWorkspaceGraph = args.resolveWorkingGraph(nextState);
    const nextDraftGraph = reuseCurrentProjectionGraphs
      ? args.draftGraphRef.current ?? nextWorkspaceGraph
      : nextWorkspaceGraph;

    traceLinkGraph("app.applyBootstrapState", {
      bootstrap: summarizeBootstrapState(nextState),
      visibleGraph: summarizeGraph(visibleGraph),
      reuseCurrentProjectionGraphs,
      durationMs: measureDuration(startedAt),
    });

    args.nodesRef.current = nextNodes;
    args.edgesRef.current = nextEdges;
    args.draftGraphRef.current = nextDraftGraph;
    args.anchorNodeIdRef.current = nextAnchorNodeId;
    args.analysisDisplayModeRef.current = nextAnalysisDisplayMode;
    args.semanticRevisionRef.current = nextState.semanticRevision ?? args.semanticRevisionRef.current;
    args.layoutRevisionRef.current = nextSceneState.layoutRevision ?? null;
    const nextCanvasState: WorkbenchCanvasState = {
      ...currentCanvasState,
      nodes: nextNodes,
      edges: nextEdges,
      selectedNodeId: nextSelectedNodeId,
      analysisDisplayMode: nextAnalysisDisplayMode,
      anchorNodeId: nextAnchorNodeId,
      currentSceneId: nextState.currentSceneId,
      sceneStates: mergedSceneStates,
      workspaceGraph: nextWorkspaceGraph,
      workspaceBaseGraph: args.resolveWorkspaceBaseGraph(nextState),
      semanticFactGraph: args.resolveSemanticFactGraph(nextState),
      workspaceRevision: nextState.workspaceRevision ?? currentCanvasState.workspaceRevision,
      factGraphView: nextFactGraphView,
      flowchartView: nextFlowchartView,
      resourceRelationView: nextResourceRelationView,
      architectureGraphView: nextArchitectureGraphView,
      classDiagramView: nextClassDiagramView,
      reviewGraphView: nextReviewGraphView,
      draftGraph: nextDraftGraph,
    };
    canvasStateRef.current = nextCanvasState;

    args.setCanvasState(() => nextCanvasState);

    args.setProjectionState((current) => ({
      ...current,
      designBaseline: args.resolveDesignBaselineGraph(nextState),
      draftWorkbenchState: nextState.draftWorkbenchState ?? { draftChanges: [], draftNotes: [] },
      draftPatchPreview: nextState.draftPatchPreview ?? null,
      canUndoDraftPatchApply: nextState.canUndoDraftPatchApply ?? false,
      lastAppliedDraftPatchSummary: nextState.lastAppliedDraftPatchSummary ?? null,
      lastDraftPatchApplyResult: nextState.lastDraftPatchApplyResult ?? null,
      qaResult: nextState.qaResult ?? null,
      qaRequestState: args.resolveRequestState(nextState.qaRequestState),
      qaRequestRecoveryState: nextState.qaRequestRecoveryState ?? { lastSubmittedRequest: null, lastFailedRequest: null },
      diffReviewResult: nextState.diffReviewResult ?? null,
      diffReviewRequestState: args.resolveRequestState(nextState.diffReviewRequestState),
      mermaidIssues: nextState.mermaidIssues ?? [],
      diffItems: nextState.diffItems ?? [],
      syncPreviewItems: nextState.syncPreviewItems ?? [],
      draftVersion: nextState.draftVersion ?? null,
      generationPlan: nextState.generationPlan ?? null,
      generationPlanDraftVersion: nextState.generationPlanDraftVersion ?? null,
      generationPlanRequestState: args.resolveRequestState(nextState.generationPlanRequestState),
      draftValidationState: nextState.draftValidationState ?? null,
      generationPlanDiscussionSession: nextState.generationPlanDiscussionSession ?? null,
      generationPlanDiscussionRequestState: args.resolveRequestState(nextState.generationPlanDiscussionRequestState),
      graphBeautificationResult: args.explanationLocalOverrideRef.current
        ? current.graphBeautificationResult
        : hasOwnBootstrapField(nextState, "graphBeautificationResult")
          ? nextState.graphBeautificationResult ?? null
          : current.graphBeautificationResult,
      graphBeautificationRequestState: args.explanationLocalOverrideRef.current
        ? current.graphBeautificationRequestState
        : hasOwnBootstrapField(nextState, "graphBeautificationRequestState")
          ? args.resolveRequestState(nextState.graphBeautificationRequestState)
          : current.graphBeautificationRequestState,
      generatedCodeDrafts: nextState.generatedCodeDrafts ?? [],
      generatedCodeDraftVersion: nextState.generatedCodeDraftVersion ?? null,
      generatedCodeDraftWarnings: nextState.generatedCodeDraftWarnings ?? [],
      generatedCodeDraftSource: nextState.generatedCodeDraftSource ?? null,
      generatedCodeDraftPromptPreview: nextState.generatedCodeDraftPromptPreview ?? null,
      generatedCodeDraftPromptPreviewArtifactId: nextState.generatedCodeDraftPromptPreviewArtifactId ?? null,
      generatedCodeDraftWriteReport: nextState.generatedCodeDraftWriteReport ?? null,
      codeDraftRequestState: args.resolveRequestState(nextState.codeDraftRequestState),
      codeEligibilityDecision: nextState.codeEligibilityDecision ?? null,
      indexedGraphRequestStates: resolveIndexedGraphRequestStates(
        nextState.indexedGraphRequestStates,
        args.resolveRequestState,
      ),
      sourceNavigationState: nextSourceNavigationState,
      operationFeedback: nextState.operationFeedback ?? null,
      workbenchSectionPreferences: nextState.workbenchSectionPreferences ?? {},
      assistantSessionState: nextState.assistantSessionState ?? current.assistantSessionState,
      lastMessageType: nextState.lastMessageType ?? null,
      graphSurfaceExperiments: nextState.graphSurfaceExperiments ?? null,
      artifactContents: nextState.artifactContents
        ? {
            ...current.artifactContents,
            ...nextState.artifactContents,
          }
        : current.artifactContents,
    }));

    args.syncManualNodeIdCounters(nextNodes);
    args.setSelectionGroupNodeIds((current) => current.filter((nodeId) => nextNodes.some((node) => node.id === nodeId)));
    args.setDiffTargetItemIds((current) => current.filter((itemId) => nextState.diffItems.some((item) => item.id === itemId)));
  }

  return {
    applyBootstrapState,
  };
}
