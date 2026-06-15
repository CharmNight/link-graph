import type { AssistantActionId, AssistantContextSnapshot, AssistantIntent, AssistantTurnKind } from "../types";
import {
  analysisDisplayModeFromAssistantContext,
  assistantActionDefinition,
  assistantActionIdForIntent,
  assistantActionsForDisplayMode,
  resolveAssistantActionIdForDisplayMode,
} from "./assistantActionRegistry";

export interface AssistantActionOption {
  id: AssistantActionId;
  intent: AssistantIntent;
  label: string;
}

export const ASSISTANT_ACTIONS: AssistantActionOption[] = [
  { id: "EXPLAIN_FLOW", intent: "EXPLAIN_CODE", label: "解释当前节点" },
  { id: "ASK_CONTEXT", intent: "ASK_CODE", label: "追问代码" },
  { id: "CHECK_CHANGE", intent: "CHECK_CHANGE", label: "检查当前改动" },
  { id: "GENERATE_IMPLEMENTATION", intent: "GENERATE_CODE", label: "生成实现建议" },
];

export function isClassDiagramAssistantContext(context?: AssistantContextSnapshot | null): boolean {
  return context?.analysisDisplayMode === "CLASS_DIAGRAM" || context?.currentSceneId === "WORKSPACE_CLASS_DIAGRAM";
}

export function assistantActionOptions(context?: AssistantContextSnapshot | null): AssistantActionOption[] {
  const analysisDisplayMode = analysisDisplayModeFromAssistantContext(context);
  return assistantActionsForDisplayMode(analysisDisplayMode).map((action) => ({
    id: action.id,
    intent: action.intent,
    label: action.label,
  }));
}

export function resolveAssistantComposerIntent(
  intent: AssistantIntent,
  context?: AssistantContextSnapshot | null,
): AssistantIntent {
  const options = assistantActionOptions(context);
  return options.some((item) => item.intent === intent) ? intent : options[0]?.intent ?? intent;
}

export function resolveAssistantComposerActionId(
  actionId: AssistantActionId | null | undefined,
  context?: AssistantContextSnapshot | null,
): AssistantActionId {
  return resolveAssistantActionId(actionId, context);
}

export function assistantIntentLabel(
  intent: AssistantIntent,
  context?: AssistantContextSnapshot | null,
): string {
  const analysisDisplayMode = analysisDisplayModeFromAssistantContext(context);
  const actionId = assistantActionIdForIntent(intent, analysisDisplayMode);
  return assistantActionDefinition(actionId, analysisDisplayMode).label;
}

export function resolveAssistantActionId(
  actionId: AssistantActionId | null | undefined,
  context?: AssistantContextSnapshot | null,
): AssistantActionId {
  return resolveAssistantActionIdForDisplayMode(actionId, analysisDisplayModeFromAssistantContext(context));
}

export function assistantTurnKindLabel(
  kind: AssistantTurnKind,
  context?: AssistantContextSnapshot | null,
  intent?: AssistantIntent | null,
  actionId?: AssistantActionId | null,
): string {
  if (actionId) {
    const analysisDisplayMode = analysisDisplayModeFromAssistantContext(context);
    return assistantActionDefinition(
      resolveAssistantActionIdForDisplayMode(actionId, analysisDisplayMode),
      analysisDisplayMode,
    ).label;
  }
  if (isClassDiagramAssistantContext(context)) {
    switch (kind) {
      case "EXPLANATION":
        return intent === "DESCRIBE_CLASS" ? "介绍这个类" : "解释关系";
      case "QA":
        return "追问类图";
      case "GENERATION_PLAN":
        return "生成实现建议";
      case "CODE_DRAFT":
        return "代码草稿";
      case "CHECK_RESULT":
        return "检查改动";
    }
  }
  switch (kind) {
    case "EXPLANATION":
      return "解释当前节点";
    case "QA":
      return "追问代码";
    case "GENERATION_PLAN":
      return "生成实现建议";
    case "CODE_DRAFT":
      return "代码草稿";
    case "CHECK_RESULT":
      return "检查当前改动";
  }
}

export function assistantComposerPlaceholder(
  intent: AssistantIntent,
  context?: AssistantContextSnapshot | null,
): string {
  if (isClassDiagramAssistantContext(context)) {
    switch (intent) {
      case "DESCRIBE_CLASS":
        return "描述要介绍的类职责、字段或协作关系";
      case "EXPLAIN_CODE":
        return "描述要解释的类、字段或类型关系";
      case "ASK_CODE":
        return "输入关于当前类、字段、构造参数或类型关系的问题";
      case "GENERATE_CODE":
        return "描述要生成或调整的类结构目标";
      case "CHECK_CHANGE":
        return "描述要检查的结构关系、影响或测试范围";
    }
  }
  switch (intent) {
    case "DESCRIBE_CLASS":
      return "描述你想介绍的类职责、字段或协作关系";
    case "EXPLAIN_CODE":
      return "描述你想讲解的代码范围或关注点";
    case "ASK_CODE":
      return "输入关于当前代码或图节点的问题";
    case "GENERATE_CODE":
      return "描述要生成或改造的实现目标";
    case "CHECK_CHANGE":
      return "描述要检查的改动、风险或测试范围";
  }
}

export const EMPTY_ASSISTANT_CONTEXT: AssistantContextSnapshot = {
  selectedNodeIds: [],
  selectedDiffItemIds: [],
  analysisDisplayMode: null,
  currentSceneId: null,
  selectedMethodSignature: null,
  scopeLabel: "",
};
