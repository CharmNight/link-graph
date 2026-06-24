// 助理提示词默认值。
// 为不同动作（解释/问答/生成/介绍类）和不同上下文（类图/普通模式 + 节点选择数）
// 生成合适的默认提示词，让用户在不主动输入时也能得到合理的结果。
import type { AssistantActionId } from "./assistantTypes";
import type { AnalysisDisplayMode } from "../types";

/** 助理输入框的默认目标：新任务。 */
export const NEW_ASSISTANT_COMPOSER_TARGET = { kind: "NewTask" } as const;

/** "生成实现建议"的默认提示词。 */
export const DEFAULT_GENERATION_PLAN_PROMPT = "请基于当前草稿和图谱上下文生成实现建议。";

/**
 * 构造"追问代码"的默认问题。
 *
 * 按节点选择数与展示模式（类图 vs 普通模式）给出不同的引导问题：
 * - 类图：聚焦字段关联、构造参数、类型依赖；
 * - 普通模式：聚焦业务链路、异常分支、资源依赖。
 */
export function buildDefaultQaQuestion(
  targetNodeIds: string[],
  targetTitle: string | null,
  analysisDisplayMode: AnalysisDisplayMode,
): string {
  if (analysisDisplayMode === "CLASS_DIAGRAM") {
    // 类图模式
    if (targetNodeIds.length === 0) {
      return "请围绕当前类图的类、字段、构造参数、返回值和类型依赖关系进行问答，重点确认结构关系是否完整、是否存在遗漏的字段关联或类型依赖。";
    }
    if (targetNodeIds.length === 1) {
      return `请围绕类图节点“${targetTitle ?? targetNodeIds[0]}”及其字段、构造参数、返回值和类型依赖关系进行问答，重点确认结构关系是否完整、是否存在遗漏的字段关联或类型依赖。`;
    }
    return `请围绕当前选中的 ${targetNodeIds.length} 个类图节点及其字段、构造参数、返回值和类型依赖关系进行问答，重点确认结构关系是否完整、是否存在遗漏的字段关联或类型依赖。`;
  }
  // 普通模式
  if (targetNodeIds.length === 0) {
    return "请围绕当前整张链路图进行问答，指出可能遗漏的业务链路、异常分支、资源依赖和数据约束。";
  }
  if (targetNodeIds.length === 1) {
    return `请围绕节点“${targetTitle ?? targetNodeIds[0]}”及其直接关联链路进行问答，指出可能遗漏的业务链路、异常分支、资源依赖和数据约束。`;
  }
  return `请围绕当前选中的 ${targetNodeIds.length} 个节点及其关联链路进行问答，指出可能遗漏的业务链路、异常分支、资源依赖和数据约束。`;
}

/**
 * 构造"讲解"动作的默认提示词。
 *
 * 类图模式：聚焦"结构关系"，明确不要介绍类职责、不要按方法调用讲解；
 * 普通模式：聚焦节点作用、上下游、关键分支。
 */
export function buildDefaultExplanationPrompt(
  targetTitle: string | null,
  analysisDisplayMode: AnalysisDisplayMode,
): string {
  if (analysisDisplayMode === "CLASS_DIAGRAM") {
    return targetTitle
      ? `请解释类图节点“${targetTitle}”的字段关联、构造参数、返回值、参数和类型依赖关系；只解释图上的结构关系，不要介绍类职责，也不要按方法调用顺序讲解。`
      : "请解释当前类图中的类、字段、构造参数、返回值、参数和类型依赖关系；只解释图上的结构关系，不要介绍类职责，也不要按方法调用顺序讲解。";
  }
  return targetTitle
    ? `请讲解节点“${targetTitle}”在当前代码链路中的作用、上下游关系和关键分支。`
    : "请讲解当前代码链路的入口、主路径、关键分支、外部依赖和潜在副作用。";
}

/**
 * 构造"介绍这个类"动作的默认提示词（类图专用）。
 *
 * 介绍类职责、核心字段、协作关系、典型使用场景，
 * 并要求给出建议下钻位置便于用户继续探索。
 */
export function buildDefaultClassDescriptionPrompt(targetTitle: string | null): string {
  return targetTitle
    ? `请介绍类图节点“${targetTitle}”：说明这个类的职责、核心字段/构造依赖、对外协作关系、典型使用场景，以及建议继续下钻的位置。`
    : "请介绍当前类图中的核心类：说明类职责、核心字段/构造依赖、对外协作关系、典型使用场景，以及建议继续下钻的位置。";
}

/**
 * 按动作 ID 派发到具体的默认提示词构造函数。
 *
 * @return 默认提示词；未识别动作返回空字符串
 */
export function buildDefaultAssistantPrompt(args: {
  actionId: AssistantActionId;
  analysisDisplayMode: AnalysisDisplayMode;
  targetNodeIds: string[];
  targetTitle: string | null;
}): string {
  if (args.actionId === "DESCRIBE_CLASS") {
    return buildDefaultClassDescriptionPrompt(args.targetTitle);
  }
  if (args.actionId === "EXPLAIN_FLOW" || args.actionId === "EXPLAIN_STRUCTURE") {
    return buildDefaultExplanationPrompt(args.targetTitle, args.analysisDisplayMode);
  }
  if (args.actionId === "ASK_CONTEXT") {
    return buildDefaultQaQuestion(args.targetNodeIds, args.targetTitle, args.analysisDisplayMode);
  }
  if (args.actionId === "GENERATE_IMPLEMENTATION") {
    return DEFAULT_GENERATION_PLAN_PROMPT;
  }
  // 其他动作（如 CHECK_CHANGE）暂无默认提示词
  return "";
}

/**
 * 取助理工作台的展示标签。
 * 类图模式 → "AI 类图工作台"；其他 → "AI 代码工作台"。
 */
export function assistantWorkbenchDisplayLabel(analysisDisplayMode: AnalysisDisplayMode): string {
  return analysisDisplayMode === "CLASS_DIAGRAM" ? "AI 类图工作台" : "AI 代码工作台";
}
