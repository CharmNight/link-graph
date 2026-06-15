import type {
  AnalysisDisplayMode,
  AssistantActionId,
  AssistantContextSnapshot,
  AssistantIntent,
} from "../types";

export interface AssistantActionDefinition {
  id: AssistantActionId;
  intent: AssistantIntent;
  label: string;
}

const EXPLAIN_FLOW_ACTION: AssistantActionDefinition = {
  id: "EXPLAIN_FLOW",
  intent: "EXPLAIN_CODE",
  label: "解释当前节点",
};

const EXPLAIN_STRUCTURE_ACTION: AssistantActionDefinition = {
  id: "EXPLAIN_STRUCTURE",
  intent: "EXPLAIN_CODE",
  label: "解释结构",
};

const CLASS_RELATION_ACTION: AssistantActionDefinition = {
  id: "EXPLAIN_STRUCTURE",
  intent: "EXPLAIN_CODE",
  label: "解释关系",
};

const DESCRIBE_CLASS_ACTION: AssistantActionDefinition = {
  id: "DESCRIBE_CLASS",
  intent: "DESCRIBE_CLASS",
  label: "介绍这个类",
};

const ASK_CODE_ACTION: AssistantActionDefinition = {
  id: "ASK_CONTEXT",
  intent: "ASK_CODE",
  label: "追问代码",
};

const ASK_CLASS_ACTION: AssistantActionDefinition = {
  id: "ASK_CONTEXT",
  intent: "ASK_CODE",
  label: "追问类图",
};

const GENERATE_ACTION: AssistantActionDefinition = {
  id: "GENERATE_IMPLEMENTATION",
  intent: "GENERATE_CODE",
  label: "生成实现建议",
};

const CHECK_ACTION: AssistantActionDefinition = {
  id: "CHECK_CHANGE",
  intent: "CHECK_CHANGE",
  label: "检查当前改动",
};

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
  CLASS_DIAGRAM: [DESCRIBE_CLASS_ACTION, CLASS_RELATION_ACTION, ASK_CLASS_ACTION, GENERATE_ACTION],
  REVIEW_GRAPH: [CHECK_ACTION, ASK_CODE_ACTION, { ...EXPLAIN_STRUCTURE_ACTION, label: "解释变更影响" }, GENERATE_ACTION],
};

export function isAnalysisDisplayMode(value?: string | null): value is AnalysisDisplayMode {
  return Boolean(value && Object.prototype.hasOwnProperty.call(ACTIONS_BY_MODE, value));
}

export function analysisDisplayModeFromAssistantContext(
  context?: AssistantContextSnapshot | null,
  fallback: AnalysisDisplayMode = "FLOWCHART",
): AnalysisDisplayMode {
  return isAnalysisDisplayMode(context?.analysisDisplayMode) ? context.analysisDisplayMode : fallback;
}

export function assistantActionsForDisplayMode(
  analysisDisplayMode: AnalysisDisplayMode,
): AssistantActionDefinition[] {
  return ACTIONS_BY_MODE[analysisDisplayMode];
}

export function defaultAssistantActionIdForDisplayMode(
  analysisDisplayMode: AnalysisDisplayMode,
): AssistantActionId {
  return assistantActionsForDisplayMode(analysisDisplayMode)[0].id;
}

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

export function assistantActionDefinition(
  actionId: AssistantActionId,
  analysisDisplayMode: AnalysisDisplayMode,
): AssistantActionDefinition {
  return assistantActionsForDisplayMode(analysisDisplayMode).find((action) => action.id === actionId)
    ?? assistantActionsForDisplayMode(analysisDisplayMode)[0];
}

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

export function assistantActionLabel(
  actionId: AssistantActionId | null | undefined,
  context?: AssistantContextSnapshot | null,
): string {
  const analysisDisplayMode = analysisDisplayModeFromAssistantContext(context);
  const resolvedActionId = resolveAssistantActionIdForDisplayMode(actionId, analysisDisplayMode);
  return assistantActionDefinition(resolvedActionId, analysisDisplayMode).label;
}
