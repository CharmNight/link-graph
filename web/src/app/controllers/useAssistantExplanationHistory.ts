import { useEffect, useRef, type Dispatch, type MutableRefObject, type SetStateAction } from "react";
import type {
  AssistantResultStore,
  AssistantSessionState,
  AssistantTurnRef,
  AsyncRequestState,
  GraphBeautificationResult,
  StepGranularity,
} from "../types";

type ExplanationRequestMode = "fresh" | "follow_up";

interface ExplanationHistoryEntry {
  result: GraphBeautificationResult;
  requestState: AsyncRequestState;
  selectedStepId: string | null;
  granularity: StepGranularity;
  sessionLabel: string;
}

interface UseAssistantExplanationHistoryArgs {
  graphBeautificationResult: GraphBeautificationResult | null;
  graphBeautificationRequestState: AsyncRequestState;
  explanationHistory: ExplanationHistoryEntry[];
  explanationLocalOverrideRef: MutableRefObject<boolean>;
  pendingExplanationRequestModeRef: MutableRefObject<ExplanationRequestMode | null>;
  pendingExplanationSessionLabelRef: MutableRefObject<string | null>;
  pendingExplanationHistoryEntryRef: MutableRefObject<ExplanationHistoryEntry | null>;
  assistantSessionState: AssistantSessionState;
  assistantResultStore: AssistantResultStore;
  setSelectedExplanationStepId: Dispatch<SetStateAction<string | null>>;
  setHoveredExplanationStepId: Dispatch<SetStateAction<string | null>>;
  setSelectedExplanationGranularity: Dispatch<SetStateAction<StepGranularity>>;
  setCurrentExplanationSessionLabel: Dispatch<SetStateAction<string>>;
  setExplanationHistory: Dispatch<SetStateAction<ExplanationHistoryEntry[]>>;
  setGraphBeautificationResult: Dispatch<SetStateAction<GraphBeautificationResult | null>>;
  setGraphBeautificationRequestState: Dispatch<SetStateAction<AsyncRequestState>>;
  setAssistantSessionState: Dispatch<SetStateAction<AssistantSessionState>>;
  setAssistantResultStore: Dispatch<SetStateAction<AssistantResultStore>>;
}

export function useAssistantExplanationHistory(args: UseAssistantExplanationHistoryArgs) {
  const historyResultSequenceRef = useRef(
    Math.max(args.assistantSessionState.nextResultSequence ?? 1, args.assistantSessionState.turns.length + 1),
  );

  function resetPendingExplanationRequest() {
    args.pendingExplanationRequestModeRef.current = null;
    args.pendingExplanationSessionLabelRef.current = null;
    args.pendingExplanationHistoryEntryRef.current = null;
  }

  function makeHistoryTurn(
    session: AssistantSessionState,
    resultId: string,
    createdAtEpochMillis: number,
  ): AssistantTurnRef {
    return {
      turnId: `explanationHistory:explanation:${session.turns.length + 1}:${createdAtEpochMillis}`,
      kind: "EXPLANATION",
      sourceMessageType: "explanationHistory",
      resultId,
      createdAtEpochMillis,
      context: session.context,
    };
  }

  function nextHistoryResultId(): string {
    let nextSequence = Math.max(
      historyResultSequenceRef.current,
      args.assistantSessionState.nextResultSequence ?? 1,
      args.assistantSessionState.turns.length + 1,
    );
    let resultId = `explanation-history:local:${nextSequence}`;
    while (Object.prototype.hasOwnProperty.call(args.assistantResultStore, resultId)) {
      nextSequence += 1;
      resultId = `explanation-history:local:${nextSequence}`;
    }
    historyResultSequenceRef.current = nextSequence + 1;
    return resultId;
  }

  function putHistoryResult(snapshot: ExplanationHistoryEntry): string {
    const resultId = nextHistoryResultId();
    const createdAtEpochMillis = Date.now();
    args.setAssistantResultStore((current) => ({
      ...current,
      [resultId]: {
        kind: "EXPLANATION",
        explanation: snapshot.result,
      },
    }));
    args.setAssistantSessionState((current) => {
      const nextTurn = makeHistoryTurn(current, resultId, createdAtEpochMillis);
      return {
        ...current,
        activeIntent: "EXPLAIN_CODE",
        nextResultSequence: Math.max(current.nextResultSequence ?? 1, historyResultSequenceRef.current),
        turns: current.turns.concat(nextTurn),
      };
    });
    return resultId;
  }

  function handleOpenExplanationHistory(historyIndex: number) {
    args.setExplanationHistory((current) => {
      const snapshot = current[historyIndex];
      if (!snapshot) {
        return current;
      }
      args.explanationLocalOverrideRef.current = true;
      resetPendingExplanationRequest();
      putHistoryResult(snapshot);
      args.setGraphBeautificationResult(snapshot.result);
      args.setGraphBeautificationRequestState(snapshot.requestState);
      args.setSelectedExplanationStepId(snapshot.selectedStepId);
      args.setSelectedExplanationGranularity(snapshot.granularity);
      args.setCurrentExplanationSessionLabel(snapshot.sessionLabel);
      args.setHoveredExplanationStepId(null);
      return current.slice(0, historyIndex);
    });
  }

  function handleReturnToPreviousExplanation() {
    handleOpenExplanationHistory(args.explanationHistory.length - 1);
  }

  useEffect(() => {
    if (args.graphBeautificationRequestState.phase !== "FAILED" && args.graphBeautificationRequestState.phase !== "TIMED_OUT") {
      return;
    }
    resetPendingExplanationRequest();
  }, [args.graphBeautificationRequestState.phase]);

  useEffect(() => {
    if (args.graphBeautificationRequestState.phase !== "SUCCEEDED" || args.graphBeautificationResult == null) {
      return;
    }
    const pendingMode = args.pendingExplanationRequestModeRef.current;
    const pendingSessionLabel = args.pendingExplanationSessionLabelRef.current;
    const pendingHistoryEntry = args.pendingExplanationHistoryEntryRef.current;

    if (pendingMode === "fresh") {
      args.setExplanationHistory([]);
    }
    if (pendingMode === "follow_up" && pendingHistoryEntry) {
      args.setExplanationHistory((current) => current.concat(pendingHistoryEntry));
    }
    if (pendingSessionLabel) {
      args.setCurrentExplanationSessionLabel(pendingSessionLabel);
    }

    resetPendingExplanationRequest();
  }, [args.graphBeautificationRequestState.phase, args.graphBeautificationResult]);

  useEffect(() => {
    const firstStepId = args.graphBeautificationResult?.steps?.[0]?.stepId ?? null;
    args.setSelectedExplanationStepId((current) => {
      if (!args.graphBeautificationResult?.steps?.length) {
        return null;
      }
      return args.graphBeautificationResult.steps.some((step) => step.stepId === current) ? current : firstStepId;
    });
    args.setHoveredExplanationStepId((current) =>
      args.graphBeautificationResult?.steps?.some((step) => step.stepId === current) ? current : null,
    );
  }, [args.graphBeautificationResult]);

  useEffect(() => {
    if (!args.graphBeautificationResult?.granularity) {
      return;
    }
    args.setSelectedExplanationGranularity(args.graphBeautificationResult.granularity);
  }, [args.graphBeautificationResult?.granularity]);

  return {
    handleReturnToPreviousExplanation,
    handleOpenExplanationHistory,
  };
}
