import { act, renderHook } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { requestQaAsync } from "../../../app/api";
import type { BridgeInvocationResult } from "../../../app/api";
import { useQaWorkbenchController } from "../../../app/controllers/useQaWorkbenchController";
import type { GraphPatchResult, QaMode } from "../../../app/types";

vi.mock("../../../app/api", () => ({
  confirmQaCandidateChange: vi.fn(),
  requestQaAsync: vi.fn(),
  resolveInvestigationThread: vi.fn(),
  retryLastQaRequestAsync: vi.fn(),
  unconfirmQaCandidateChange: vi.fn(),
  updateWorkbenchSectionPreference: vi.fn(),
}));

function qaResultWithThread(): GraphPatchResult {
  return {
    source: "LOCAL_RULE",
    question: "这里有没有风险？",
    requestedMode: "AUTO",
    effectiveMode: "REVIEW",
    answer: "需要继续取证。",
    promptPreview: null,
    findings: [],
    candidateChanges: [],
    newCandidateChanges: [],
    investigationThreads: [
      {
        threadId: "thread-path-risk",
        status: "OPEN",
        title: "补充路径风险说明",
        targetStepIds: [],
        targetNodeIds: ["method:upload-file"],
        summary: "当前只有调用点证据。",
        evidenceGap: "还没有看到上传工具内部路径校验实现。",
        recommendedQuestion: "请继续取证：展开 FileUploadUtils.upload，确认是否存在路径规范化或目录校验。",
        claimType: "RISK_HINT",
        evidence: [],
      },
    ],
    warnings: [],
  };
}

function renderController(overrides: Partial<Parameters<typeof useQaWorkbenchController>[0]> = {}) {
  const submitAsyncBridgeCommand = vi.fn((_label: string, command: () => BridgeInvocationResult) => command());
  const runBridgeCommand = vi.fn((_label: string, command: () => BridgeInvocationResult) => command());
  const setQaQuestionMode = vi.fn();
  const args: Parameters<typeof useQaWorkbenchController>[0] = {
    qaResult: null,
    qaRequestRecoveryState: {},
    qaSourceThreadId: null,
    qaQuestionMode: "AUTO",
    qaTargetNodeIds: [],
    selectedQaChangeId: null,
    selectedQaThreadId: null,
    nodes: [],
    draftWorkbenchState: { draftChanges: [], draftNotes: [] },
    bridgeCommands: {
      runBridgeCommand,
      submitAsyncBridgeCommand,
    },
    setQaQuestionDraft: vi.fn(),
    setQaQuestionMode,
    setQaTargetNodeIds: vi.fn(),
    setQaSourceThreadId: vi.fn(),
    setActiveWorkbenchTab: vi.fn(),
    setOperationFeedback: vi.fn(),
    setDraftWorkbenchState: vi.fn(),
    setSelectedDraftEntryId: vi.fn(),
    setQaResult: vi.fn(),
    setSelectedQaChangeId: vi.fn(),
    setSelectedQaThreadId: vi.fn(),
    selectExplanationTargetNode: vi.fn(),
    toDraftWorkbenchEntry: vi.fn(),
    updateGraphPatchResultCandidateStatus: vi.fn(),
    updateGraphPatchResultThreadResolution: vi.fn(),
    resolveDraftEntryTargetNodeIds: vi.fn(() => []),
    resolveDisplayedNodeId: vi.fn(() => null),
    resolveEvidenceTargetNodeId: vi.fn(() => null),
    ...overrides,
  };
  const hook = renderHook(() => useQaWorkbenchController(args));
  return {
    ...hook,
    args,
    runBridgeCommand,
    submitAsyncBridgeCommand,
    setQaQuestionMode,
  };
}

describe("useQaWorkbenchController", () => {
  it("passes the selected QA mode to the qa bridge command", () => {
    const { result } = renderController({ qaQuestionMode: "ANSWER" as QaMode });

    act(() => {
      result.current.handleRequestQa(" 这个方法是如何触发的？ ", ["method:upload-file"]);
    });

    expect(requestQaAsync).toHaveBeenCalledWith(
      "这个方法是如何触发的？",
      ["method:upload-file"],
      null,
      "ANSWER",
    );
  });

  it("uses INVESTIGATE for risk-thread continuation regardless of composer mode", () => {
    const { result } = renderController({
      qaResult: qaResultWithThread(),
      qaQuestionMode: "CHANGE" as QaMode,
    });

    act(() => {
      result.current.handleInvestigateQaThread("thread-path-risk");
    });

    expect(requestQaAsync).toHaveBeenCalledWith(
      "请继续取证：展开 FileUploadUtils.upload，确认是否存在路径规范化或目录校验。",
      ["method:upload-file"],
      "thread-path-risk",
      "INVESTIGATE",
    );
  });
});
