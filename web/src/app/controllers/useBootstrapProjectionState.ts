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
  DraftPatchApplyResult,
  DraftWorkbenchEntry,
  FactGraphViewDocument,
  FlowchartViewDocument,
  GeneratedCodeDraft,
  GeneratedCodeDraftWriteReport,
  GraphBeautificationResult,
  GraphPatch,
  GraphPatchResult,
  GraphSurfaceExperimentFlags,
  LinkGraphBootstrapState,
  LinkGraphDocument,
  LinkGraphEdge,
  LinkGraphLayoutState,
  LinkGraphNode,
  MermaidIssue,
  OperationFeedback,
  QaRequestRecoveryState,
  ResourceRelationViewDocument,
  SourceNavigationState,
  StageEligibilityDecision,
  WorkbenchSectionPreferences,
} from "../types";

const DEFAULT_ANALYSIS_DISPLAY_MODE: AnalysisDisplayMode = "FLOWCHART";
const REQUEST_ONLY_SELECTION_MESSAGE_TYPES = new Set([
  "requestAudit",
  "auditResult",
]);

interface UseBootstrapProjectionStateArgs {
  nodesRef: MutableRefObject<LinkGraphNode[]>;
  edgesRef: MutableRefObject<LinkGraphEdge[]>;
  draftGraphRef: MutableRefObject<LinkGraphDocument | null>;
  anchorNodeIdRef: MutableRefObject<string | null>;
  analysisDisplayModeRef: MutableRefObject<AnalysisDisplayMode>;
  semanticRevisionRef: MutableRefObject<number | null>;
  layoutRevisionRef: MutableRefObject<number | null>;
  selectedNodeId: string | null;
  nodes: LinkGraphNode[];
  edges: LinkGraphEdge[];
  factGraphView: FactGraphViewDocument;
  flowchartView: FlowchartViewDocument;
  resourceRelationView: ResourceRelationViewDocument;
  setNodes: Dispatch<SetStateAction<LinkGraphNode[]>>;
  setEdges: Dispatch<SetStateAction<LinkGraphEdge[]>>;
  setFactGraphView: Dispatch<SetStateAction<FactGraphViewDocument>>;
  setFlowchartView: Dispatch<SetStateAction<FlowchartViewDocument>>;
  setResourceRelationView: Dispatch<SetStateAction<ResourceRelationViewDocument>>;
  setReferenceWorkingGraph: Dispatch<SetStateAction<LinkGraphDocument | null>>;
  setFactGraph: Dispatch<SetStateAction<LinkGraphDocument | null>>;
  setAnalysisDisplayMode: Dispatch<SetStateAction<AnalysisDisplayMode>>;
  setDraftGraph: Dispatch<SetStateAction<LinkGraphDocument | null>>;
  setDraftWorkbenchState: Dispatch<SetStateAction<{ draftChanges: DraftWorkbenchEntry[]; draftNotes: DraftWorkbenchEntry[] }>>;
  setDesignBaseline: Dispatch<SetStateAction<LinkGraphDocument | null>>;
  setDraftPatchPreview: Dispatch<SetStateAction<GraphPatch | null>>;
  setCanUndoDraftPatchApply: Dispatch<SetStateAction<boolean>>;
  setLastAppliedDraftPatchSummary: Dispatch<SetStateAction<string | null>>;
  setLastAppliedDraftPatchPreview: Dispatch<SetStateAction<GraphPatch | null>>;
  setLastDraftPatchApplyResult: Dispatch<SetStateAction<DraftPatchApplyResult | null>>;
  setAuditResult: Dispatch<SetStateAction<GraphPatchResult | null>>;
  setAuditRequestState: Dispatch<SetStateAction<AsyncRequestState>>;
  setQaRequestRecoveryState: Dispatch<SetStateAction<QaRequestRecoveryState>>;
  setDiffReviewResult: Dispatch<SetStateAction<GraphPatchResult | null>>;
  setDiffReviewRequestState: Dispatch<SetStateAction<AsyncRequestState>>;
  setAnchorNodeId: Dispatch<SetStateAction<string | null>>;
  setSelectedNodeId: Dispatch<SetStateAction<string | null>>;
  setMermaidIssues: Dispatch<SetStateAction<MermaidIssue[]>>;
  setDiffItems: Dispatch<SetStateAction<any[]>>;
  setSyncPreviewItems: Dispatch<SetStateAction<any[]>>;
  setDraftVersion: Dispatch<SetStateAction<number | null>>;
  setGenerationPlan: Dispatch<SetStateAction<any | null>>;
  setGenerationPlanDraftVersion: Dispatch<SetStateAction<number | null>>;
  setGenerationPlanRequestState: Dispatch<SetStateAction<AsyncRequestState>>;
  setDraftValidationState: Dispatch<SetStateAction<any | null>>;
  setGenerationPlanDiscussionSession: Dispatch<SetStateAction<any | null>>;
  setGenerationPlanDiscussionRequestState: Dispatch<SetStateAction<AsyncRequestState>>;
  explanationLocalOverrideRef: MutableRefObject<boolean>;
  setGraphBeautificationResult: Dispatch<SetStateAction<GraphBeautificationResult | null>>;
  setGraphBeautificationRequestState: Dispatch<SetStateAction<AsyncRequestState>>;
  setGeneratedCodeDrafts: Dispatch<SetStateAction<GeneratedCodeDraft[]>>;
  setGeneratedCodeDraftVersion: Dispatch<SetStateAction<number | null>>;
  setGeneratedCodeDraftWarnings: Dispatch<SetStateAction<string[]>>;
  setGeneratedCodeDraftSource: Dispatch<SetStateAction<"DISABLED" | "MOCK" | "REMOTE" | null>>;
  setGeneratedCodeDraftPromptPreview: Dispatch<SetStateAction<string | null>>;
  setGeneratedCodeDraftPromptPreviewArtifactId: Dispatch<SetStateAction<string | null>>;
  setGeneratedCodeDraftWriteReport: Dispatch<SetStateAction<GeneratedCodeDraftWriteReport | null>>;
  setCodeDraftRequestState: Dispatch<SetStateAction<AsyncRequestState>>;
  setCodeEligibilityDecision: Dispatch<SetStateAction<StageEligibilityDecision | null>>;
  setSourceNavigationState: Dispatch<SetStateAction<SourceNavigationState>>;
  setOperationFeedback: Dispatch<SetStateAction<OperationFeedback | null>>;
  setWorkbenchSectionPreferences: Dispatch<SetStateAction<WorkbenchSectionPreferences>>;
  setLastMessageType: Dispatch<SetStateAction<string | null>>;
  setGraphSurfaceExperiments: Dispatch<SetStateAction<GraphSurfaceExperimentFlags | null>>;
  setArtifactContents: Dispatch<SetStateAction<Record<string, string>>>;
  setDetailNodeId: Dispatch<SetStateAction<string | null>>;
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
  applyBootstrapRoutesToDocument: (nextDocument: LinkGraphDocument, currentDocument: LinkGraphDocument) => LinkGraphDocument;
  resolveAnchorNodeId: (nodes: LinkGraphNode[], preferredNodeId: string | null) => string | null;
  shouldResetAnchorNode: (state: LinkGraphBootstrapState, semanticGraphChanged: boolean) => boolean;
  deriveFactGraphSummary: (
    visibleGraph: LinkGraphDocument,
    fullGraph: LinkGraphDocument,
    anchorNodeId: string | null,
  ) => FactGraphViewDocument["summary"];
  resolveReferenceWorkingGraph: (state: LinkGraphBootstrapState, displayMode: AnalysisDisplayMode) => LinkGraphDocument | null;
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
    let nextFactGraphView = args.applyBootstrapRoutesToViewDocument(args.resolveFactGraphView(nextState), args.factGraphView);
    let nextFlowchartView = args.applyBootstrapRoutesToViewDocument(args.resolveFlowchartView(nextState), args.flowchartView);
    let nextResourceRelationView = args.applyBootstrapRoutesToViewDocument(
      args.resolveResourceRelationView(nextState),
      args.resourceRelationView,
    );
    const nextWorkingGraph = args.applyBootstrapRoutesToDocument(args.resolveWorkingGraph(nextState), effectiveCurrentDraftGraph);
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
      args.setDraftWorkbenchState(nextState.draftWorkbenchState ?? { draftChanges: [], draftNotes: [] });
      args.setDraftPatchPreview(nextState.draftPatchPreview ?? null);
      args.setCanUndoDraftPatchApply(nextState.canUndoDraftPatchApply ?? false);
      args.setLastAppliedDraftPatchSummary(nextState.lastAppliedDraftPatchSummary ?? null);
      args.setLastDraftPatchApplyResult(nextState.lastDraftPatchApplyResult ?? null);
      if (!(nextState.canUndoDraftPatchApply ?? false) && !nextState.lastAppliedDraftPatchSummary) {
        args.setLastAppliedDraftPatchPreview(null);
      }
      args.setAuditResult(nextState.auditResult ?? null);
      args.setAuditRequestState(args.resolveRequestState(nextState.auditRequestState));
      args.setQaRequestRecoveryState(nextState.qaRequestRecoveryState ?? { lastSubmittedRequest: null, lastFailedRequest: null });
      args.setDiffReviewResult(nextState.diffReviewResult ?? null);
      args.setDiffReviewRequestState(args.resolveRequestState(nextState.diffReviewRequestState));
      args.setDraftVersion(nextState.draftVersion ?? null);
      args.setGenerationPlan(nextState.generationPlan ?? null);
      args.setGenerationPlanDraftVersion(nextState.generationPlanDraftVersion ?? null);
      args.setGenerationPlanRequestState(args.resolveRequestState(nextState.generationPlanRequestState));
      args.setDraftValidationState(nextState.draftValidationState ?? null);
      args.setGenerationPlanDiscussionSession(nextState.generationPlanDiscussionSession ?? null);
      args.setGenerationPlanDiscussionRequestState(args.resolveRequestState(nextState.generationPlanDiscussionRequestState));
      if (!args.explanationLocalOverrideRef.current) {
        args.setGraphBeautificationResult(nextState.graphBeautificationResult ?? null);
        args.setGraphBeautificationRequestState(args.resolveRequestState(nextState.graphBeautificationRequestState));
      }
      args.setGeneratedCodeDrafts(nextState.generatedCodeDrafts ?? []);
      args.setGeneratedCodeDraftVersion(nextState.generatedCodeDraftVersion ?? null);
      args.setGeneratedCodeDraftWarnings(nextState.generatedCodeDraftWarnings ?? []);
      args.setGeneratedCodeDraftSource(nextState.generatedCodeDraftSource ?? null);
      args.setGeneratedCodeDraftPromptPreview(nextState.generatedCodeDraftPromptPreview ?? null);
      args.setGeneratedCodeDraftPromptPreviewArtifactId(nextState.generatedCodeDraftPromptPreviewArtifactId ?? null);
      args.setGeneratedCodeDraftWriteReport(nextState.generatedCodeDraftWriteReport ?? null);
      args.setCodeDraftRequestState(args.resolveRequestState(nextState.codeDraftRequestState));
      args.setCodeEligibilityDecision(nextState.codeEligibilityDecision ?? null);
      args.setSourceNavigationState(nextSourceNavigationState);
      args.setOperationFeedback(nextState.operationFeedback ?? null);
      args.setWorkbenchSectionPreferences(nextState.workbenchSectionPreferences ?? {});
      args.setLastMessageType(nextState.lastMessageType ?? null);
      args.setGraphSurfaceExperiments(nextState.graphSurfaceExperiments ?? null);
      if (nextState.artifactContents) {
        args.setArtifactContents((current) => ({
          ...current,
          ...nextState.artifactContents,
        }));
      }
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
    const visibleSemanticChanged = skipGraphSignatureChecks
      ? false
      : revisionsAvailable
      ? analysisDisplayModeChanged
        || (
          semanticRevisionAdvanced
          && graphSemanticSignature({ nodes: currentNodes, edges: currentEdges }) !== graphSemanticSignature(visibleGraph)
        )
      : analysisDisplayModeChanged
        || graphSemanticSignature({ nodes: currentNodes, edges: currentEdges }) !== graphSemanticSignature(visibleGraph);
    const visibleLayoutChanged = skipGraphSignatureChecks
      ? false
      : revisionsAvailable
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
    nextFactGraphView = reuseCurrentViewGraphs(nextFactGraphView, args.factGraphView, reuseCurrentViewGraphsWhenStable);
    nextFlowchartView = reuseCurrentViewGraphs(nextFlowchartView, args.flowchartView, reuseCurrentViewGraphsWhenStable);
    nextResourceRelationView = reuseCurrentViewGraphs(
      nextResourceRelationView,
      args.resourceRelationView,
      reuseCurrentViewGraphsWhenStable,
    );
    const requestedDraftPatchFocusNodeId = nextState.lastMessageType === "draftPatchApplied"
      ? nextState.lastDraftPatchApplyResult?.focusNodeId ?? null
      : null;
    const nextSelectedNodeId = nextState.selectedNodeId ?? nextGraph.nodes[0]?.id ?? null;
    const shouldPreserveLocalSelection = Boolean(
      args.selectedNodeId
      && !semanticGraphChanged
      && !layoutGraphChanged
      && REQUEST_ONLY_SELECTION_MESSAGE_TYPES.has(nextState.lastMessageType ?? "")
      && nextGraph.nodes.some((node) => node.id === args.selectedNodeId),
    );
    const effectiveSelectedNodeId = requestedDraftPatchFocusNodeId && nextGraph.nodes.some((node) => node.id === requestedDraftPatchFocusNodeId)
      ? requestedDraftPatchFocusNodeId
      : shouldPreserveLocalSelection
        ? args.selectedNodeId
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
    let nextDraftGraph = nextWorkingGraph;
    const nextDraftGraphWithPositions = nextDraftGraph.nodes.length > 0
      ? {
          ...nextDraftGraph,
          nodes: applyBootstrapNodePositions(
            nextDraftGraph.nodes,
            effectiveCurrentDraftGraph.nodes,
            nextState.layoutState,
            !analysisDisplayModeChanged,
            nextState.analysisDisplayMode ?? DEFAULT_ANALYSIS_DISPLAY_MODE,
          ),
        }
      : nextDraftGraph;
    const workingSemanticChanged = nextState.workingGraph != null
      ? skipGraphSignatureChecks
        ? false
        : revisionsAvailable
          ? semanticRevisionAdvanced
            && graphSemanticSignature(effectiveCurrentDraftGraph) !== graphSemanticSignature(nextDraftGraph)
          : graphSemanticSignature(effectiveCurrentDraftGraph) !== graphSemanticSignature(nextDraftGraph)
      : semanticGraphChanged;
    const workingLayoutChanged = nextState.workingGraph != null
      ? skipGraphSignatureChecks
        ? false
        : graphLayoutSignature(effectiveCurrentDraftGraph.nodes) !== graphLayoutSignature(nextDraftGraphWithPositions.nodes)
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
    if (semanticGraphChanged || layoutGraphChanged) {
      args.setNodes(nextNodes);
    }
    if (semanticGraphChanged) {
      args.setEdges(nextGraph.edges);
    }
    args.setFactGraphView(
      nextAnalysisDisplayMode === "FACT_GRAPH"
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
    );
    args.setFlowchartView(
      nextAnalysisDisplayMode === "FLOWCHART"
        ? {
            ...nextFlowchartView,
            anchorNodeId: nextAnchorNodeId,
          }
        : nextFlowchartView,
    );
    args.setResourceRelationView(
      nextAnalysisDisplayMode === "RESOURCE_RELATION_VIEW"
        ? {
            ...nextResourceRelationView,
            anchorNodeId: nextAnchorNodeId,
          }
        : nextResourceRelationView,
    );
    args.setReferenceWorkingGraph(args.resolveReferenceWorkingGraph(nextState, nextAnalysisDisplayMode));
    args.setFactGraph(args.resolveReferenceFactGraph(nextState));
    args.setAnalysisDisplayMode(nextAnalysisDisplayMode);
    args.setDraftGraph(nextDraftGraphWithPositions.nodes.length > 0
      ? {
          ...nextDraftGraphWithPositions,
          nodes: nextDraftGraphNodes,
        }
      : nextDraftGraphWithPositions);
    args.setDraftWorkbenchState(nextState.draftWorkbenchState ?? { draftChanges: [], draftNotes: [] });
    args.setDesignBaseline(args.resolveDesignBaselineGraph(nextState));
    args.setDraftPatchPreview(nextState.draftPatchPreview ?? null);
    args.setCanUndoDraftPatchApply(nextState.canUndoDraftPatchApply ?? false);
    args.setLastAppliedDraftPatchSummary(nextState.lastAppliedDraftPatchSummary ?? null);
    args.setLastDraftPatchApplyResult(nextState.lastDraftPatchApplyResult ?? null);
    if (!(nextState.canUndoDraftPatchApply ?? false) && !nextState.lastAppliedDraftPatchSummary) {
      args.setLastAppliedDraftPatchPreview(null);
    }
    args.setAuditResult(nextState.auditResult ?? null);
    args.setAuditRequestState(args.resolveRequestState(nextState.auditRequestState));
    args.setQaRequestRecoveryState(nextState.qaRequestRecoveryState ?? { lastSubmittedRequest: null, lastFailedRequest: null });
    args.setDiffReviewResult(nextState.diffReviewResult ?? null);
    args.setDiffReviewRequestState(args.resolveRequestState(nextState.diffReviewRequestState));
    args.setAnchorNodeId(nextAnchorNodeId);
    args.setSelectedNodeId(effectiveSelectedNodeId);
    args.setMermaidIssues(nextState.mermaidIssues ?? []);
    args.setDiffItems(nextState.diffItems);
    args.setSyncPreviewItems(nextState.syncPreviewItems);
    args.setDraftVersion(nextState.draftVersion ?? null);
    args.setGenerationPlan(nextState.generationPlan ?? null);
    args.setGenerationPlanDraftVersion(nextState.generationPlanDraftVersion ?? null);
    args.setGenerationPlanRequestState(args.resolveRequestState(nextState.generationPlanRequestState));
    args.setDraftValidationState(nextState.draftValidationState ?? null);
    args.setGenerationPlanDiscussionSession(nextState.generationPlanDiscussionSession ?? null);
    args.setGenerationPlanDiscussionRequestState(args.resolveRequestState(nextState.generationPlanDiscussionRequestState));
    if (!args.explanationLocalOverrideRef.current) {
      args.setGraphBeautificationResult(nextState.graphBeautificationResult ?? null);
      args.setGraphBeautificationRequestState(args.resolveRequestState(nextState.graphBeautificationRequestState));
    }
    args.setGeneratedCodeDrafts(nextState.generatedCodeDrafts ?? []);
    args.setGeneratedCodeDraftVersion(nextState.generatedCodeDraftVersion ?? null);
    args.setGeneratedCodeDraftWarnings(nextState.generatedCodeDraftWarnings ?? []);
    args.setGeneratedCodeDraftSource(nextState.generatedCodeDraftSource ?? null);
    args.setGeneratedCodeDraftPromptPreview(nextState.generatedCodeDraftPromptPreview ?? null);
    args.setGeneratedCodeDraftPromptPreviewArtifactId(nextState.generatedCodeDraftPromptPreviewArtifactId ?? null);
    args.setGeneratedCodeDraftWriteReport(nextState.generatedCodeDraftWriteReport ?? null);
    args.setCodeDraftRequestState(args.resolveRequestState(nextState.codeDraftRequestState));
    args.setCodeEligibilityDecision(nextState.codeEligibilityDecision ?? null);
    args.setSourceNavigationState(nextSourceNavigationState);
    args.setOperationFeedback(nextState.operationFeedback ?? null);
    args.setWorkbenchSectionPreferences(nextState.workbenchSectionPreferences ?? {});
    args.setLastMessageType(nextState.lastMessageType ?? null);
    args.setGraphSurfaceExperiments(nextState.graphSurfaceExperiments ?? null);
    if (nextState.artifactContents) {
      args.setArtifactContents((current) => ({
        ...current,
        ...nextState.artifactContents,
      }));
    }
    if (requestedDraftPatchFocusNodeId && nextNodes.some((node) => node.id === requestedDraftPatchFocusNodeId)) {
      args.setDetailNodeId(requestedDraftPatchFocusNodeId);
    }
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
    args.setDetailNodeId((current) => (current && nextNodes.some((node) => node.id === current) ? current : null));
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
