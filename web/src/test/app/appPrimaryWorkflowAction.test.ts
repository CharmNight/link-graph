import { describe, expect, it } from "vitest";
import {
  primaryWorkflowActionCommand,
  primaryWorkflowActionDisabled,
  primaryWorkflowActionLabel,
  type PrimaryWorkflowActionCommandState,
} from "../../app/appPrimaryWorkflowAction";
import type { AnalysisDisplayMode, AsyncRequestState } from "../../app/types";
import type { WorkflowStage } from "../../app/workflow/workflowStage";

const idle: AsyncRequestState = { phase: "IDLE" };
const running: AsyncRequestState = { phase: "RUNNING" };

function baseArgs(overrides: Partial<PrimaryWorkflowActionCommandState> = {}) {
  return {
    activeWorkflowStage: "understand" as WorkflowStage,
    analysisDisplayMode: "FLOWCHART" as AnalysisDisplayMode,
    graphBeautificationRequestState: idle,
    qaRequestState: idle,
    generationPlanRequestState: idle,
    codeDraftRequestState: idle,
    codeDiffStatus: "MISSING" as const,
    generatedCodeDraftCount: 0,
    assistantComposerDraft: "",
    selectedNodeId: null,
    selectedNodeTitle: null,
    assistantTargetTitle: null,
    ...overrides,
  };
}

describe("appPrimaryWorkflowAction", () => {
  it("resolves stage-specific primary action labels", () => {
    expect(primaryWorkflowActionLabel(baseArgs())).toBe("链路讲解");
    expect(primaryWorkflowActionLabel(baseArgs({
      analysisDisplayMode: "CLASS_DIAGRAM",
    }))).toBe("解释类关系");
    expect(primaryWorkflowActionLabel(baseArgs({
      activeWorkflowStage: "qa",
      analysisDisplayMode: "CLASS_DIAGRAM",
    }))).toBe("类图问答");
    expect(primaryWorkflowActionLabel(baseArgs({
      activeWorkflowStage: "code",
      codeDiffStatus: "FRESH",
    }))).toBe("写入全部");
  });

  it("uses running labels while async requests are active", () => {
    expect(primaryWorkflowActionLabel(baseArgs({
      graphBeautificationRequestState: running,
    }))).toBe("讲解中");
    expect(primaryWorkflowActionLabel(baseArgs({
      activeWorkflowStage: "understand",
      analysisDisplayMode: "CLASS_DIAGRAM",
      graphBeautificationRequestState: running,
    }))).toBe("解释中");
    expect(primaryWorkflowActionLabel(baseArgs({
      activeWorkflowStage: "draft",
      generationPlanRequestState: running,
    }))).toBe("生成中");
    expect(primaryWorkflowActionLabel(baseArgs({
      activeWorkflowStage: "code",
      codeDraftRequestState: running,
    }))).toBe("生成中");
  });

  it("disables the primary action only for active work or empty fresh code drafts", () => {
    expect(primaryWorkflowActionDisabled(baseArgs({
      graphBeautificationRequestState: running,
    }))).toBe(true);
    expect(primaryWorkflowActionDisabled(baseArgs({
      activeWorkflowStage: "evidence",
    }))).toBe(false);
    expect(primaryWorkflowActionDisabled(baseArgs({
      activeWorkflowStage: "code",
      codeDiffStatus: "FRESH",
      generatedCodeDraftCount: 0,
    }))).toBe(true);
    expect(primaryWorkflowActionDisabled(baseArgs({
      activeWorkflowStage: "code",
      codeDiffStatus: "FRESH",
      generatedCodeDraftCount: 1,
    }))).toBe(false);
  });


  it("resolves primary workflow commands without embedding side effects in App", () => {
    expect(primaryWorkflowActionCommand(baseArgs({
      activeWorkflowStage: "understand",
      assistantComposerDraft: "",
      selectedNodeId: "node:flow",
      selectedNodeTitle: "提交订单",
      assistantTargetTitle: "订单草稿",
    }))).toMatchObject({
      kind: "SUBMIT_ASSISTANT",
      intent: "EXPLAIN_CODE",
      prompt: expect.stringContaining("提交订单"),
    });

    expect(primaryWorkflowActionCommand(baseArgs({
      activeWorkflowStage: "understand",
      analysisDisplayMode: "CLASS_DIAGRAM",
      assistantComposerDraft: "",
      selectedNodeTitle: "提交订单",
      assistantTargetTitle: "OrderDraft",
    }))).toMatchObject({
      kind: "SUBMIT_ASSISTANT",
      intent: "EXPLAIN_CODE",
      prompt: expect.stringContaining("OrderDraft"),
    });

    expect(primaryWorkflowActionCommand(baseArgs({
      activeWorkflowStage: "qa",
      assistantComposerDraft: "  继续问答  ",
    }))).toEqual({
      kind: "SUBMIT_ASSISTANT",
      intent: "ASK_CODE",
      prompt: "  继续问答  ",
    });
    expect(primaryWorkflowActionCommand(baseArgs({
      activeWorkflowStage: "qa",
      assistantComposerDraft: "",
      selectedNodeId: "node:flow",
    }))).toEqual({
      kind: "OPEN_QA",
      targetNodeId: "node:flow",
    });
    expect(primaryWorkflowActionCommand(baseArgs({
      activeWorkflowStage: "draft",
    }))).toEqual({ kind: "PRIME_GENERATION_PLAN" });
    expect(primaryWorkflowActionCommand(baseArgs({
      activeWorkflowStage: "code",
      codeDiffStatus: "FRESH",
    }))).toEqual({ kind: "WRITE_DRAFTS" });
    expect(primaryWorkflowActionCommand(baseArgs({
      activeWorkflowStage: "code",
      codeDiffStatus: "STALE",
    }))).toEqual({ kind: "REQUEST_CODE_DRAFTS" });
  });
});
