import type {
  AssistantSessionState,
  AssistantTurn,
  AssistantTurnKind,
  AssistantTurnRef,
  GeneratedCodeDraft,
  GenerationPlan,
  GenerationPlanDiscussionSession,
  GraphBeautificationResult,
  GraphPatchResult,
} from "../types";
import { EMPTY_ASSISTANT_CONTEXT } from "./assistantModels";

interface BuildAssistantTurnsArgs {
  assistantSessionState: AssistantSessionState;
  qaResult: GraphPatchResult | null;
  graphBeautificationResult: GraphBeautificationResult | null;
  generationPlan: GenerationPlan | null;
  generationPlanDiscussionSession: GenerationPlanDiscussionSession | null;
  generatedCodeDrafts: GeneratedCodeDraft[];
  diffReviewResult: GraphPatchResult | null;
}

export function buildAssistantTurns({
  assistantSessionState,
  qaResult,
  graphBeautificationResult,
  generationPlan,
  generationPlanDiscussionSession,
  generatedCodeDrafts,
  diffReviewResult,
}: BuildAssistantTurnsArgs): AssistantTurn[] {
  const refs = assistantSessionState.turns ?? [];
  if (refs.length > 0) {
    return refs.map((ref) => buildTurnFromRef(ref, {
      qaResult,
      graphBeautificationResult,
      generationPlan,
      generationPlanDiscussionSession,
      generatedCodeDrafts,
      diffReviewResult,
    }));
  }

  return synthesizeTurns({
    assistantSessionState,
    qaResult,
    graphBeautificationResult,
    generationPlan,
    generationPlanDiscussionSession,
    generatedCodeDrafts,
    diffReviewResult,
  });
}

function buildTurnFromRef(
  ref: AssistantTurnRef,
  results: Omit<BuildAssistantTurnsArgs, "assistantSessionState">,
): AssistantTurn {
  const baseTurn = {
    turnId: ref.turnId,
    kind: ref.kind,
    createdAtEpochMillis: ref.createdAtEpochMillis,
    context: ref.context,
  };

  switch (ref.kind) {
    case "EXPLANATION":
      return {
        ...baseTurn,
        explanation: matchesResultId(ref, graphBeautificationResultId(results.graphBeautificationResult))
          ? results.graphBeautificationResult
          : null,
      };
    case "QA":
      return {
        ...baseTurn,
        qa: matchesResultId(ref, graphPatchResultId("qa", results.qaResult)) ? results.qaResult : null,
      };
    case "GENERATION_PLAN":
      const generationDiscussionMatches = matchesDiscussionSessionId(ref, results.generationPlanDiscussionSession);
      return {
        ...baseTurn,
        generationPlan: matchesResultId(ref, generationPlanResultId(results.generationPlan)) || generationDiscussionMatches
          ? results.generationPlan
          : null,
        generationDiscussionSession: generationDiscussionMatches
          ? results.generationPlanDiscussionSession
          : null,
      };
    case "CODE_DRAFT":
      const codeDraftsMatch = matchesResultId(ref, codeDraftsResultId(results.generatedCodeDrafts));
      return {
        ...baseTurn,
        generationPlan: codeDraftsMatch ? results.generationPlan : null,
        generationDiscussionSession: codeDraftsMatch ? results.generationPlanDiscussionSession : null,
        codeDrafts: codeDraftsMatch ? results.generatedCodeDrafts : [],
      };
    case "CHECK_RESULT":
      return {
        ...baseTurn,
        check: matchedCheckResult(ref, results),
      };
  }
}

