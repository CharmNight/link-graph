import { describe, expect, it } from "vitest";
import { buildAssistantTurns } from "../../../app/assistant/assistantResultAdapters";
import type {
  AssistantSessionState,
  AssistantTurnRef,
  GeneratedCodeDraft,
  GraphBeautificationResult,
  GraphPatchResult,
} from "../../../app/types";

function turnRef(overrides: Partial<AssistantTurnRef>): AssistantTurnRef {
  return {
    turnId: "turn-1",
    kind: "QA",
    sourceMessageType: "qaResult",
    resultId: "qa:test",
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
    composer: {
      draft: "",
      target: {
        kind: "NewTask",
      },
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

function codeDraft(id: string): GeneratedCodeDraft {
  return {
    id,
    sourceNodeId: "method:submit-order",
    title: `${id}.kt`,
    targetPath: `src/main/kotlin/${id}.kt`,
    content: "class Draft",
    warnings: [],
  };
}

describe("assistantResultAdapters", () => {
  it("restores assistant turn refs only from the assistant result store", () => {
    const checkResult = {
      ...qaResult(),
      question: "检查这次改动",
      answer: "当前改动需要补相关测试。",
      requestedMode: "REVIEW" as const,
    };
    const turns = buildAssistantTurns({
      assistantSessionState: assistantSession([
        turnRef({
          turnId: "turn-explain",
          kind: "EXPLANATION",
          sourceMessageType: "graphBeautificationResult",
          resultId: "explanation:submit",
        }),
        turnRef({
          turnId: "turn-qa",
          kind: "QA",
          sourceMessageType: "qaResult",
          resultId: "qa:850385e5",
        }),
        turnRef({
          turnId: "turn-check",
          kind: "CHECK_RESULT",
          sourceMessageType: "diffReviewResult",
          resultId: "diff-review:current",
        }),
      ]),
      assistantResultStore: {
        "explanation:submit": {
          kind: "EXPLANATION",
          explanation: explanationResult(),
        },
        "qa:850385e5": {
          kind: "QA",
          qa: qaResult(),
        },
        "diff-review:current": {
          kind: "CHECK_RESULT",
          check: checkResult,
        },
      },
    });

    expect(turns.map((turn) => turn.kind)).toEqual(["EXPLANATION", "QA", "CHECK_RESULT"]);
    expect(turns[0].explanation?.steps[0]?.title).toBe("提交订单");
    expect(turns[1].qa?.answer).toBe("会影响订单提交流程。");
    expect(turns[2].check?.answer).toBe("当前改动需要补相关测试。");
  });

  it("preserves assistant turn action so class descriptions and relationship explanations stay distinguishable", () => {
    const turns = buildAssistantTurns({
      assistantSessionState: assistantSession([
        turnRef({
          turnId: "turn-describe-class",
          kind: "EXPLANATION",
          intent: "DESCRIBE_CLASS",
          actionId: "DESCRIBE_CLASS",
          sourceMessageType: "graphBeautificationResult",
          resultId: "explanation:describe-class",
          context: {
            selectedNodeIds: ["class:quota-manager"],
            selectedDiffItemIds: [],
            analysisDisplayMode: "CLASS_DIAGRAM",
            currentSceneId: "WORKSPACE_CLASS_DIAGRAM",
            selectedMethodSignature: null,
            scopeLabel: "ClientRequestQuotaManager",
          },
        }),
      ]),
      assistantResultStore: {
        "explanation:describe-class": {
          kind: "EXPLANATION",
          explanation: explanationResult(),
        },
      },
    });

    expect(turns[0].intent).toBe("DESCRIBE_CLASS");
    expect(turns[0].actionId).toBe("DESCRIBE_CLASS");
  });

  it("does not synthesize visible turns from current results when bootstrap has no refs", () => {
    const turns = buildAssistantTurns({
      assistantSessionState: assistantSession([]),
    });

    expect(turns).toEqual([]);
  });

  it("does not attach current same-kind results when the result store is missing", () => {
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
    });

    expect(turns[0].qa).toBeNull();
    expect(turns[1].qa).toBeNull();
  });

  it("does not attach a later successful result to a failed turn ref", () => {
    const turns = buildAssistantTurns({
      assistantSessionState: assistantSession([
        turnRef({
          turnId: "turn-qa-failed",
          kind: "QA",
          sourceMessageType: "requestAssistantTask",
          resultId: "qa-failure:41",
        }),
        turnRef({
          turnId: "turn-qa-latest",
          kind: "QA",
          sourceMessageType: "qaResult",
          resultId: "qa:850385e5",
        }),
      ]),
      assistantResultStore: {
        "qa:850385e5": {
          kind: "QA",
          qa: qaResult(),
        },
      },
    });

    expect(turns[0].qa).toBeNull();
    expect(turns[1].qa?.answer).toBe("会影响订单提交流程。");
  });

  it("restores failed turns from failure entries in the assistant result store", () => {
    const turns = buildAssistantTurns({
      assistantSessionState: assistantSession([
        turnRef({
          turnId: "turn-qa-failed",
          kind: "QA",
          sourceMessageType: "requestAssistantTask",
          resultId: "qa-failure:41",
        }),
      ]),
      assistantResultStore: {
        "qa-failure:41": {
          kind: "QA",
          failure: {
            resultId: "qa-failure:41",
            message: "上游超时",
            detailMessage: "HTTP 504 from qa provider",
            phase: "FAILED",
            requestId: 41,
            sourceMessageType: "requestAssistantTask",
          },
        },
      },
    });

    expect(turns[0].qa).toBeNull();
    expect(turns[0].failure?.message).toBe("上游超时");
    expect(turns[0].failure?.detailMessage).toBe("HTTP 504 from qa provider");
  });

  it("restores historical turns from the assistant result store by result id", () => {
    const historicalQaResult = {
      ...qaResult(),
      question: "旧问题",
      answer: "旧答案",
    };
    const latestQa = qaResult();

    const turns = buildAssistantTurns({
      assistantSessionState: assistantSession([
        turnRef({
          turnId: "turn-qa-old",
          kind: "QA",
          resultId: "qa:old-result",
        }),
        turnRef({
          turnId: "turn-qa-latest",
          kind: "QA",
          resultId: "qa:850385e5",
        }),
      ]),
      assistantResultStore: {
        "qa:old-result": {
          kind: "QA",
          qa: historicalQaResult,
        },
        "qa:850385e5": {
          kind: "QA",
          qa: latestQa,
        },
      },
    });

    expect(turns.map((turn) => turn.qa?.answer)).toEqual(["旧答案", "会影响订单提交流程。"]);
  });

  it("keeps same-kind assistant turns bound to their own store entries", () => {
    const firstCheck = {
      ...qaResult(),
      question: "检查第一批改动",
      answer: "第一批需要补单测。",
      requestedMode: "REVIEW" as const,
    };
    const secondCheck = {
      ...qaResult(),
      question: "检查第二批改动",
      answer: "第二批需要补集成测试。",
      requestedMode: "REVIEW" as const,
    };

    const turns = buildAssistantTurns({
      assistantSessionState: assistantSession([
        turnRef({
          turnId: "turn-check-first",
          kind: "CHECK_RESULT",
          sourceMessageType: "diffReviewResult",
          resultId: "diff-review:first",
        }),
        turnRef({
          turnId: "turn-check-second",
          kind: "CHECK_RESULT",
          sourceMessageType: "diffReviewResult",
          resultId: "diff-review:second",
        }),
      ]),
      assistantResultStore: {
        "diff-review:first": {
          kind: "CHECK_RESULT",
          check: firstCheck,
        },
        "diff-review:second": {
          kind: "CHECK_RESULT",
          check: secondCheck,
        },
      },
    });

    expect(turns.map((turn) => turn.check?.answer)).toEqual(["第一批需要补单测。", "第二批需要补集成测试。"]);
  });

  it("restores code draft turns from the assistant result store", () => {
    const firstDrafts = [codeDraft("draft-old")];
    const latestDrafts = [codeDraft("draft-latest")];

    const turns = buildAssistantTurns({
      assistantSessionState: assistantSession([
        turnRef({
          turnId: "turn-code-old",
          kind: "CODE_DRAFT",
          sourceMessageType: "requestCodeDrafts",
          resultId: "code-draft:old",
        }),
        turnRef({
          turnId: "turn-code-latest",
          kind: "CODE_DRAFT",
          sourceMessageType: "requestCodeDrafts",
          resultId: "code-draft:latest",
        }),
      ]),
      assistantResultStore: {
        "code-draft:old": {
          kind: "CODE_DRAFT",
          codeDrafts: firstDrafts,
        },
        "code-draft:latest": {
          kind: "CODE_DRAFT",
          codeDrafts: latestDrafts,
        },
      },
    });

    expect(turns.map((turn) => turn.codeDrafts?.[0]?.id)).toEqual(["draft-old", "draft-latest"]);
  });
});
