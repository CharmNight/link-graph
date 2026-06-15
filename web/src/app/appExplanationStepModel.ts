import type {
  AnalysisDisplayMode,
  AssistantIntent,
  GraphBeautificationResult,
  GraphBeautificationStep,
} from "./types";

export const DEFAULT_EXPLANATION_FOLLOW_UP_QUESTION = "请继续解释这一步的关键输入、条件和输出。";

export function findExplanationStep(
  result: GraphBeautificationResult | null | undefined,
  stepId: string,
): GraphBeautificationStep | null {
  return result?.steps.find((step) => step.stepId === stepId) ?? null;
}

export function resolveExplanationStepRawNodeId(
  step: GraphBeautificationStep | null | undefined,
): string | null {
  return step?.primaryNodeId
    ?? step?.evidence.flatMap((finding) => finding.references).find((reference) => reference.nodeId)?.nodeId
    ?? null;
}

export function resolveExplanationFollowUpQuestion(
  step: GraphBeautificationStep,
  customQuestion?: string,
): string {
  return customQuestion?.trim()
    || step.followUpQuestions[0]
    || DEFAULT_EXPLANATION_FOLLOW_UP_QUESTION;
}

export function resolveExplanationRerunIntent(
  analysisDisplayMode: AnalysisDisplayMode,
  activeIntent: AssistantIntent,
): AssistantIntent {
  return analysisDisplayMode === "CLASS_DIAGRAM" && activeIntent === "DESCRIBE_CLASS"
    ? "DESCRIBE_CLASS"
    : "EXPLAIN_CODE";
}
