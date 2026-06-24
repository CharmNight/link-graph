// 助理动作注册中心。
// 维护"动作 → 意图 → 标签"的映射，按展示模式（类图/普通模式等）给出可用动作集合。
// 不同模式支持的动作不同（例如类图模式没有 CHECK_CHANGE，普通模式没有 DESCRIBE_CLASS）。
import type { AssistantActionId, AssistantIntent } from "./assistantTypes";
import type { AnalysisDisplayMode, AssistantContextSnapshot } from "../types";

/** 助理动作定义：ID + 对应意图 + 展示标签。 */
export interface AssistantActionDefinition {
  id: AssistantActionId;
  intent: AssistantIntent;
  label: string;
}

// ===== 各动作的具体定义（同一动作在不同模式下可能有不同 label） =====

/** 解释当前节点（事实图/流程图模式）。 */
const EXPLAIN_FLOW_ACTION: AssistantActionDefinition = {
  id: "EXPLAIN_FLOW",
  intent: "EXPLAIN_CODE",
  label: "解释当前节点",
};

/** 解释结构（资源关系/架构图模式的默认解释动作）。 */
const EXPLAIN_STRUCTURE_ACTION: AssistantActionDefinition = {
  id: "EXPLAIN_STRUCTURE",
  intent: "EXPLAIN_CODE",
  label: "解释结构",
};

/** 类图模式下的"解释关系"动作（与 EXPLAIN_STRUCTURE 同 ID 但不同 label）。 */
const CLASS_RELATION_ACTION: AssistantActionDefinition = {
  id: "EXPLAIN_STRUCTURE",
  intent: "EXPLAIN_CODE",
  label: "解释关系",
};

/** 类图模式下的"介绍这个类"动作。 */
const DESCRIBE_CLASS_ACTION: AssistantActionDefinition = {
  id: "DESCRIBE_CLASS",
  intent: "DESCRIBE_CLASS",
  label: "介绍这个类",
};

/** 普通模式下的"追问代码"动作。 */
const ASK_CODE_ACTION: AssistantActionDefinition = {
  id: "ASK_CONTEXT",
  intent: "ASK_CODE",
  label: "追问代码",
};

/** 类图模式下的"追问类图"动作。 */
const ASK_CLASS_ACTION: AssistantActionDefinition = {
  id: "ASK_CONTEXT",
  intent: "ASK_CODE",
  label: "追问类图",
};

/** "生成实现建议"动作。 */
const GENERATE_ACTION: AssistantActionDefinition = {
  id: "GENERATE_IMPLEMENTATION",
  intent: "GENERATE_CODE",
  label: "生成实现建议",
};

/** "检查当前改动"动作。 */
const CHECK_ACTION: AssistantActionDefinition = {
  id: "CHECK_CHANGE",
  intent: "CHECK_CHANGE",
  label: "检查当前改动",
};

/**
 * 按展示模式分组的可用动作集合。
 *
 * 每种模式有自己的动作组合；标签可能因模式不同而变化
 * （例如资源关系模式下的 EXPLAIN_STRUCTURE 标签为"解释资源关系"）。
 */
const ACTIONS_BY_MODE: Record<AnalysisDisplayMode, AssistantActionDefinition[]> = {
  FACT_GRAPH: [EXPLAIN_FLOW_ACTION, ASK_CODE_ACTION, CHECK_ACTION, GENERATE_ACTION],
  FLOWCHART: [EXPLAIN_FLOW_ACTION, ASK_CODE_ACTION, CHECK_ACTION, GENERATE_ACTION],
  RESOURCE_RELATION_VIEW: [
    { ...EXPLAIN_STRUCTURE_ACTION, label: "解释资源关系" },
    ASK_CODE_ACTION,
    CHECK_ACTION,
    GENERATE_ACTION,
  ],
  ARCHITECTURE_GRAPH: [
    { ...EXPLAIN_STRUCTURE_ACTION, label: "解释项目结构" },
    ASK_CODE_ACTION,
    CHECK_ACTION,
    GENERATE_ACTION,
  ],
  // 类图模式：介绍类 + 解释关系 + 追问类图 + 生成（无 CHECK）
  CLASS_DIAGRAM: [DESCRIBE_CLASS_ACTION, CLASS_RELATION_ACTION, ASK_CLASS_ACTION, GENERATE_ACTION],
  // 审查图：检查 + 追问 + 解释变更影响 + 生成
  REVIEW_GRAPH: [CHECK_ACTION, ASK_CODE_ACTION, { ...EXPLAIN_STRUCTURE_ACTION, label: "解释变更影响" }, GENERATE_ACTION],
};

