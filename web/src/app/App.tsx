import { useEffect, useMemo, useRef, useState } from "react";
import { undoLastDraftPatchApply } from "./api";
import { AssistantWorkbenchShell } from "./assistant/AssistantWorkbenchShell";
import { buildAssistantTurns } from "./assistant/assistantResultAdapters";
import { useAssistantActionController, type AssistantDisplayModeDocuments } from "./assistant/useAssistantActionController";
import {
  buildDefaultClassDescriptionPrompt,
  buildDefaultExplanationPrompt,
  buildDefaultQaQuestion,
  DEFAULT_GENERATION_PLAN_PROMPT,
  NEW_ASSISTANT_COMPOSER_TARGET,
} from "./assistant/assistantPromptDefaults";
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
  resolveQaTargetNodeIds,
  resolveDraftEntryPrimaryNodeId,
  resolveDraftEntryTargetNodeIds,
  resolveEntryOwnerSignatures,
  resolveEvidenceTargetNodeId,
  resolveNodeOwnerSignature,
  reuseCurrentViewGraphs,
  scopeFlowchartGraphToAnchorMethod,
  syncFactGraphViewDocument,
  syncArchitectureGraphViewLayout,
  syncClassDiagramViewLayout,
  syncReviewGraphViewLayout,
  syncFlowchartViewLayout,
  syncResourceRelationViewLayout,
} from "./appGraphSupport";
import {
  activeAnchorNodeIdForDisplayMode,
  activeFullGraphForDisplayMode,
  activeProjectionIndex,
  workflowStageToAssistantTarget,
} from "./appDisplaySelectors";
import {
  findExplanationStep,
  resolveExplanationFollowUpQuestion,
  resolveExplanationRerunIntent,
  resolveExplanationStepRawNodeId as resolveRawNodeIdForExplanationStep,
} from "./appExplanationStepModel";
import {
  clampAssistantWorkbenchWidth,
  readHybridWorkbenchLayoutPreference,
  writeHybridWorkbenchLayoutPreference,
} from "./appWorkbenchPreferences";
import {
  primaryWorkflowActionCommand,
} from "./appPrimaryWorkflowAction";
import { deriveInvestigationThreads } from "./investigationThreads";
import { AppDialogs } from "./components/AppDialogs";
import { AppGraphStagePanel } from "./components/AppGraphStagePanel";
import { AppWorkbenchChrome } from "./components/AppWorkbenchChrome";
import {
  deriveChangeTrayState,
  deriveCurrentTarget,
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
  AssistantActionId,
  AssistantComposerTarget,
  AssistantIntent,
  CandidateDraftChange,
  DraftWorkbenchEntry,
  GeneratedCodeDraft,
  GraphBeautificationResult,
  GraphPatchResult,
  ArchitectureGraphViewDocument,
  ClassDiagramViewDocument,
  FactGraphViewDocument,
  FlowchartViewDocument,
  GraphSurfaceExperimentFlags,
  LinkGraphDocument,
  LinkGraphBootstrapState,
  LinkGraphEdge,
  LinkGraphNode,
  LinkGraphSceneId,
  QaRequestRecoveryState,
  ResultEvidenceReference,
  ReviewGraphViewDocument,
  RiskResolutionStatus,
  StepGranularity,
} from "./types";
import type { EditableStageProps, IndexedReadonlyStageProps } from "./views/viewStageProps";
import { useAssistantQaActions } from "./controllers/useAssistantQaActions";
import { useBootstrapProjectionState } from "./controllers/useBootstrapProjectionState";
import { useBootstrapStateController } from "./controllers/useBootstrapStateController";
import { useAppBridgeController } from "./controllers/useAppBridgeController";
import { useBridgeCommandController } from "./controllers/useBridgeCommandController";
import { useAssistantExplanationHistory } from "./controllers/useAssistantExplanationHistory";
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
import type { WorkflowStage } from "./workflow/workflowStage";
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
  resolveArchitectureGraphView,
  resolveClassDiagramView,
  resolveReviewGraphView,
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
export { resolveQaTargetNodeIds } from "./appGraphSupport";

