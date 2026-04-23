import type { Dispatch, MutableRefObject, SetStateAction } from "react";
import {
  measureDuration,
  measureStart,
  summarizeBootstrapState,
  summarizeGraph,
  traceLinkGraph,
} from "../debug";
import {
  applyBootstrapNodePositions,
  applyLayoutOnlyNodePositions,
  graphLayoutSignature,
  graphSemanticSignature,
  hasRevision,
  normalizeGraphNodes,
} from "../graphState";
import type {
  AnalysisDisplayMode,
  AsyncRequestState,
  FactGraphViewDocument,
  FlowchartViewDocument,
  LinkGraphBootstrapState,
  LinkGraphDocument,
  LinkGraphNode,
  ResourceRelationViewDocument,
  SourceNavigationState,
} from "../types";
import type {
  WorkbenchCanvasState,
  WorkbenchProjectionState,
} from "./useWorkbenchState";

const DEFAULT_ANALYSIS_DISPLAY_MODE: AnalysisDisplayMode = "FLOWCHART";
const REQUEST_ONLY_SELECTION_MESSAGE_TYPES = new Set([
  "requestAudit",
  "auditResult",
]);

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
  setCollapsedNodeIds: Dispatch<SetStateAction<string[]>>;
  setDiffTargetItemIds: Dispatch<SetStateAction<string[]>>;
  syncManualNodeIdCounters: (nextNodes: Array<{ id: string }>) => void;
  resolveSourceNavigationState: (state: LinkGraphBootstrapState) => SourceNavigationState;
  resolveFactGraphView: (state: LinkGraphBootstrapState) => FactGraphViewDocument;
  resolveFlowchartView: (state: LinkGraphBootstrapState) => FlowchartViewDocument;
  resolveResourceRelationView: (state: LinkGraphBootstrapState) => ResourceRelationViewDocument;
  resolveWorkingGraph: (state: LinkGraphBootstrapState) => LinkGraphDocument;
  resolveActiveViewDocument: (
    state: LinkGraphBootstrapState & {
      factGraphView: FactGraphViewDocument;
      flowchartView: FlowchartViewDocument;
      resourceRelationView: ResourceRelationViewDocument;
    },
    displayMode: AnalysisDisplayMode,
  ) => { visibleGraph: LinkGraphDocument };
  applyBootstrapRoutesToViewDocument: <T extends { visibleGraph: LinkGraphDocument; fullGraph: LinkGraphDocument }>(
    nextView: T,
    currentView: T,
  ) => T;
  applyBootstrapRoutesToDocument: (
    nextDocument: LinkGraphDocument,
    currentDocument: LinkGraphDocument,
  ) => LinkGraphDocument;
  resolveAnchorNodeId: (nodes: LinkGraphNode[], preferredNodeId: string | null) => string | null;
  shouldResetAnchorNode: (state: LinkGraphBootstrapState, semanticGraphChanged: boolean) => boolean;
  deriveFactGraphSummary: (
    visibleGraph: LinkGraphDocument,
    fullGraph: LinkGraphDocument,
    anchorNodeId: string | null,
  ) => FactGraphViewDocument["summary"];
  resolveReferenceWorkingGraph: (
    state: LinkGraphBootstrapState,
    displayMode: AnalysisDisplayMode,
  ) => LinkGraphDocument | null;
  resolveReferenceFactGraph: (state: LinkGraphBootstrapState) => LinkGraphDocument | null;
  resolveDesignBaselineGraph: (state: LinkGraphBootstrapState) => LinkGraphDocument | null;
  resolveRequestState: (state?: AsyncRequestState | null) => AsyncRequestState;
}

