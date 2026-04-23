import { useEffect, useMemo, useRef, useState } from "react";
import {
  applyDraftPatchPreview,
  clearDraftPatchPreview,
  publishGraphChange,
  restoreDraftPatchPreview,
  confirmAuditCandidateChange,
  undoLastDraftPatchApply,
} from "./api";
import {
  applyLayoutUpdatesToGraphDocument,
  applyBootstrapRoutesToDocument,
  applyBootstrapRoutesToViewDocument,
  deriveFactGraphSummary,
  deriveFlowchartSummary,
  deriveLatestTurnOutcome,
  deriveResourceRelationSummary,
  overlayDraftEntryOntoFlowchartView,
  resolveAnchorNodeId,
  resolveCollapsedDescendantSummary,
  resolveDisplayedNodeId,
  resolveAuditTargetNodeIds,
  resolveDraftEntryPrimaryNodeId,
  resolveDraftEntryTargetNodeIds,
  resolveEntryOwnerSignatures,
  resolveEvidenceTargetNodeId,
  resolveNodeOwnerSignature,
  scopeFlowchartGraphToAnchorMethod,
  shouldResetAnchorNode,
  syncFactGraphViewDocument,
  syncFlowchartViewLayout,
  syncResourceRelationViewLayout,
  toDraftWorkbenchEntry,
  updateGraphPatchResultCandidateStatus,
} from "./appGraphSupport";
import { AsyncRequestFailureDialog } from "./components/AsyncRequestFailureDialog";
import { AppGraphStage } from "./components/AppGraphStage";
import { AppWorkbenchPanels, type WorkbenchTab } from "./components/AppWorkbenchPanels";
import { DiffPanel } from "./components/DiffPanel";
import { IssuePanel } from "./components/IssuePanel";
import { Legend } from "./components/Legend";
import { MermaidImportDialog } from "./components/MermaidImportDialog";
import { PropertyPanel } from "./components/PropertyPanel";
import { SyncPreviewPanel } from "./components/SyncPreviewPanel";
import {
  applyBootstrapNodePositions,
  applyLayoutOnlyNodePositions,
  clearStoredNodePosition,
  collectDownstreamSubtreeNodeIds,
  extractLayoutPayload,
  fallbackDesignPosition,
  graphLayoutSignature,
  graphSemanticSignature,
  hasRevision,
  normalizeGraphNodes,
  resolveNodePosition,
  syncNodePosition,
} from "./graphState";
import { canEditNodeLayout } from "./layoutEditability";
import type {
  AsyncRequestState,
  AnalysisDisplayMode,
  CandidateDraftChange,
  DiffItem,
  DraftPatchPreviewSource,
  DraftWorkbenchEntry,
  GeneratedCodeDraft,
  GeneratedCodeDraftWriteReport,
  GraphBeautificationResult,
  LinkGraphLayoutState,
  GraphPatch,
  GraphPatchResult,
  DraftPatchApplyResult,
  FactGraphViewDocument,
  FlowchartViewDocument,
  GraphSurfaceExperimentFlags,
  LinkGraphDocument,
  LinkGraphBootstrapState,
  LinkGraphEdge,
  LinkGraphNode,
  MermaidIssue,
  InvestigationThread,
  InvestigationTurnOutcome,
  OperationFeedback,
  QaRequestRecoveryState,
  ResourceRelationViewDocument,
  StageEligibilityDecision,
  StepGranularity,
  WorkbenchSectionPreferences,
} from "./types";
import { GraphWorkbench } from "./workbench/GraphWorkbench";
import { WorkbenchPropertyDrawer } from "./workbench/WorkbenchPropertyDrawer";
import { WorkbenchToolbar } from "./workbench/WorkbenchToolbar";
import type { RequestFailureNotice } from "./controllers/bridgeCommandTypes";
import { useAuditWorkbenchController } from "./controllers/useAuditWorkbenchController";
import { useAppWorkbenchShellController } from "./controllers/useAppWorkbenchShellController";
import { useBootstrapProjectionState } from "./controllers/useBootstrapProjectionState";
import { useBootstrapStateController } from "./controllers/useBootstrapStateController";
import { useAppBridgeController } from "./controllers/useAppBridgeController";
import { useBridgeCommandController } from "./controllers/useBridgeCommandController";
import { useDraftWorkbenchController } from "./controllers/useDraftWorkbenchController";
import { useExplanationWorkbenchController } from "./controllers/useExplanationWorkbenchController";
import { useGraphCanvasController } from "./controllers/useGraphCanvasController";
import { useGraphEditController } from "./controllers/useGraphEditController";
import { useInteractionProbeController } from "./controllers/useInteractionProbeController";
import { useManualNodeIdController } from "./controllers/useManualNodeIdController";
import { useNodeSelectionController } from "./controllers/useNodeSelectionController";
import { useSourceNavigationController } from "./controllers/useSourceNavigationController";
import { useWorkbenchDerivedState } from "./controllers/useWorkbenchDerivedState";
import { useWorkbenchCommandController } from "./controllers/useWorkbenchCommandController";
import { useWorkbenchState } from "./controllers/useWorkbenchState";
import { resolveToolbarFeedback } from "./asyncRequestStatus";
import {
  DEFAULT_ANALYSIS_DISPLAY_MODE,
  EMPTY_QA_REQUEST_RECOVERY_STATE,
  EMPTY_STATE,
  IDLE_REQUEST_STATE,
  IDLE_SOURCE_NAVIGATION_STATE,
  SAMPLE_STATE,
  resolveActiveViewDocument,
  resolveDesignBaselineGraph,
  resolveFactGraphView,
  resolveFlowchartView,
  resolveInitialState,
  resolveReferenceFactGraph,
  resolveReferenceWorkingGraph,
  resolveRequestState,
  resolveResourceRelationView,
  resolveSourceNavigationState,
  resolveWorkingGraph,
} from "./sampleState";

