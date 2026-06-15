import type {
  AssistantResultStore,
  AssistantResultStoreEntry,
  AssistantSessionState,
  AssistantTurn,
  AssistantTurnRef,
} from "../types";

interface BuildAssistantTurnsArgs {
  assistantSessionState: AssistantSessionState;
  assistantResultStore?: AssistantResultStore | null;
}

export function buildAssistantTurns({
  assistantSessionState,
  assistantResultStore = null,
}: BuildAssistantTurnsArgs): AssistantTurn[] {
  const refs = assistantSessionState.turns ?? [];
  return refs.map((ref) => buildTurnFromRef(ref, assistantResultStore));
}

function buildTurnFromRef(
  ref: AssistantTurnRef,
  assistantResultStore: AssistantResultStore | null | undefined,
): AssistantTurn {
  const baseTurn = {
    turnId: ref.turnId,
    kind: ref.kind,
    intent: ref.intent ?? null,
    actionId: ref.actionId ?? null,
    createdAtEpochMillis: ref.createdAtEpochMillis,
    context: ref.context,
    failure: null,
  };
  const stored = resolveStoredResult(ref, assistantResultStore);
  if (stored) {
    return storedTurn(baseTurn, stored);
  }
  return emptyTurn(baseTurn);
}

function resolveStoredResult(
  ref: AssistantTurnRef,
  resultStore: AssistantResultStore | null | undefined,
): AssistantResultStoreEntry | null {
  if (!ref.resultId || !resultStore) {
    return null;
  }
  const stored = resultStore[ref.resultId] ?? null;
  if (!stored || stored.kind !== ref.kind) {
    return null;
  }
  return stored;
}

function emptyTurn(
  baseTurn: Pick<AssistantTurn, "turnId" | "kind" | "intent" | "actionId" | "createdAtEpochMillis" | "context" | "failure">,
): AssistantTurn {
  switch (baseTurn.kind) {
    case "EXPLANATION":
      return {
        ...baseTurn,
        explanation: null,
      };
    case "QA":
      return {
        ...baseTurn,
        qa: null,
      };
    case "GENERATION_PLAN":
      return {
        ...baseTurn,
        generationPlan: null,
        generationDiscussionSession: null,
      };
    case "CODE_DRAFT":
      return {
        ...baseTurn,
        generationPlan: null,
        generationDiscussionSession: null,
        codeDrafts: [],
        codeDraftWarnings: [],
      };
    case "CHECK_RESULT":
      return {
        ...baseTurn,
        check: null,
      };
  }
}

function storedTurn(
  baseTurn: Pick<AssistantTurn, "turnId" | "kind" | "intent" | "actionId" | "createdAtEpochMillis" | "context" | "failure">,
  stored: AssistantResultStoreEntry,
): AssistantTurn {
  const failure = stored.failure ?? null;
  switch (stored.kind) {
    case "EXPLANATION":
      return {
        ...baseTurn,
        failure,
        explanation: stored.explanation ?? null,
      };
    case "QA":
      return {
        ...baseTurn,
        failure,
        qa: stored.qa ?? null,
      };
    case "GENERATION_PLAN":
      return {
        ...baseTurn,
        failure,
        generationPlan: stored.generationPlan ?? null,
        generationDiscussionSession: stored.generationDiscussionSession ?? null,
      };
    case "CODE_DRAFT":
      return {
        ...baseTurn,
        failure,
        generationPlan: stored.generationPlan ?? null,
        generationDiscussionSession: stored.generationDiscussionSession ?? null,
        codeDrafts: stored.codeDrafts ?? [],
        codeDraftWarnings: stored.codeDraftWarnings ?? [],
      };
    case "CHECK_RESULT":
      return {
        ...baseTurn,
        failure,
        check: stored.check ?? null,
      };
  }
}
