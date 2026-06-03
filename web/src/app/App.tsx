import { useEffect, useMemo, useRef, useState } from "react";
import { requestAssistantTask, undoLastDraftPatchApply } from "./api";
import { AssistantWorkbenchShell } from "./assistant/AssistantWorkbenchShell";
import { buildAssistantTurns } from "./assistant/assistantResultAdapters";
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
  GraphProjectionIndex,
  GraphSurfaceExperimentFlags,
  IndexedGraphSummary,
  LinkGraphDocument,
  LinkGraphBootstrapState,
  LinkGraphEdge,
  LinkGraphNode,
  QaRequestRecoveryState,
  ResultEvidenceReference,
  ReviewGraphViewDocument,
  RiskResolutionStatus,
  StepGranularity,
} from "./types";
import type { EditableStageProps, IndexedReadonlyStageProps } from "./views/viewStageProps";
import { GraphWorkbench } from "./workbench/GraphWorkbench";
import { WorkbenchPropertyDrawer } from "./workbench/WorkbenchPropertyDrawer";
import type { RequestFailureNotice } from "./controllers/bridgeCommandTypes";
import { useQaWorkbenchController } from "./controllers/useQaWorkbenchController";
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

function activeIndexedGraphSummary(
  analysisDisplayMode: AnalysisDisplayMode,
  architectureSummary: IndexedGraphSummary | null,
  classDiagramSummary: IndexedGraphSummary | null,
  reviewSummary: IndexedGraphSummary | null,
): IndexedGraphSummary | null {
  switch (analysisDisplayMode) {
    case "ARCHITECTURE_GRAPH":
      return architectureSummary;
    case "CLASS_DIAGRAM":
      return classDiagramSummary;
    case "REVIEW_GRAPH":
      return reviewSummary;
    default:
      return null;
  }
}

function activeProjectionIndex(
  analysisDisplayMode: AnalysisDisplayMode,
  factProjectionIndex: GraphProjectionIndex | null | undefined,
  flowchartProjectionIndex: GraphProjectionIndex | null | undefined,
  resourceProjectionIndex: GraphProjectionIndex | null | undefined,
  architectureProjectionIndex: GraphProjectionIndex | null | undefined,
  classDiagramProjectionIndex: GraphProjectionIndex | null | undefined,
  reviewProjectionIndex: GraphProjectionIndex | null | undefined,
): GraphProjectionIndex | null {
  switch (analysisDisplayMode) {
    case "FLOWCHART":
      return flowchartProjectionIndex ?? null;
    case "RESOURCE_RELATION_VIEW":
      return resourceProjectionIndex ?? null;
    case "ARCHITECTURE_GRAPH":
      return architectureProjectionIndex ?? null;
    case "CLASS_DIAGRAM":
      return classDiagramProjectionIndex ?? null;
    case "REVIEW_GRAPH":
      return reviewProjectionIndex ?? null;
    case "FACT_GRAPH":
    default:
      return factProjectionIndex ?? null;
  }
}

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
export { resolveQaTargetNodeIds } from "./appGraphSupport";

