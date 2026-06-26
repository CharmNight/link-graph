import {
  buildDefaultClassDescriptionPrompt,
  buildDefaultExplanationPrompt,
  NEW_ASSISTANT_COMPOSER_TARGET,
} from "../assistant/assistantPromptDefaults";
import type {
  AssistantActionId,
  AssistantIntent,
} from "../assistant/assistantTypes";
import type { WorkflowStage } from "../workflow/workflowStage";
import {
  findExplanationStep,
  resolveExplanationFollowUpQuestion,
  resolveExplanationRerunIntent,
  resolveExplanationStepRawNodeId as resolveRawNodeIdForExplanationStep,
} from "../appExplanationStepModel";
import { resolveDisplayedNodeId } from "../appGraphSupport";
import type {
  AnalysisDisplayMode,
  AssistantComposerTarget,
  GraphBeautificationResult,
  LinkGraphSceneId,
  LinkGraphNode,
  QaMode,
  StepGranularity,
} from "../types";

/**
 * 讲解步骤交互动作集合（P2-1 前端架构拆分）。
 *
 * 抽取自 App.tsx，封装用户在 AI 工作台中对讲解步骤发起的全部交互：
 * - 选中步骤并联动定位
 * - 仅定位 / 仅查看详情
 * - 切换粒度并重新发起讲解
 * - 发起步骤追问
 *
 * 这些动作共享同一组依赖（graphBeautificationResult / nodes / 助手输入框 / 视图聚焦），
 * 集中在此 hook 中既减少了 App.tsx 的行数，也明确了「讲解步骤交互」的职责边界。
 */
export interface ExplanationStepActionsDeps {
  graphBeautificationResult: GraphBeautificationResult | null;
  nodes: LinkGraphNode[];
  selectedNodeId: string | null;
  selectedNode: LinkGraphNode | null;
  analysisDisplayMode: AnalysisDisplayMode;
  activeIntent: AssistantIntent;
  setSelectedExplanationStepId: (stepId: string | null) => void;
  setSelectedExplanationGranularity: (granularity: StepGranularity) => void;
  selectExplanationTargetNode: (nodeId: string, options?: { focusViewport?: boolean }) => void;
  handleInspectNode: (nodeId: string) => void;
  setOperationFeedback: (feedback: { level: "INFO" | "WARNING" | "ERROR"; message: string }) => void;
  primeAssistantComposer: (
    actionId: AssistantIntent,
    draft: string,
    options: {
      target?: AssistantComposerTarget;
      stage?: WorkflowStage;
      actionId?: AssistantActionId;
      draftSource?: "AUTO" | "USER" | null;
      qaMode?: QaMode | null;
    },
  ) => void;
  setAssistantComposer: (
    draft: string,
    target?: AssistantComposerTarget,
    options?: {
      draftSource?: "AUTO" | "USER" | null;
      actionId?: AssistantActionId | null;
      sceneId?: LinkGraphSceneId | null;
      qaMode?: QaMode | null;
    },
  ) => void;
  handleAssistantSubmit: (
    intent: AssistantIntent,
    prompt: string,
    options?: {
      explanationGranularity?: StepGranularity;
      selectedNodeIds?: string[];
      target?: AssistantComposerTarget;
      actionId?: AssistantActionId;
    },
  ) => void;
  assistantComposerDraft: string;
  selectedAssistantNodeIds: () => string[];
  assistantTargetTitle: (nodeIds: string[]) => string | null;
}

/** 解析步骤原始节点 ID（不含显示层映射） */
function resolveExplanationStepRawNodeId(
  result: GraphBeautificationResult | null,
  stepId: string,
): string | null {
  return resolveRawNodeIdForExplanationStep(findExplanationStep(result, stepId));
}

/** 解析步骤目标节点 ID（经过显示层映射，找不到返回 null） */
function resolveExplanationStepTargetNodeId(
  result: GraphBeautificationResult | null,
  stepId: string,
  nodes: LinkGraphNode[],
): string | null {
  const rawNodeId = resolveExplanationStepRawNodeId(result, stepId);
  return resolveDisplayedNodeId(rawNodeId, nodes);
}

