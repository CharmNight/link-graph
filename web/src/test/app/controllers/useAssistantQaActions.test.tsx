import { act, renderHook } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import {
  confirmQaCandidateChange,
  resolveInvestigationThread,
  retryLastQaRequestAsync,
} from "../../../app/api";
import type { BridgeInvocationResult } from "../../../app/api";
import { useAssistantQaActions } from "../../../app/controllers/useAssistantQaActions";
import type { CandidateDraftChange, GraphPatchResult } from "../../../app/types";

vi.mock("../../../app/api", () => ({
  confirmQaCandidateChange: vi.fn(),
  resolveInvestigationThread: vi.fn(),
  retryLastQaRequestAsync: vi.fn(),
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

function candidateChangeFixture(): CandidateDraftChange {
  return {
    changeId: "change-compensate",
    status: "PENDING_CONFIRMATION",
    title: "补充失败补偿说明",
    targetStepIds: ["step-submit-order"],
    targetNodeIds: ["method:upload-file"],
    beforeState: "当前没有失败补偿说明",
    afterState: "补充失败补偿逻辑说明",
    reason: "当前链路缺少失败补偿语义。",
    impactSummary: "影响上传失败后的处理理解。",
    claimType: "CODE_FACT",
    evidence: [
      {
        id: "finding-compensate",
        claim: "当前源码里直接能看到失败补偿缺失。",
        evidenceLevel: "DIRECT_SOURCE",
        references: [{ nodeId: "method:upload-file" }],
      },
    ],
  };
}

function renderController(overrides: Partial<Parameters<typeof useAssistantQaActions>[0]> = {}) {
  const submitAsyncBridgeCommand = vi.fn((_label: string, command: () => BridgeInvocationResult) => command());
  const runBridgeCommand = vi.fn((
    _label: string,
    command: () => BridgeInvocationResult,
    options?: { onAccepted?: () => void },
  ) => {
    const result = command();
    options?.onAccepted?.();
    return result;
  });
  const args: Parameters<typeof useAssistantQaActions>[0] = {
    qaResult: null,
    qaRequestRecoveryState: {},
    nodes: [],
    bridgeCommands: {
      runBridgeCommand,
      submitAsyncBridgeCommand,
    },
    setOperationFeedback: vi.fn(),
    setSelectedQaChangeId: vi.fn(),
    setSelectedQaThreadId: vi.fn(),
    selectExplanationTargetNode: vi.fn(),
    resolveDraftEntryTargetNodeIds: vi.fn(() => []),
    resolveDisplayedNodeId: vi.fn(() => null),
    resolveEvidenceTargetNodeId: vi.fn(() => null),
    ...overrides,
  };
  const hook = renderHook(() => useAssistantQaActions(args));
  return {
    ...hook,
    args,
    runBridgeCommand,
    submitAsyncBridgeCommand,
  };
}

describe("useAssistantQaActions", () => {
  it("confirms a directly supported QA candidate through the backend command without local draft synthesis", () => {
    const candidate = candidateChangeFixture();
    const selectExplanationTargetNode = vi.fn();
    const { result } = renderController({
      qaResult: {
        ...qaResultWithThread(),
        candidateChanges: [candidate],
        newCandidateChanges: [candidate],
        investigationThreads: [],
      },
      nodes: [
        {
          id: "method:upload-file",
          type: "METHOD",
          title: "upload",
          inputs: [],
          outputs: [],
          confidence: "VERIFIED",
          binding: "CODE_BOUND",
        },
      ],
      selectExplanationTargetNode,
      resolveDraftEntryTargetNodeIds: vi.fn(() => ["method:upload-file"]),
      resolveDisplayedNodeId: vi.fn((nodeId) => nodeId ?? null),
    });

    act(() => {
      result.current.handleConfirmCandidateChange("change-compensate");
    });

    expect(confirmQaCandidateChange).toHaveBeenCalledWith("change-compensate");
    expect(selectExplanationTargetNode).toHaveBeenCalledWith("method:upload-file", { focusViewport: true });
  });

  it("selects and resolves QA risk threads through explicit bridge commands", () => {
    const setSelectedQaThreadId = vi.fn();
    const selectExplanationTargetNode = vi.fn();
    const { result } = renderController({
      qaResult: qaResultWithThread(),
      nodes: [
        {
          id: "method:upload-file",
          type: "METHOD",
          title: "upload",
          inputs: [],
          outputs: [],
          confidence: "VERIFIED",
          binding: "CODE_BOUND",
        },
      ],
      setSelectedQaThreadId,
      selectExplanationTargetNode,
      resolveEvidenceTargetNodeId: vi.fn(() => "method:upload-file"),
      resolveDisplayedNodeId: vi.fn((nodeId) => nodeId ?? null),
    });

    act(() => {
      result.current.handleSelectQaThread("thread-path-risk");
      result.current.handleResolveQaThread("thread-path-risk", "DEFERRED");
    });

    expect(setSelectedQaThreadId).toHaveBeenCalledWith("thread-path-risk");
    expect(selectExplanationTargetNode).toHaveBeenCalledWith("method:upload-file", { focusViewport: true });
    expect(resolveInvestigationThread).toHaveBeenCalledWith("thread-path-risk", "DEFERRED");
  });

  it("retries the failed QA request through the recovery command", () => {
    const { result, submitAsyncBridgeCommand } = renderController({
      qaRequestRecoveryState: {
        lastFailedRequest: {
          requestId: "qa-retry-1",
          kind: "ASK",
          question: "失败的问题",
          selectedNodeIds: ["method:upload-file"],
          sourceThreadId: null,
          mode: "AUTO",
        },
      },
    });

    act(() => {
      result.current.handleRetryLastQaRequest();
    });

    expect(retryLastQaRequestAsync).toHaveBeenCalledTimes(1);
    expect(submitAsyncBridgeCommand).toHaveBeenCalledWith(
      "问答",
      expect.any(Function),
      expect.objectContaining({
        successFeedback: expect.objectContaining({ level: "INFO" }),
      }),
    );
  });
});
