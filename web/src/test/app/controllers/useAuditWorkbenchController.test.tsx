import { act, renderHook } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { requestAuditAsync } from "../../../app/api";
import { useAuditWorkbenchController } from "../../../app/controllers/useAuditWorkbenchController";
import type { GraphPatchResult, QaMode } from "../../../app/types";

vi.mock("../../../app/api", () => ({
  confirmAuditCandidateChange: vi.fn(),
  requestAuditAsync: vi.fn(),
  resolveInvestigationThread: vi.fn(),
  retryLastAuditRequestAsync: vi.fn(),
  unconfirmAuditCandidateChange: vi.fn(),
  updateWorkbenchSectionPreference: vi.fn(),
}));

function auditResultWithThread(): GraphPatchResult {
  return {
    source: "MOCK",
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

function renderController(overrides: Partial<Parameters<typeof useAuditWorkbenchController>[0]> = {}) {
  const submitAsyncBridgeCommand = vi.fn((_label: string, command: () => unknown) => command());
  const runBridgeCommand = vi.fn((_label: string, command: () => unknown) => command());
  const setAuditQuestionMode = vi.fn();
  const args: Parameters<typeof useAuditWorkbenchController>[0] = {
    auditResult: null,
    qaRequestRecoveryState: {},
    auditSourceThreadId: null,
    auditQuestionMode: "AUTO",
    auditTargetNodeIds: [],
    selectedAuditChangeId: null,
    selectedAuditThreadId: null,
    nodes: [],
    draftWorkbenchState: { draftChanges: [], draftNotes: [] },
    bridgeCommands: {
      runBridgeCommand,
      submitAsyncBridgeCommand,
    },
    setAuditQuestionDraft: vi.fn(),
    setAuditQuestionMode,
    setAuditTargetNodeIds: vi.fn(),
    setAuditSourceThreadId: vi.fn(),
    setActiveWorkbenchTab: vi.fn(),
    setOperationFeedback: vi.fn(),
    setDraftWorkbenchState: vi.fn(),
    setSelectedDraftEntryId: vi.fn(),
    setAuditResult: vi.fn(),
    setSelectedAuditChangeId: vi.fn(),
    setSelectedAuditThreadId: vi.fn(),
    selectExplanationTargetNode: vi.fn(),
    toDraftWorkbenchEntry: vi.fn(),
    updateGraphPatchResultCandidateStatus: vi.fn(),
    updateGraphPatchResultThreadResolution: vi.fn(),
    resolveDraftEntryTargetNodeIds: vi.fn(() => []),
    resolveDisplayedNodeId: vi.fn(() => null),
    resolveEvidenceTargetNodeId: vi.fn(() => null),
    ...overrides,
  };
  const hook = renderHook(() => useAuditWorkbenchController(args));
  return {
    ...hook,
    args,
    runBridgeCommand,
    submitAsyncBridgeCommand,
    setAuditQuestionMode,
  };
}

describe("useAuditWorkbenchController", () => {
  it("passes the selected QA mode to the audit bridge command", () => {
    const { result } = renderController({ auditQuestionMode: "ANSWER" as QaMode });

    act(() => {
      result.current.handleRequestAudit(" 这个方法是如何触发的？ ", ["method:upload-file"]);
    });

    expect(requestAuditAsync).toHaveBeenCalledWith(
      "这个方法是如何触发的？",
      ["method:upload-file"],
      null,
      "ANSWER",
    );
  });

  it("uses INVESTIGATE for risk-thread continuation regardless of composer mode", () => {
    const { result } = renderController({
      auditResult: auditResultWithThread(),
      auditQuestionMode: "CHANGE" as QaMode,
    });

    act(() => {
      result.current.handleInvestigateAuditThread("thread-path-risk");
    });

    expect(requestAuditAsync).toHaveBeenCalledWith(
      "请继续取证：展开 FileUploadUtils.upload，确认是否存在路径规范化或目录校验。",
      ["method:upload-file"],
      "thread-path-risk",
      "INVESTIGATE",
    );
  });
});