const DEFAULT_GENERATION_DISCUSSION_PROMPT = "请继续讨论这份实现建议的取舍、风险和下一步。";

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
    qaTargetNodeIds,
    setQaTargetNodeIds,
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
    resolveArchitectureGraphView,
    resolveClassDiagramView,
    resolveReviewGraphView,
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
    architectureGraphView,
    classDiagramView,
    reviewGraphView,
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
    setArchitectureGraphView,
    setClassDiagramView,
    setReviewGraphView,
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
    qaResult,
    qaRequestState,
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
    indexedGraphRequestStates,
    sourceNavigationState: _sourceNavigationState,
    operationFeedback,
    lastMessageType,
    graphSurfaceExperiments,
    artifactContents,
    assistantSessionState,
    assistantResultStore,
  } = projectionState;
  const {
    setDetailNodeId,
    setDraftWorkbenchState,
    setDesignBaseline,
    setDraftPatchPreview,
    setLastAppliedDraftPatchPreview,
    setCanUndoDraftPatchApply,
    setLastAppliedDraftPatchSummary,
    setQaResult,
    setQaRequestState,
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
    setLastMessageType,
    setGraphSurfaceExperiments,
    setArtifactContents,
    setAssistantSessionState,
    setAssistantResultStore,
  } = projectionSetters;
  const bridgeCommands = useBridgeCommandController({
    setOperationFeedback,
    setRequestFailureNotice,
  });
  const {
    handleConfirmImportMermaid,
    handleWriteSingleCodeDraft,
    handleOpenCodeDraftNativeDiff,
    handleRequestArtifact,
  } = useAppBridgeController({
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
    availability: {
      architectureGraphLoaded: architectureGraphView.summary.indexed != null,
      classDiagramLoaded: classDiagramView.summary.indexed != null,
      reviewGraphLoaded: reviewGraphView.summary.indexed != null,
    },
  });
  const toolbarFeedback = useMemo(() => resolveToolbarFeedback({
    operationFeedback,
    lastMessageType,
    requestStates: [
      qaRequestState,
      diffReviewRequestState,
      graphBeautificationRequestState,
      generationPlanRequestState,
      codeDraftRequestState,
      indexedGraphRequestStates.ARCHITECTURE ?? IDLE_REQUEST_STATE,
      indexedGraphRequestStates.CLASS_DIAGRAM ?? IDLE_REQUEST_STATE,
      indexedGraphRequestStates.REVIEW ?? IDLE_REQUEST_STATE,
    ],
  }), [
    qaRequestState,
    codeDraftRequestState,
    diffReviewRequestState,
    generationPlanRequestState,
    graphBeautificationRequestState,
    indexedGraphRequestStates,
    lastMessageType,
    operationFeedback,
  ]);
  const [activeWorkflowStage, setActiveWorkflowStage] = useState<WorkflowStage>("understand");
  const [hybridLayoutPreference, setHybridLayoutPreference] = useState(readHybridWorkbenchLayoutPreference);
  const activeAssistantTarget = workflowStageToAssistantTarget(activeWorkflowStage);

  function handleAssistantRevealReference(reference: ResultEvidenceReference) {
    if (reference.nodeId) {
      const displayedNodeId = resolveDisplayedNodeId(reference.nodeId, nodes) ?? reference.nodeId;
      setSelectedNodeId(displayedNodeId);
      requestViewportFocus(displayedNodeId);
      setDetailNodeId(displayedNodeId);
      return;
    }
    setOperationFeedback({
      level: "WARNING",
      message: reference.filePath
        ? `当前引用来自源码文件：${reference.filePath}`
        : "当前引用没有可直接定位的图节点。",
    });
  }

  function handleAssistantResolveThread(threadId: string, status: RiskResolutionStatus) {
    if (status === "DEFERRED" || status === "ACCEPTED_RISK" || status === "DISMISSED") {
      handleResolveQaThread(threadId, status);
    }
  }
  function handleRequestAnalysisDisplayMode(displayMode: AnalysisDisplayMode) {
    if (displayMode === "REVIEW_GRAPH") {
      setActiveWorkflowStage("qa");
    }
    const reviewGraphDiffItemIds = diffTargetItemIds.length > 0
      ? diffTargetItemIds
      : selectedNodeId && diffItems.some((item) => item.id === selectedNodeId)
        ? [selectedNodeId]
        : [];
    workbenchCommands.handleRequestAnalysisDisplayMode(displayMode, reviewGraphDiffItemIds);
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
  const [selectedQaChangeId, setSelectedQaChangeId] = useState<string | null>(null);
  const [selectedQaThreadId, setSelectedQaThreadId] = useState<string | null>(null);
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
  const assistantViewDocuments = useMemo<AssistantDisplayModeDocuments>(() => ({
    FACT_GRAPH: factGraphView,
    FLOWCHART: flowchartView,
    RESOURCE_RELATION_VIEW: resourceRelationView,
    ARCHITECTURE_GRAPH: architectureGraphView,
    CLASS_DIAGRAM: classDiagramView,
    REVIEW_GRAPH: reviewGraphView,
  }), [
    factGraphView,
    flowchartView,
    resourceRelationView,
    architectureGraphView,
    classDiagramView,
    reviewGraphView,
  ]);
  const {
    assistantComposerDraft,
    assistantComposerTarget,
    handleAssistantActionChange,
    handleAssistantSubmit,
    primeAssistantComposer,
    selectedAssistantNodeIds,
    assistantTargetTitle,
    setAssistantComposer,
    setAssistantComposerDraft,
  } = useAssistantActionController({
    analysisDisplayMode,
    currentSceneId,
    selectedMethodSignature: assistantSessionState.context.selectedMethodSignature ?? null,
    selectedNodeId,
    anchorNodeId,
    selectionGroupNodeIds,
    diffTargetItemIds,
    diffItems,
    viewDocuments: assistantViewDocuments,
    assistantSessionState,
    setAssistantSessionState,
    selectedExplanationGranularity,
    bridgeCommands,
    setActiveWorkflowStage,
    onExplanationAccepted: ({ actionId, intent, target }) => {
      const explanationFollowUp = intent === "EXPLAIN_CODE" && target.kind === "ExplanationFollowUp" ? target : null;
      if (explanationFollowUp) {
        pendingExplanationRequestModeRef.current = "follow_up";
        pendingExplanationHistoryEntryRef.current = graphBeautificationResult
          ? {
              result: graphBeautificationResult,
              requestState: graphBeautificationRequestState,
              selectedStepId: selectedExplanationStepId,
              granularity: selectedExplanationGranularity,
              sessionLabel: currentExplanationSessionLabel,
            }
          : null;
        pendingExplanationSessionLabelRef.current = `围绕 ${explanationFollowUp.stepTitle ?? explanationFollowUp.stepId} 继续讲解`;
        setSelectedExplanationStepId(explanationFollowUp.stepId);
      } else {
        pendingExplanationRequestModeRef.current = "fresh";
        pendingExplanationHistoryEntryRef.current = null;
        pendingExplanationSessionLabelRef.current = actionId === "DESCRIBE_CLASS"
          ? "当前类介绍"
          : DEFAULT_EXPLANATION_SESSION_LABEL;
      }
      explanationLocalOverrideRef.current = false;
    },
  });
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
  const qaTargetTitle = qaTargetNodeIds.length === 1
    ? nodes.find((node) => node.id === qaTargetNodeIds[0])?.title ?? null
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
    setQaResult(null);
    setQaRequestState(IDLE_REQUEST_STATE);
    setQaRequestRecoveryState(EMPTY_QA_REQUEST_RECOVERY_STATE);
    setDiffReviewResult(null);
    setDiffReviewRequestState(IDLE_REQUEST_STATE);
    setSyncPreviewItems([]);
    setGenerationPlan(null);
    setGenerationPlanRequestState(IDLE_REQUEST_STATE);
    setDraftValidationState(null);
    setGenerationPlanDiscussionSession(null);
    setGenerationPlanDiscussionRequestState(IDLE_REQUEST_STATE);
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
    setArchitectureGraphView,
    setClassDiagramView,
    setReviewGraphView,
    setQaTargetNodeIds,
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
    projectionIndex: activeProjectionIndex(
      analysisDisplayMode,
      factGraphView.projectionIndex,
      flowchartView.projectionIndex,
      resourceRelationView.projectionIndex,
      architectureGraphView.projectionIndex,
      classDiagramView.projectionIndex,
      reviewGraphView.projectionIndex,
    ),
    collapsedNodeIds,
    nextManualNodeIdRef,
    anchorNodeIdRef,
    setNodes,
    setSceneLayoutState,
    setDraftGraph,
    setFactGraphView,
    setFlowchartView,
    setResourceRelationView,
    setArchitectureGraphView,
    setClassDiagramView,
    setReviewGraphView,
    setCollapsedNodeIds,
    setSelectionGroupNodeIds,
    setQaTargetNodeIds,
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
    syncArchitectureGraphViewLayout,
    syncClassDiagramViewLayout,
    syncReviewGraphViewLayout,
    resolveCollapsedDescendantSummary,
  });

  const {
    handleConfirmCandidateChange,
    handleRetryLastQaRequest,
    handleSelectQaThread,
    handleResolveQaThread,
  } = useAssistantQaActions({
    qaRequestRecoveryState,
    qaResult,
    nodes,
    bridgeCommands,
    setOperationFeedback,
    setSelectedQaChangeId,
    setSelectedQaThreadId,
    selectExplanationTargetNode,
    resolveDraftEntryTargetNodeIds,
    resolveDisplayedNodeId,
    resolveEvidenceTargetNodeId,
  });

  function handleEditFailedQaRequestFromAssistantWorkbench() {
    const failedRequest = qaRequestRecoveryState.lastFailedRequest;
    if (!failedRequest) {
      return;
    }
    setQaTargetNodeIds(failedRequest.selectedNodeIds);
    primeAssistantComposer("ASK_CODE", failedRequest.question, {
      target: {
        kind: "QaRecovery",
        requestId: failedRequest.requestId,
        sourceThreadId: failedRequest.sourceThreadId ?? null,
        mode: failedRequest.mode ?? "AUTO",
        selectedNodeIds: failedRequest.selectedNodeIds,
      },
      stage: "qa",
    });
    setOperationFeedback({
      level: "INFO",
      message: "已把失败问答回填到 AI 工作台输入框，可修改后重新提交。",
    });
  }
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
    resolveArchitectureGraphView,
    resolveClassDiagramView,
    resolveReviewGraphView,
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

  function resolveQaScope(targetNodeId?: string) {
    const nodeIds = resolveQaTargetNodeIds(targetNodeId, selectionGroupNodeIds);
    return {
      nodeIds,
      title: nodeIds.length === 1
        ? nodes.find((node) => node.id === nodeIds[0])?.title ?? null
        : null,
    };
  }

  function handleOpenQa(targetNodeId?: string) {
    const scope = resolveQaScope(targetNodeId);
    const question = buildDefaultQaQuestion(scope.nodeIds, scope.title, analysisDisplayMode);
    setQaTargetNodeIds(scope.nodeIds);
    primeAssistantComposer("ASK_CODE", question, {
      target: NEW_ASSISTANT_COMPOSER_TARGET,
      stage: "qa",
    });
  }

  function primeGenerationPlanComposer() {
    setActiveWorkflowStage("code");
    primeAssistantComposer("GENERATE_CODE", DEFAULT_GENERATION_PLAN_PROMPT, {
      target: NEW_ASSISTANT_COMPOSER_TARGET,
      stage: "code",
    });
  }

  function handleRequestCodeDrafts() {
    setActiveWorkflowStage("code");
    workbenchCommands.handleRequestCodeDrafts();
  }

  function handleDiscussGenerationPlanFromAssistant(question?: string) {
    primeAssistantComposer("GENERATE_CODE", question?.trim() || DEFAULT_GENERATION_DISCUSSION_PROMPT, {
      target: {
        kind: "GenerationDiscussion",
        planItemId: generationPlanDiscussionSession?.focusItemId ?? generationPlan?.items[0]?.id ?? null,
      },
      stage: "code",
    });
  }

  function handleConfirmImportMermaidDraft() {
    const mermaid = mermaidDraft.trim();
    if (mermaid.length === 0) {
      setOperationFeedback({
        level: "WARNING",
        message: "请输入 Mermaid 内容后再导入。",
      });
      return;
    }
    handleConfirmImportMermaid(mermaid);
  }

  function handleExpandOverflowNode(nodeId: string) {
    const nodeTitle = nodes.find((node) => node.id === nodeId)?.title ?? nodeId;
    workbenchCommands.handleExpandOverflowNode(nodeId, nodeTitle);
  }

  const {
    handleReturnToPreviousExplanation,
    handleOpenExplanationHistory,
  } = useAssistantExplanationHistory({
    graphBeautificationResult,
    graphBeautificationRequestState,
    explanationHistory,
    explanationLocalOverrideRef,
    pendingExplanationRequestModeRef,
    pendingExplanationSessionLabelRef,
    pendingExplanationHistoryEntryRef,
    assistantSessionState,
    assistantResultStore,
    setSelectedExplanationStepId,
    setHoveredExplanationStepId,
    setSelectedExplanationGranularity,
    setCurrentExplanationSessionLabel,
    setExplanationHistory,
    setGraphBeautificationResult,
    setGraphBeautificationRequestState,
    setAssistantSessionState,
    setAssistantResultStore,
  });

  function resolveExplanationStepRawNodeId(stepId: string): string | null {
    return resolveRawNodeIdForExplanationStep(findExplanationStep(graphBeautificationResult, stepId));
  }

  function resolveExplanationStepTargetNodeId(stepId: string): string | null {
    return resolveDisplayedNodeId(resolveExplanationStepRawNodeId(stepId), nodes);
  }

  function resolveExplanationStepFocusNodeId(stepId: string): string | null {
    const rawNodeId = resolveExplanationStepRawNodeId(stepId);
    if (!rawNodeId) {
      return null;
    }
    return resolveDisplayedNodeId(rawNodeId, nodes) ?? rawNodeId;
  }

  function handleSelectExplanationStepFromAssistant(stepId: string) {
    setSelectedExplanationStepId(stepId);
    const targetNodeId = resolveExplanationStepTargetNodeId(stepId);
    if (targetNodeId) {
      selectExplanationTargetNode(targetNodeId);
    }
  }

  function handleLocateExplanationStepNodeFromAssistant(stepId: string) {
    setSelectedExplanationStepId(stepId);
    const targetNodeId = resolveExplanationStepTargetNodeId(stepId);
    if (!targetNodeId) {
      setOperationFeedback({
        level: "WARNING",
        message: "当前步骤没有可定位的图节点。",
      });
      return;
    }
    selectExplanationTargetNode(targetNodeId, { focusViewport: true });
    const targetNode = nodes.find((node) => node.id === targetNodeId) ?? null;
    setOperationFeedback({
      level: "INFO",
      message: `已定位到图中节点：${targetNode?.title ?? targetNodeId}`,
    });
  }

  function handleInspectExplanationStepNodeFromAssistant(stepId: string) {
    setSelectedExplanationStepId(stepId);
    const targetNodeId = resolveExplanationStepTargetNodeId(stepId);
    if (!targetNodeId) {
      setOperationFeedback({
        level: "WARNING",
        message: "当前步骤没有可编辑的图节点。",
      });
      return;
    }
    handleInspectNode(targetNodeId);
  }

  function handleChangeExplanationGranularityFromAssistant(granularity: StepGranularity) {
    setSelectedExplanationGranularity(granularity);
    setAssistantComposer(assistantComposerDraft, NEW_ASSISTANT_COMPOSER_TARGET);
    const rerunIntent = resolveExplanationRerunIntent(analysisDisplayMode, assistantSessionState.activeIntent);
    const targetNodeIds = selectedAssistantNodeIds();
    const targetTitle = assistantTargetTitle(targetNodeIds) ?? selectedNode?.title ?? null;
    const prompt = rerunIntent === "DESCRIBE_CLASS"
      ? buildDefaultClassDescriptionPrompt(targetTitle)
      : buildDefaultExplanationPrompt(targetTitle, analysisDisplayMode);
    handleAssistantSubmit(rerunIntent, prompt, {
      explanationGranularity: granularity,
    });
  }

  function handleFollowUpExplanationStepFromAssistant(stepId: string, customQuestion?: string) {
    const step = findExplanationStep(graphBeautificationResult, stepId);
    if (!step) {
      return;
    }
    const question = resolveExplanationFollowUpQuestion(step, customQuestion);
    const focusNodeId = resolveExplanationStepFocusNodeId(stepId)
      ?? (selectedNodeId ? resolveDisplayedNodeId(selectedNodeId, nodes) ?? selectedNodeId : null);
    setSelectedExplanationStepId(step.stepId);
    if (focusNodeId) {
      selectExplanationTargetNode(focusNodeId, { focusViewport: true });
    }
    primeAssistantComposer("EXPLAIN_CODE", question, {
      target: {
        kind: "ExplanationFollowUp",
        stepId: step.stepId,
        stepTitle: step.title,
        focusNodeId,
      },
      stage: "understand",
    });
  }

  const {
    codeDiffStatus,
    activeViewGraph,
    selectedDraftEntry,
    presentedFlowchartView,
    explanationFocusNodeId,
    draftChangedNodeIds,
    draftCompareProjection,
  } = useWorkbenchDerivedState({
    analysisDisplayMode,
    activeAssistantTarget,
    qaResult,
    qaRequestState,
    qaRequestRecoveryState,
    qaTargetNodeIds,
    qaTargetTitle,
    selectedQaChangeId,
    selectedQaThreadId,
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
    architectureGraphView,
    classDiagramView,
    reviewGraphView,
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
  const activeDisplayGraphDocuments = {
    factGraphView,
    flowchartView,
    resourceRelationView,
    architectureGraphView,
    classDiagramView,
    reviewGraphView,
  };
  const activeFullGraph = activeFullGraphForDisplayMode(analysisDisplayMode, activeDisplayGraphDocuments);
  const activeAnchorNodeId = activeAnchorNodeIdForDisplayMode(analysisDisplayMode, activeDisplayGraphDocuments);
  const activeAnchorNode = activeViewGraph.nodes.find((node) => node.id === activeAnchorNodeId) ?? null;
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
    qaResult,
    draftWorkbenchState,
    draftChangedNodeIds,
  }), [
    activeAnchorNodeId,
    activeFullGraph,
    activeViewGraph,
    qaResult,
    draftChangedNodeIds,
    draftWorkbenchState,
    selectedNodeId,
  ]);
  const changeTrayState = useMemo(() => deriveChangeTrayState({
    qaResult,
    draftWorkbenchState,
    draftValidationState,
    codeDiffStatus,
    canUndoDraftPatchApply,
    lastAppliedDraftPatchSummary,
    lastDraftPatchApplyResult,
  }), [
    qaResult,
    canUndoDraftPatchApply,
    codeDiffStatus,
    draftValidationState,
    draftWorkbenchState,
    lastAppliedDraftPatchSummary,
    lastDraftPatchApplyResult,
  ]);
  const workflowStageStates = useMemo(() => deriveWorkflowStageStates({
    graphBeautificationResult,
    graphBeautificationRequestState,
    qaResult,
    qaRequestState,
    draftWorkbenchState,
    draftValidationState,
    codeDiffStatus,
    codeDraftRequestState,
  }), [
    codeDiffStatus,
    codeDraftRequestState,
    draftValidationState,
    draftWorkbenchState,
    graphBeautificationRequestState,
    graphBeautificationResult,
    qaRequestState,
    qaResult,
  ]);
  const [outlineQuery, setOutlineQuery] = useState("");

  useEffect(() => {
    writeHybridWorkbenchLayoutPreference(hybridLayoutPreference);
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
      workbenchWidth: clampAssistantWorkbenchWidth(workbenchWidth),
    }));
  }

  function handleSelectDraftEntry(entryId: string) {
    setSelectedDraftEntryId(entryId);
    const entry = draftWorkbenchState.draftChanges.find((item) => item.entryId === entryId)
      ?? draftWorkbenchState.draftNotes.find((item) => item.entryId === entryId)
      ?? null;
    const targetNodeId = resolveDraftEntryTargetNodeIds(entry)
      .map((nodeId) => resolveDisplayedNodeId(nodeId, nodes))
      .find(Boolean)
      ?? null;
    if (targetNodeId) {
      selectExplanationTargetNode(targetNodeId, { focusViewport: true });
    }
  }

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

  function handleExplainCurrentScope(focusNodeId?: string) {
    const targetNodeId = focusNodeId ?? selectedNodeId ?? null;
    const displayedTargetNodeId = targetNodeId ? resolveDisplayedNodeId(targetNodeId, nodes) ?? targetNodeId : null;
    const targetNode = displayedTargetNodeId ? nodes.find((node) => node.id === displayedTargetNodeId) ?? null : null;
    if (displayedTargetNodeId) {
      selectExplanationTargetNode(displayedTargetNodeId);
    }
    const prompt = buildDefaultExplanationPrompt(targetNode?.title ?? null, analysisDisplayMode);
    if (analysisDisplayMode === "CLASS_DIAGRAM") {
      handleAssistantSubmit("EXPLAIN_CODE", prompt, {
        selectedNodeIds: displayedTargetNodeId ? [displayedTargetNodeId] : undefined,
        target: NEW_ASSISTANT_COMPOSER_TARGET,
      });
      return;
    }
    primeAssistantComposer("EXPLAIN_CODE", prompt, {
      stage: "understand",
    });
  }

  function findAssistantRiskThread(threadId: string) {
    return [
      ...deriveInvestigationThreads(qaResult),
      ...deriveInvestigationThreads(diffReviewResult),
    ].find((thread) => thread.threadId === threadId) ?? null;
  }

  function handleInvestigateThreadFromAssistant(threadId: string) {
    const thread = findAssistantRiskThread(threadId);
    if (!thread) {
      return;
    }
    setSelectedQaThreadId(threadId);
    setQaTargetNodeIds(thread.targetNodeIds);
    const question = thread.recommendedQuestion.trim()
      || `请继续取证：核对“${thread.title}”对应的直接源码证据。`;
    const targetNodeId = resolveDisplayedNodeId(
      resolveEvidenceTargetNodeId(thread.targetNodeIds, thread.evidence),
      nodes,
    );
    if (targetNodeId) {
      selectExplanationTargetNode(targetNodeId, { focusViewport: true });
    }
    primeAssistantComposer("ASK_CODE", question, {
      target: {
        kind: "RiskInvestigation",
        threadId,
        targetNodeIds: thread.targetNodeIds,
      },
      stage: "qa",
    });
  }

  const baseStageProps = {
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
    onSelectNode: handleSelectNode,
    onSelectionGroupChange: handleSelectionGroupChange,
    onInspectNode: handleInspectNode,
    onMoveNode: handleMoveNode,
    onMoveNodes: handleMoveNodes,
    onFormatLayout: handleFormatLayout,
    onRequestBeautification: handleExplainCurrentScope,
    onRequestSourceNavigation: handleRequestSourceNavigation,
    onPrimeQuestionComposer: handleOpenQa,
    onToggleCollapseNode: handleToggleCollapseNode,
    onOpenQa: handleOpenQa,
    onExpandOverflowNode: handleExpandOverflowNode,
    onExpandInvocation: workbenchCommands.handleExpandInvocation,
    onRemoveInvocationExpansion: workbenchCommands.handleRemoveInvocationExpansion,
  };

  const editableStageProps: EditableStageProps = {
    ...baseStageProps,
    onAddNode: handleAddNode,
    onDeleteNode: handleDeleteNode,
    onDeleteNodeSubtree: handleDeleteNodeSubtree,
    onCreateEdge: handleCreateEdge,
    onDeleteEdge: handleDeleteEdge,
    onInsertNodeIntoEdge: handleInsertNodeIntoEdge,
    onImportMermaid: handleOpenImportMermaid,
  };

  const indexedReadonlyStageProps: IndexedReadonlyStageProps = {
    ...baseStageProps,
    indexedGraphRequestStates,
    onRequestArchitectureGraph: workbenchCommands.handleRequestArchitectureGraph,
    onRequestClassDiagram: workbenchCommands.handleRequestClassDiagram,
    onRequestClassDiagramWithOptions: workbenchCommands.handleRequestClassDiagramWithOptions,
    onRequestClassUsages: workbenchCommands.handleRequestClassUsages,
    onRequestPackageDependencyGraph: workbenchCommands.handleRequestPackageDependencyGraph,
    onRequestReviewGraphWithOptions: workbenchCommands.handleRequestReviewGraphWithOptions,
  };

  const primaryWorkflowActionState = {
    activeWorkflowStage,
    analysisDisplayMode,
    graphBeautificationRequestState,
    qaRequestState,
    generationPlanRequestState,
    codeDraftRequestState,
    codeDiffStatus,
    generatedCodeDraftCount: generatedCodeDrafts.length,
    assistantComposerDraft,
    selectedNodeId,
    selectedNodeTitle: selectedNode?.title ?? null,
    assistantTargetTitle: assistantTargetTitle(selectedAssistantNodeIds()) ?? null,
  };

  function handlePrimaryWorkflowAction() {
    const command = primaryWorkflowActionCommand(primaryWorkflowActionState);
    switch (command.kind) {
      case "SUBMIT_ASSISTANT":
        handleAssistantSubmit(command.intent, command.prompt);
        return;
      case "OPEN_QA":
        handleOpenQa(command.targetNodeId ?? undefined);
        return;
      case "PRIME_GENERATION_PLAN":
        primeGenerationPlanComposer();
        return;
      case "WRITE_DRAFTS":
        workbenchCommands.handleWriteDrafts();
        return;
      case "REQUEST_CODE_DRAFTS":
        handleRequestCodeDrafts();
    }
  }

  function handleSelectOutlineItem(itemId: string) {
    if (itemId.startsWith("thread:")) {
      const threadId = itemId.slice("thread:".length);
      setActiveWorkflowStage("qa");
      handleSelectQaThread(threadId);
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

  function handleUndoDraftPatchApply() {
    bridgeCommands.runBridgeCommand("回退草稿应用", () => undoLastDraftPatchApply(), {
      successFeedback: {
        level: "INFO",
        message: "已请求回退上次草稿应用。",
      },
    });
  }

  function resolveArtifactText(artifactId: string): string | null {
    return artifactContents[artifactId] ?? null;
  }

  const assistantTurns = useMemo(() => buildAssistantTurns({
    assistantSessionState,
    assistantResultStore,
  }), [
    assistantSessionState,
    assistantResultStore,
  ]);
  const visibleAssistantTurns = assistantTurns;
  const assistantRequestRunning = [
    qaRequestState,
    diffReviewRequestState,
    graphBeautificationRequestState,
    generationPlanRequestState,
    generationPlanDiscussionRequestState,
    codeDraftRequestState,
  ].some((state) => state.phase === "RUNNING");
  const assistantWorkbenchContent = (
    <AssistantWorkbenchShell
      assistantSessionState={assistantSessionState}
      turns={visibleAssistantTurns}
      activeIntent={assistantSessionState.activeIntent}
      activeActionId={assistantSessionState.activeActionId}
      requestRunning={assistantRequestRunning}
      qaRequestRecoveryState={qaRequestRecoveryState}
      composerDraft={assistantComposerDraft}
      onComposerDraftChange={setAssistantComposerDraft}
      onActionChange={handleAssistantActionChange}
      onSubmit={handleAssistantSubmit}
      onRetryLastQaRequest={handleRetryLastQaRequest}
      onEditFailedQaRequest={handleEditFailedQaRequestFromAssistantWorkbench}
      onPrimeGenerationPlan={primeGenerationPlanComposer}
      onRequestCodeDrafts={handleRequestCodeDrafts}
      onWriteCodeDrafts={workbenchCommands.handleWriteDrafts}
      onWriteSingleCodeDraft={handleWriteSingleCodeDraft}
      onOpenNativeDiff={handleOpenCodeDraftNativeDiff}
      onOpenDraft={workbenchCommands.handleOpenDraft}
      onRevealReference={handleAssistantRevealReference}
      selectedExplanationStepId={selectedExplanationStepId}
      selectedExplanationGranularity={selectedExplanationGranularity}
      explanationHistoryTrail={[
        ...explanationHistory.map((entry) => entry.sessionLabel),
        currentExplanationSessionLabel,
      ]}
      previousExplanationSessionLabel={explanationHistory[explanationHistory.length - 1]?.sessionLabel ?? null}
      onSelectExplanationStep={handleSelectExplanationStepFromAssistant}
      onLocateExplanationStepNode={handleLocateExplanationStepNodeFromAssistant}
      onInspectExplanationStepNode={handleInspectExplanationStepNodeFromAssistant}
      onHoverExplanationStep={setHoveredExplanationStepId}
      onLeaveExplanationStep={() => setHoveredExplanationStepId(null)}
      onChangeExplanationGranularity={handleChangeExplanationGranularityFromAssistant}
      onFollowUpExplanationStep={handleFollowUpExplanationStepFromAssistant}
      onReturnToPreviousExplanation={handleReturnToPreviousExplanation}
      onOpenExplanationHistory={handleOpenExplanationHistory}
      canReturnToPreviousExplanation={explanationHistory.length > 0}
      onDiscussGenerationPlan={handleDiscussGenerationPlanFromAssistant}
      onConfirmCandidateChange={handleConfirmCandidateChange}
      onInvestigateThread={handleInvestigateThreadFromAssistant}
      onResolveThread={handleAssistantResolveThread}
      resolveArtifactText={resolveArtifactText}
      onRequestArtifact={handleRequestArtifact}
    />
  );
  const graphStage = (
    <AppGraphStagePanel
      analysisDisplayMode={analysisDisplayMode}
      activeWorkflowStage={activeWorkflowStage}
      activeViewGraph={activeViewGraph}
      fullNodeCount={activeFullGraph.nodeCount ?? activeFullGraph.nodes.length}
      hasExplanationFocus={explanationFocusNodeId != null}
      draftChangedNodeCount={draftChangedNodeIds.length}
      draftCompareProjection={draftCompareProjection}
      codeDiffStatus={codeDiffStatus}
      editableStageProps={editableStageProps}
      indexedReadonlyStageProps={indexedReadonlyStageProps}
      factGraphView={factGraphView}
      presentedFlowchartView={presentedFlowchartView}
      flowchartView={flowchartView}
      resourceRelationView={resourceRelationView}
      architectureGraphView={architectureGraphView}
      classDiagramView={classDiagramView}
      reviewGraphView={reviewGraphView}
      onRequestAnalysisDisplayMode={handleRequestAnalysisDisplayMode}
    />
  );

  return (
    <AppWorkbenchChrome
      currentTarget={currentTarget}
      activeWorkflowStage={activeWorkflowStage}
      workflowStageStates={workflowStageStates}
      analysisDisplayMode={analysisDisplayMode}
      indexedArchitectureSummary={architectureGraphView.summary.indexed ?? null}
      changeTrayState={changeTrayState}
      toolbarFeedback={toolbarFeedback}
      primaryWorkflowActionState={primaryWorkflowActionState}
      hybridLayoutPreference={hybridLayoutPreference}
      outlineState={outlineState}
      outlineQuery={outlineQuery}
      selectedNodeId={selectedNodeId}
      graphStage={graphStage}
      assistantWorkbench={assistantWorkbenchContent}
      dialogs={(
        <AppDialogs
          importDialogOpen={isImportDialogOpen}
          mermaidDraft={mermaidDraft}
          requestFailureNotice={requestFailureNotice}
          onMermaidDraftChange={setMermaidDraft}
          onCancelImport={() => setImportDialogOpen(false)}
          onConfirmImport={handleConfirmImportMermaidDraft}
          onCloseRequestFailure={() => setRequestFailureNotice(null)}
        />
      )}
      detailNode={detailNode}
      onImportMermaid={handleOpenImportMermaid}
      onExportMermaid={workbenchCommands.handleExportMermaid}
      onShowDiff={workbenchCommands.handleShowDiffMode}
      onRequestSync={workbenchCommands.handleRequestSyncPreview}
      onOpenSettings={workbenchCommands.handleOpenSettings}
      onPrimaryAction={handlePrimaryWorkflowAction}
      onOutlineCollapsedChange={handleOutlineCollapsedChange}
      onWorkbenchWidthChange={handleWorkbenchWidthChange}
      onOutlineQueryChange={setOutlineQuery}
      onSelectOutlineItem={handleSelectOutlineItem}
      onOpenDraft={() => {
        setDraftCompareMode("after");
        setActiveWorkflowStage("draft");
      }}
      onOpenDraftCompare={() => {
        setDraftCompareMode("compare");
        setActiveWorkflowStage("draft");
      }}
      onOpenCode={() => setActiveWorkflowStage("code")}
      onApplyChanges={workbenchCommands.handleWriteDrafts}
      onRevertChanges={handleUndoDraftPatchApply}
      onUpdateNode={handleUpdateNode}
      onDeleteNode={handleDeleteNode}
      onDeleteNodeSubtree={handleDeleteNodeSubtree}
      onRequestSourceNavigation={handleRequestSourceNavigation}
      onClosePropertyDrawer={() => setDetailNodeId(null)}
    />
  );
}
