import { useMemo, type Dispatch, type ReactElement, type SetStateAction } from "react";
import { AssistantWorkbenchShell } from "../assistant/AssistantWorkbenchShell";
import { buildAssistantTurns } from "../assistant/assistantResultAdapters";
import { useAssistantActionController, type AssistantDisplayModeDocuments } from "../assistant/useAssistantActionController";
import {
  buildDefaultExplanationPrompt,
  buildDefaultQaQuestion,
  DEFAULT_GENERATION_PLAN_PROMPT,
  NEW_ASSISTANT_COMPOSER_TARGET,
} from "../assistant/assistantPromptDefaults";
import {
  resolveDisplayedNodeId,
  resolveDraftEntryTargetNodeIds,
  resolveEvidenceTargetNodeId,
  resolveQaTargetNodeIds,
} from "../appGraphSupport";
import { deriveInvestigationThreads } from "../investigationThreads";
import { useAssistantQaActions } from "./useAssistantQaActions";
import { useAssistantExplanationHistory } from "./useAssistantExplanationHistory";
import { useExplanationState, DEFAULT_EXPLANATION_SESSION_LABEL, type ExplanationHistoryEntry } from "./useExplanationState";
import { useExplanationStepActions } from "./useExplanationStepActions";
import { useSelectionState } from "./useSelectionState";
import { useBridgeCommandController } from "./useBridgeCommandController";
import { useWorkbenchCommandController } from "./useWorkbenchCommandController";
import type { AssistantIntent } from "../assistant/assistantTypes";
import type {
  AnalysisDisplayMode,
  AsyncRequestState,
  AssistantResultStore,
  AssistantSessionState,
  DiffItem,
  DraftWorkbenchState,
  GenerationPlan,
  GenerationPlanDiscussionSession,
  GraphBeautificationResult,
  GraphPatchResult,
  LinkGraphBootstrapState,
  LinkGraphNode,
  LinkGraphSceneId,
  OperationFeedback,
  QaRequestRecoveryState,
  ResultEvidenceReference,
  RiskResolutionStatus,
  StepGranularity,
} from "../types";
import type { WorkflowStage } from "../workflow/workflowStage";

/** 用户在生成计划讨论未输入问题时使用的默认提示语 */
const DEFAULT_GENERATION_DISCUSSION_PROMPT = "请继续讨论这份实现建议的取舍、风险和下一步。";

/**
 * useAssistantWorkbench 入参：App.tsx 提供的上下文（只读 state + 回调）。
 *
 * 设计原则：所有跨域共享的 state（projection state、graph context）通过本结构传入，
 * hook 内部只持有 assistant 域自有的 state（explanation step / QA 选择 / 草稿选择等）。
 */
export interface AssistantWorkbenchContext {
  /** Bootstrap 状态（用于初始化 useSelectionState） */
  initialState: LinkGraphBootstrapState;

  // ---- Graph context（只读）----
  nodes: LinkGraphNode[];
  selectedNodeId: string | null;
  selectedNode: LinkGraphNode | null;
  anchorNodeId: string | null;
  analysisDisplayMode: AnalysisDisplayMode;
  currentSceneId: LinkGraphSceneId;
  selectionGroupNodeIds: string[];
  diffTargetItemIds: string[];
  diffItems: DiffItem[];
  viewDocuments: AssistantDisplayModeDocuments;

  // ---- Projection state（只读，跨域共享）----
  assistantSessionState: AssistantSessionState;
  assistantResultStore: AssistantResultStore;
  qaResult: GraphPatchResult | null;
  qaRequestState: AsyncRequestState;
  qaRequestRecoveryState: QaRequestRecoveryState;
  diffReviewResult: GraphPatchResult | null;
  diffReviewRequestState: AsyncRequestState;
  draftWorkbenchState: DraftWorkbenchState;
  generationPlan: GenerationPlan | null;
  generationPlanRequestState: AsyncRequestState;
  generationPlanDiscussionRequestState: AsyncRequestState;
  generationPlanDiscussionSession: GenerationPlanDiscussionSession | null;
  graphBeautificationResult: GraphBeautificationResult | null;
  graphBeautificationRequestState: AsyncRequestState;
  codeDraftRequestState: AsyncRequestState;

  // ---- 投影 setters（assistant 域需要回写投影状态；这些是 React useState setter，支持 updater 函数）----
  setAssistantSessionState: Dispatch<SetStateAction<AssistantSessionState>>;
  setAssistantResultStore: Dispatch<SetStateAction<AssistantResultStore>>;
  setGraphBeautificationResult: Dispatch<SetStateAction<GraphBeautificationResult | null>>;
  setGraphBeautificationRequestState: Dispatch<SetStateAction<AsyncRequestState>>;
  setQaTargetNodeIds: (ids: string[]) => void;

  // ---- App.tsx 持有的图状态 setters（panel 通过这些回调写入图选中状态）----
  setSelectedNodeId: (id: string | null) => void;
  setDetailNodeId: (id: string | null) => void;
  requestViewportFocus: (nodeId: string) => void;

  // ---- 外部命令与回调 ----
  bridgeCommands: ReturnType<typeof useBridgeCommandController>;
  workbenchCommands: ReturnType<typeof useWorkbenchCommandController>;
  setActiveWorkflowStage: (stage: WorkflowStage) => void;
  setOperationFeedback: Dispatch<SetStateAction<OperationFeedback | null>>;
  selectExplanationTargetNode: (nodeId: string, options?: { focusViewport?: boolean }) => void;
  handleInspectNode: (nodeId: string) => void;
  handleWriteSingleCodeDraft: (draftId: string) => void;
  handleOpenCodeDraftNativeDiff: (draftId: string) => void;
  handleRequestArtifact: (artifactId: string) => void;
  resolveArtifactText: (artifactId: string) => string | null;
}