type ExplanationRequestMode = "fresh" | "follow_up";

interface ExplanationHistoryEntry {
  result: GraphBeautificationResult;
  requestState: AsyncRequestState;
  selectedStepId: string | null;
  granularity: StepGranularity;
  sessionLabel: string;
}

const DEFAULT_EXPLANATION_SESSION_LABEL = "当前链路讲解";
export { resolveAuditTargetNodeIds } from "./appGraphSupport";

export function App() {
  const initialStateRef = useRef<LinkGraphBootstrapState | null>(null);
  if (!initialStateRef.current) {
    initialStateRef.current = resolveInitialState({
      emptyState: EMPTY_STATE,
      sampleState: SAMPLE_STATE,
    });
  }
  const initialState = initialStateRef.current;
  const initialGraph = resolveActiveViewDocument(initialState).visibleGraph;
  const initialAnchorNodeId = resolveAnchorNodeId(
    initialGraph.nodes,
    initialState.selectedNodeId ?? initialGraph.nodes[0]?.id ?? null,
  );
  const {
    nodes,
    setNodes,
    edges,
    setEdges,
    selectedNodeId,
    setSelectedNodeId,
    analysisDisplayMode,
    setAnalysisDisplayMode,
    anchorNodeId,
    setAnchorNodeId,
    detailNodeId,
    setDetailNodeId,
    auditRequestState,
    setAuditRequestState,
    auditTargetNodeIds,
    setAuditTargetNodeIds,
    auditQuestionDraft,
    setAuditQuestionDraft,
    selectionGroupNodeIds,
    setSelectionGroupNodeIds,
    collapsedNodeIds,
    setCollapsedNodeIds,
    referenceWorkingGraph,
    setReferenceWorkingGraph,
    factGraph,
    setFactGraph,
    factGraphView,
    setFactGraphView,
    flowchartView,
    setFlowchartView,
    resourceRelationView,
    setResourceRelationView,
    draftGraph,
    setDraftGraph,
    draftWorkbenchState,
    setDraftWorkbenchState,
    designBaseline,
    setDesignBaseline,
    draftPatchPreview,
    setDraftPatchPreview,
    lastAppliedDraftPatchPreview,
    setLastAppliedDraftPatchPreview,
    canUndoDraftPatchApply,
    setCanUndoDraftPatchApply,
    lastAppliedDraftPatchSummary,
    setLastAppliedDraftPatchSummary,
    auditResult,
    setAuditResult,
    diffReviewResult,
    setDiffReviewResult,
    mermaidIssues,
    setMermaidIssues,
    diffItems,
    setDiffItems,
    syncPreviewItems,
    setSyncPreviewItems,
    draftVersion,
    setDraftVersion,
    generationPlan,
    setGenerationPlan,
    generationPlanDraftVersion,
    setGenerationPlanDraftVersion,
    generationPlanRequestState,
    setGenerationPlanRequestState,
    draftValidationState,
    setDraftValidationState,
    generationPlanDiscussionSession,
    setGenerationPlanDiscussionSession,
    generationPlanDiscussionRequestState,
    setGenerationPlanDiscussionRequestState,
    diffReviewRequestState,
    setDiffReviewRequestState,
    graphBeautificationResult,
    setGraphBeautificationResult,
    graphBeautificationRequestState,
    setGraphBeautificationRequestState,
    generatedCodeDrafts,
    setGeneratedCodeDrafts,
    generatedCodeDraftVersion,
    setGeneratedCodeDraftVersion,
    generatedCodeDraftWarnings,
    setGeneratedCodeDraftWarnings,
    generatedCodeDraftSource,
    setGeneratedCodeDraftSource,
    generatedCodeDraftPromptPreview,
    setGeneratedCodeDraftPromptPreview,
    generatedCodeDraftPromptPreviewArtifactId,
    setGeneratedCodeDraftPromptPreviewArtifactId,
    generatedCodeDraftWriteReport,
    setGeneratedCodeDraftWriteReport,
    lastDraftPatchApplyResult,
    setLastDraftPatchApplyResult,
    codeDraftRequestState,
    setCodeDraftRequestState,
    requestFailureNotice,
    setRequestFailureNotice,
    sourceNavigationState: _sourceNavigationState,
    setSourceNavigationState,
    operationFeedback,
    setOperationFeedback,
    lastMessageType,
    setLastMessageType,
    graphSurfaceExperiments,
    setGraphSurfaceExperiments,
    artifactContents,
    setArtifactContents,
    isImportDialogOpen,
    setImportDialogOpen,
    mermaidDraft,
    setMermaidDraft,
    diffTargetItemIds,
    setDiffTargetItemIds,
  } = useWorkbenchState({
    initialState,
    initialGraph,
    initialAnchorNodeId,
    resolveRequestState,
    resolveReferenceWorkingGraph,
    resolveReferenceFactGraph: (state) => resolveReferenceFactGraph(state, EMPTY_STATE),
    resolveFactGraphView: (state) => resolveFactGraphView(state, EMPTY_STATE),
    resolveFlowchartView: (state) => resolveFlowchartView(state, EMPTY_STATE),
    resolveResourceRelationView: (state) => resolveResourceRelationView(state, EMPTY_STATE),
    resolveWorkingGraph,
    resolveDesignBaselineGraph,
    resolveSourceNavigationState: (state) => resolveSourceNavigationState(state, IDLE_SOURCE_NAVIGATION_STATE),
    normalizeGraphNodes,
  });
  const [qaRequestRecoveryState, setQaRequestRecoveryState] = useState<QaRequestRecoveryState>(
    () => initialState.qaRequestRecoveryState ?? EMPTY_QA_REQUEST_RECOVERY_STATE,
  );
  const [codeEligibilityDecision, setCodeEligibilityDecision] = useState<StageEligibilityDecision | null>(
    () => initialState.codeEligibilityDecision ?? null,
  );
  const [generationPlanDiscussionQuestionDraft, setGenerationPlanDiscussionQuestionDraft] = useState("");
  const bridgeCommands = useBridgeCommandController({
    setOperationFeedback,
    setRequestFailureNotice,
  });
  const {
    resolveArtifactText,
    handleRequestArtifact,
    handleWorkbenchSectionPreferenceChange,
    handleConfirmImportMermaid,
    handleRequestDiffReview,
    handleWriteSingleCodeDraft,
    handleOpenCodeDraftNativeDiff,
  } = useAppBridgeController({
    artifactContents,
    bridgeCommands,
    setImportDialogOpen,
  });
  const sourceNavigationCommands = useSourceNavigationController({
    nodes,
    selectNode: (nodeId) => {
      setSelectedNodeId(nodeId);
    },
    setSourceNavigationState,
    setOperationFeedback,
    bridgeCommands,
  });
  const workbenchCommands = useWorkbenchCommandController({
    bridgeCommands,
  });
  const toolbarFeedback = useMemo(() => resolveToolbarFeedback({
    operationFeedback,
    lastMessageType,
    requestStates: [
      auditRequestState,
      diffReviewRequestState,
      graphBeautificationRequestState,
      generationPlanRequestState,
      codeDraftRequestState,
    ],
  }), [
    auditRequestState,
    codeDraftRequestState,
    diffReviewRequestState,
    generationPlanRequestState,
    graphBeautificationRequestState,
    lastMessageType,
    operationFeedback,
  ]);
  const [activeWorkbenchTab, setActiveWorkbenchTab] = useState<WorkbenchTab>("explanation");
  const [workbenchSectionPreferences, setWorkbenchSectionPreferences] = useState<WorkbenchSectionPreferences>(
    () => initialState.workbenchSectionPreferences ?? {},
  );
  const [selectedExplanationStepId, setSelectedExplanationStepId] = useState<string | null>(
    () => graphBeautificationResult?.steps?.[0]?.stepId ?? null,
  );
  const [selectedExplanationGranularity, setSelectedExplanationGranularity] = useState<StepGranularity>(
    () => graphBeautificationResult?.granularity ?? "BUSINESS",
  );
  const [explanationHistory, setExplanationHistory] = useState<ExplanationHistoryEntry[]>([]);
  const [currentExplanationSessionLabel, setCurrentExplanationSessionLabel] = useState(DEFAULT_EXPLANATION_SESSION_LABEL);
  const [hoveredExplanationStepId, setHoveredExplanationStepId] = useState<string | null>(null);
  const [selectedAuditChangeId, setSelectedAuditChangeId] = useState<string | null>(null);
  const [selectedAuditThreadId, setSelectedAuditThreadId] = useState<string | null>(null);
  const [auditSourceThreadId, setAuditSourceThreadId] = useState<string | null>(null);
  const [selectedDraftEntryId, setSelectedDraftEntryId] = useState<string | null>(
    () => initialState.draftWorkbenchState?.draftChanges[0]?.entryId
      ?? initialState.draftWorkbenchState?.draftNotes[0]?.entryId
      ?? null,
  );
  const [draftCompareMode, setDraftCompareMode] = useState<"after" | "compare">("after");
  const semanticRevisionRef = useRef<number | null>(initialState.semanticRevision ?? null);
  const layoutRevisionRef = useRef<number | null>(initialState.layoutRevision ?? null);
  const nodesRef = useRef(nodes);
  const edgesRef = useRef(edges);
  const draftGraphRef = useRef(draftGraph);
  const anchorNodeIdRef = useRef(anchorNodeId);
  const analysisDisplayModeRef = useRef(analysisDisplayMode);
  const pendingExplanationDrillTargetRef = useRef<string | null>(null);
  const explanationLocalOverrideRef = useRef(false);
  const pendingExplanationRequestModeRef = useRef<ExplanationRequestMode | null>(null);
  const pendingExplanationSessionLabelRef = useRef<string | null>(null);
  const pendingExplanationHistoryEntryRef = useRef<ExplanationHistoryEntry | null>(null);
  const {
    nextManualNodeIdRef,
    syncManualNodeIdCounters,
  } = useManualNodeIdController(initialGraph.nodes);
  useEffect(() => {
    nodesRef.current = nodes;
  }, [nodes]);

  useEffect(() => {
    edgesRef.current = edges;
  }, [edges]);

  useEffect(() => {
    draftGraphRef.current = draftGraph;
  }, [draftGraph]);

  useEffect(() => {
    anchorNodeIdRef.current = anchorNodeId;
  }, [anchorNodeId]);

  useEffect(() => {
    setSelectedDraftEntryId((current) => {
      const allEntries = draftWorkbenchState.draftChanges.concat(draftWorkbenchState.draftNotes);
      if (current && allEntries.some((entry) => entry.entryId === current)) {
        return current;
      }
      return draftWorkbenchState.draftChanges[0]?.entryId
        ?? draftWorkbenchState.draftNotes[0]?.entryId
        ?? null;
    });
  }, [draftWorkbenchState.draftChanges, draftWorkbenchState.draftNotes]);

  useEffect(() => {
    analysisDisplayModeRef.current = analysisDisplayMode;
  }, [analysisDisplayMode]);

  const selectedNode = nodes.find((node) => node.id === selectedNodeId) ?? null;
  const detailNode = nodes.find((node) => node.id === detailNodeId) ?? null;
  const auditTargetTitle = auditTargetNodeIds.length === 1
    ? nodes.find((node) => node.id === auditTargetNodeIds[0])?.title ?? null
    : null;

  const collapsedSummary = useMemo(
    () => resolveCollapsedDescendantSummary(nodes, edges, collapsedNodeIds),
    [nodes, edges, collapsedNodeIds],
  );
  const hiddenNodeIdSet = collapsedSummary.hiddenNodeIds;
  const hiddenNodeIds = useMemo(
    () => Array.from(hiddenNodeIdSet),
    [hiddenNodeIdSet],
  );

  function clearLocalDerivedGraphState() {
    setDraftPatchPreview(null);
    setLastAppliedDraftPatchPreview(null);
    setCanUndoDraftPatchApply(false);
    setLastAppliedDraftPatchSummary(null);
    setAuditResult(null);
    setAuditRequestState(IDLE_REQUEST_STATE);
    setQaRequestRecoveryState(EMPTY_QA_REQUEST_RECOVERY_STATE);
    setDiffReviewResult(null);
    setDiffReviewRequestState(IDLE_REQUEST_STATE);
    setSyncPreviewItems([]);
    setGenerationPlan(null);
    setGenerationPlanRequestState(IDLE_REQUEST_STATE);
    setDraftValidationState(null);
    setGenerationPlanDiscussionSession(null);
    setGenerationPlanDiscussionRequestState(IDLE_REQUEST_STATE);
    setGenerationPlanDiscussionQuestionDraft("");
    setGraphBeautificationResult(null);
    setGraphBeautificationRequestState(IDLE_REQUEST_STATE);
    setGeneratedCodeDrafts([]);
    setGeneratedCodeDraftWarnings([]);
    setGeneratedCodeDraftSource(null);
    setGeneratedCodeDraftPromptPreview(null);
    setGeneratedCodeDraftPromptPreviewArtifactId(null);
    setGeneratedCodeDraftWriteReport(null);
    setLastDraftPatchApplyResult(null);
    setCodeDraftRequestState(IDLE_REQUEST_STATE);
    setCodeEligibilityDecision(null);
  }

  const { syncGraph } = useGraphEditController({
    selectedNodeId,
    detailNodeId,
    analysisDisplayMode,
    anchorNodeIdRef,
    setNodes,
    setEdges,
    setAnchorNodeId,
    setSelectedNodeId,
    setCollapsedNodeIds,
    setDetailNodeId,
    setDraftGraph,
    setFactGraphView,
    setFlowchartView,
    setResourceRelationView,
    setAuditTargetNodeIds,
    clearLocalDerivedGraphState,
    syncManualNodeIdCounters,
    resolveAnchorNodeId,
    syncFactGraphViewDocument,
    deriveFlowchartSummary,
    deriveResourceRelationSummary,
  });

  const {
    focusNodeRequest,
    handleSelectNode,
    handleInspectNode,
    handleSelectionGroupChange,
    requestViewportFocus,
    selectExplanationTargetNode,
  } = useNodeSelectionController({
    bridgeCommands,
    setSelectedNodeId,
    setDetailNodeId,
    setSelectionGroupNodeIds,
  });

  function handleRequestSourceNavigation(nodeId: string) {
    sourceNavigationCommands.handleRequestSourceNavigation(nodeId);
  }

  function handleOpenImportMermaid() {
    setMermaidDraft("");
    setImportDialogOpen(true);
  }
  const {
    handleAddNode,
    handleDeleteNode,
    handleDeleteNodeSubtree,
    handleUpdateNode,
    handleCreateEdge,
    handleDeleteEdge,
    handleInsertNodeIntoEdge,
    handleMoveNode,
    handleMoveNodes,
    handleToggleCollapseNode,
    handleFormatLayout,
  } = useGraphCanvasController({
    nodes,
    edges,
    selectedNodeId,
    detailNodeId,
    analysisDisplayMode,
    collapsedNodeIds,
    nextManualNodeIdRef,
    anchorNodeIdRef,
    setNodes,
    setDraftGraph,
    setFactGraphView,
    setFlowchartView,
    setResourceRelationView,
    setCollapsedNodeIds,
    setSelectionGroupNodeIds,
    setAuditTargetNodeIds,
    setSelectedNodeId,
    setDetailNodeId,
    setOperationFeedback,
    syncGraph,
    fallbackDesignPosition,
    resolveNodePosition,
    syncNodePosition,
    collectDownstreamSubtreeNodeIds,
    applyLayoutUpdatesToGraphDocument,
    syncFactGraphViewDocument,
    syncFlowchartViewLayout,
    syncResourceRelationViewLayout,
    resolveCollapsedDescendantSummary,
  });

  const {
    handleRequestAudit,
    handleConfirmCandidateChange,
    handleRetryLastAuditRequest,
    handleEditFailedAuditRequest,
    handleSelectAuditChange,
    handleSelectAuditThread,
    handleInvestigateAuditThread,
    handleResolveAuditThread,
    handleUnconfirmDraftChange,
  } = useAuditWorkbenchController({
    qaRequestRecoveryState,
    auditResult,
    auditSourceThreadId,
    auditTargetNodeIds,
    selectedAuditChangeId,
    selectedAuditThreadId,
    nodes,
    draftWorkbenchState,
    bridgeCommands,
    setAuditQuestionDraft,
    setAuditTargetNodeIds,
    setAuditSourceThreadId,
    setActiveWorkbenchTab,
    setOperationFeedback,
    setDraftWorkbenchState,
    setSelectedDraftEntryId,
    setAuditResult,
    setSelectedAuditChangeId,
    setSelectedAuditThreadId,
    selectExplanationTargetNode,
    toDraftWorkbenchEntry,
    updateGraphPatchResultCandidateStatus,
    resolveDraftEntryTargetNodeIds,
    resolveDisplayedNodeId,
    resolveEvidenceTargetNodeId,
  });
  const { applyBootstrapState } = useBootstrapProjectionState({
    nodesRef,
    edgesRef,
    draftGraphRef,
    anchorNodeIdRef,
    analysisDisplayModeRef,
    semanticRevisionRef,
    layoutRevisionRef,
    selectedNodeId,
    nodes,
    edges,
    factGraphView,
    flowchartView,
    resourceRelationView,
    setNodes,
    setEdges,
    setFactGraphView,
    setFlowchartView,
    setResourceRelationView,
    setReferenceWorkingGraph,
    setFactGraph,
    setAnalysisDisplayMode,
    setDraftGraph,
    setDraftWorkbenchState,
    setDesignBaseline,
    setDraftPatchPreview,
    setCanUndoDraftPatchApply,
    setLastAppliedDraftPatchSummary,
    setLastAppliedDraftPatchPreview,
    setLastDraftPatchApplyResult,
    setAuditResult,
    setAuditRequestState,
    setQaRequestRecoveryState,
    setDiffReviewResult,
    setDiffReviewRequestState,
    setAnchorNodeId,
    setSelectedNodeId,
    setMermaidIssues,
    setDiffItems,
    setSyncPreviewItems,
    setDraftVersion,
    setGenerationPlan,
    setGenerationPlanDraftVersion,
    setGenerationPlanRequestState,
    setDraftValidationState,
    setGenerationPlanDiscussionSession,
    setGenerationPlanDiscussionRequestState,
    explanationLocalOverrideRef,
    setGraphBeautificationResult,
    setGraphBeautificationRequestState,
    setGeneratedCodeDrafts,
    setGeneratedCodeDraftVersion,
    setGeneratedCodeDraftWarnings,
    setGeneratedCodeDraftSource,
    setGeneratedCodeDraftPromptPreview,
    setGeneratedCodeDraftPromptPreviewArtifactId,
    setGeneratedCodeDraftWriteReport,
    setCodeDraftRequestState,
    setCodeEligibilityDecision,
    setSourceNavigationState,
    setOperationFeedback,
    setWorkbenchSectionPreferences,
    setLastMessageType,
    setGraphSurfaceExperiments,
    setArtifactContents,
    setDetailNodeId,
    setSelectionGroupNodeIds,
    setCollapsedNodeIds,
    setDiffTargetItemIds,
    syncManualNodeIdCounters,
    resolveSourceNavigationState,
    resolveFactGraphView,
    resolveFlowchartView,
    resolveResourceRelationView,
    resolveWorkingGraph,
    resolveActiveViewDocument,
    applyBootstrapRoutesToViewDocument,
    applyBootstrapRoutesToDocument,
    resolveAnchorNodeId,
    shouldResetAnchorNode,
    deriveFactGraphSummary,
    resolveReferenceWorkingGraph,
    resolveReferenceFactGraph,
    resolveDesignBaselineGraph,
    resolveRequestState,
  });

  useBootstrapStateController({
    initialRevision: initialState.snapshotRevision ?? Number.NEGATIVE_INFINITY,
    applyBootstrapState,
  });

  useInteractionProbeController({
    nodes,
    edges,
    nodesRef,
    selectionGroupNodeIds,
    detailNodeId,
    detailNode,
    sourceNavigationState: _sourceNavigationState,
    fallbackDesignPosition,
    handleSelectionGroupChange,
    handleInspectNode,
    handleMoveNode,
    handleRequestSourceNavigation,
  });

  const {
    handleOpenAudit,
    handleOpenDraftValidation,
    handleRequestGenerationPlan,
    handleRequestCodeDrafts,
    handleRequestGenerationPlanDiscussion,
    handleRequestScopedAudit,
    handleConfirmImportMermaidDraft,
    handleExpandOverflowNode,
    handleFocusDiffItem,
  } = useAppWorkbenchShellController({
    nodes,
    selectionGroupNodeIds,
    mermaidDraft,
    activeWorkbenchTab,
    selectedNodeId,
    analysisDisplayMode,
    generationPlan,
    generationPlanRequestState,
    generationPlanDiscussionQuestionDraft,
    generationPlanDiscussionSession,
    setAuditTargetNodeIds,
    setAuditQuestionDraft,
    setAuditSourceThreadId,
    setActiveWorkbenchTab,
    setWorkbenchSectionPreferences,
    setOperationFeedback,
    setDiffTargetItemIds,
    handleRequestAudit,
    handleInspectNode,
    bridgeCommands: {
      handleWorkbenchSectionPreferenceChange,
      handleConfirmImportMermaid,
    },
    workbenchCommands,
  });

  const {
    handleRequestGraphBeautification,
    handleChangeExplanationGranularity,
    handleHoverExplanationStep,
    handleLeaveExplanationStep,
    handleFollowUpExplanationStep,
    handleReturnToPreviousExplanation,
    handleOpenExplanationHistory,
    handleSelectExplanationStep,
    handleLocateExplanationStepNode,
    handleInspectExplanationStepNode,
    handleDrillDownExplanationStep,
    handleRevealExplanationReference,
  } = useExplanationWorkbenchController({
    nodes,
    graphBeautificationResult,
    graphBeautificationRequestState,
    selectedExplanationGranularity,
    selectedExplanationStepId,
    currentExplanationSessionLabel,
    explanationHistory,
    explanationLocalOverrideRef,
    pendingExplanationRequestModeRef,
    pendingExplanationSessionLabelRef,
    pendingExplanationHistoryEntryRef,
    pendingExplanationDrillTargetRef,
    bridgeCommands,
    setActiveWorkbenchTab,
    setSelectedExplanationStepId,
    setHoveredExplanationStepId,
    setSelectedExplanationGranularity,
    setCurrentExplanationSessionLabel,
    setExplanationHistory,
    setGraphBeautificationResult,
    setGraphBeautificationRequestState,
    setSelectedNodeId,
    setDetailNodeId,
    setOperationFeedback,
    selectExplanationTargetNode,
    requestViewportFocus,
    handleInspectNode,
    handleExpandOverflowNode,
    resolveDisplayedNodeId,
    defaultSessionLabel: DEFAULT_EXPLANATION_SESSION_LABEL,
  });

  useEffect(() => {
    if (!generationPlan) {
      setGenerationPlanDiscussionQuestionDraft("");
      return;
    }
    if (generationPlanDiscussionRequestState.phase === "SUCCEEDED") {
      setGenerationPlanDiscussionQuestionDraft("");
    }
  }, [generationPlan, generationPlanDiscussionRequestState.phase]);

  const {
    explanationState,
    auditState,
    draftImplementationSuggestionState,
    codeDiffStatus,
    activeViewGraph,
    selectedDraftEntry,
    presentedFlowchartView,
    explanationFocusNodeId,
    draftChangedNodeIds,
    draftCompareProjection,
    draftState,
  } = useWorkbenchDerivedState({
    analysisDisplayMode,
    activeWorkbenchTab,
    auditResult,
    auditRequestState,
    qaRequestRecoveryState,
    auditQuestionDraft,
    auditTargetNodeIds,
    auditTargetTitle,
    selectedAuditChangeId,
    selectedAuditThreadId,
    graphBeautificationResult,
    graphBeautificationRequestState,
    selectedExplanationStepId,
    selectedExplanationGranularity,
    hoveredExplanationStepId,
    explanationHistory,
    currentExplanationSessionLabel,
    draftWorkbenchState,
    selectedDraftEntryId,
    draftCompareMode,
    draftGraph,
    factGraph,
    referenceWorkingGraph,
    factGraphView,
    flowchartView,
    resourceRelationView,
    generationPlan,
    generationPlanRequestState,
    generationPlanDraftVersion,
    draftVersion,
    generatedCodeDrafts,
    generatedCodeDraftVersion,
    codeDraftRequestState,
    resolveNodeOwnerSignature,
    resolveEntryOwnerSignatures: (entry, graph) => resolveEntryOwnerSignatures(entry, graph),
    resolveDraftEntryTargetNodeIds,
    resolveDisplayedNodeId,
    overlayDraftEntryOntoFlowchartView,
  });

  function resolveDraftNodeTitle(nodeId: string) {
    return nodes.find((node) => node.id === nodeId)?.title ?? nodeId;
  }

  const {
    handleAddExplanationNoteToDraft,
    handleSelectDraftEntry,
    handleLocateDraftChangeNode,
    handleOpenDraftNote,
    handleLocateDraftNoteNode,
  } = useDraftWorkbenchController({
    graphBeautificationResult,
    draftWorkbenchState,
    nodes,
    setDraftWorkbenchState,
    setSelectedDraftEntryId,
    setActiveWorkbenchTab,
    setOperationFeedback,
    selectExplanationTargetNode,
    handleSelectExplanationStep,
    resolveDraftEntryTargetNodeIds,
    resolveDisplayedNodeId,
  });

  useEffect(() => {
    if (activeWorkbenchTab !== "draft" || !selectedDraftEntry) {
      return;
    }
    const targetNodeId = resolveDraftEntryPrimaryNodeId(selectedDraftEntry);
    const displayedTargetNodeId = resolveDisplayedNodeId(targetNodeId, nodes);
    if (!displayedTargetNodeId) {
      return;
    }
    if (selectedNodeId === displayedTargetNodeId) {
      return;
    }
    selectExplanationTargetNode(displayedTargetNodeId, { focusViewport: true });
  }, [activeWorkbenchTab, nodes, selectedDraftEntry, selectedNodeId]);

  const codePanelProps = {
    drafts: generatedCodeDrafts,
    warnings: generatedCodeDraftWarnings,
    requestState: codeDraftRequestState,
    source: generatedCodeDraftSource,
    promptPreview: generatedCodeDraftPromptPreview,
    promptPreviewArtifactId: generatedCodeDraftPromptPreviewArtifactId,
    resolveArtifactText,
    onRequestArtifact: handleRequestArtifact,
    writeReport: generatedCodeDraftWriteReport,
    hasPlan: generationPlan != null,
    eligibilityDecision: codeEligibilityDecision,
    draftVersion,
    generatedCodeDraftVersion,
    onOpenDraftWorkbench: () => setActiveWorkbenchTab("draft"),
    onOpenDraftValidation: handleOpenDraftValidation,
    onRequestPlan: handleRequestGenerationPlan,
    onRequestDrafts: handleRequestCodeDrafts,
    onWriteDrafts: workbenchCommands.handleWriteDrafts,
    onWriteSingleDraft: handleWriteSingleCodeDraft,
    onOpenNativeDiff: handleOpenCodeDraftNativeDiff,
    onOpenDraft: workbenchCommands.handleOpenDraft,
  };

  const auditTabProps = {
    state: auditState,
    onQuestionDraftChange: setAuditQuestionDraft,
    onSubmitQuestion: () => handleRequestAudit(auditQuestionDraft),
    onRetryLastRequest: handleRetryLastAuditRequest,
    onEditFailedRequest: handleEditFailedAuditRequest,
    onSelectChange: handleSelectAuditChange,
    onConfirmChange: handleConfirmCandidateChange,
    onSelectThread: handleSelectAuditThread,
    onInvestigateThread: handleInvestigateAuditThread,
    onDeferRisk: (threadId: string) => handleResolveAuditThread(threadId, "DEFERRED"),
    onAcceptRisk: (threadId: string) => handleResolveAuditThread(threadId, "ACCEPTED_RISK"),
    onDismissRisk: (threadId: string) => handleResolveAuditThread(threadId, "DISMISSED"),
    sectionPreferences: workbenchSectionPreferences,
    onSectionPreferenceChange: handleWorkbenchSectionPreferenceChange,
  };

  const draftTabProps = {
    state: draftState,
    implementationSuggestion: draftImplementationSuggestionState,
    implementationSuggestionRequestState: generationPlanRequestState,
    draftValidationState,
    implementationSuggestionDiscussionQuestionDraft: generationPlanDiscussionQuestionDraft,
    implementationSuggestionDiscussionSession: generationPlanDiscussionSession,
    implementationSuggestionDiscussionRequestState: generationPlanDiscussionRequestState,
    draftVersion,
    codeDiffStatus,
    codeDiffDraftVersion: generatedCodeDraftVersion,
    resolveArtifactText,
    onRequestArtifact: handleRequestArtifact,
    onRequestGeneratePlan: handleRequestGenerationPlan,
    onImplementationSuggestionDiscussionQuestionDraftChange: setGenerationPlanDiscussionQuestionDraft,
    onSubmitImplementationSuggestionDiscussion: handleRequestGenerationPlanDiscussion,
    onOpenAuditWorkbench: () => setActiveWorkbenchTab("audit"),
    onToggleCompare: () => setDraftCompareMode((current) => current === "after" ? "compare" : "after"),
    onSelectEntry: handleSelectDraftEntry,
    onLocateChangeNode: handleLocateDraftChangeNode,
    onUnconfirmChange: handleUnconfirmDraftChange,
    onOpenNote: handleOpenDraftNote,
    onLocateNoteNode: handleLocateDraftNoteNode,
    resolveNodeTitle: resolveDraftNodeTitle,
    sectionPreferences: workbenchSectionPreferences,
    onSectionPreferenceChange: handleWorkbenchSectionPreferenceChange,
  };

  const explanationTabProps = {
    state: explanationState,
    onSelectStep: handleSelectExplanationStep,
    onLocateStepNode: handleLocateExplanationStepNode,
    onInspectStepNode: handleInspectExplanationStepNode,
    onGranularityChange: handleChangeExplanationGranularity,
    onHoverStep: handleHoverExplanationStep,
    onLeaveStep: handleLeaveExplanationStep,
    onAddToDraft: handleAddExplanationNoteToDraft,
    onDrillDown: handleDrillDownExplanationStep,
    onFollowUp: handleFollowUpExplanationStep,
    onRevealReference: handleRevealExplanationReference,
    onReturnToPrevious: handleReturnToPreviousExplanation,
    onOpenHistory: handleOpenExplanationHistory,
    sectionPreferences: workbenchSectionPreferences,
    onSectionPreferenceChange: handleWorkbenchSectionPreferenceChange,
  };

  const stageProps = {
    selectedNodeId,
    focusNodeRequest,
    explanationFocusNodeId,
    draftChangedNodeIds,
    draftCompareProjection,
    selectedGroupNodeIds: selectionGroupNodeIds,
    hiddenNodeIds,
    collapsedNodeIds,
    collapsedDescendantCountByNodeId: collapsedSummary.descendantCountByNodeId,
    experiments: graphSurfaceExperiments,
    onAddNode: handleAddNode,
    onSelectNode: handleSelectNode,
    onSelectionGroupChange: handleSelectionGroupChange,
    onInspectNode: handleInspectNode,
    onDeleteNode: handleDeleteNode,
    onDeleteNodeSubtree: handleDeleteNodeSubtree,
    onCreateEdge: handleCreateEdge,
    onDeleteEdge: handleDeleteEdge,
    onInsertNodeIntoEdge: handleInsertNodeIntoEdge,
    onMoveNode: handleMoveNode,
    onMoveNodes: handleMoveNodes,
    onFormatLayout: handleFormatLayout,
    onRequestBeautification: handleRequestGraphBeautification,
    onRequestSourceNavigation: handleRequestSourceNavigation,
    onRequestAudit: handleRequestScopedAudit,
    onToggleCollapseNode: handleToggleCollapseNode,
    onOpenAudit: handleOpenAudit,
    onImportMermaid: handleOpenImportMermaid,
    onExpandOverflowNode: handleExpandOverflowNode,
  };

  return (
    <GraphWorkbench
      toolbar={(
        <WorkbenchToolbar
        analysisDisplayMode={analysisDisplayMode}
        operationFeedback={toolbarFeedback}
        onRequestAnalysisDisplayMode={workbenchCommands.handleRequestAnalysisDisplayMode}
        onImportMermaid={handleOpenImportMermaid}
        onExportMermaid={workbenchCommands.handleExportMermaid}
        onShowDiff={workbenchCommands.handleShowDiffMode}
        onRequestSync={workbenchCommands.handleRequestSyncPreview}
        onRequestGenerationPlan={handleRequestGenerationPlan}
        onRequestGraphBeautification={() => {
          handleRequestGraphBeautification();
        }}
        onRequestCodeDrafts={handleRequestCodeDrafts}
        onOpenSettings={workbenchCommands.handleOpenSettings}
        />
      )}
      legend={(
        <Legend
          analysisDisplayMode={analysisDisplayMode}
          hasExplanationFocus={explanationFocusNodeId != null}
          hasDraftChanges={draftChangedNodeIds.length > 0}
          draftCompareProjection={draftCompareProjection}
        />
      )}
      dialogs={(
        <>
          <MermaidImportDialog
        open={isImportDialogOpen}
        value={mermaidDraft}
        onChange={setMermaidDraft}
        onCancel={() => setImportDialogOpen(false)}
        onConfirm={handleConfirmImportMermaidDraft}
          />

          <AsyncRequestFailureDialog
        open={requestFailureNotice !== null}
        title={requestFailureNotice?.title ?? ""}
        message={requestFailureNotice?.message ?? ""}
        detailMessage={requestFailureNotice?.detailMessage ?? null}
        onClose={() => setRequestFailureNotice(null)}
          />
        </>
      )}
      stage={(
        <AppGraphStage
          analysisDisplayMode={analysisDisplayMode}
          stageProps={stageProps}
          factGraphView={factGraphView}
          presentedFlowchartView={presentedFlowchartView}
          flowchartView={flowchartView}
          resourceRelationView={resourceRelationView}
        />
      )}
      workbench={(
        <AppWorkbenchPanels
          activeWorkbenchTab={activeWorkbenchTab}
          onTabChange={setActiveWorkbenchTab}
          codePanelProps={codePanelProps}
          auditTabProps={auditTabProps}
          draftTabProps={draftTabProps}
          explanationTabProps={explanationTabProps}
        />
      )}
      propertyDrawer={(
        <WorkbenchPropertyDrawer
        selectedNode={detailNode}
        onUpdateNode={handleUpdateNode}
        onDeleteNode={handleDeleteNode}
        onDeleteNodeSubtree={handleDeleteNodeSubtree}
        onRequestSourceNavigation={handleRequestSourceNavigation}
        onClose={() => setDetailNodeId(null)}
        />
      )}
    />
  );
}