/** 解析步骤聚焦用节点 ID（优先显示层映射，回退到原始 ID） */
function resolveExplanationStepFocusNodeId(
  result: GraphBeautificationResult | null,
  stepId: string,
  nodes: LinkGraphNode[],
): string | null {
  const rawNodeId = resolveExplanationStepRawNodeId(result, stepId);
  if (!rawNodeId) {
    return null;
  }
  return resolveDisplayedNodeId(rawNodeId, nodes) ?? rawNodeId;
}

export function useExplanationStepActions(deps: ExplanationStepActionsDeps) {
  const {
    graphBeautificationResult,
    nodes,
    selectedNodeId,
    selectedNode,
    analysisDisplayMode,
    activeIntent,
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
  } = deps;

  /** 从助手结果中选中某个讲解步骤，并联动定位到对应节点 */
  function handleSelectExplanationStepFromAssistant(stepId: string) {
    setSelectedExplanationStepId(stepId);
    const targetNodeId = resolveExplanationStepTargetNodeId(graphBeautificationResult, stepId, nodes);
    if (targetNodeId) {
      selectExplanationTargetNode(targetNodeId);
    }
  }

  /** 从助手结果中定位某步骤的图节点，并触发视图聚焦 */
  function handleLocateExplanationStepNodeFromAssistant(stepId: string) {
    setSelectedExplanationStepId(stepId);
    const targetNodeId = resolveExplanationStepTargetNodeId(graphBeautificationResult, stepId, nodes);
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

  /** 从助手结果中查看某步骤节点的详情面板 */
  function handleInspectExplanationStepNodeFromAssistant(stepId: string) {
    setSelectedExplanationStepId(stepId);
    const targetNodeId = resolveExplanationStepTargetNodeId(graphBeautificationResult, stepId, nodes);
    if (!targetNodeId) {
      setOperationFeedback({
        level: "WARNING",
        message: "当前步骤没有可编辑的图节点。",
      });
      return;
    }
    handleInspectNode(targetNodeId);
  }

  /** 在助手侧切换讲解粒度并按新粒度重新发起讲解请求 */
  function handleChangeExplanationGranularityFromAssistant(granularity: StepGranularity) {
    setSelectedExplanationGranularity(granularity);
    setAssistantComposer(assistantComposerDraft, NEW_ASSISTANT_COMPOSER_TARGET);
    const rerunIntent = resolveExplanationRerunIntent(analysisDisplayMode, activeIntent);
    const targetNodeIds = selectedAssistantNodeIds();
    const targetTitle = assistantTargetTitle(targetNodeIds) ?? selectedNode?.title ?? null;
    const prompt = rerunIntent === "DESCRIBE_CLASS"
      ? buildDefaultClassDescriptionPrompt(targetTitle)
      : buildDefaultExplanationPrompt(targetTitle, analysisDisplayMode);
    handleAssistantSubmit(rerunIntent, prompt, {
      explanationGranularity: granularity,
    });
  }

  /** 针对某个讲解步骤发起追问：定位步骤节点并预填问答输入框 */
  function handleFollowUpExplanationStepFromAssistant(stepId: string, customQuestion?: string) {
    const step = findExplanationStep(graphBeautificationResult, stepId);
    if (!step) {
      return;
    }
    const question = resolveExplanationFollowUpQuestion(step, customQuestion);
    const focusNodeId = resolveExplanationStepFocusNodeId(graphBeautificationResult, stepId, nodes)
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

  return {
    handleSelectExplanationStepFromAssistant,
    handleLocateExplanationStepNodeFromAssistant,
    handleInspectExplanationStepNodeFromAssistant,
    handleChangeExplanationGranularityFromAssistant,
    handleFollowUpExplanationStepFromAssistant,
  };
}