export function useBootstrapProjectionState(args: UseBootstrapProjectionStateArgs) {
  function applyBootstrapState(nextState: LinkGraphBootstrapState) {
    const startedAt = measureStart();
    const currentNodes = args.nodesRef.current;
    const currentEdges = args.edgesRef.current;
    const currentDraftGraph = args.draftGraphRef.current;
    const effectiveCurrentDraftGraph = currentDraftGraph ?? {
      nodes: currentNodes,
      edges: currentEdges,
    };
    const currentAnchorNodeId = args.anchorNodeIdRef.current;
    const currentAnalysisDisplayMode = args.analysisDisplayModeRef.current;
    const nextAnalysisDisplayMode = nextState.analysisDisplayMode ?? DEFAULT_ANALYSIS_DISPLAY_MODE;
    const analysisDisplayModeChanged = nextAnalysisDisplayMode !== currentAnalysisDisplayMode;
    const nextSourceNavigationState = args.resolveSourceNavigationState(nextState);
    let nextFactGraphView = args.applyBootstrapRoutesToViewDocument(
      args.resolveFactGraphView(nextState),
      args.canvasState.factGraphView,
    );
    let nextFlowchartView = args.applyBootstrapRoutesToViewDocument(
      args.resolveFlowchartView(nextState),
      args.canvasState.flowchartView,
    );
    let nextResourceRelationView = args.applyBootstrapRoutesToViewDocument(
      args.resolveResourceRelationView(nextState),
      args.canvasState.resourceRelationView,
    );
    const nextWorkingGraph = args.applyBootstrapRoutesToDocument(
      args.resolveWorkingGraph(nextState),
      effectiveCurrentDraftGraph,
    );
    traceLinkGraph("app.applyBootstrapState.start", {
      bootstrap: summarizeBootstrapState(nextState),
      currentGraph: summarizeGraph({ nodes: currentNodes, edges: currentEdges }),
    });
    const visibleGraph = args.resolveActiveViewDocument({
      ...nextState,
      factGraphView: nextFactGraphView,
      flowchartView: nextFlowchartView,
      resourceRelationView: nextResourceRelationView,
    }, nextAnalysisDisplayMode).visibleGraph;
    const revisionsAvailable = hasRevision(nextState.semanticRevision) || hasRevision(nextState.layoutRevision);
    const semanticRevisionAdvanced = hasRevision(nextState.semanticRevision)
      && nextState.semanticRevision !== args.semanticRevisionRef.current;
    const layoutRevisionAdvanced = hasRevision(nextState.layoutRevision)
      && nextState.layoutRevision !== args.layoutRevisionRef.current;
    const skipGraphSignatureChecks = revisionsAvailable
      && !analysisDisplayModeChanged
      && !semanticRevisionAdvanced
      && !layoutRevisionAdvanced;
    if (skipGraphSignatureChecks) {
      args.setProjectionState({
        ...args.projectionState,
        draftWorkbenchState: nextState.draftWorkbenchState ?? { draftChanges: [], draftNotes: [] },
        draftPatchPreview: nextState.draftPatchPreview ?? null,
        canUndoDraftPatchApply: nextState.canUndoDraftPatchApply ?? false,
        lastAppliedDraftPatchSummary: nextState.lastAppliedDraftPatchSummary ?? null,
        lastAppliedDraftPatchPreview: !(nextState.canUndoDraftPatchApply ?? false) && !nextState.lastAppliedDraftPatchSummary
          ? null
          : args.projectionState.lastAppliedDraftPatchPreview,
        lastDraftPatchApplyResult: nextState.lastDraftPatchApplyResult ?? null,
        auditResult: nextState.auditResult ?? null,
        auditRequestState: args.resolveRequestState(nextState.auditRequestState),
        qaRequestRecoveryState: nextState.qaRequestRecoveryState ?? { lastSubmittedRequest: null, lastFailedRequest: null },
        diffReviewResult: nextState.diffReviewResult ?? null,
        diffReviewRequestState: args.resolveRequestState(nextState.diffReviewRequestState),
        draftVersion: nextState.draftVersion ?? null,
        generationPlan: nextState.generationPlan ?? null,
        generationPlanDraftVersion: nextState.generationPlanDraftVersion ?? null,
        generationPlanRequestState: args.resolveRequestState(nextState.generationPlanRequestState),
        draftValidationState: nextState.draftValidationState ?? null,
        generationPlanDiscussionSession: nextState.generationPlanDiscussionSession ?? null,
        generationPlanDiscussionRequestState: args.resolveRequestState(nextState.generationPlanDiscussionRequestState),
        graphBeautificationResult: args.explanationLocalOverrideRef.current
          ? args.projectionState.graphBeautificationResult
          : nextState.graphBeautificationResult ?? null,
        graphBeautificationRequestState: args.explanationLocalOverrideRef.current
          ? args.projectionState.graphBeautificationRequestState
          : args.resolveRequestState(nextState.graphBeautificationRequestState),
        generatedCodeDrafts: nextState.generatedCodeDrafts ?? [],
        generatedCodeDraftVersion: nextState.generatedCodeDraftVersion ?? null,
        generatedCodeDraftWarnings: nextState.generatedCodeDraftWarnings ?? [],
        generatedCodeDraftSource: nextState.generatedCodeDraftSource ?? null,
        generatedCodeDraftPromptPreview: nextState.generatedCodeDraftPromptPreview ?? null,
        generatedCodeDraftPromptPreviewArtifactId: nextState.generatedCodeDraftPromptPreviewArtifactId ?? null,
        generatedCodeDraftWriteReport: nextState.generatedCodeDraftWriteReport ?? null,
        codeDraftRequestState: args.resolveRequestState(nextState.codeDraftRequestState),
        codeEligibilityDecision: nextState.codeEligibilityDecision ?? null,
        sourceNavigationState: nextSourceNavigationState,
        operationFeedback: nextState.operationFeedback ?? null,
        workbenchSectionPreferences: nextState.workbenchSectionPreferences ?? {},
        lastMessageType: nextState.lastMessageType ?? null,
        graphSurfaceExperiments: nextState.graphSurfaceExperiments ?? null,
        artifactContents: nextState.artifactContents
          ? {
              ...args.projectionState.artifactContents,
              ...nextState.artifactContents,
            }
          : args.projectionState.artifactContents,
      });
      if (hasRevision(nextState.semanticRevision)) {
        args.semanticRevisionRef.current = nextState.semanticRevision;
      }
      if (hasRevision(nextState.layoutRevision)) {
        args.layoutRevisionRef.current = nextState.layoutRevision;
      }
      return;
    }
    let nextVisibleGraphWithPositionsCache: LinkGraphDocument | null = null;
    function nextVisibleGraphWithPositions() {
      if (nextVisibleGraphWithPositionsCache) {
        return nextVisibleGraphWithPositionsCache;
      }
      nextVisibleGraphWithPositionsCache = {
        ...visibleGraph,
        nodes: applyBootstrapNodePositions(
          visibleGraph.nodes,
          currentNodes,
          nextState.layoutState,
          !analysisDisplayModeChanged,
          nextState.analysisDisplayMode ?? DEFAULT_ANALYSIS_DISPLAY_MODE,
        ),
      };
      return nextVisibleGraphWithPositionsCache;
    }
    const visibleSemanticChanged = revisionsAvailable
      ? analysisDisplayModeChanged
        || (
          semanticRevisionAdvanced
          && graphSemanticSignature({ nodes: currentNodes, edges: currentEdges }) !== graphSemanticSignature(visibleGraph)
        )
      : analysisDisplayModeChanged
        || graphSemanticSignature({ nodes: currentNodes, edges: currentEdges }) !== graphSemanticSignature(visibleGraph);
    const visibleLayoutChanged = revisionsAvailable
      ? layoutRevisionAdvanced
        && graphLayoutSignature(currentNodes) !== graphLayoutSignature(nextVisibleGraphWithPositions().nodes)
      : graphLayoutSignature(currentNodes) !== graphLayoutSignature(nextVisibleGraphWithPositions().nodes);
    const semanticGraphChanged = visibleSemanticChanged;
    const layoutGraphChanged = revisionsAvailable
      ? semanticGraphChanged || (layoutRevisionAdvanced && visibleLayoutChanged)
      : semanticGraphChanged || visibleLayoutChanged;
    const nextGraph = semanticGraphChanged || layoutGraphChanged
      ? nextVisibleGraphWithPositions()
      : {
          nodes: currentNodes,
          edges: currentEdges,
        };
    const reuseCurrentViewGraphsWhenStable = !semanticGraphChanged && !layoutGraphChanged;
    nextFactGraphView = reuseCurrentViewGraphs(nextFactGraphView, args.canvasState.factGraphView, reuseCurrentViewGraphsWhenStable);
    nextFlowchartView = reuseCurrentViewGraphs(nextFlowchartView, args.canvasState.flowchartView, reuseCurrentViewGraphsWhenStable);
    nextResourceRelationView = reuseCurrentViewGraphs(
      nextResourceRelationView,
      args.canvasState.resourceRelationView,
      reuseCurrentViewGraphsWhenStable,
    );
    const requestedDraftPatchFocusNodeId = nextState.lastMessageType === "draftPatchApplied"
      ? nextState.lastDraftPatchApplyResult?.focusNodeId ?? null
      : null;
    const nextSelectedNodeId = nextState.selectedNodeId ?? nextGraph.nodes[0]?.id ?? null;
    const shouldPreserveLocalSelection = Boolean(
      args.canvasState.selectedNodeId
      && !semanticGraphChanged
      && !layoutGraphChanged
      && REQUEST_ONLY_SELECTION_MESSAGE_TYPES.has(nextState.lastMessageType ?? "")
      && nextGraph.nodes.some((node) => node.id === args.canvasState.selectedNodeId),
    );
    const effectiveSelectedNodeId = requestedDraftPatchFocusNodeId && nextGraph.nodes.some((node) => node.id === requestedDraftPatchFocusNodeId)
      ? requestedDraftPatchFocusNodeId
      : shouldPreserveLocalSelection
        ? args.canvasState.selectedNodeId
        : nextSelectedNodeId;
    const nextAnchorNodeId = args.resolveAnchorNodeId(
      nextGraph.nodes,
      args.shouldResetAnchorNode(nextState, semanticGraphChanged)
        ? effectiveSelectedNodeId
        : currentAnchorNodeId ?? effectiveSelectedNodeId,
    );
    const nextNodes = semanticGraphChanged
      ? normalizeGraphNodes(
          nextGraph.nodes,
          nextGraph.edges,
          nextAnchorNodeId,
          nextState.analysisDisplayMode ?? DEFAULT_ANALYSIS_DISPLAY_MODE,
        )
      : layoutGraphChanged
        ? applyLayoutOnlyNodePositions(currentNodes, nextGraph.nodes)
        : currentNodes;
    args.syncManualNodeIdCounters(nextNodes);
    const nextDraftGraphWithPositions = nextWorkingGraph.nodes.length > 0
      ? {
          ...nextWorkingGraph,
          nodes: applyBootstrapNodePositions(
            nextWorkingGraph.nodes,
            effectiveCurrentDraftGraph.nodes,
            nextState.layoutState,
            !analysisDisplayModeChanged,
            nextState.analysisDisplayMode ?? DEFAULT_ANALYSIS_DISPLAY_MODE,
          ),
        }
      : nextWorkingGraph;
    const workingSemanticChanged = nextState.workingGraph != null
      ? revisionsAvailable
        ? semanticRevisionAdvanced
          && graphSemanticSignature(effectiveCurrentDraftGraph) !== graphSemanticSignature(nextWorkingGraph)
        : graphSemanticSignature(effectiveCurrentDraftGraph) !== graphSemanticSignature(nextWorkingGraph)
      : semanticGraphChanged;
    const workingLayoutChanged = nextState.workingGraph != null
      ? graphLayoutSignature(effectiveCurrentDraftGraph.nodes) !== graphLayoutSignature(nextDraftGraphWithPositions.nodes)
      : layoutGraphChanged;
    const draftSemanticChanged = workingSemanticChanged;
    const draftLayoutChanged = revisionsAvailable
      ? draftSemanticChanged || (layoutRevisionAdvanced && workingLayoutChanged)
      : draftSemanticChanged || workingLayoutChanged;
    const nextDraftGraphNodes = draftSemanticChanged && nextDraftGraphWithPositions.nodes.length > 0
      ? normalizeGraphNodes(
          nextDraftGraphWithPositions.nodes,
          nextDraftGraphWithPositions.edges,
          nextAnchorNodeId,
          nextState.analysisDisplayMode ?? DEFAULT_ANALYSIS_DISPLAY_MODE,
        )
      : draftLayoutChanged
        ? applyLayoutOnlyNodePositions(effectiveCurrentDraftGraph.nodes, nextDraftGraphWithPositions.nodes)
        : effectiveCurrentDraftGraph.nodes;
    traceLinkGraph("app.applyBootstrapState.computed", {
      lastMessageType: nextState.lastMessageType ?? null,
      analysisDisplayModeChanged,
      currentAnalysisDisplayMode,
      nextAnalysisDisplayMode,
      semanticGraphChanged,
      layoutGraphChanged,
      visibleSemanticChanged,
      visibleLayoutChanged,
      draftSemanticChanged,
      draftLayoutChanged,
      semanticRevisionAdvanced,
      layoutRevisionAdvanced,
      currentSemanticRevision: args.semanticRevisionRef.current,
      nextSemanticRevision: nextState.semanticRevision ?? null,
      currentLayoutRevision: args.layoutRevisionRef.current,
      nextLayoutRevision: nextState.layoutRevision ?? null,
      reuseCurrentViewGraphsWhenStable,
      nextAnchorNodeId,
      nextSelectedNodeId,
      nextGraph: summarizeGraph(nextGraph),
      normalizedGraph: summarizeGraph({ nodes: nextNodes, edges: nextGraph.edges }),
      durationMs: measureDuration(startedAt),
    });
    traceLinkGraph("app.applyBootstrapState.mutationPlan", {
      willSetNodes: semanticGraphChanged || layoutGraphChanged,
      willSetEdges: semanticGraphChanged,
      nextSelectedNodeId: effectiveSelectedNodeId,
      nextAnchorNodeId,
      lastMessageType: nextState.lastMessageType ?? null,
    });
    args.setCanvasState({
      ...args.canvasState,
      nodes: semanticGraphChanged || layoutGraphChanged ? nextNodes : currentNodes,
      edges: semanticGraphChanged ? nextGraph.edges : currentEdges,
      selectedNodeId: effectiveSelectedNodeId,
      analysisDisplayMode: nextAnalysisDisplayMode,
      anchorNodeId: nextAnchorNodeId,
      referenceWorkingGraph: args.resolveReferenceWorkingGraph(nextState, nextAnalysisDisplayMode),
      factGraph: args.resolveReferenceFactGraph(nextState),
      factGraphView: nextAnalysisDisplayMode === "FACT_GRAPH"
        ? {
            ...nextFactGraphView,
            anchorNodeId: nextAnchorNodeId,
            summary: args.deriveFactGraphSummary(
              nextFactGraphView.visibleGraph,
              nextFactGraphView.fullGraph,
              nextAnchorNodeId,
            ),
          }
        : nextFactGraphView,
      flowchartView: nextAnalysisDisplayMode === "FLOWCHART"
        ? {
            ...nextFlowchartView,
            anchorNodeId: nextAnchorNodeId,
          }
        : nextFlowchartView,
      resourceRelationView: nextAnalysisDisplayMode === "RESOURCE_RELATION_VIEW"
        ? {
            ...nextResourceRelationView,
            anchorNodeId: nextAnchorNodeId,
          }
        : nextResourceRelationView,
      draftGraph: nextDraftGraphWithPositions.nodes.length > 0
        ? {
            ...nextDraftGraphWithPositions,
            nodes: nextDraftGraphNodes,
          }
        : nextDraftGraphWithPositions,
    });
    const requestedDetailNodeId = requestedDraftPatchFocusNodeId && nextNodes.some((node) => node.id === requestedDraftPatchFocusNodeId)
      ? requestedDraftPatchFocusNodeId
      : args.projectionState.detailNodeId;
    args.setProjectionState({
      ...args.projectionState,
      detailNodeId: requestedDetailNodeId && nextNodes.some((node) => node.id === requestedDetailNodeId)
        ? requestedDetailNodeId
        : null,
      draftWorkbenchState: nextState.draftWorkbenchState ?? { draftChanges: [], draftNotes: [] },
      designBaseline: args.resolveDesignBaselineGraph(nextState),
      draftPatchPreview: nextState.draftPatchPreview ?? null,
      canUndoDraftPatchApply: nextState.canUndoDraftPatchApply ?? false,
      lastAppliedDraftPatchSummary: nextState.lastAppliedDraftPatchSummary ?? null,
      lastAppliedDraftPatchPreview: !(nextState.canUndoDraftPatchApply ?? false) && !nextState.lastAppliedDraftPatchSummary
        ? null
        : args.projectionState.lastAppliedDraftPatchPreview,
      lastDraftPatchApplyResult: nextState.lastDraftPatchApplyResult ?? null,
      auditResult: nextState.auditResult ?? null,
      auditRequestState: args.resolveRequestState(nextState.auditRequestState),
      qaRequestRecoveryState: nextState.qaRequestRecoveryState ?? { lastSubmittedRequest: null, lastFailedRequest: null },
      diffReviewResult: nextState.diffReviewResult ?? null,
      diffReviewRequestState: args.resolveRequestState(nextState.diffReviewRequestState),
      mermaidIssues: nextState.mermaidIssues ?? [],
      diffItems: nextState.diffItems,
      syncPreviewItems: nextState.syncPreviewItems,
      draftVersion: nextState.draftVersion ?? null,
      generationPlan: nextState.generationPlan ?? null,
      generationPlanDraftVersion: nextState.generationPlanDraftVersion ?? null,
      generationPlanRequestState: args.resolveRequestState(nextState.generationPlanRequestState),
      draftValidationState: nextState.draftValidationState ?? null,
      generationPlanDiscussionSession: nextState.generationPlanDiscussionSession ?? null,
      generationPlanDiscussionRequestState: args.resolveRequestState(nextState.generationPlanDiscussionRequestState),
      graphBeautificationResult: args.explanationLocalOverrideRef.current
        ? args.projectionState.graphBeautificationResult
        : nextState.graphBeautificationResult ?? null,
      graphBeautificationRequestState: args.explanationLocalOverrideRef.current
        ? args.projectionState.graphBeautificationRequestState
        : args.resolveRequestState(nextState.graphBeautificationRequestState),
      generatedCodeDrafts: nextState.generatedCodeDrafts ?? [],
      generatedCodeDraftVersion: nextState.generatedCodeDraftVersion ?? null,
      generatedCodeDraftWarnings: nextState.generatedCodeDraftWarnings ?? [],
      generatedCodeDraftSource: nextState.generatedCodeDraftSource ?? null,
      generatedCodeDraftPromptPreview: nextState.generatedCodeDraftPromptPreview ?? null,
      generatedCodeDraftPromptPreviewArtifactId: nextState.generatedCodeDraftPromptPreviewArtifactId ?? null,
      generatedCodeDraftWriteReport: nextState.generatedCodeDraftWriteReport ?? null,
      codeDraftRequestState: args.resolveRequestState(nextState.codeDraftRequestState),
      codeEligibilityDecision: nextState.codeEligibilityDecision ?? null,
      sourceNavigationState: nextSourceNavigationState,
      operationFeedback: nextState.operationFeedback ?? null,
      workbenchSectionPreferences: nextState.workbenchSectionPreferences ?? {},
      lastMessageType: nextState.lastMessageType ?? null,
      graphSurfaceExperiments: nextState.graphSurfaceExperiments ?? null,
      artifactContents: nextState.artifactContents
        ? {
            ...args.projectionState.artifactContents,
            ...nextState.artifactContents,
          }
        : args.projectionState.artifactContents,
    });
    if (semanticGraphChanged) {
      args.setSelectionGroupNodeIds([]);
      args.setCollapsedNodeIds([]);
      args.setDiffTargetItemIds([]);
    } else {
      args.setSelectionGroupNodeIds((current) => current.filter((nodeId) => nextNodes.some((node) => node.id === nodeId)));
      args.setCollapsedNodeIds((current) => current.filter((nodeId) => nextNodes.some((node) => node.id === nodeId)));
      args.setDiffTargetItemIds((current) => current.filter((itemId) => nextState.diffItems.some((item) => item.id === itemId)));
    }
    if (hasRevision(nextState.semanticRevision)) {
      args.semanticRevisionRef.current = nextState.semanticRevision;
    }
    if (hasRevision(nextState.layoutRevision)) {
      args.layoutRevisionRef.current = nextState.layoutRevision;
    }
  }

  return {
    applyBootstrapState,
  };
}

function reuseCurrentViewGraphs<T extends { visibleGraph: LinkGraphDocument; fullGraph: LinkGraphDocument }>(
  nextView: T,
  currentView: T,
  reuseCurrentGraph: boolean,
): T {
  if (!reuseCurrentGraph) {
    return nextView;
  }
  return {
    ...nextView,
    visibleGraph: currentView.visibleGraph,
    fullGraph: currentView.fullGraph,
  };
}
