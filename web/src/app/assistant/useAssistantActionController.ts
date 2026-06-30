import { useCallback, useEffect, type Dispatch, type SetStateAction } from "react";
import { requestAssistantTask, type BridgeInvocationResult } from "../api";
import type { SubmitAsyncBridgeCommandOptions } from "../controllers/bridgeCommandTypes";
import type { WorkflowStage } from "../workflow/workflowStage";
import type { AssistantActionId, AssistantIntent } from "./assistantTypes";
import type {
  AnalysisDisplayMode,
  AssistantComposerTarget,
  AssistantSessionState,
  DiffItem,
  LinkGraphDocument,
  LinkGraphSceneId,
  QaMode,
  StepGranularity,
} from "../types";
import {
  assistantActionDefinition,
  assistantActionIdForIntent,
  resolveAssistantActionIdForDisplayMode,
} from "./assistantActionRegistry";
import {
  buildAssistantContextSnapshot,
  resolveAssistantNodeIds,
  resolveAssistantScopeLabel,
  sceneIdForAnalysisDisplayMode,
} from "./assistantContextResolver";
import { applyAssistantSessionPolicy } from "./assistantSessionPolicy";
import {
  assistantWorkbenchDisplayLabel,
  buildDefaultAssistantPrompt as buildDefaultAssistantPromptText,
  NEW_ASSISTANT_COMPOSER_TARGET,
} from "./assistantPromptDefaults";

/**
 * 助手与后端桥接命令的接口约束，描述如何向底层桥接层提交一次异步命令调用。
 */
interface AssistantBridgeCommands {
  submitAsyncBridgeCommand: (
    scene: string,
    invoke: () => BridgeInvocationResult,
    options?: SubmitAsyncBridgeCommandOptions,
  ) => BridgeInvocationResult;
}

/**
 * 不同分析展示模式下对应的视图文档集合，每一项包含该模式下需要呈现的图谱以及锚点节点信息。
 */
export type AssistantDisplayModeDocuments = Record<
  AnalysisDisplayMode,
  {
    visibleGraph: LinkGraphDocument;
    anchorNodeId?: string | null;
  }
>;

/**
 * 当用户接受解释类动作（例如代码解释、类描述）后向上派发的事件结构。
 */
interface ExplanationAcceptedEvent {
  actionId: AssistantActionId;
  intent: AssistantIntent;
  target: AssistantComposerTarget;
}

/**
 * 助手动作控制器 hook 的入参集合，包含当前场景、选择状态、Diff 信息、会话状态以及外部回调和桥接命令。
 */
interface UseAssistantActionControllerArgs {
  analysisDisplayMode: AnalysisDisplayMode;
  currentSceneId: LinkGraphSceneId;
  selectedMethodSignature?: string | null;
  selectedNodeId: string | null;
  anchorNodeId: string | null;
  selectionGroupNodeIds: string[];
  diffTargetItemIds: string[];
  diffItems: DiffItem[];
  viewDocuments: AssistantDisplayModeDocuments;
  assistantSessionState: AssistantSessionState;
  setAssistantSessionState: Dispatch<SetStateAction<AssistantSessionState>>;
  selectedExplanationGranularity: StepGranularity;
  bridgeCommands: AssistantBridgeCommands;
  setActiveWorkflowStage: (stage: WorkflowStage) => void;
  onExplanationAccepted: (event: ExplanationAcceptedEvent) => void;
}

/**
 * 助手动作控制器 hook，负责协调输入框草稿、目标节点、动作切换、提交链路以及会话上下文的派生更新。
 * 它将上层传入的视图状态与会话状态串联起来，最终通过桥接命令发起助手任务请求。
 */