function isProjectStructureDisplay(
  analysisDisplayMode: AnalysisDisplayMode,
  architectureSummary: IndexedGraphSummary | null | undefined,
): boolean {
  return analysisDisplayMode === "ARCHITECTURE_GRAPH" && architectureSummary?.scopeKind === "PROJECT";
}

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
    qaTargetNodeIds,
    setQaTargetNodeIds,
    qaQuestionDraft,
    setQaQuestionDraft,
    qaQuestionMode,
    setQaQuestionMode,
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
    workbenchSectionPreferences,
    lastMessageType,
    graphSurfaceExperiments,
    artifactContents,
    assistantSessionState,
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
    setWorkbenchSectionPreferences,
    setLastMessageType,
    setGraphSurfaceExperiments,
    setArtifactContents,
    setAssistantSessionState,
  } = projectionSetters;
  const [generationPlanDiscussionQuestionDraft, setGenerationPlanDiscussionQuestionDraft] = useState("");
  const [assistantComposerDraft, setAssistantComposerDraft] = useState("");
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
  const activeWorkbenchTab = workflowStageToWorkbenchTab(activeWorkflowStage) ?? "explanation";
  const activeWorkbenchTabForDerived = workflowStageToWorkbenchTab(activeWorkflowStage) ?? "qa";
  function setActiveWorkbenchTabCompat(tab: WorkbenchTab) {
    setActiveWorkflowStage(workbenchTabToWorkflowStage(tab));
  }
  function updateAssistantIntent(intent: AssistantIntent) {
    setAssistantSessionState((current) => ({
      ...current,
      activeIntent: intent,
    }));
  }

  function selectedAssistantNodeIds() {
    if (selectionGroupNodeIds.length > 0) {
      return selectionGroupNodeIds;
    }
    return selectedNodeId ? [selectedNodeId] : [];
  }

  function selectedAssistantDiffItemIds() {
    if (diffTargetItemIds.length > 0) {
      return diffTargetItemIds;
    }
    return selectedNodeId && diffItems.some((item) => item.id === selectedNodeId) ? [selectedNodeId] : [];
  }

  function handleAssistantIntentChange(intent: AssistantIntent) {
    updateAssistantIntent(intent);
  }

  function handleAssistantSubmit(intent: AssistantIntent, prompt: string) {
    const normalizedPrompt = prompt.trim();
    if (!normalizedPrompt && intent !== "CHECK_CHANGE") {
      return;
    }
    updateAssistantIntent(intent);
    switch (intent) {
      case "EXPLAIN_CODE":
        setActiveWorkflowStage("understand");
        break;
      case "ASK_CODE":
        setActiveWorkflowStage("qa");
        break;
      case "GENERATE_CODE":
        setActiveWorkflowStage("code");
        break;
      case "CHECK_CHANGE":
        setActiveWorkflowStage("qa");
        break;
    }
    bridgeCommands.submitAsyncBridgeCommand(
      "AI 代码工作台",
      () => requestAssistantTask({
        intent,
        prompt: normalizedPrompt,
        selectedNodeIds: selectedAssistantNodeIds(),
        selectedDiffItemIds: selectedAssistantDiffItemIds(),
      }),
      {
        successFeedback: {
          level: "INFO",
          message: "已提交 AI 代码工作台请求。",
        },
      },
    );
  }

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
  const [qaSourceThreadId, setQaSourceThreadId] = useState<string | null>(null);
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
    handleRequestQa,
    handleConfirmCandidateChange,
    handleRetryLastQaRequest,
    handleEditFailedQaRequest,
    handleSelectQaChange,
    handleSelectQaThread,
    handleInvestigateQaThread,
    handleResolveQaThread,
    handleUnconfirmDraftChange,
  } = useQaWorkbenchController({
    qaRequestRecoveryState,
    qaResult,
    qaSourceThreadId,
    qaQuestionMode,
    qaTargetNodeIds,
    selectedQaChangeId,
    selectedQaThreadId,
    nodes,
    draftWorkbenchState,
    bridgeCommands,
    setQaQuestionDraft,
    setQaQuestionMode,
    setQaTargetNodeIds,
    setQaSourceThreadId,
    setActiveWorkbenchTab: setActiveWorkbenchTabCompat,
    setOperationFeedback,
    setDraftWorkbenchState,
    setSelectedDraftEntryId,
    setQaResult,
    setSelectedQaChangeId,
    setSelectedQaThreadId,
    selectExplanationTargetNode,
    toDraftWorkbenchEntry,
    updateGraphPatchResultCandidateStatus,
    updateGraphPatchResultThreadResolution,
    resolveDraftEntryTargetNodeIds,
    resolveDisplayedNodeId,
    resolveEvidenceTargetNodeId,
  });

  function handleEditFailedQaRequestFromAssistantWorkbench() {
    const failedRequest = qaRequestRecoveryState.lastFailedRequest;
    handleEditFailedQaRequest();
    if (!failedRequest) {
      return;
    }
    setAssistantComposerDraft(failedRequest.question);
    updateAssistantIntent("ASK_CODE");
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

  const {
    handleOpenQa,
    handleOpenDraftValidation,
    handleRequestGenerationPlan,
    handleRequestCodeDrafts,
    handleRequestGenerationPlanDiscussion,
    handleRequestScopedQa,
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
    setQaTargetNodeIds,
    setQaQuestionDraft,
    setQaQuestionMode,
    setQaSourceThreadId,
    setActiveWorkbenchTab: setActiveWorkbenchTabCompat,
    setOperationFeedback,
    setDiffTargetItemIds,
    handleRequestQa,
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
    qaState,
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
    qaResult,
    qaRequestState,
    qaRequestRecoveryState,
    qaQuestionDraft,
    qaQuestionMode,
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
  const activeFullGraph = analysisDisplayMode === "FLOWCHART"
    ? flowchartView.fullGraph
    : analysisDisplayMode === "RESOURCE_RELATION_VIEW"
      ? resourceRelationView.fullGraph
      : analysisDisplayMode === "ARCHITECTURE_GRAPH"
        ? architectureGraphView.fullGraph
        : analysisDisplayMode === "CLASS_DIAGRAM"
          ? classDiagramView.fullGraph
          : analysisDisplayMode === "REVIEW_GRAPH"
            ? reviewGraphView.fullGraph
            : factGraphView.fullGraph;
  const activeAnchorNodeId = analysisDisplayMode === "FLOWCHART"
    ? flowchartView.anchorNodeId ?? null
    : analysisDisplayMode === "RESOURCE_RELATION_VIEW"
      ? resourceRelationView.anchorNodeId ?? null
      : analysisDisplayMode === "ARCHITECTURE_GRAPH"
        ? architectureGraphView.anchorNodeId ?? null
        : analysisDisplayMode === "CLASS_DIAGRAM"
          ? classDiagramView.anchorNodeId ?? null
          : analysisDisplayMode === "REVIEW_GRAPH"
            ? reviewGraphView.anchorNodeId ?? null
            : factGraphView.anchorNodeId ?? null;
  const activeAnchorNode = activeViewGraph.nodes.find((node) => node.id === activeAnchorNodeId) ?? null;
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
    qaRequestState,
    qaResult,
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
  const evidencePanelState = useMemo(() => deriveEvidencePanelState({
    selectedNode,
    activeViewGraph,
    qaResult,
    graphBeautificationResult,
  }), [activeViewGraph, qaResult, graphBeautificationResult, selectedNode]);
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
    onOpenQaWorkbench: () => setActiveWorkbenchTabCompat("qa"),
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

  const qaTabProps = {
    state: qaState,
    onQuestionDraftChange: setQaQuestionDraft,
    onQuestionModeChange: setQaQuestionMode,
    onSubmitQuestion: () => handleRequestQa(qaQuestionDraft),
    onRetryLastRequest: handleRetryLastQaRequest,
    onEditFailedRequest: handleEditFailedQaRequestFromAssistantWorkbench,
    onSelectChange: handleSelectQaChange,
    onConfirmChange: handleConfirmCandidateChange,
    onSelectThread: handleSelectQaThread,
    onInvestigateThread: handleInvestigateQaThread,
    onDeferRisk: (threadId: string) => handleResolveQaThread(threadId, "DEFERRED"),
    onAcceptRisk: (threadId: string) => handleResolveQaThread(threadId, "ACCEPTED_RISK"),
    onDismissRisk: (threadId: string) => handleResolveQaThread(threadId, "DISMISSED"),
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
    onRequestBeautification: handleRequestGraphBeautification,
    onRequestSourceNavigation: handleRequestSourceNavigation,
    onRequestQa: handleRequestScopedQa,
    onToggleCollapseNode: handleToggleCollapseNode,
    onOpenQa: handleOpenQa,
    onExpandOverflowNode: handleExpandOverflowNode,
    onExpandInvocation: handleExpandInvocation,
    onRemoveInvocationExpansion: handleRemoveInvocationExpansion,
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
    onRequestPackageDependencyGraph: workbenchCommands.handleRequestPackageDependencyGraph,
    onRequestReviewGraphWithOptions: workbenchCommands.handleRequestReviewGraphWithOptions,
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
        handleOpenQa(selectedNodeId ?? undefined);
        return;
      case "qa":
        handleRequestQa(qaQuestionDraft);
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
        return qaRequestState.phase === "RUNNING" ? "问答中" : "提交问答";
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
        return qaRequestState.phase === "RUNNING" || qaQuestionDraft.trim().length === 0;
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

  function handleOpenEvidenceQa() {
    const scopeIds = selectedNodeId ? [selectedNodeId] : [];
    const targetTitle = selectedNode?.title ?? null;
    setQaTargetNodeIds(scopeIds);
    setQaQuestionDraft(
      scopeIds.length === 1
        ? `请围绕节点“${targetTitle ?? scopeIds[0]}”及其直接关联链路进行问答，指出可能遗漏的业务链路、异常分支、资源依赖和数据约束。`
        : "请围绕当前整张链路图进行问答，指出可能遗漏的业务链路、异常分支、资源依赖和数据约束。",
    );
    setQaQuestionMode("AUTO");
    setQaSourceThreadId(null);
    setActiveWorkflowStage("qa");
  }

  function handleEvidenceThreadSelect(threadId: string) {
    setActiveWorkflowStage("qa");
    handleSelectQaThread(threadId);
  }

  function handleEvidenceCandidateSelect(changeId: string) {
    setActiveWorkflowStage("qa");
    handleSelectQaChange(changeId);
  }

  function handleUndoDraftPatchApply() {
    bridgeCommands.runBridgeCommand("回退草稿应用", () => undoLastDraftPatchApply(), {
      successFeedback: {
        level: "INFO",
        message: "已请求回退上次草稿应用。",
      },
    });
  }

  const assistantTurns = useMemo(() => buildAssistantTurns({
    assistantSessionState,
    qaResult,
    graphBeautificationResult,
    generationPlan,
    generationPlanDiscussionSession,
    generatedCodeDrafts,
    diffReviewResult,
  }), [
    assistantSessionState,
    diffReviewResult,
    generatedCodeDrafts,
    generationPlan,
    generationPlanDiscussionSession,
    graphBeautificationResult,
    qaResult,
  ]);
  const visibleAssistantTurns = assistantSessionState.turns.length > 0 ? assistantTurns : [];
  const assistantRequestRunning = [
    qaRequestState,
    diffReviewRequestState,
    graphBeautificationRequestState,
    generationPlanRequestState,
    generationPlanDiscussionRequestState,
    codeDraftRequestState,
  ].some((state) => state.phase === "RUNNING");
  const legacyWorkbenchContent = activeWorkflowStage === "evidence"
    ? (
      <EvidenceStagePanel
        state={evidencePanelState}
        isLoading={qaRequestState.phase === "RUNNING" || graphBeautificationRequestState.phase === "RUNNING"}
        errorMessage={qaRequestState.errorMessage ?? graphBeautificationRequestState.errorMessage ?? null}
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
        qaTabProps={qaTabProps}
        draftTabProps={draftTabProps}
        explanationTabProps={explanationTabProps}
        showTabs={false}
      />
    );
  const assistantWorkbenchContent = (
    <AssistantWorkbenchShell
      assistantSessionState={assistantSessionState}
      turns={visibleAssistantTurns}
      activeIntent={assistantSessionState.activeIntent}
      requestRunning={assistantRequestRunning}
      qaRequestRecoveryState={qaRequestRecoveryState}
      composerDraft={assistantComposerDraft}
      onComposerDraftChange={setAssistantComposerDraft}
      generationDiscussionQuestionDraft={generationPlanDiscussionQuestionDraft}
      onGenerationDiscussionQuestionDraftChange={setGenerationPlanDiscussionQuestionDraft}
      onSubmitGenerationDiscussion={handleRequestGenerationPlanDiscussion}
      onIntentChange={handleAssistantIntentChange}
      onSubmit={handleAssistantSubmit}
      onRetryLastQaRequest={handleRetryLastQaRequest}
      onEditFailedQaRequest={handleEditFailedQaRequestFromAssistantWorkbench}
      onRequestGenerationPlan={handleRequestGenerationPlan}
      onRequestCodeDrafts={handleRequestCodeDrafts}
      onWriteCodeDrafts={workbenchCommands.handleWriteDrafts}
      onWriteSingleCodeDraft={handleWriteSingleCodeDraft}
      onOpenNativeDiff={handleOpenCodeDraftNativeDiff}
      onOpenDraft={workbenchCommands.handleOpenDraft}
      onRevealReference={handleAssistantRevealReference}
      onFollowUpExplanationStep={handleFollowUpExplanationStep}
      onReturnToPreviousExplanation={handleReturnToPreviousExplanation}
      canReturnToPreviousExplanation={explanationHistory.length > 0}
      onConfirmCandidateChange={handleConfirmCandidateChange}
      onInvestigateThread={handleInvestigateQaThread}
      onResolveThread={handleAssistantResolveThread}
      legacyWorkbenchContent={legacyWorkbenchContent}
    />
  );
  const graphFocusedLayout = analysisDisplayMode === "CLASS_DIAGRAM" || isProjectStructureDisplay(
    analysisDisplayMode,
    architectureGraphView.summary.indexed ?? null,
  );
  const graphStage = (
    <section className="graph-stage" aria-label="图谱舞台">
      <GraphStageHeader
        analysisDisplayMode={analysisDisplayMode}
        activeStage={activeWorkflowStage}
        onRequestAnalysisDisplayMode={handleRequestAnalysisDisplayMode}
      />
      <div className="graph-stage-canvas">
        {analysisDisplayMode === "ARCHITECTURE_GRAPH" ||
        analysisDisplayMode === "CLASS_DIAGRAM" ||
        analysisDisplayMode === "REVIEW_GRAPH" ? (
          <AppGraphStage
            analysisDisplayMode={analysisDisplayMode}
            stageProps={indexedReadonlyStageProps}
            factGraphView={factGraphView}
            presentedFlowchartView={presentedFlowchartView}
            flowchartView={flowchartView}
            resourceRelationView={resourceRelationView}
            architectureGraphView={architectureGraphView}
            classDiagramView={classDiagramView}
            reviewGraphView={reviewGraphView}
          />
        ) : (
          <AppGraphStage
            analysisDisplayMode={analysisDisplayMode}
            stageProps={editableStageProps}
            factGraphView={factGraphView}
            presentedFlowchartView={presentedFlowchartView}
            flowchartView={flowchartView}
            resourceRelationView={resourceRelationView}
            architectureGraphView={architectureGraphView}
            classDiagramView={classDiagramView}
            reviewGraphView={reviewGraphView}
          />
        )}
      </div>
      <GraphStageFooter
        analysisDisplayMode={analysisDisplayMode}
        activeViewGraph={activeViewGraph}
        fullNodeCount={activeFullGraph.nodeCount ?? activeFullGraph.nodes.length}
        hasExplanationFocus={explanationFocusNodeId != null}
        draftChangedNodeCount={draftChangedNodeIds.length}
        draftCompareProjection={draftCompareProjection}
        indexedSummary={activeIndexedGraphSummary(
          analysisDisplayMode,
          architectureGraphView.summary.indexed ?? null,
          classDiagramView.summary.indexed ?? null,
          reviewGraphView.summary.indexed ?? null,
        )}
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
          outlineCollapsed={graphFocusedLayout ? true : hybridLayoutPreference.outlineCollapsed}
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
              content={assistantWorkbenchContent}
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