/** useAssistantWorkbench 输出：assistant 域自有 state + 跨域共享的 handler + 渲染好的 JSX。 */
export interface AssistantWorkbench {
  /** 渲染好的助手工作台 JSX（App.tsx 直接嵌入到布局） */
  workbench: ReactElement;

  // ---- 跨域共享的 state（App.tsx 用于 derived state / chrome 按钮）----
  selectedExplanationStepId: string | null;
  selectedExplanationGranularity: StepGranularity;
  hoveredExplanationStepId: string | null;
  explanationHistory: ExplanationHistoryEntry[];
  currentExplanationSessionLabel: string;
  selectedQaChangeId: string | null;
  selectedQaThreadId: string | null;
  selectedDraftEntryId: string | null;
  draftCompareMode: "after" | "compare";
  /** 讲解本地 override 标记 ref——useBootstrapProjectionState 用它判断是否要用本地的 graph beautification 状态 */
  explanationLocalOverrideRef: import("react").MutableRefObject<boolean>;

  // ---- 跨域共享的 setter（App.tsx 用于 effect / 大纲路由 / chrome 按钮）----
  setSelectedDraftEntryId: (id: string | null) => void;
  setDraftCompareMode: (mode: "after" | "compare") => void;
  setSelectedQaThreadId: (id: string | null) => void;

  // ---- 跨域共享的 handler（App.tsx 用于 primaryWorkflowAction / 大纲 / stageProps）----
  handleOpenQa: (targetNodeId?: string) => void;
  handleExplainCurrentScope: (focusNodeId?: string) => void;
  handleSelectDraftEntry: (entryId: string) => void;
  handleSelectQaThread: (threadId: string) => void;
  primeGenerationPlanComposer: () => void;
  handleRequestCodeDrafts: () => void;
  handleAssistantSubmit: (intent: AssistantIntent, prompt: string) => void;

  // ---- primaryWorkflowActionState 需要的字段 ----
  assistantComposerDraft: string;
  selectedAssistantNodeIds: () => string[];
  assistantTargetTitle: (selectedIds: string[]) => string | null;
}

/**
 * 把整个 assistant 工作台的 state、handler、JSX 收敛到一个 hook。
 *
 * App.tsx 只需要：
 * 1. 准备好 [AssistantWorkbenchContext] 入参
 * 2. 调用本 hook
 * 3. 把返回的 `workbench` JSX 嵌入到布局
 * 4. 用返回的跨域共享字段驱动 App.tsx 自己的 derived state / chrome 按钮 / 大纲路由
 *
 * Hook 内部按依赖顺序调用：
 *   状态 hook：useExplanationState → useSelectionState → useAssistantActionController
 *   动作 hook：→ useAssistantQaActions / useAssistantExplanationHistory / useExplanationStepActions
 * 然后定义 12+ 个内联 handler，最后把所有内容打包成 [AssistantWorkbench] 返回。
 */
export function useAssistantWorkbench(ctx: AssistantWorkbenchContext): AssistantWorkbench {
  const {
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
    viewDocuments,
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
    resolveArtifactText,
  } = ctx;

  // ===== 讲解相关本地 state + ref 集合 =====
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
    explanationLocalOverrideRef,
    pendingExplanationRequestModeRef,
    pendingExplanationSessionLabelRef,
    pendingExplanationHistoryEntryRef,
  } = useExplanationState(graphBeautificationResult);

  // ===== 选区 state（QA 候选 / 风险线程 / 草稿条目 / draft compare mode）=====
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

  // ===== 助手 composer + 动作 =====
  const {
    assistantComposerDraft,
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
    viewDocuments,
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

  // ===== QA 动作（候选确认 / 重试 / 线程选择 / 线程解决）=====
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

  // ===== 讲解历史回溯动作 =====
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

  // ===== 讲解步骤交互动作（选中 / 定位 / 查看 / 切换粒度 / 追问）=====
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

  // ===== 内联 handler：assistant → graph 交互 =====

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
      || `请继续取证：核对"${thread.title}"对应的直接源码证据。`;
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

  // ===== 内联 handler：composer / workflow 编排 =====

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

  // ===== 内联 helper：讲解步骤节点解析 =====

  // ===== 派生：assistantTurns + requestRunning =====
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

  // ===== 渲染：AssistantWorkbenchShell =====
  const workbench = (
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

  return {
    workbench,
    selectedExplanationStepId,
    selectedExplanationGranularity,
    hoveredExplanationStepId,
    explanationHistory,
    currentExplanationSessionLabel,
    selectedQaChangeId,
    selectedQaThreadId,
    selectedDraftEntryId,
    draftCompareMode,
    explanationLocalOverrideRef,
    setSelectedDraftEntryId,
    setDraftCompareMode,
    setSelectedQaThreadId,
    handleOpenQa,
    handleExplainCurrentScope,
    handleSelectDraftEntry,
    handleSelectQaThread,
    primeGenerationPlanComposer,
    handleRequestCodeDrafts,
    handleAssistantSubmit,
    assistantComposerDraft,
    selectedAssistantNodeIds,
    assistantTargetTitle,
  };
}
