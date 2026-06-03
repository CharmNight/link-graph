import type {
  QaConversationSession,
  GraphPatchResult,
  InvestigationThread,
} from "./types";

export function deriveInvestigationThreads(result: GraphPatchResult | null): InvestigationThread[] {
  return result?.investigationThreads ?? [];
}

export function deriveConversationInvestigationThreads(
  result: GraphPatchResult | null,
  session: QaConversationSession | null | undefined,
): InvestigationThread[] {
  if ((session?.investigationThreads ?? []).length > 0) {
    return session?.investigationThreads ?? [];
  }
  return deriveInvestigationThreads(result);
}
