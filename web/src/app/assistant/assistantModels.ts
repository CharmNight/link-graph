// 助理动作 / 意图相关的展示模型与工具函数。
// 把后端的动作枚举转换为面向用户的标签、占位文案等，
// 并按当前上下文（类图 vs 普通模式）做差异化展示。
import type { AssistantActionId, AssistantIntent, AssistantTurnKind } from "./assistantTypes";
import type { AssistantContextSnapshot } from "../types";
import {
  analysisDisplayModeFromAssistantContext,
  assistantActionDefinition,
  assistantActionIdForIntent,
  assistantActionsForDisplayMode,
  resolveAssistantActionIdForDisplayMode,
} from "./assistantActionRegistry";

/** 单个助理动作选项（用于选择器）。 */
export interface AssistantActionOption {
  /** 动作 ID。 */
  id: AssistantActionId;
  /** 对应意图。 */
  intent: AssistantIntent;
  /** 展示标签。 */
  label: string;
}

/**
 * 全局助理动作集合（不区分类图/普通模式，作为兜底列表）。
 *
 * 注意：实际可选项按上下文动态变化（见 [assistantActionOptions]）。
 * 这里的列表主要用于默认配置与文档参考。
 */
export const ASSISTANT_ACTIONS: AssistantActionOption[] = [
  { id: "EXPLAIN_FLOW", intent: "EXPLAIN_CODE", label: "解释当前节点" },
  { id: "ASK_CONTEXT", intent: "ASK_CODE", label: "追问代码" },
  { id: "CHECK_CHANGE", intent: "CHECK_CHANGE", label: "检查当前改动" },
  { id: "GENERATE_IMPLEMENTATION", intent: "GENERATE_CODE", label: "生成实现建议" },
];

/**
 * 判断上下文是否为类图模式。
 * 综合判断 analysisDisplayMode 字段与 currentSceneId（任一命中即视为类图）。
 */
export function isClassDiagramAssistantContext(context?: AssistantContextSnapshot | null): boolean {
  return context?.analysisDisplayMode === "CLASS_DIAGRAM" || context?.currentSceneId === "WORKSPACE_CLASS_DIAGRAM";
}

/**
 * 按上下文返回可用动作选项列表。
 * 不同模式（类图 vs 普通模式）支持的动作不同，由 [assistantActionsForDisplayMode] 决定。
 */
export function assistantActionOptions(context?: AssistantContextSnapshot | null): AssistantActionOption[] {
  const analysisDisplayMode = analysisDisplayModeFromAssistantContext(context);
  return assistantActionsForDisplayMode(analysisDisplayMode).map((action) => ({
    id: action.id,
    intent: action.intent,
    label: action.label,
  }));
}

/**
 * 解析输入框的意图：如果给定意图在当前可用选项中，原样返回；否则回退到首个选项。
 */
export function resolveAssistantComposerIntent(
  intent: AssistantIntent,
  context?: AssistantContextSnapshot | null,
): AssistantIntent {
  const options = assistantActionOptions(context);
  return options.some((item) => item.intent === intent) ? intent : options[0]?.intent ?? intent;
}

/** 解析输入框的动作 ID：按当前上下文校正。 */
export function resolveAssistantComposerActionId(
  actionId: AssistantActionId | null | undefined,
  context?: AssistantContextSnapshot | null,
): AssistantActionId {
  return resolveAssistantActionId(actionId, context);
}

/** 取意图对应的展示标签（按上下文区分）。 */
export function assistantIntentLabel(
  intent: AssistantIntent,
  context?: AssistantContextSnapshot | null,
): string {
  const analysisDisplayMode = analysisDisplayModeFromAssistantContext(context);
  const actionId = assistantActionIdForIntent(intent, analysisDisplayMode);
  return assistantActionDefinition(actionId, analysisDisplayMode).label;
}

/**
 * 解析动作 ID：按当前上下文校正（例如类图模式下传入 EXPLAIN_FLOW 会回退到 DESCRIBE_CLASS）。
 */
export function resolveAssistantActionId(
  actionId: AssistantActionId | null | undefined,
  context?: AssistantContextSnapshot | null,
): AssistantActionId {
  return resolveAssistantActionIdForDisplayMode(actionId, analysisDisplayModeFromAssistantContext(context));
}

/**
 * 取轮次种类的展示标签。
 *
 * 优先用 actionId 找到对应动作的标签；
 * 否则按上下文 + kind 给出默认文案（类图 vs 普通模式不同）。
 */
export function assistantTurnKindLabel(
  kind: AssistantTurnKind,
  context?: AssistantContextSnapshot | null,
  intent?: AssistantIntent | null,
  actionId?: AssistantActionId | null,
): string {
  // 优先用 actionId 取标签
  if (actionId) {
    const analysisDisplayMode = analysisDisplayModeFromAssistantContext(context);
    return assistantActionDefinition(
      resolveAssistantActionIdForDisplayMode(actionId, analysisDisplayMode),
      analysisDisplayMode,
    ).label;
  }
  // 类图模式
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
  // 普通模式默认
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

/**
 * 取输入框的占位文案。
 *
 * 按意图 + 上下文（类图 vs 普通模式）给出不同的引导文案，
 * 让用户知道"在这里应该输入什么"。
 */
export function assistantComposerPlaceholder(
  intent: AssistantIntent,
  context?: AssistantContextSnapshot | null,
): string {
  // 类图模式占位文案
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
  // 普通模式占位文案
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

/** 空上下文快照单例；用于初始化等场景。 */
export const EMPTY_ASSISTANT_CONTEXT: AssistantContextSnapshot = {
  selectedNodeIds: [],
  selectedDiffItemIds: [],
  analysisDisplayMode: null,
  currentSceneId: null,
  selectedMethodSignature: null,
  scopeLabel: "",
};
