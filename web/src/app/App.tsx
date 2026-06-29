import { useEffect, useMemo, useRef, useState } from "react";
import { undoLastDraftPatchApply } from "./api";
import {
  applyLayoutUpdatesToGraphDocument,
  applyBootstrapRoutesToViewDocument,
  deriveFlowchartSummary,
  deriveResourceRelationSummary,
  overlayDraftEntryOntoFlowchartView,
  resolveAnchorNodeId,
  resolveCollapsedDescendantSummary,
  resolveDisplayedNodeId,
  resolveDraftEntryPrimaryNodeId,
  resolveDraftEntryTargetNodeIds,
  resolveEntryOwnerSignatures,
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
import { primaryWorkflowActionCommand } from "./appPrimaryWorkflowAction";
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
import type { AssistantIntent } from "./assistant/assistantTypes";
import type {
  AnalysisDisplayMode,
  LinkGraphBootstrapState,
  LinkGraphSceneId,
} from "./types";
import type { EditableStageProps, IndexedReadonlyStageProps } from "./views/viewStageProps";
import type { AssistantDisplayModeDocuments } from "./assistant/useAssistantActionController";
import { useAssistantWorkbench } from "./controllers/useAssistantWorkbench";
import { useWorkbenchChromeActions } from "./controllers/useWorkbenchChromeActions";
import { useBootstrapProjectionState } from "./controllers/useBootstrapProjectionState";
import { useBootstrapStateController } from "./controllers/useBootstrapStateController";
import { useAppBridgeController } from "./controllers/useAppBridgeController";
import { useBridgeCommandController } from "./controllers/useBridgeCommandController";
import { useGraphDataRefs } from "./controllers/useGraphDataRefs";
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
import { useWorkbenchLayoutState } from "./controllers/useWorkbenchLayoutState";

export { resolveQaTargetNodeIds } from "./appGraphSupport";
// P2-1: 讲解状态管理委托给 useExplanationState hook；保留对外 re-export 不破坏既有 import
export {
  DEFAULT_EXPLANATION_SESSION_LABEL as EXPLANATION_SESSION_LABEL,
  type ExplanationRequestMode,
  type ExplanationHistoryEntry,
} from "./controllers/useExplanationState";

/**
 * 前端根组件。
 *
 * 经过 P2-11 组件分解后，本文件只承担三件事：
 * 1. 设置跨域共享的 state（canvas + projection + bridge + bootstrap）
 * 2. 调用 [useAssistantWorkbench] 把整个 assistant 域收敛到一处（hook 自带 state、handler、JSX）
 * 3. 装配 graph / dialog / chrome 的 JSX 布局
 *
 * 详细的 assistant 业务逻辑（explanation state、composer、QA actions、风险线程取证、
 * 讲解步骤导航、composer priming 等）全部在 useAssistantWorkbench 内部，
 * App.tsx 只通过返回的字段消费跨域共享的部分（如 selectedExplanationStepId 用于 derived state）。
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

  // ===== 工作台基础 state（canvas + projection + UI flags）=====
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

  // ===== 桥接命令 + 应用层命令控制器 =====
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

  // ===== 工作流阶段 + 布局偏好 =====
  const [activeWorkflowStage, setActiveWorkflowStage] = useState<WorkflowStage>("understand");
  const {
    hybridLayoutPreference,
    outlineQuery,
    setOutlineQuery,
    handleOutlineCollapsedChange,
    handleWorkbenchWidthChange,
  } = useWorkbenchLayoutState();
  const activeAssistantTarget = workflowStageToAssistantTarget(activeWorkflowStage);

  // ===== 图数据 ref（供回调内读取最新值）=====
  const semanticRevisionRef = useRef<number | null>(initialState.semanticRevision ?? null);
  const layoutRevisionRef = useRef<number | null>(resolveCurrentSceneState(initialState).layoutRevision ?? null);
  const {
    nodesRef,
    edgesRef,
    draftGraphRef,
    anchorNodeIdRef,
    analysisDisplayModeRef,
  } = useGraphDataRefs(nodes, edges, draftGraph, anchorNodeId, analysisDisplayMode);

  // ===== Assistant 视图文档 memo（assistant 域 + workbench derived 共用）=====
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

  // ===== Manual node id 计数器 + 节点派生 =====
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

  // ===== Assistant 工作台：所有 assistant 域 state、handler、JSX 收敛到 useAssistantWorkbench =====
  const assistant = useAssistantWorkbench({
    initialState,
    nodes,
    selectedNodeId,
    selectedNode,
    anchorNodeId,
    analysisDisplayMode,
    currentSceneId,
    selectionGroupNodeIds,
    diffTargetItemIds,
    diffItems,
    viewDocuments: assistantViewDocuments,
    assistantSessionState,
    assistantResultStore,
    qaResult,
    qaRequestState,
    qaRequestRecoveryState,
    diffReviewResult,
    diffReviewRequestState,
    draftWorkbenchState,
    generationPlan,
    generationPlanRequestState,
    generationPlanDiscussionRequestState,
    generationPlanDiscussionSession,
    graphBeautificationResult,
    graphBeautificationRequestState,
    codeDraftRequestState,
    setAssistantSessionState,
    setAssistantResultStore,
    setGraphBeautificationResult,
    setGraphBeautificationRequestState,
    setQaTargetNodeIds,
    setSelectedNodeId,
    setDetailNodeId,
    requestViewportFocus,
    bridgeCommands,
    workbenchCommands,
    setActiveWorkflowStage,
    setOperationFeedback,
    selectExplanationTargetNode,
    handleInspectNode,
    handleWriteSingleCodeDraft,
    handleOpenCodeDraftNativeDiff,
    handleRequestArtifact,
    resolveArtifactText: (artifactId: string) => artifactContents[artifactId] ?? null,
  });

  // ===== Bootstrap 投影 + 状态控制器 =====
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
    explanationLocalOverrideRef: assistant.explanationLocalOverrideRef,
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
    handleRequestSourceNavigation: (nodeId: string) => sourceNavigationCommands.handleRequestSourceNavigation(nodeId),
  });

  // ===== Chrome 层 handler（toolbar / 大纲 / 导入对话框 / diff 模式切换）=====
  // 抽出自 App.tsx 的 5 个内联 handler，让 App.tsx 不再关心 chrome 层的事件实现细节
  const {
    handleRequestAnalysisDisplayMode,
    handleOpenImportMermaid,
    handleConfirmImportMermaidDraft,
    handleExpandOverflowNode,
    handleUndoDraftPatchApply,
  } = useWorkbenchChromeActions({
    nodes,
    selectedNodeId,
    diffTargetItemIds,
    diffItems,
    mermaidDraft,
    bridgeCommands,
    workbenchCommands,
    handleConfirmImportMermaid,
    setActiveWorkflowStage,
    setMermaidDraft,
    setImportDialogOpen,
    setOperationFeedback,
  });

  // ===== Derived state（需要 assistant 输出 + graph state）=====
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
    selectedQaChangeId: assistant.selectedQaChangeId,
    selectedQaThreadId: assistant.selectedQaThreadId,
    graphBeautificationResult,
    graphBeautificationRequestState,
    selectedExplanationStepId: assistant.selectedExplanationStepId,
    selectedExplanationGranularity: assistant.selectedExplanationGranularity,
    hoveredExplanationStepId: assistant.hoveredExplanationStepId,
    explanationHistory: assistant.explanationHistory,
    currentExplanationSessionLabel: assistant.currentExplanationSessionLabel,
    draftWorkbenchState,
    selectedDraftEntryId: assistant.selectedDraftEntryId,
    draftCompareMode: assistant.draftCompareMode,
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

  // ===== 草稿条目自动定位 effect（需要 assistant.selectedDraftEntryId）=====
  // selectedNodeId 只用于"已经选中就不再重复定位"的早退判断，不应作为依赖——否则
  // selectExplanationTargetNode 改动 selectedNodeId 会立刻重触发 effect 形成震荡。
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
  }, [activeWorkflowStage, nodes, selectedDraftEntry, selectExplanationTargetNode]);

  // ===== 主工作流动作分发 =====
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
    assistantComposerDraft: assistant.assistantComposerDraft,
    selectedNodeId,
    selectedNodeTitle: selectedNode?.title ?? null,
    assistantTargetTitle: assistant.assistantTargetTitle(assistant.selectedAssistantNodeIds()) ?? null,
  };

  function handlePrimaryWorkflowAction() {
    const command = primaryWorkflowActionCommand(primaryWorkflowActionState);
    switch (command.kind) {
      case "SUBMIT_ASSISTANT":
        assistant.handleAssistantSubmit(command.intent, command.prompt);
        return;
      case "OPEN_QA":
        assistant.handleOpenQa(command.targetNodeId ?? undefined);
        return;
      case "PRIME_GENERATION_PLAN":
        assistant.primeGenerationPlanComposer();
        return;
      case "WRITE_DRAFTS":
        workbenchCommands.handleWriteDrafts();
        return;
      case "REQUEST_CODE_DRAFTS":
        assistant.handleRequestCodeDrafts();
    }
  }

  function handleSelectOutlineItem(itemId: string) {
    if (itemId.startsWith("thread:")) {
      const threadId = itemId.slice("thread:".length);
      setActiveWorkflowStage("qa");
      assistant.handleSelectQaThread(threadId);
      return;
    }
    if (itemId.startsWith("draft:")) {
      const entryId = itemId.slice("draft:".length);
      setActiveWorkflowStage("draft");
      assistant.handleSelectDraftEntry(entryId);
      return;
    }
    handleSelectNode(itemId);
  }

  // ===== JSX：基础 stage props + 编辑/只读 stage props =====
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
    onRequestBeautification: assistant.handleExplainCurrentScope,
    onRequestSourceNavigation: (nodeId: string) => sourceNavigationCommands.handleRequestSourceNavigation(nodeId),
    onPrimeQuestionComposer: assistant.handleOpenQa,
    onToggleCollapseNode: handleToggleCollapseNode,
    onOpenQa: assistant.handleOpenQa,
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

  // ===== 图 stage JSX（assistant.workbench 是 hook 内部已渲染好的 JSX）=====
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
      assistantWorkbench={assistant.workbench}
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
        assistant.setDraftCompareMode("after");
        setActiveWorkflowStage("draft");
      }}
      onOpenDraftCompare={() => {
        assistant.setDraftCompareMode("compare");
        setActiveWorkflowStage("draft");
      }}
      onOpenCode={() => setActiveWorkflowStage("code")}
      onUpdateNode={handleUpdateNode}
      onDeleteNode={handleDeleteNode}
      onDeleteNodeSubtree={handleDeleteNodeSubtree}
      onRequestSourceNavigation={(nodeId: string) => sourceNavigationCommands.handleRequestSourceNavigation(nodeId)}
      onClosePropertyDrawer={() => setDetailNodeId(null)}
    />
  );
}
