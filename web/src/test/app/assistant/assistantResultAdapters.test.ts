import { describe, expect, it } from "vitest";
import { buildAssistantTurns } from "../../../app/assistant/assistantResultAdapters";
import type {
  AssistantSessionState,
  AssistantTurnRef,
  GraphBeautificationResult,
  GraphPatchResult,
} from "../../../app/types";

function turnRef(overrides: Partial<AssistantTurnRef>): AssistantTurnRef {
  return {
    turnId: "turn-1",
    kind: "QA",
    sourceMessageType: "qaResult",
    resultId: null,
    createdAtEpochMillis: 1,
    context: {
      selectedNodeIds: ["method:submit-order"],
      selectedDiffItemIds: [],
      analysisDisplayMode: "FLOWCHART",
      currentSceneId: "WORKSPACE_FLOWCHART",
      selectedMethodSignature: "com.example.OrderController.submit():void",
      scopeLabel: "OrderController.submit",
    },
    ...overrides,
  };
}

function assistantSession(turns: AssistantTurnRef[]): AssistantSessionState {
  return {
    sessionId: "assistant-session-test",
    activeIntent: "ASK_CODE",
    contextLocked: false,
    context: {
      selectedNodeIds: ["method:submit-order"],
      selectedDiffItemIds: [],
      analysisDisplayMode: "FLOWCHART",
      currentSceneId: "WORKSPACE_FLOWCHART",
      selectedMethodSignature: "com.example.OrderController.submit():void",
      scopeLabel: "OrderController.submit",
    },
    turns,
  };
}

function qaResult(): GraphPatchResult {
  return {
    source: "LOCAL_RULE",
    question: "这个方法会影响哪里？",
    answer: "会影响订单提交流程。",
    promptPreview: null,
    findings: [],
    candidateChanges: [],
    newCandidateChanges: [],
    warnings: [],
  };
}

function explanationResult(): GraphBeautificationResult {
  return {
    source: "LOCAL_RULE",
    granularity: "BUSINESS",
    steps: [
      {
        stepId: "step-submit",
        title: "提交订单",
        granularity: "BUSINESS",
        kind: "BUSINESS_ACTION",
        description: "接收入参并调用服务。",
        primaryNodeId: "method:submit-order",
        evidence: [],
        followUpQuestions: [],
        downstreamTargets: [],
      },
    ],
    promptPreview: null,
    warnings: [],
  };
}

describe("assistantResultAdapters", () => {
  it("maps assistant turn refs to existing result payloads without copying large results into the session", () => {
    const turns = buildAssistantTurns({
      assistantSessionState: assistantSession([
        turnRef({
          turnId: "turn-explain",
          kind: "EXPLANATION",
          sourceMessageType: "graphBeautificationResult",
        }),
        turnRef({
          turnId: "turn-qa",
          kind: "QA",
          sourceMessageType: "qaResult",
        }),
        turnRef({
          turnId: "turn-check",
          kind: "CHECK_RESULT",
          sourceMessageType: "requestDiffReview",
        }),
      ]),
      qaResult: qaResult(),
      graphBeautificationResult: explanationResult(),
      generationPlan: null,
      generationPlanDiscussionSession: null,
      generatedCodeDrafts: [],
      diffReviewResult: {
        ...qaResult(),
        question: "检查这次改动",
        answer: "当前改动需要补相关测试。",
        requestedMode: "REVIEW",
      },
    });

    expect(turns.map((turn) => turn.kind)).toEqual(["EXPLANATION", "QA", "CHECK_RESULT"]);
    expect(turns[0].explanation?.steps[0]?.title).toBe("提交订单");
    expect(turns[1].qa?.answer).toBe("会影响订单提交流程。");
    expect(turns[2].check?.answer).toBe("当前改动需要补相关测试。");
  });

  it("can synthesize visible turns from current results when bootstrap has no refs yet", () => {
    const turns = buildAssistantTurns({
      assistantSessionState: assistantSession([]),
      qaResult: qaResult(),
      graphBeautificationResult: explanationResult(),
      generationPlan: {
        source: "LOCAL_RULE",
        summary: "先补失败兜底，再补测试。",
        warnings: [],
        promptPreview: null,
        items: [],
      },
      generationPlanDiscussionSession: null,
      generatedCodeDrafts: [],
      diffReviewResult: null,
    });

    expect(turns.map((turn) => turn.kind)).toEqual(["EXPLANATION", "QA", "GENERATION_PLAN"]);
  });

  it("does not attach the latest same-kind result to older turn refs with a different result id", () => {
    const turns = buildAssistantTurns({
      assistantSessionState: assistantSession([
        turnRef({
          turnId: "turn-qa-old",
          kind: "QA",
          sourceMessageType: "qaResult",
          resultId: "qa:old-question:old-answer",
        }),
        turnRef({
          turnId: "turn-qa-latest",
          kind: "QA",
          sourceMessageType: "qaResult",
          resultId: "qa:850385e5",
        }),
      ]),
      qaResult: qaResult(),
      graphBeautificationResult: null,
      generationPlan: null,
      generationPlanDiscussionSession: null,
      generatedCodeDrafts: [],
      diffReviewResult: null,
    });

    expect(turns[0].qa).toBeNull();
    expect(turns[1].qa?.answer).toBe("会影响订单提交流程。");
  });

  it("does not attach a later successful result to a failed turn ref", () => {
    const turns = buildAssistantTurns({
      assistantSessionState: assistantSession([
        turnRef({
          turnId: "turn-qa-failed",
          kind: "QA",
          sourceMessageType: "requestQa",
          resultId: "qa-failure:41",
        }),
        turnRef({
          turnId: "turn-qa-latest",
          kind: "QA",
          sourceMessageType: "qaResult",
          resultId: "qa:850385e5",
        }),
      ]),
      qaResult: qaResult(),
      graphBeautificationResult: null,
      generationPlan: null,
      generationPlanDiscussionSession: null,
      generatedCodeDrafts: [],
      diffReviewResult: null,
    });

    expect(turns[0].qa).toBeNull();
    expect(turns[1].qa?.answer).toBe("会影响订单提交流程。");
  });
});
