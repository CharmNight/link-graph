import { describe, expect, it } from "vitest";
import {
  primaryWorkflowActionActionLabel,
  primaryWorkflowActionCommand,
  primaryWorkflowActionDisabled,
  primaryWorkflowActionLabel,
  primaryWorkflowActionSubtitle,
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
    blockingRiskCount: 0,
    assistantComposerDraft: "",
    selectedNodeId: null,
    selectedNodeTitle: null,
    assistantTargetTitle: null,
    ...overrides,
  };
}

describe("appPrimaryWorkflowAction", () => {
  it("keeps the primary label fixed as 下一步 regardless of stage, delegating detail to subtitle", () => {
    // 主标题固定「下一步」，位置语义稳定；具体动作交给 actionLabel / subtitle
    expect(primaryWorkflowActionLabel(baseArgs())).toBe("下一步");
    expect(primaryWorkflowActionLabel(baseArgs({
      analysisDisplayMode: "CLASS_DIAGRAM",
    }))).toBe("下一步");
    expect(primaryWorkflowActionLabel(baseArgs({
      activeWorkflowStage: "qa",
      analysisDisplayMode: "CLASS_DIAGRAM",
    }))).toBe("下一步");
    expect(primaryWorkflowActionLabel(baseArgs({
      activeWorkflowStage: "code",
      codeDiffStatus: "FRESH",
    }))).toBe("下一步");
  });

  it("carries the concrete action in actionLabel and combines position+action in subtitle", () => {
    expect(primaryWorkflowActionActionLabel(baseArgs())).toBe("生成链路讲解");
    expect(primaryWorkflowActionActionLabel(baseArgs({
      analysisDisplayMode: "CLASS_DIAGRAM",
    }))).toBe("解释类关系");
    expect(primaryWorkflowActionActionLabel(baseArgs({
      activeWorkflowStage: "code",
      codeDiffStatus: "FRESH",
    }))).toBe("写入全部 diff");

    // subtitle = 阶段位置 · 具体动作
    expect(primaryWorkflowActionSubtitle(baseArgs())).toBe("阶段 1 / 5 · 理解链路 · 生成链路讲解");
  });

  it("uses 处理中 label while async requests are active", () => {
    expect(primaryWorkflowActionLabel(baseArgs({
      graphBeautificationRequestState: running,
    }))).toBe("处理中");
    expect(primaryWorkflowActionLabel(baseArgs({
      activeWorkflowStage: "draft",
      generationPlanRequestState: running,
    }))).toBe("处理中");
    expect(primaryWorkflowActionLabel(baseArgs({
      activeWorkflowStage: "code",
      codeDraftRequestState: running,
    }))).toBe("处理中");
  });

  it("disables the primary action for active work, empty fresh code drafts, or blocking risk before write", () => {
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
    expect(primaryWorkflowActionDisabled(baseArgs({
      activeWorkflowStage: "code",
      codeDiffStatus: "FRESH",
      generatedCodeDraftCount: 1,
      blockingRiskCount: 1,
    }))).toBe(true);
  });


  it("resolves subtitle as position plus concrete action for every stage", () => {
    expect(primaryWorkflowActionSubtitle(baseArgs())).toBe("阶段 1 / 5 · 理解链路 · 生成链路讲解");
    expect(primaryWorkflowActionSubtitle(baseArgs({ activeWorkflowStage: "evidence" }))).toBe("阶段 2 / 5 · 核验证据 · 去问答");
    expect(primaryWorkflowActionSubtitle(baseArgs({ activeWorkflowStage: "qa" }))).toBe("阶段 3 / 5 · 风险问答 · 代码问答");
    expect(primaryWorkflowActionSubtitle(baseArgs({ activeWorkflowStage: "draft" }))).toBe("阶段 4 / 5 · 采纳改动 · 生成实现建议");
    expect(primaryWorkflowActionSubtitle(baseArgs({ activeWorkflowStage: "code" }))).toBe("阶段 5 / 5 · 写入代码 · 生成代码 diff");
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
