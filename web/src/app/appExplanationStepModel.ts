import type { AssistantIntent } from "./assistant/assistantTypes";
import type {
  AnalysisDisplayMode,
  GraphBeautificationResult,
  GraphBeautificationStep,
} from "./types";

/**
 * 当某一步骤没有提供自定义追问、也没有预生成的追问时使用的默认文案。
 * 文案刻意强调"输入/条件/输出"，引导用户继续深入这一步骤的关键决策。
 */
export const DEFAULT_EXPLANATION_FOLLOW_UP_QUESTION = "请继续解释这一步的关键输入、条件和输出。";

/**
 * 在图美化（分步讲解）结果中按 ID 定位单个步骤。
 * 美化结果可能为空（未生成），此时返回 null 表示无此步骤。
 */
export function findExplanationStep(
  result: GraphBeautificationResult | null | undefined,
  stepId: string,
): GraphBeautificationStep | null {
  return result?.steps.find((step) => step.stepId === stepId) ?? null;
}

/**
 * 解析一个步骤对应的原始（未投影）节点 ID，用于把讲解步骤关联到底层图节点。
 * 优先级：步骤的主节点 > 步骤证据引用的第一个节点 ID。
 * 都没有时返回 null，表示此步骤只是抽象描述、无对应节点。
 */
export function resolveExplanationStepRawNodeId(
  step: GraphBeautificationStep | null | undefined,
): string | null {
  return step?.primaryNodeId
    ?? step?.evidence.flatMap((finding) => finding.references).find((reference) => reference.nodeId)?.nodeId
    ?? null;
}

/**
 * 决定在某一步骤后给用户提示的追问文案。
 * 优先使用调用方提供的自定义问题，其次用步骤预生成的首个追问，
 * 最后回落到全局默认文案，保证 UI 不会出现空追问。
 */
export function resolveExplanationFollowUpQuestion(
  step: GraphBeautificationStep,
  customQuestion?: string,
): string {
  return customQuestion?.trim()
    || step.followUpQuestions[0]
    || DEFAULT_EXPLANATION_FOLLOW_UP_QUESTION;
}

/**
 * 决定"重新运行解释"时应使用哪种助理意图。
 * 在类图视图且当前正在描述类时保持 DESCRIBE_CLASS，避免被错误地降级为通用解释；
 * 其余情况统一使用 EXPLAIN_CODE 重新触发代码解释流程。
 */
export function resolveExplanationRerunIntent(
  analysisDisplayMode: AnalysisDisplayMode,
  activeIntent: AssistantIntent,
): AssistantIntent {
  return analysisDisplayMode === "CLASS_DIAGRAM" && activeIntent === "DESCRIBE_CLASS"
    ? "DESCRIBE_CLASS"
    : "EXPLAIN_CODE";
}
