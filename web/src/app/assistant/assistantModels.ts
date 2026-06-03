import type { AssistantContextSnapshot, AssistantIntent, AssistantTurnKind } from "../types";

export const ASSISTANT_INTENTS: Array<{ id: AssistantIntent; label: string }> = [
  { id: "EXPLAIN_CODE", label: "解释代码" },
  { id: "ASK_CODE", label: "代码问答" },
  { id: "GENERATE_CODE", label: "生成代码" },
  { id: "CHECK_CHANGE", label: "检查改动" },
];

export function assistantIntentLabel(intent: AssistantIntent): string {
  return ASSISTANT_INTENTS.find((item) => item.id === intent)?.label ?? intent;
}

export function assistantTurnKindLabel(kind: AssistantTurnKind): string {
  switch (kind) {
    case "EXPLANATION":
      return "解释代码";
    case "QA":
      return "代码问答";
    case "GENERATION_PLAN":
      return "生成代码";
    case "CODE_DRAFT":
      return "代码草稿";
    case "CHECK_RESULT":
      return "检查改动";
  }
}

export function assistantComposerPlaceholder(intent: AssistantIntent): string {
  switch (intent) {
    case "EXPLAIN_CODE":
      return "描述你想讲解的代码范围或关注点";
    case "ASK_CODE":
      return "输入关于当前代码或图节点的问题";
    case "GENERATE_CODE":
      return "描述要生成或改造的代码目标";
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