function synthesizeTurns({
  assistantSessionState,
  qaResult,
  graphBeautificationResult,
  generationPlan,
  generationPlanDiscussionSession,
  generatedCodeDrafts,
  diffReviewResult,
}: BuildAssistantTurnsArgs): AssistantTurn[] {
  const context = assistantSessionState.context ?? EMPTY_ASSISTANT_CONTEXT;
  const turns: AssistantTurn[] = [];
  if (graphBeautificationResult) {
    turns.push(syntheticTurn("EXPLANATION", "graphBeautificationResult", turns.length, context, {
      explanation: graphBeautificationResult,
    }));
  }
  if (qaResult && !reviewQaResult(qaResult)) {
    turns.push(syntheticTurn("QA", "qaResult", turns.length, context, {
      qa: qaResult,
    }));
  }
  if (generationPlan || generationPlanDiscussionSession) {
    turns.push(syntheticTurn("GENERATION_PLAN", "requestGenerationPlan", turns.length, context, {
      generationPlan,
      generationDiscussionSession: generationPlanDiscussionSession,
    }));
  }
  if (generatedCodeDrafts.length > 0) {
    turns.push(syntheticTurn("CODE_DRAFT", "requestCodeDrafts", turns.length, context, {
      generationPlan,
      generationDiscussionSession: generationPlanDiscussionSession,
      codeDrafts: generatedCodeDrafts,
    }));
  }
  const checkResult = diffReviewResult ?? reviewQaResult(qaResult);
  if (checkResult) {
    turns.push(syntheticTurn("CHECK_RESULT", "requestDiffReview", turns.length, context, {
      check: checkResult,
    }));
  }
  return turns;
}

function syntheticTurn(
  kind: AssistantTurnKind,
  source: string,
  index: number,
  context: AssistantTurn["context"],
  payload: Partial<AssistantTurn>,
): AssistantTurn {
  return {
    turnId: `synthetic:${source}:${index + 1}`,
    kind,
    createdAtEpochMillis: index + 1,
    context,
    ...payload,
  };
}

function reviewQaResult(result: GraphPatchResult | null): GraphPatchResult | null {
  if (!result) {
    return null;
  }
  return result.requestedMode === "REVIEW" || result.effectiveMode === "REVIEW" ? result : null;
}

function matchedCheckResult(
  ref: AssistantTurnRef,
  results: Omit<BuildAssistantTurnsArgs, "assistantSessionState">,
): GraphPatchResult | null {
  const diffReviewId = graphPatchResultId("diff-review", results.diffReviewResult);
  if (results.diffReviewResult && matchesResultId(ref, diffReviewId)) {
    return results.diffReviewResult;
  }
  const reviewQa = reviewQaResult(results.qaResult);
  return reviewQa && matchesResultId(ref, graphPatchResultId("qa", reviewQa)) ? reviewQa : null;
}

function matchesResultId(ref: AssistantTurnRef, currentResultId: string | null): boolean {
  if (!ref.resultId) {
    return true;
  }
  return ref.resultId === currentResultId;
}

function matchesDiscussionSessionId(
  ref: AssistantTurnRef,
  session: GenerationPlanDiscussionSession | null,
): boolean {
  if (!ref.resultId) {
    return true;
  }
  return ref.resultId === session?.sessionId;
}

function graphPatchResultId(prefix: string, result: GraphPatchResult | null): string | null {
  if (!result) {
    return null;
  }
  return `${prefix}:${assistantStableHash(`${result.question.trim()}${ASSISTANT_RESULT_HASH_SEPARATOR}${result.answer.trim()}`)}`;
}

function graphBeautificationResultId(result: GraphBeautificationResult | null): string | null {
  if (!result) {
    return null;
  }
  return `explanation:${assistantStableHash(`${result.granularity}${ASSISTANT_RESULT_HASH_SEPARATOR}${result.steps.map((step) => step.stepId).join("|")}`)}`;
}

function generationPlanResultId(result: GenerationPlan | null): string | null {
  if (!result) {
    return null;
  }
  return `generation-plan:${assistantStableHash(`${result.summary.trim()}${ASSISTANT_RESULT_HASH_SEPARATOR}${result.items.map((item) => item.id).join("|")}`)}`;
}

function codeDraftsResultId(drafts: GeneratedCodeDraft[]): string | null {
  return drafts[0]?.id ?? null;
}

const ASSISTANT_RESULT_HASH_SEPARATOR = "\u001F";

function assistantStableHash(input: string): string {
  let hash = 0x811c9dc5;
  for (let index = 0; index < input.length; index += 1) {
    hash ^= input.charCodeAt(index);
    hash = Math.imul(hash, 0x01000193) >>> 0;
  }
  return hash.toString(16).padStart(8, "0");
}
