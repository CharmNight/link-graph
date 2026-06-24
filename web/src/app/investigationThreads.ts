import type {
  QaConversationSession,
  GraphPatchResult,
  InvestigationThread,
} from "./types";

/**
 * 从一次图补丁应用的返回结果中抽取调查线程列表。
 * 调查线程（InvestigationThread）代表补丁过程中浮现的、尚未化解的风险点，
 * 调用方未携带该信息时返回空数组。
 */
export function deriveInvestigationThreads(result: GraphPatchResult | null): InvestigationThread[] {
  return result?.investigationThreads ?? [];
}

/**
 * 优先以当前 QA 会话自带的调查线程为准；只有当会话未提供时才回退到补丁结果中的全局线程。
 * 这样可以保证 UI 在多轮对话中显示的是与当前会话紧密相关的风险点，而非历史快照。
 */
export function deriveConversationInvestigationThreads(
  result: GraphPatchResult | null,
  session: QaConversationSession | null | undefined,
): InvestigationThread[] {
  // 会话级别的调查线程优先，体现"当前对话最新发现"的语义
  if ((session?.investigationThreads ?? []).length > 0) {
    return session?.investigationThreads ?? [];
  }
  // 会话没有线索时退回到补丁全局结果，避免遗漏早前的风险点
  return deriveInvestigationThreads(result);
}
