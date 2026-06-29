import { useEffect, useMemo, useRef, useState } from "react";
import { undoLastDraftPatchApply } from "./api";
import { AssistantWorkbenchShell } from "./assistant/AssistantWorkbenchShell";
import { buildAssistantTurns } from "./assistant/assistantResultAdapters";
import { useAssistantActionController, type AssistantDisplayModeDocuments } from "./assistant/useAssistantActionController";
import {
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
  resolveExplanationStepRawNodeId as resolveRawNodeIdForExplanationStep,
} from "./appExplanationStepModel";
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
} from "./components/hybridDerivations";
import {
  collectDownstreamSubtreeNodeIds,
  fallbackDesignPosition,
  normalizeGraphNodes,
  resolveNodePosition,
  syncNodePosition,
} from "./graphState";
import { canEditNodeLayout } from "./layoutEditability";
import type { AssistantActionId, AssistantIntent } from "./assistant/assistantTypes";
import type {
  AsyncRequestState,
  AnalysisDisplayMode,
  AssistantComposerTarget,
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
import { useGraphDataRefs } from "./controllers/useGraphDataRefs";
import { useSelectionState } from "./controllers/useSelectionState";
import { useAssistantExplanationHistory } from "./controllers/useAssistantExplanationHistory";
import { useGraphCanvasController } from "./controllers/useGraphCanvasController";
import { useGraphEditController } from "./controllers/useGraphEditController";
import { useInteractionProbeController } from "./controllers/useInteractionProbeController";
import { useManualNodeIdController } from "./controllers/useManualNodeIdController";
import { useNodeSelectionController } from "./controllers/useNodeSelectionController";
import { useSourceNavigationController } from "./controllers/useSourceNavigationController";
import { useStageNavController } from "./controllers/useStageNavController";
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
import { useExplanationState, DEFAULT_EXPLANATION_SESSION_LABEL } from "./controllers/useExplanationState";
import { useExplanationStepActions } from "./controllers/useExplanationStepActions";
import { useWorkbenchLayoutState } from "./controllers/useWorkbenchLayoutState";

export { resolveQaTargetNodeIds } from "./appGraphSupport";
// P2-1: 讲解状态管理委托给 useExplanationState hook
export {
  DEFAULT_EXPLANATION_SESSION_LABEL as EXPLANATION_SESSION_LABEL,
  type ExplanationRequestMode,
  type ExplanationHistoryEntry,
} from "./controllers/useExplanationState";

/** 用户在生成计划讨论未输入问题时使用的默认提示语 */
const DEFAULT_GENERATION_DISCUSSION_PROMPT = "请继续讨论这份实现建议的取舍、风险和下一步。";

/**
 * 前端根组件。汇总工作台、图视图、AI 助手面板等所有核心状态与控制器，
 * 是 LinkGraph 插件前端的总入口。
 */
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
  // 当前激活的工作流阶段（理解/问答/草稿/代码）
  const [activeWorkflowStage, setActiveWorkflowStage] = useState<WorkflowStage>("understand");
  // P2-1: 工作台布局偏好（折叠/宽度/大纲查询）委托给 useWorkbenchLayoutState hook
  const {
    hybridLayoutPreference,
    outlineQuery,
    setOutlineQuery,
    handleOutlineCollapsedChange,
    handleWorkbenchWidthChange,
  } = useWorkbenchLayoutState();
  const activeAssistantTarget = workflowStageToAssistantTarget(activeWorkflowStage);

  /** 用户在助手结果中点击引用证据时，定位到对应节点并打开详情面板 */
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

  /** 将助手侧的风险线程状态变更转交 QA 线程处理器，仅处理延期/接受/忽略三种状态 */
  function handleAssistantResolveThread(threadId: string, status: RiskResolutionStatus) {
    if (status === "DEFERRED" || status === "ACCEPTED_RISK" || status === "DISMISSED") {
      handleResolveQaThread(threadId, status);
    }
  }
  /** 切换图视图分析模式；切到评审图时联动跳转到 QA 流程并带上当前 diff 目标项 */
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
  // P2-1: 讲解相关本地状态 + ref 集合委托给 useExplanationState hook
  const {
    selectedExplanationStepId,
    setSelectedExplanationStepId,
    selectedExplanationGranularity,
    setSelectedExplanationGranularity,
    explanationHistory,
    setExplanationHistory,
    currentExplanationSessionLabel,
    setCurrentExplanationSessionLabel,
    hoveredExplanationStepId,
    setHoveredExplanationStepId,
    pendingExplanationDrillTargetRef,
    explanationLocalOverrideRef,
    pendingExplanationRequestModeRef,
    pendingExplanationSessionLabelRef,
    pendingExplanationHistoryEntryRef,
  } = useExplanationState(graphBeautificationResult);
  // 当前选中的候选变更 / 风险线程 / 草稿条目 + draftCompareMode 集中委托给 useSelectionState
  const {
    selectedQaChangeId,
    setSelectedQaChangeId,
    selectedQaThreadId,
    setSelectedQaThreadId,
    selectedDraftEntryId,
    setSelectedDraftEntryId,
    draftCompareMode,
    setDraftCompareMode,
  } = useSelectionState(initialState, draftWorkbenchState);
  // 用于快照比对的语义版本号，记录最新已知后端版本
  const semanticRevisionRef = useRef<number | null>(initialState.semanticRevision ?? null);
  // 当前场景的布局版本号，记录最新已知后端布局版本
  const layoutRevisionRef = useRef<number | null>(resolveCurrentSceneState(initialState).layoutRevision ?? null);
  // 图谱相关 ref 集合 + 同步 effects 委托给 useGraphDataRefs
  const {
    nodesRef,
    edgesRef,
    draftGraphRef,
    anchorNodeIdRef,
    analysisDisplayModeRef,
  } = useGraphDataRefs(nodes, edges, draftGraph, anchorNodeId, analysisDisplayMode);
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
    selectedQaMode,
    handleAssistantActionChange,
    handleAssistantQaModeChange,
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

  /** 清空所有由图谱派生的本地状态（问答、diff、草稿、生成计划、代码草稿等） */
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

  /** 用户请求跳转到某个节点对应的源码位置 */
  function handleRequestSourceNavigation(nodeId: string) {
    sourceNavigationCommands.handleRequestSourceNavigation(nodeId);
  }

  /** 打开 Mermaid 导入对话框，并清空草稿输入 */
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

  /** 把上次失败的 QA 请求回填到 AI 工作台输入框，便于用户修改后重新提交 */
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
      qaMode: failedRequest.mode ?? "AUTO",
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

  /** 计算问答作用域的节点 ID 与对应标题（单选时才有标题） */
  function resolveQaScope(targetNodeId?: string) {
    const nodeIds = resolveQaTargetNodeIds(targetNodeId, selectionGroupNodeIds);
    return {
      nodeIds,
      title: nodeIds.length === 1
        ? nodes.find((node) => node.id === nodeIds[0])?.title ?? null
        : null,
    };
  }

  /** 打开问答：先设置目标节点，再用默认提示词填充助手输入框 */
  function handleOpenQa(targetNodeId?: string) {
    const scope = resolveQaScope(targetNodeId);
    const question = buildDefaultQaQuestion(scope.nodeIds, scope.title, analysisDisplayMode);
    setQaTargetNodeIds(scope.nodeIds);
    primeAssistantComposer("ASK_CODE", question, {
      target: NEW_ASSISTANT_COMPOSER_TARGET,
      stage: "qa",
    });
  }

  /** 进入代码阶段并用默认提示词预填生成计划的输入框 */
  function primeGenerationPlanComposer() {
    setActiveWorkflowStage("code");
    primeAssistantComposer("GENERATE_CODE", DEFAULT_GENERATION_PLAN_PROMPT, {
      target: NEW_ASSISTANT_COMPOSER_TARGET,
      stage: "code",
    });
  }

  /** 进入代码阶段并请求生成代码草稿 */
  function handleRequestCodeDrafts() {
    setActiveWorkflowStage("code");
    workbenchCommands.handleRequestCodeDrafts();
  }

  /** 进入代码阶段，针对当前生成计划项发起继续讨论 */
  function handleDiscussGenerationPlanFromAssistant(question?: string) {
    primeAssistantComposer("GENERATE_CODE", question?.trim() || DEFAULT_GENERATION_DISCUSSION_PROMPT, {
      target: {
        kind: "GenerationDiscussion",
        planItemId: generationPlanDiscussionSession?.focusItemId ?? generationPlan?.items[0]?.id ?? null,
      },
      stage: "code",
    });
  }

  /** 校验 Mermaid 输入非空后转交导入流程 */
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

  /** 用户点击展开溢出节点时，把节点标题一并传入对应的展开处理 */
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

  /** 根据步骤 ID 反查步骤原始节点 ID（不含显示层映射） */
  function resolveExplanationStepRawNodeId(stepId: string): string | null {
    return resolveRawNodeIdForExplanationStep(findExplanationStep(graphBeautificationResult, stepId));
  }

  /** 解析步骤目标节点 ID（经过显示层映射，找不到返回 null） */
  function resolveExplanationStepTargetNodeId(stepId: string): string | null {
    return resolveDisplayedNodeId(resolveExplanationStepRawNodeId(stepId), nodes);
  }

  /** 解析步骤聚焦用节点 ID（优先显示层映射，回退到原始 ID） */
  function resolveExplanationStepFocusNodeId(stepId: string): string | null {
    const rawNodeId = resolveExplanationStepRawNodeId(stepId);
    if (!rawNodeId) {
      return null;
    }
    return resolveDisplayedNodeId(rawNodeId, nodes) ?? rawNodeId;
  }

  // P2-1: 讲解步骤交互动作（选中/定位/查看/切换粒度/追问）委托给 useExplanationStepActions hook
  const {
    handleSelectExplanationStepFromAssistant,
    handleLocateExplanationStepNodeFromAssistant,
    handleInspectExplanationStepNodeFromAssistant,
    handleChangeExplanationGranularityFromAssistant,
    handleFollowUpExplanationStepFromAssistant,
  } = useExplanationStepActions({
    graphBeautificationResult,
    nodes,
    selectedNodeId,
    selectedNode,
    analysisDisplayMode,
    activeIntent: assistantSessionState.activeIntent,
    setSelectedExplanationStepId,
    setSelectedExplanationGranularity,
    selectExplanationTargetNode,
    handleInspectNode,
    setOperationFeedback,
    primeAssistantComposer,
    setAssistantComposer,
    handleAssistantSubmit,
    assistantComposerDraft,
    selectedAssistantNodeIds,
    assistantTargetTitle,
  });

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
  const { stageStatusEntries, handleSelectStage } = useStageNavController({
    activeStage: activeWorkflowStage,
    setActiveStage: setActiveWorkflowStage,
    setOperationFeedback,
    graphBeautificationRequestState,
    qaRequestState,
    generationPlanRequestState,
    codeDraftRequestState,
    codeDiffStatus,
    confirmedDraftCount: changeTrayState.confirmedDraftCount,
    pendingCandidateCount: changeTrayState.pendingCandidateCount,
    blockingRiskCount: changeTrayState.blockingRiskCount,
    generatedCodeDraftCount: generatedCodeDrafts.length,
  });
  /** 选中某个草稿条目并联动定位到对应的图节点 */
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

  // 选中草稿条目后自动定位到主节点。selectedNodeId 只用于"已经选中就不再重复定位"的早退判断，
  // 不应作为依赖——否则 selectExplanationTargetNode 改动 selectedNodeId 会立刻重触发 effect，形成震荡。
  // 用 ref 读取最新值，依赖里只放真正应该触发定位的 stage / nodes / selectedDraftEntry。
  const selectedNodeIdRef = useRef(selectedNodeId);
  selectedNodeIdRef.current = selectedNodeId;
  useEffect(() => {
    if (activeWorkflowStage !== "draft" || !selectedDraftEntry) {
      return;
    }
    const targetNodeId = resolveDraftEntryPrimaryNodeId(selectedDraftEntry);
    const displayedTargetNodeId = resolveDisplayedNodeId(targetNodeId, nodes);
    if (!displayedTargetNodeId) {
      return;
    }
    if (selectedNodeIdRef.current === displayedTargetNodeId) {
      return;
    }
    selectExplanationTargetNode(displayedTargetNodeId, { focusViewport: true });
  }, [activeWorkflowStage, nodes, selectedDraftEntry]);

  /** 触发对当前作用域的讲解：定位节点、构建默认提示词并按模式直接提交或预填输入框 */
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

  /** 在 QA 与 diff 评审结果中查找指定 ID 的风险线程 */
  function findAssistantRiskThread(threadId: string) {
    return [
      ...deriveInvestigationThreads(qaResult),
      ...deriveInvestigationThreads(diffReviewResult),
    ].find((thread) => thread.threadId === threadId) ?? null;
  }

  /** 从助手结果发起风险线程取证：设置目标节点并预填取证问题 */
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
    blockingRiskCount: changeTrayState.blockingRiskCount,
    assistantComposerDraft,
    selectedNodeId,
    selectedNodeTitle: selectedNode?.title ?? null,
    assistantTargetTitle: assistantTargetTitle(selectedAssistantNodeIds()) ?? null,
  };

  /** 根据当前流程状态计算主操作命令并分发到对应处理（提交助手/开 QA/写草稿等） */
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

  /** 选中大纲项：按前缀区分风险线程、草稿条目或图节点，分别路由 */
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

  /** 通过桥接命令请求回退上次草稿应用 */
  function handleUndoDraftPatchApply() {
    bridgeCommands.runBridgeCommand("回退草稿应用", () => undoLastDraftPatchApply(), {
      successFeedback: {
        level: "INFO",
        message: "已请求回退上次草稿应用。",
      },
    });
  }

  /** 按 ID 从本地缓存中读取产物文本内容 */
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
      turns={assistantTurns}
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
      selectedQaMode={selectedQaMode}
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
      onQaModeChange={handleAssistantQaModeChange}
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
      analysisDisplayMode={analysisDisplayMode}
      indexedArchitectureSummary={architectureGraphView.summary.indexed ?? null}
      changeTrayState={changeTrayState}
      toolbarFeedback={toolbarFeedback}
      primaryWorkflowActionState={primaryWorkflowActionState}
      stageStatusEntries={stageStatusEntries}
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
      onSelectStage={handleSelectStage}
      onOutlineCollapsedChange={handleOutlineCollapsedChange}
      onWorkbenchWidthChange={handleWorkbenchWidthChange}
      onOutlineQueryChange={setOutlineQuery}
      onSelectOutlineItem={handleSelectOutlineItem}
      onApplyChanges={workbenchCommands.handleWriteDrafts}
      onRevertChanges={handleUndoDraftPatchApply}
      onOpenDraft={() => {
        setDraftCompareMode("after");
        setActiveWorkflowStage("draft");
      }}
      onOpenDraftCompare={() => {
        setDraftCompareMode("compare");
        setActiveWorkflowStage("draft");
      }}
      onOpenCode={() => setActiveWorkflowStage("code")}
      onUpdateNode={handleUpdateNode}
      onDeleteNode={handleDeleteNode}
      onDeleteNodeSubtree={handleDeleteNodeSubtree}
      onRequestSourceNavigation={handleRequestSourceNavigation}
      onClosePropertyDrawer={() => setDetailNodeId(null)}
    />
  );
}
