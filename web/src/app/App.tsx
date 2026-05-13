import { useEffect, useMemo, useRef, useState } from "react";
import { undoLastDraftPatchApply } from "./api";
import {
  applyLayoutUpdatesToGraphDocument,
  applyBootstrapRoutesToViewDocument,
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
  reuseCurrentViewGraphs,
  scopeFlowchartGraphToAnchorMethod,
  syncFactGraphViewDocument,
  syncFlowchartViewLayout,
  syncResourceRelationViewLayout,
  toDraftWorkbenchEntry,
  updateGraphPatchResultCandidateStatus,
  updateGraphPatchResultThreadResolution,
} from "./appGraphSupport";
import { AsyncRequestFailureDialog } from "./components/AsyncRequestFailureDialog";
import { AppGraphStage } from "./components/AppGraphStage";
import { AppWorkbenchPanels, type WorkbenchTab } from "./components/AppWorkbenchPanels";
import { ChangeTray } from "./components/ChangeTray";
import { EvidenceStagePanel } from "./components/EvidenceStagePanel";
import { GraphStageFooter } from "./components/GraphStageFooter";
import { GraphStageHeader } from "./components/GraphStageHeader";
import { HybridWorkbenchLayout } from "./components/HybridWorkbenchLayout";
import { LinkGraphOutline } from "./components/LinkGraphOutline";
import { MermaidImportDialog } from "./components/MermaidImportDialog";
import { StageWorkbench } from "./components/StageWorkbench";
import { WorkflowTaskbar } from "./components/WorkflowTaskbar";
import {
  deriveChangeTrayState,
  deriveCurrentTarget,
  deriveEvidencePanelState,
  deriveLinkGraphOutline,
  deriveWorkflowStageStates,
} from "./components/hybridDerivations";
import {
  collectDownstreamSubtreeNodeIds,
  fallbackDesignPosition,
  normalizeGraphNodes,
  resolveNodePosition,
  syncNodePosition,
} from "./graphState";
import { canEditNodeLayout } from "./layoutEditability";
import type {
  AsyncRequestState,
  AnalysisDisplayMode,
  CandidateDraftChange,
  DraftWorkbenchEntry,
  GeneratedCodeDraft,
  GraphBeautificationResult,
  GraphPatchResult,
  FactGraphViewDocument,
  FlowchartViewDocument,
  GraphSurfaceExperimentFlags,
  LinkGraphDocument,
  LinkGraphBootstrapState,
  LinkGraphEdge,
  LinkGraphNode,
  QaRequestRecoveryState,
  StepGranularity,
} from "./types";
import { GraphWorkbench } from "./workbench/GraphWorkbench";
import { WorkbenchPropertyDrawer } from "./workbench/WorkbenchPropertyDrawer";
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
  type WorkflowStage,
  workbenchTabToWorkflowStage,
  workflowStageToWorkbenchTab,
} from "./workflow/workflowStage";
import {
  EMPTY_QA_REQUEST_RECOVERY_STATE,
  EMPTY_STATE,
  IDLE_REQUEST_STATE,
  SAMPLE_STATE,
  resolveActiveViewDocument,
  resolveCurrentSceneState,
  resolveDesignBaselineGraph,
  resolveFactGraphView,
  resolveFlowchartView,
  resolveInitialAnchorNodeId,
  resolveInitialState,
  resolveRequestState,
  resolveResourceRelationView,
  resolveSemanticFactGraph,
  resolveSourceNavigationState,
  resolveWorkspaceBaseGraph,
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
const WORKBENCH_LAYOUT_STORAGE_KEY = "linkGraph.hybridWorkbenchLayout";
const DEFAULT_STAGE_WORKBENCH_WIDTH = 420;
const MIN_STAGE_WORKBENCH_WIDTH = 320;
const MAX_STAGE_WORKBENCH_WIDTH = 720;
export { resolveAuditTargetNodeIds } from "./appGraphSupport";

function clampStageWorkbenchWidth(width: number): number {
  return Math.max(MIN_STAGE_WORKBENCH_WIDTH, Math.min(MAX_STAGE_WORKBENCH_WIDTH, Math.round(width)));
}

function readHybridWorkbenchLayoutPreference() {
  if (typeof window === "undefined") {
    return {
      outlineCollapsed: false,
      workbenchWidth: DEFAULT_STAGE_WORKBENCH_WIDTH,
    };
  }
  try {
    const raw = window.localStorage.getItem(WORKBENCH_LAYOUT_STORAGE_KEY);
    if (!raw) {
      return {
        outlineCollapsed: false,
        workbenchWidth: DEFAULT_STAGE_WORKBENCH_WIDTH,
      };
    }
    const parsed = JSON.parse(raw) as Partial<{ outlineCollapsed: boolean; workbenchWidth: number }>;
    return {
      outlineCollapsed: parsed.outlineCollapsed === true,
      workbenchWidth: typeof parsed.workbenchWidth === "number"
        ? clampStageWorkbenchWidth(parsed.workbenchWidth)
        : DEFAULT_STAGE_WORKBENCH_WIDTH,
    };
  } catch {
    return {
      outlineCollapsed: false,
      workbenchWidth: DEFAULT_STAGE_WORKBENCH_WIDTH,
    };
  }
}

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
  const initialAnchorNodeId = resolveInitialAnchorNodeId(initialState);
  const {
    canvasState,
    setCanvasState,
    canvasSetters,
    projectionState,
    setProjectionState,
    projectionSetters,
    auditTargetNodeIds,
    setAuditTargetNodeIds,
    auditQuestionDraft,
    setAuditQuestionDraft,
    auditQuestionMode,
    setAuditQuestionMode,
    selectionGroupNodeIds,
    setSelectionGroupNodeIds,
    collapsedNodeIds,
    setCollapsedNodeIds,
    requestFailureNotice,
    setRequestFailureNotice,
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
    resolveWorkspaceBaseGraph,
    resolveSemanticFactGraph,
    resolveFactGraphView,
    resolveFlowchartView,
    resolveResourceRelationView,
    resolveCurrentSceneState,
    resolveWorkingGraph,
    resolveDesignBaselineGraph,
    resolveSourceNavigationState,
    normalizeGraphNodes,
  });
  const {
    nodes,
    edges,
    selectedNodeId,
    analysisDisplayMode,
    anchorNodeId,
    currentSceneId,
    workspaceBaseGraph,
    semanticFactGraph,
    workspaceRevision,
    factGraphView,
    flowchartView,
    resourceRelationView,
    draftGraph,
  } = canvasState;
  const {
    setNodes,
    setEdges,
    setSelectedNodeId,
    setAnchorNodeId,
    setSceneLayoutState,
    setFactGraphView,
    setFlowchartView,
    setResourceRelationView,
    setDraftGraph,
  } = canvasSetters;
  const {
    detailNodeId,
    draftWorkbenchState,
    designBaseline,
    draftPatchPreview,
    lastAppliedDraftPatchPreview,
    canUndoDraftPatchApply,
    lastAppliedDraftPatchSummary,
    auditResult,
    auditRequestState,
    qaRequestRecoveryState,
    diffReviewResult,
    diffReviewRequestState,
    mermaidIssues,
    diffItems,
    syncPreviewItems,
    draftVersion,
    generationPlan,
    generationPlanDraftVersion,
    generationPlanRequestState,
    draftValidationState,
    generationPlanDiscussionSession,
    generationPlanDiscussionRequestState,
    graphBeautificationResult,
    graphBeautificationRequestState,
    generatedCodeDrafts,
    generatedCodeDraftVersion,
    generatedCodeDraftWarnings,
    generatedCodeDraftSource,
    generatedCodeDraftPromptPreview,
    generatedCodeDraftPromptPreviewArtifactId,
    generatedCodeDraftWriteReport,
    lastDraftPatchApplyResult,
    codeDraftRequestState,
    codeEligibilityDecision,
    sourceNavigationState: _sourceNavigationState,
    operationFeedback,
    workbenchSectionPreferences,
    lastMessageType,
    graphSurfaceExperiments,
    artifactContents,
  } = projectionState;
  const {
    setDetailNodeId,
    setDraftWorkbenchState,
    setDesignBaseline,
    setDraftPatchPreview,
    setLastAppliedDraftPatchPreview,
    setCanUndoDraftPatchApply,
    setLastAppliedDraftPatchSummary,
    setAuditResult,
    setAuditRequestState,
    setQaRequestRecoveryState,
    setDiffReviewResult,
    setDiffReviewRequestState,
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
    setGraphBeautificationResult,
    setGraphBeautificationRequestState,
    setGeneratedCodeDrafts,
    setGeneratedCodeDraftVersion,
    setGeneratedCodeDraftWarnings,
    setGeneratedCodeDraftSource,
    setGeneratedCodeDraftPromptPreview,
    setGeneratedCodeDraftPromptPreviewArtifactId,
    setGeneratedCodeDraftWriteReport,
    setLastDraftPatchApplyResult,
    setCodeDraftRequestState,
    setCodeEligibilityDecision,
    setSourceNavigationState,
    setOperationFeedback,
    setWorkbenchSectionPreferences,
    setLastMessageType,
    setGraphSurfaceExperiments,
    setArtifactContents,
  } = projectionSetters;
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
    setWorkbenchSectionPreferences,
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
  const [activeWorkflowStage, setActiveWorkflowStage] = useState<WorkflowStage>("understand");
  const [hybridLayoutPreference, setHybridLayoutPreference] = useState(readHybridWorkbenchLayoutPreference);
  const activeWorkbenchTab = workflowStageToWorkbenchTab(activeWorkflowStage) ?? "explanation";
  const activeWorkbenchTabForDerived = workflowStageToWorkbenchTab(activeWorkflowStage) ?? "audit";
  function setActiveWorkbenchTabCompat(tab: WorkbenchTab) {
    setActiveWorkflowStage(workbenchTabToWorkflowStage(tab));
  }
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
  const layoutRevisionRef = useRef<number | null>(resolveCurrentSceneState(initialState).layoutRevision ?? null);
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
    nodes,
    edges,
    selectedNodeId,
    detailNodeId,
    analysisDisplayMode,
    currentSceneId,
    workspaceRevision,
    anchorNodeIdRef,
    setNodes,
    setEdges,
    setAnchorNodeId,
    setSelectedNodeId,
    setSceneLayoutState,
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
    setSceneLayoutState,
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
    auditQuestionMode,
    auditTargetNodeIds,
    selectedAuditChangeId,
    selectedAuditThreadId,
    nodes,
    draftWorkbenchState,
    bridgeCommands,
    setAuditQuestionDraft,
    setAuditQuestionMode,
    setAuditTargetNodeIds,
    setAuditSourceThreadId,
    setActiveWorkbenchTab: setActiveWorkbenchTabCompat,
    setOperationFeedback,
    setDraftWorkbenchState,
    setSelectedDraftEntryId,
    setAuditResult,
    setSelectedAuditChangeId,
    setSelectedAuditThreadId,
    selectExplanationTargetNode,
    toDraftWorkbenchEntry,
    updateGraphPatchResultCandidateStatus,
    updateGraphPatchResultThreadResolution,
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
    canvasState,
    setCanvasState,
    projectionState,
    setProjectionState,
    explanationLocalOverrideRef,
    setSelectionGroupNodeIds,
    setDiffTargetItemIds,
    syncManualNodeIdCounters,
    resolveSourceNavigationState,
    resolveFactGraphView,
    resolveFlowchartView,
    resolveResourceRelationView,
    resolveWorkingGraph,
    resolveActiveViewDocument,
    applyBootstrapRoutesToViewDocument,
    reuseCurrentViewGraphs,
    resolveAnchorNodeId,
    resolveWorkspaceBaseGraph,
    resolveSemanticFactGraph,
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
    handleExpandInvocation,
    handleRemoveInvocationExpansion,
  } = useAppWorkbenchShellController({
    nodes,
    selectionGroupNodeIds,
    mermaidDraft,
    activeWorkbenchTab: activeWorkbenchTabForDerived,
    selectedNodeId,
    analysisDisplayMode,
    generationPlan,
    generationPlanRequestState,
    generationPlanDiscussionQuestionDraft,
    generationPlanDiscussionSession,
    setAuditTargetNodeIds,
    setAuditQuestionDraft,
    setAuditQuestionMode,
    setAuditSourceThreadId,
    setActiveWorkbenchTab: setActiveWorkbenchTabCompat,
    setOperationFeedback,
    setDiffTargetItemIds,
    handleRequestAudit,
    handleInspectNode,
    bridgeCommands: {
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
    selectedNodeId,
    currentExplanationSessionLabel,
    explanationHistory,
    explanationLocalOverrideRef,
    pendingExplanationRequestModeRef,
    pendingExplanationSessionLabelRef,
    pendingExplanationHistoryEntryRef,
    pendingExplanationDrillTargetRef,
    bridgeCommands,
    setActiveWorkbenchTab: setActiveWorkbenchTabCompat,
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
    activeWorkbenchTab: activeWorkbenchTabForDerived,
    auditResult,
    auditRequestState,
    qaRequestRecoveryState,
    auditQuestionDraft,
    auditQuestionMode,
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
    semanticFactGraph,
    workspaceBaseGraph,
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
  const activeFullGraph = analysisDisplayMode === "FLOWCHART"
    ? flowchartView.fullGraph
    : analysisDisplayMode === "RESOURCE_RELATION_VIEW"
      ? resourceRelationView.fullGraph
      : factGraphView.fullGraph;
  const activeAnchorNodeId = analysisDisplayMode === "FLOWCHART"
    ? flowchartView.anchorNodeId ?? null
    : analysisDisplayMode === "RESOURCE_RELATION_VIEW"
      ? resourceRelationView.anchorNodeId ?? null
      : factGraphView.anchorNodeId ?? null;
  const activeAnchorNode = activeViewGraph.nodes.find((node) => node.id === activeAnchorNodeId) ?? null;
  const workflowStageStates = useMemo(() => deriveWorkflowStageStates({
    graphBeautificationResult,
    graphBeautificationRequestState,
    auditResult,
    auditRequestState,
    draftWorkbenchState,
    draftValidationState,
    codeDiffStatus,
    codeDraftRequestState,
  }), [
    auditRequestState,
    auditResult,
    codeDiffStatus,
    codeDraftRequestState,
    draftValidationState,
    draftWorkbenchState,
    graphBeautificationRequestState,
    graphBeautificationResult,
  ]);
  const currentTarget = useMemo(() => deriveCurrentTarget({
    detailNode,
    activeAnchorNode,
    selectedNodeId,
  }), [activeAnchorNode, detailNode, selectedNodeId]);
  const outlineState = useMemo(() => deriveLinkGraphOutline({
    activeViewGraph,
    fullGraph: activeFullGraph,
    anchorNodeId: activeAnchorNodeId,
    selectedNodeId,
    auditResult,
    draftWorkbenchState,
    draftChangedNodeIds,
  }), [
    activeAnchorNodeId,
    activeFullGraph,
    activeViewGraph,
    auditResult,
    draftChangedNodeIds,
    draftWorkbenchState,
    selectedNodeId,
  ]);
  const evidencePanelState = useMemo(() => deriveEvidencePanelState({
    selectedNode,
    auditResult,
    graphBeautificationResult,
  }), [auditResult, graphBeautificationResult, selectedNode]);
  const changeTrayState = useMemo(() => deriveChangeTrayState({
    auditResult,
    draftWorkbenchState,
    draftValidationState,
    codeDiffStatus,
    canUndoDraftPatchApply,
    lastAppliedDraftPatchSummary,
    lastDraftPatchApplyResult,
  }), [
    auditResult,
    canUndoDraftPatchApply,
    codeDiffStatus,
    draftValidationState,
    draftWorkbenchState,
    lastAppliedDraftPatchSummary,
    lastDraftPatchApplyResult,
  ]);
  const [outlineQuery, setOutlineQuery] = useState("");

  useEffect(() => {
    try {
      window.localStorage.setItem(WORKBENCH_LAYOUT_STORAGE_KEY, JSON.stringify(hybridLayoutPreference));
    } catch {
      // Layout persistence is a convenience; rendering should not depend on storage availability.
    }
  }, [hybridLayoutPreference]);

  function handleOutlineCollapsedChange(outlineCollapsed: boolean) {
    setHybridLayoutPreference((current) => ({
      ...current,
      outlineCollapsed,
    }));
  }

  function handleWorkbenchWidthChange(workbenchWidth: number) {
    setHybridLayoutPreference((current) => ({
      ...current,
      workbenchWidth: clampStageWorkbenchWidth(workbenchWidth),
    }));
  }

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
    setActiveWorkbenchTab: setActiveWorkbenchTabCompat,
    setOperationFeedback,
    selectExplanationTargetNode,
    handleSelectExplanationStep,
    resolveDraftEntryTargetNodeIds,
    resolveDisplayedNodeId,
  });

  useEffect(() => {
    if (activeWorkflowStage !== "draft" || !selectedDraftEntry) {
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
  }, [activeWorkflowStage, nodes, selectedDraftEntry, selectedNodeId]);

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
    onOpenDraftWorkbench: () => setActiveWorkbenchTabCompat("draft"),
    onOpenDraftValidation: handleOpenDraftValidation,
    onOpenAuditWorkbench: () => setActiveWorkbenchTabCompat("audit"),
    draftValidationState,
    implementationSuggestion: draftImplementationSuggestionState,
    implementationSuggestionRequestState: generationPlanRequestState,
    implementationSuggestionDiscussionQuestionDraft: generationPlanDiscussionQuestionDraft,
    implementationSuggestionDiscussionSession: generationPlanDiscussionSession,
    implementationSuggestionDiscussionRequestState: generationPlanDiscussionRequestState,
    onRequestPlan: handleRequestGenerationPlan,
    onRequestDrafts: handleRequestCodeDrafts,
    onImplementationSuggestionDiscussionQuestionDraftChange: setGenerationPlanDiscussionQuestionDraft,
    onSubmitImplementationSuggestionDiscussion: handleRequestGenerationPlanDiscussion,
    onWriteDrafts: workbenchCommands.handleWriteDrafts,
    onWriteSingleDraft: handleWriteSingleCodeDraft,
    onOpenNativeDiff: handleOpenCodeDraftNativeDiff,
    onOpenDraft: workbenchCommands.handleOpenDraft,
  };

  const auditTabProps = {
    state: auditState,
    onQuestionDraftChange: setAuditQuestionDraft,
    onQuestionModeChange: setAuditQuestionMode,
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
    resolveArtifactText,
    onRequestArtifact: handleRequestArtifact,
    sectionPreferences: workbenchSectionPreferences,
    onSectionPreferenceChange: handleWorkbenchSectionPreferenceChange,
  };

  const draftTabProps = {
    state: draftState,
    implementationSuggestion: draftImplementationSuggestionState,
    draftCompareProjection,
    draftVersion,
    codeDiffStatus,
    codeDiffDraftVersion: generatedCodeDraftVersion,
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
    resolveArtifactText,
    onRequestArtifact: handleRequestArtifact,
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
    onExpandInvocation: handleExpandInvocation,
    onRemoveInvocationExpansion: handleRemoveInvocationExpansion,
  };

  function handleWorkflowStageChange(stage: WorkflowStage) {
    setActiveWorkflowStage(stage);
  }

  function handlePrimaryWorkflowAction() {
    switch (activeWorkflowStage) {
      case "understand":
        handleRequestGraphBeautification();
        return;
      case "evidence":
        handleOpenAudit(selectedNodeId ?? undefined);
        return;
      case "qa":
        handleRequestAudit(auditQuestionDraft);
        return;
      case "draft":
        handleRequestGenerationPlan();
        return;
      case "code":
        if (codeDiffStatus === "FRESH") {
          workbenchCommands.handleWriteDrafts();
          return;
        }
        handleRequestCodeDrafts();
    }
  }

  function resolvePrimaryActionLabel(): string {
    switch (activeWorkflowStage) {
      case "understand":
        return graphBeautificationRequestState.phase === "RUNNING" ? "讲解中" : "链路讲解";
      case "evidence":
        return "去问答";
      case "qa":
        return auditRequestState.phase === "RUNNING" ? "问答中" : "提交问答";
      case "draft":
        return generationPlanRequestState.phase === "RUNNING" ? "生成中" : "生成实现建议";
      case "code":
        return codeDiffStatus === "FRESH" ? "写入全部" : codeDraftRequestState.phase === "RUNNING" ? "生成中" : "生成代码 diff";
    }
  }

  function resolvePrimaryActionDisabled(): boolean {
    switch (activeWorkflowStage) {
      case "understand":
        return graphBeautificationRequestState.phase === "RUNNING";
      case "qa":
        return auditRequestState.phase === "RUNNING" || auditQuestionDraft.trim().length === 0;
      case "draft":
        return generationPlanRequestState.phase === "RUNNING" || draftWorkbenchState.draftChanges.length === 0;
      case "code":
        return codeDraftRequestState.phase === "RUNNING" || (codeDiffStatus === "FRESH" ? generatedCodeDrafts.length === 0 : false);
      case "evidence":
      default:
        return false;
    }
  }

  function handleSelectOutlineItem(itemId: string) {
    if (itemId.startsWith("thread:")) {
      const threadId = itemId.slice("thread:".length);
      setActiveWorkflowStage("qa");
      handleSelectAuditThread(threadId);
      return;
    }
    if (itemId.startsWith("draft:")) {
      const entryId = itemId.slice("draft:".length);
      setActiveWorkflowStage("draft");
      handleSelectDraftEntry(entryId);
      return;
    }
    handleSelectNode(itemId);
  }

  function handleOpenEvidenceQa() {
    const scopeIds = selectedNodeId ? [selectedNodeId] : [];
    const targetTitle = selectedNode?.title ?? null;
    setAuditTargetNodeIds(scopeIds);
    setAuditQuestionDraft(
      scopeIds.length === 1
        ? `请围绕节点“${targetTitle ?? scopeIds[0]}”及其直接关联链路进行问答，指出可能遗漏的业务链路、异常分支、资源依赖和数据约束。`
        : "请围绕当前整张链路图进行问答，指出可能遗漏的业务链路、异常分支、资源依赖和数据约束。",
    );
    setAuditQuestionMode("AUTO");
    setAuditSourceThreadId(null);
    setActiveWorkflowStage("qa");
  }

  function handleEvidenceThreadSelect(threadId: string) {
    setActiveWorkflowStage("qa");
    handleSelectAuditThread(threadId);
  }

  function handleEvidenceCandidateSelect(changeId: string) {
    setActiveWorkflowStage("qa");
    handleSelectAuditChange(changeId);
  }

  function handleUndoDraftPatchApply() {
    bridgeCommands.runBridgeCommand("回退草稿应用", () => undoLastDraftPatchApply(), {
      successFeedback: {
        level: "INFO",
        message: "已请求回退上次草稿应用。",
      },
    });
  }

  const stageWorkbenchContent = activeWorkflowStage === "evidence"
    ? (
      <EvidenceStagePanel
        state={evidencePanelState}
        isLoading={auditRequestState.phase === "RUNNING" || graphBeautificationRequestState.phase === "RUNNING"}
        errorMessage={auditRequestState.errorMessage ?? graphBeautificationRequestState.errorMessage ?? null}
        onOpenQa={handleOpenEvidenceQa}
        onSelectThread={handleEvidenceThreadSelect}
        onSelectCandidateChange={handleEvidenceCandidateSelect}
        onRequestSourceNavigation={handleRequestSourceNavigation}
      />
    )
    : (
      <AppWorkbenchPanels
        activeWorkbenchTab={activeWorkbenchTab}
        onTabChange={setActiveWorkbenchTabCompat}
        codePanelProps={codePanelProps}
        auditTabProps={auditTabProps}
        draftTabProps={draftTabProps}
        explanationTabProps={explanationTabProps}
        showTabs={false}
      />
    );
  const graphStage = (
    <section className="graph-stage" aria-label="图谱舞台">
      <GraphStageHeader
        analysisDisplayMode={analysisDisplayMode}
        activeStage={activeWorkflowStage}
        onRequestAnalysisDisplayMode={workbenchCommands.handleRequestAnalysisDisplayMode}
      />
      <div className="graph-stage-canvas">
        <AppGraphStage
          analysisDisplayMode={analysisDisplayMode}
          stageProps={stageProps}
          factGraphView={factGraphView}
          presentedFlowchartView={presentedFlowchartView}
          flowchartView={flowchartView}
          resourceRelationView={resourceRelationView}
        />
      </div>
      <GraphStageFooter
        analysisDisplayMode={analysisDisplayMode}
        activeViewGraph={activeViewGraph}
        fullNodeCount={activeFullGraph.nodes.length}
        hasExplanationFocus={explanationFocusNodeId != null}
        draftChangedNodeCount={draftChangedNodeIds.length}
        draftCompareProjection={draftCompareProjection}
        codeDiffStatus={codeDiffStatus}
      />
    </section>
  );

  return (
    <GraphWorkbench
      taskbar={(
        <WorkflowTaskbar
          title={currentTarget.title}
          path={currentTarget.path}
          activeStage={activeWorkflowStage}
          stageStates={workflowStageStates}
          riskCount={changeTrayState.blockingRiskCount}
          draftCandidateCount={changeTrayState.pendingCandidateCount + changeTrayState.confirmedDraftCount}
          operationFeedback={toolbarFeedback}
          primaryActionLabel={resolvePrimaryActionLabel()}
          primaryActionDisabled={resolvePrimaryActionDisabled()}
          onStageChange={handleWorkflowStageChange}
          onImportMermaid={handleOpenImportMermaid}
          onExportMermaid={workbenchCommands.handleExportMermaid}
          onShowDiff={workbenchCommands.handleShowDiffMode}
          onRequestSync={workbenchCommands.handleRequestSyncPreview}
          onOpenSettings={workbenchCommands.handleOpenSettings}
          onPrimaryAction={handlePrimaryWorkflowAction}
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
      body={(
        <HybridWorkbenchLayout
          outlineCollapsed={hybridLayoutPreference.outlineCollapsed}
          onOutlineCollapsedChange={handleOutlineCollapsedChange}
          workbenchWidth={hybridLayoutPreference.workbenchWidth}
          onWorkbenchWidthChange={handleWorkbenchWidthChange}
          outline={(
            <LinkGraphOutline
              metrics={outlineState.metrics}
              items={outlineState.items}
              activeItemId={selectedNodeId}
              query={outlineQuery}
              onQueryChange={setOutlineQuery}
              onSelectItem={handleSelectOutlineItem}
            />
          )}
          graphStage={graphStage}
          workbench={(
            <StageWorkbench
              activeStage={activeWorkflowStage}
              stageStates={workflowStageStates}
              onStageChange={handleWorkflowStageChange}
              content={stageWorkbenchContent}
            />
          )}
        />
      )}
      tray={(
        <ChangeTray
          {...changeTrayState}
          onOpenDraft={() => setActiveWorkflowStage("draft")}
          onOpenCode={() => setActiveWorkflowStage("code")}
          onApply={workbenchCommands.handleWriteDrafts}
          onRevert={handleUndoDraftPatchApply}
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
