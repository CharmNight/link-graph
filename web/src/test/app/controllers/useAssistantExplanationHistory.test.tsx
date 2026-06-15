import { act, renderHook } from "@testing-library/react";
import { useRef, useState } from "react";
import { describe, expect, it } from "vitest";
import { useAssistantExplanationHistory } from "../../../app/controllers/useAssistantExplanationHistory";
import type {
  AssistantResultStore,
  AssistantSessionState,
  AsyncRequestState,
  GraphBeautificationResult,
  StepGranularity,
} from "../../../app/types";

interface ExplanationHistoryEntry {
  result: GraphBeautificationResult;
  requestState: AsyncRequestState;
  selectedStepId: string | null;
  granularity: StepGranularity;
  sessionLabel: string;
}

function explanation(description: string): GraphBeautificationResult {
  return {
    source: "LOCAL_RULE",
    granularity: "BUSINESS",
    steps: [
      {
        stepId: "step-submit",
        title: "提交订单",
        granularity: "BUSINESS",
        kind: "BUSINESS_ACTION",
        description,
        evidence: [],
        followUpQuestions: [],
        downstreamTargets: [],
      },
    ],
    promptPreview: null,
    warnings: [],
  };
}

function historyEntry(description: string, sessionLabel: string): ExplanationHistoryEntry {
  return {
    result: explanation(description),
    requestState: {
      phase: "SUCCEEDED",
    },
    selectedStepId: "step-submit",
    granularity: "BUSINESS",
    sessionLabel,
  };
}

function initialAssistantSession(): AssistantSessionState {
  return {
    sessionId: "assistant-session",
    activeIntent: "EXPLAIN_CODE",
    contextLocked: false,
    context: {
      selectedNodeIds: [],
      selectedDiffItemIds: [],
      analysisDisplayMode: "FLOWCHART",
      currentSceneId: "WORKSPACE_FLOWCHART",
      selectedMethodSignature: null,
      scopeLabel: "FLOWCHART",
    },
    composer: {
      draft: "",
      target: { kind: "NewTask" },
    },
    turns: [],
  };
}

function renderExplanationHistoryHarness(initialHistory: ExplanationHistoryEntry[]) {
  return renderHook(() => {
    const [graphBeautificationResult, setGraphBeautificationResult] = useState<GraphBeautificationResult | null>(null);
    const [graphBeautificationRequestState, setGraphBeautificationRequestState] = useState<AsyncRequestState>({
      phase: "IDLE",
    });
    const [explanationHistory, setExplanationHistory] = useState(initialHistory);
    const [assistantSessionState, setAssistantSessionState] = useState(initialAssistantSession());
    const [assistantResultStore, setAssistantResultStore] = useState<AssistantResultStore>({});
    const [, setSelectedExplanationStepId] = useState<string | null>(null);
    const [, setHoveredExplanationStepId] = useState<string | null>(null);
    const [, setSelectedExplanationGranularity] = useState<StepGranularity>("BUSINESS");
    const [, setCurrentExplanationSessionLabel] = useState("当前解释");
    const explanationLocalOverrideRef = useRef(false);
    const pendingExplanationRequestModeRef = useRef<"fresh" | "follow_up" | null>(null);
    const pendingExplanationSessionLabelRef = useRef<string | null>(null);
    const pendingExplanationHistoryEntryRef = useRef<ExplanationHistoryEntry | null>(null);

    const controller = useAssistantExplanationHistory({
      graphBeautificationResult,
      graphBeautificationRequestState,
      explanationHistory,
      explanationLocalOverrideRef,
      pendingExplanationRequestModeRef,
      pendingExplanationSessionLabelRef,
      pendingExplanationHistoryEntryRef,
      assistantSessionState,
      assistantResultStore,
      setSelectedExplanationStepId,
      setHoveredExplanationStepId,
      setSelectedExplanationGranularity,
      setCurrentExplanationSessionLabel,
      setExplanationHistory,
      setGraphBeautificationResult,
      setGraphBeautificationRequestState,
      setAssistantSessionState,
      setAssistantResultStore,
    });

    return {
      ...controller,
      assistantSessionState,
      assistantResultStore,
      explanationHistory,
    };
  });
}

describe("useAssistantExplanationHistory", () => {
  it("keeps separate assistant turn results for history snapshots with the same step ids", () => {
    const hook = renderExplanationHistoryHarness([
      historyEntry("第一轮解释", "第一轮"),
      historyEntry("第二轮解释", "第二轮"),
    ]);

    act(() => {
      hook.result.current.handleOpenExplanationHistory(1);
    });
    act(() => {
      hook.result.current.handleOpenExplanationHistory(0);
    });

    const turns = hook.result.current.assistantSessionState.turns;
    const store = hook.result.current.assistantResultStore;
    expect(turns).toHaveLength(2);
    expect(new Set(turns.map((turn) => turn.resultId)).size).toBe(2);
    expect(store[turns[0].resultId]?.explanation?.steps[0]?.description).toBe("第二轮解释");
    expect(store[turns[1].resultId]?.explanation?.steps[0]?.description).toBe("第一轮解释");
  });
});