export function useAssistantActionController({
  analysisDisplayMode,
  currentSceneId,
  selectedMethodSignature = null,
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
  onExplanationAccepted,
}: UseAssistantActionControllerArgs) {
  const assistantComposerDraft = assistantSessionState.composer?.draft ?? "";
  const assistantComposerTarget = assistantSessionState.composer?.target ?? NEW_ASSISTANT_COMPOSER_TARGET;
  const selectedQaMode = assistantSessionState.composer?.qaMode ?? "AUTO";

  // 根据指定的展示模式取得该模式下需要呈现给助手的图谱文档
  const assistantGraphForDisplayMode = useCallback((mode: AnalysisDisplayMode): LinkGraphDocument => {
    return viewDocuments[mode].visibleGraph;
  }, [viewDocuments]);

  // 取得指定展示模式下的锚点节点 ID，若未提供则回退为空
  const assistantAnchorNodeIdForDisplayMode = useCallback((mode: AnalysisDisplayMode): string | null => {
    return viewDocuments[mode].anchorNodeId ?? null;
  }, [viewDocuments]);

  // 解析指定展示模式对应的场景 ID，当前模式直接复用外部传入的场景，其他模式由映射函数推导
  const assistantSceneIdForDisplayMode = useCallback((mode: AnalysisDisplayMode): LinkGraphSceneId => {
    return mode === analysisDisplayMode ? currentSceneId : sceneIdForAnalysisDisplayMode(mode);
  }, [analysisDisplayMode, currentSceneId]);

  // 根据意图更新当前激活的动作，并将其持久化到助手会话状态中
  function updateAssistantAction(intent: AssistantIntent, actionId?: AssistantActionId | null) {
    const resolvedActionId = resolveAssistantActionIdForDisplayMode(
      actionId ?? assistantActionIdForIntent(intent, analysisDisplayMode),
      analysisDisplayMode,
    );
    const action = assistantActionDefinition(resolvedActionId, analysisDisplayMode);
    setAssistantSessionState((current) => ({
      ...current,
      activeIntent: action.intent,
      activeActionId: resolvedActionId,
    }));
  }

  // 用户手动编辑输入框草稿时调用，会以 USER 来源写入并保留既有动作、场景与问答模式
  function setAssistantComposerDraft(value: string) {
    setAssistantSessionState((current) => ({
      ...current,
      composer: {
        ...current.composer,
        draft: value,
        target: current.composer?.target ?? NEW_ASSISTANT_COMPOSER_TARGET,
        draftSource: "USER",
        actionId: current.activeActionId
          ?? current.composer?.actionId
          ?? assistantActionIdForIntent(current.activeIntent, analysisDisplayMode),
        sceneId: current.composer?.sceneId ?? currentSceneId,
        qaMode: current.composer?.qaMode ?? "AUTO",
      },
    }));
  }

  // 写入一整套输入框状态，包括草稿、目标对象、来源标记等，便于不同来源统一更新
  function setAssistantComposer(
    draft: string,
    target: AssistantComposerTarget = NEW_ASSISTANT_COMPOSER_TARGET,
    options: {
      draftSource?: "AUTO" | "USER" | null;
      actionId?: AssistantActionId | null;
      sceneId?: LinkGraphSceneId | null;
      qaMode?: QaMode | null;
    } = {},
  ) {
    setAssistantSessionState((current) => ({
      ...current,
      composer: {
        draft,
        target,
        draftSource: options.draftSource ?? null,
        actionId: options.actionId
          ?? current.activeActionId
          ?? assistantActionIdForIntent(current.activeIntent, analysisDisplayMode),
        sceneId: options.sceneId ?? currentSceneId,
        qaMode: options.qaMode ?? current.composer?.qaMode ?? "AUTO",
      },
    }));
  }

  // 切换问答模式（自动/手动等），仅更新输入框中的 qaMode 字段，不影响其他草稿内容
  function handleAssistantQaModeChange(mode: QaMode) {
    setAssistantSessionState((current) => ({
      ...current,
      composer: {
        ...(current.composer ?? {
          draft: "",
          target: NEW_ASSISTANT_COMPOSER_TARGET,
        }),
        qaMode: mode,
      },
    }));
  }

  // 预填输入框：根据意图解析动作、写入草稿，并可选地切换工作流阶段，常用于通过外部入口直接展开助手
  function primeAssistantComposer(
    intent: AssistantIntent,
    draft: string,
    options: {
      target?: AssistantComposerTarget;
      stage?: WorkflowStage;
      actionId?: AssistantActionId;
      draftSource?: "AUTO" | "USER" | null;
      qaMode?: QaMode | null;
    } = {},
  ) {
    const actionId = options.actionId ?? assistantActionIdForIntent(intent, analysisDisplayMode);
    updateAssistantAction(intent, actionId);
    setAssistantComposer(draft, options.target ?? NEW_ASSISTANT_COMPOSER_TARGET, {
      actionId,
      draftSource: options.draftSource ?? "AUTO",
      qaMode: options.qaMode,
    });
    if (options.stage) {
      setActiveWorkflowStage(options.stage);
    }
  }

  // 解析当前选择下要传给助手的节点 ID 列表，综合考虑选中节点、组选择和锚点节点
  const selectedAssistantNodeIds = useCallback((mode: AnalysisDisplayMode = analysisDisplayMode): string[] => {
    return resolveAssistantNodeIds({
      graph: assistantGraphForDisplayMode(mode),
      selectionGroupNodeIds,
      selectedNodeId,
      anchorNodeId: assistantAnchorNodeIdForDisplayMode(mode) ?? anchorNodeId,
    });
  }, [
    analysisDisplayMode,
    anchorNodeId,
    assistantAnchorNodeIdForDisplayMode,
    assistantGraphForDisplayMode,
    selectedNodeId,
    selectionGroupNodeIds,
  ]);

  // 计算当前选中的 Diff 项 ID 列表，优先使用外部传入的目标项，再回退到当前节点是否命中 Diff 项
  const selectedAssistantDiffItemIds = useCallback((): string[] => {
    if (diffTargetItemIds.length > 0) {
      return diffTargetItemIds;
    }
    return selectedNodeId && diffItems.some((item) => item.id === selectedNodeId) ? [selectedNodeId] : [];
  }, [diffItems, diffTargetItemIds, selectedNodeId]);

  // 根据输入框的目标类型决定要使用的节点 ID 列表，不同目标类型对节点的来源有不同语义
  function selectedNodeIdsForComposerTarget(target: AssistantComposerTarget): string[] {
    switch (target.kind) {
      case "QaRecovery":
        return target.selectedNodeIds?.length ? target.selectedNodeIds : selectedAssistantNodeIds();
      case "RiskInvestigation":
        return target.targetNodeIds?.length ? target.targetNodeIds : selectedAssistantNodeIds();
      case "NewTask":
      case "ExplanationFollowUp":
      case "GenerationDiscussion":
        return selectedAssistantNodeIds();
    }
  }

  // 当目标节点只有一个时尝试取其展示标题，用于在输入框默认提示中给出更明确的上下文
  const assistantTargetTitle = useCallback((
    targetNodeIds: string[],
    mode: AnalysisDisplayMode = analysisDisplayMode,
  ): string | null => {
    if (targetNodeIds.length !== 1) {
      return null;
    }
    return assistantGraphForDisplayMode(mode).nodes.find((node) => node.id === targetNodeIds[0])?.title
      ?? targetNodeIds[0];
  }, [analysisDisplayMode, assistantGraphForDisplayMode]);

  // 构造某个动作在当前上下文下的默认提示语，便于在动作切换或场景初始化时给出推荐输入
  const buildDefaultAssistantPrompt = useCallback((
    actionId: AssistantActionId,
    mode: AnalysisDisplayMode = analysisDisplayMode,
    targetNodeIds: string[] = selectedAssistantNodeIds(mode),
  ): string => {
    return buildDefaultAssistantPromptText({
      actionId,
      analysisDisplayMode: mode,
      targetNodeIds,
      targetTitle: assistantTargetTitle(targetNodeIds, mode),
    });
  }, [analysisDisplayMode, assistantTargetTitle, selectedAssistantNodeIds]);

  // 处理动作切换：解析动作定义、更新意图，并在有默认提示时将其作为 AUTO 来源写入草稿
  function handleAssistantActionChange(actionId: AssistantActionId) {
    const resolvedActionId = resolveAssistantActionIdForDisplayMode(actionId, analysisDisplayMode);
    const action = assistantActionDefinition(resolvedActionId, analysisDisplayMode);
    updateAssistantAction(action.intent, resolvedActionId);
    const defaultPrompt = buildDefaultAssistantPrompt(resolvedActionId);
    if (defaultPrompt) {
      setAssistantComposer(defaultPrompt, NEW_ASSISTANT_COMPOSER_TARGET, {
        actionId: resolvedActionId,
        draftSource: "AUTO",
      });
    }
  }

  // 提交助手任务：校验草稿、切换工作流阶段、组装请求参数，并通过桥接命令异步发起请求；
  // 请求被接受时会根据意图派发解释接受事件并清空输入框草稿
  function handleAssistantSubmit(
    intent: AssistantIntent,
    prompt: string,
    options: {
      explanationGranularity?: StepGranularity;
      selectedNodeIds?: string[];
      target?: AssistantComposerTarget;
      actionId?: AssistantActionId;
    } = {},
  ) {
    const actionId = resolveAssistantActionIdForDisplayMode(
      options.actionId ?? assistantActionIdForIntent(intent, analysisDisplayMode),
      analysisDisplayMode,
    );
    const action = assistantActionDefinition(actionId, analysisDisplayMode);
    const resolvedIntent = action.intent;
    const normalizedPrompt = prompt.trim();
    if (!normalizedPrompt && resolvedIntent !== "CHECK_CHANGE") {
      return;
    }
    const target = options.target ?? assistantComposerTarget;
    updateAssistantAction(resolvedIntent, actionId);
    switch (resolvedIntent) {
      case "DESCRIBE_CLASS":
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
    const requestSelectedNodeIds = options.selectedNodeIds ?? selectedNodeIdsForComposerTarget(target);
    const assistantWorkbenchLabel = assistantWorkbenchDisplayLabel(analysisDisplayMode);
    bridgeCommands.submitAsyncBridgeCommand(
      assistantWorkbenchLabel,
      () => requestAssistantTask({
        actionId,
        sceneId: assistantSceneIdForDisplayMode(analysisDisplayMode),
        intent: resolvedIntent,
        prompt: normalizedPrompt,
        selectedNodeIds: requestSelectedNodeIds,
        selectedDiffItemIds: selectedAssistantDiffItemIds(),
        target,
        explanationGranularity: options.explanationGranularity ?? selectedExplanationGranularity,
        mode: resolvedIntent === "ASK_CODE" ? selectedQaMode : null,
      }),
      {
        onAccepted: () => {
          if (resolvedIntent === "EXPLAIN_CODE" || resolvedIntent === "DESCRIBE_CLASS") {
            onExplanationAccepted({
              actionId,
              intent: resolvedIntent,
              target,
            });
          }
          setAssistantComposer("", NEW_ASSISTANT_COMPOSER_TARGET, {
            actionId,
            draftSource: null,
          });
        },
        successFeedback: {
          level: "INFO",
          message: `已提交 ${assistantWorkbenchLabel}请求。`,
        },
      },
    );
  }

  // 当外部选择、Diff 或场景发生变化时，根据会话策略刷新输入框草稿与上下文快照，
  // 保证助手始终基于最新的可视图谱与选择信息展开工作
  useEffect(() => {
    setAssistantSessionState((current) => {
      const actionId = resolveAssistantActionIdForDisplayMode(
        current.activeActionId
          ?? current.composer?.actionId
          ?? assistantActionIdForIntent(current.activeIntent, analysisDisplayMode),
        analysisDisplayMode,
      );
      const selectedNodeIds = selectedAssistantNodeIds(analysisDisplayMode);
      const graph = assistantGraphForDisplayMode(analysisDisplayMode);
      const context = buildAssistantContextSnapshot({
        selectedNodeIds,
        selectedDiffItemIds: selectedAssistantDiffItemIds(),
        analysisDisplayMode,
        currentSceneId,
        selectedMethodSignature: selectedMethodSignature ?? null,
        scopeLabel: resolveAssistantScopeLabel(selectedNodeIds, graph.nodes, analysisDisplayMode),
      });
      return applyAssistantSessionPolicy({
        session: current,
        context,
        defaultDraft: buildDefaultAssistantPrompt(actionId, analysisDisplayMode, selectedNodeIds),
        analysisDisplayMode,
        sceneId: currentSceneId,
      });
    });
  }, [
    analysisDisplayMode,
    currentSceneId,
    selectedMethodSignature,
    selectedNodeId,
    anchorNodeId,
    selectionGroupNodeIds,
    diffTargetItemIds,
    diffItems,
    viewDocuments,
    assistantGraphForDisplayMode,
    buildDefaultAssistantPrompt,
    setAssistantSessionState,
    selectedAssistantDiffItemIds,
    selectedAssistantNodeIds,
  ]);

  return {
    assistantComposerDraft,
    assistantComposerTarget,
    selectedQaMode,
    buildDefaultAssistantPrompt,
    handleAssistantActionChange,
    handleAssistantQaModeChange,
    handleAssistantSubmit,
    primeAssistantComposer,
    selectedAssistantNodeIds,
    assistantTargetTitle,
    setAssistantComposer,
    setAssistantComposerDraft,
  };
}
