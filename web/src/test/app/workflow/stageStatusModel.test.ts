import { describe, expect, it } from "vitest";
import { deriveStageStatuses, type StageStatusInput } from "../../../app/workflow/stageStatusModel";
import type { AsyncRequestState } from "../../../app/types";
import type { WorkflowStage } from "../../../app/workflow/workflowStage";

const idle: AsyncRequestState = { phase: "IDLE" };
const succeeded: AsyncRequestState = { phase: "SUCCEEDED" };
const running: AsyncRequestState = { phase: "RUNNING" };

function baseInput(overrides: Partial<StageStatusInput> = {}): StageStatusInput {
  return {
    activeStage: "understand" as WorkflowStage,
    graphBeautificationRequestState: idle,
    qaRequestState: idle,
    generationPlanRequestState: idle,
    codeDraftRequestState: idle,
    codeDiffStatus: "MISSING",
    confirmedDraftCount: 0,
    pendingCandidateCount: 0,
    blockingRiskCount: 0,
    generatedCodeDraftCount: 0,
    ...overrides,
  };
}

describe("deriveStageStatuses", () => {
  it("returns 5 ordered entries matching the workflow definitions", () => {
    const entries = deriveStageStatuses(baseInput());
    expect(entries.map((e) => e.stage)).toEqual([
      "understand",
      "evidence",
      "qa",
      "draft",
      "code",
    ]);
    expect(entries.map((e) => e.index)).toEqual([1, 2, 3, 4, 5]);
  });

  it("marks only the active stage as active, even under blocking risk", () => {
    const entries = deriveStageStatuses(baseInput({
      activeStage: "code",
      blockingRiskCount: 2,
    }));
    const code = entries.find((e) => e.stage === "code");
    expect(code?.status).toBe("active");
    expect(code?.meta).toBe("2 处阻塞风险");
  });

  it("marks blocked when the code stage has blocking risk and is not active", () => {
    const entries = deriveStageStatuses(baseInput({
      activeStage: "qa",
      blockingRiskCount: 1,
    }));
    const code = entries.find((e) => e.stage === "code");
    expect(code?.status).toBe("blocked");
    expect(code?.meta).toBe("1 处阻塞风险");
  });

  it("treats understand as done after a successful beautification request", () => {
    const entries = deriveStageStatuses(baseInput({
      activeStage: "qa",
      graphBeautificationRequestState: succeeded,
    }));
    const understand = entries.find((e) => e.stage === "understand");
    const evidence = entries.find((e) => e.stage === "evidence");
    expect(understand?.status).toBe("done");
    expect(evidence?.status).toBe("done");
    expect(understand?.meta).toBe("讲解已生成");
  });

  it("marks draft done when there are confirmed changes", () => {
    const entries = deriveStageStatuses(baseInput({
      activeStage: "code",
      confirmedDraftCount: 3,
    }));
    const draft = entries.find((e) => e.stage === "draft");
    expect(draft?.status).toBe("done");
    expect(draft?.meta).toBe("已采纳 3 项");
  });

  it("marks code done only when diff is fresh and drafts exist", () => {
    const freshNoDrafts = deriveStageStatuses(baseInput({
      activeStage: "understand",
      codeDiffStatus: "FRESH",
      generatedCodeDraftCount: 0,
    }));
    expect(freshNoDrafts.find((e) => e.stage === "code")?.status).toBe("idle");

    const freshWithDrafts = deriveStageStatuses(baseInput({
      activeStage: "understand",
      codeDiffStatus: "FRESH",
      generatedCodeDraftCount: 2,
    }));
    const code = freshWithDrafts.find((e) => e.stage === "code");
    expect(code?.status).toBe("done");
    expect(code?.meta).toBe("已生成 2 条 diff");
  });

  it("exposes pending candidate count as qa meta when present", () => {
    const entries = deriveStageStatuses(baseInput({
      activeStage: "draft",
      pendingCandidateCount: 4,
      qaRequestState: succeeded,
    }));
    const qa = entries.find((e) => e.stage === "qa");
    expect(qa?.meta).toBe("4 处待确认");
  });

  it("does not throw on running requests", () => {
    expect(() => deriveStageStatuses(baseInput({
      graphBeautificationRequestState: running,
      qaRequestState: running,
    }))).not.toThrow();
  });
});