/** 类型守卫：判断字符串是否为合法的展示模式。 */
export function isAnalysisDisplayMode(value?: string | null): value is AnalysisDisplayMode {
  return Boolean(value && Object.prototype.hasOwnProperty.call(ACTIONS_BY_MODE, value));
}

/**
 * 从助理上下文中解析展示模式。
 * 不合法时回退到 fallback（默认 FLOWCHART）。
 */
export function analysisDisplayModeFromAssistantContext(
  context?: AssistantContextSnapshot | null,
  fallback: AnalysisDisplayMode = "FLOWCHART",
): AnalysisDisplayMode {
  return isAnalysisDisplayMode(context?.analysisDisplayMode) ? context.analysisDisplayMode : fallback;
}

/** 取展示模式对应的可用动作列表。 */
export function assistantActionsForDisplayMode(
  analysisDisplayMode: AnalysisDisplayMode,
): AssistantActionDefinition[] {
  return ACTIONS_BY_MODE[analysisDisplayMode];
}

/** 取展示模式的默认（首个）动作 ID。 */
export function defaultAssistantActionIdForDisplayMode(
  analysisDisplayMode: AnalysisDisplayMode,
): AssistantActionId {
  return assistantActionsForDisplayMode(analysisDisplayMode)[0].id;
}

/**
 * 校正动作 ID：若当前模式支持该动作则原样返回，否则回退到默认动作。
 *
 * 用于用户切换模式后，旧的动作可能不再适用时做自动校正。
 */
export function resolveAssistantActionIdForDisplayMode(
  actionId: AssistantActionId | null | undefined,
  analysisDisplayMode: AnalysisDisplayMode,
): AssistantActionId {
  const actions = assistantActionsForDisplayMode(analysisDisplayMode);
  if (actionId && actions.some((action) => action.id === actionId)) {
    return actionId;
  }
  return defaultAssistantActionIdForDisplayMode(analysisDisplayMode);
}

/**
 * 取指定动作的定义。
 * 不存在时回退到该模式的默认动作（首个）。
 */
export function assistantActionDefinition(
  actionId: AssistantActionId,
  analysisDisplayMode: AnalysisDisplayMode,
): AssistantActionDefinition {
  return assistantActionsForDisplayMode(analysisDisplayMode).find((action) => action.id === actionId)
    ?? assistantActionsForDisplayMode(analysisDisplayMode)[0];
}

/**
 * 按意图推断动作 ID。
 *
 * 不同模式下的同一意图对应不同动作：
 * - DESCRIBE_CLASS → 始终 DESCRIBE_CLASS（但仅在类图模式可用）；
 * - EXPLAIN_CODE → FACT/FLOWCHART 用 EXPLAIN_FLOW，其他用 EXPLAIN_STRUCTURE；
 * - ASK_CODE → ASK_CONTEXT；
 * - GENERATE_CODE → GENERATE_IMPLEMENTATION；
 * - CHECK_CHANGE → CHECK_CHANGE（但仅在部分模式可用）。
 */
export function assistantActionIdForIntent(
  intent: AssistantIntent,
  analysisDisplayMode: AnalysisDisplayMode,
): AssistantActionId {
  switch (intent) {
    case "DESCRIBE_CLASS":
      return resolveAssistantActionIdForDisplayMode("DESCRIBE_CLASS", analysisDisplayMode);
    case "EXPLAIN_CODE":
      return analysisDisplayMode === "FACT_GRAPH" || analysisDisplayMode === "FLOWCHART"
        ? "EXPLAIN_FLOW"
        : "EXPLAIN_STRUCTURE";
    case "ASK_CODE":
      return resolveAssistantActionIdForDisplayMode("ASK_CONTEXT", analysisDisplayMode);
    case "GENERATE_CODE":
      return resolveAssistantActionIdForDisplayMode("GENERATE_IMPLEMENTATION", analysisDisplayMode);
    case "CHECK_CHANGE":
      return resolveAssistantActionIdForDisplayMode("CHECK_CHANGE", analysisDisplayMode);
  }
}

/**
 * 取动作的展示标签（按上下文模式校正 ID 后）。
 */
export function assistantActionLabel(
  actionId: AssistantActionId | null | undefined,
  context?: AssistantContextSnapshot | null,
): string {
  const analysisDisplayMode = analysisDisplayModeFromAssistantContext(context);
  const resolvedActionId = resolveAssistantActionIdForDisplayMode(actionId, analysisDisplayMode);
  return assistantActionDefinition(resolvedActionId, analysisDisplayMode).label;
}
