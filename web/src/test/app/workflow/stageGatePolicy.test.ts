import { describe, expect, it } from "vitest";
import { canEnterStage, type StageGateContext } from "../../../app/workflow/stageGatePolicy";
import type { WorkflowStage } from "../../../app/workflow/workflowStage";

const noRiskNoDraft: StageGateContext = { blockingRiskCount: 0, hasConfirmedDraft: false };
const noRiskWithDraft: StageGateContext = { blockingRiskCount: 0, hasConfirmedDraft: true };
const withRiskNoDraft: StageGateContext = { blockingRiskCount: 2, hasConfirmedDraft: false };
const withRiskWithDraft: StageGateContext = { blockingRiskCount: 2, hasConfirmedDraft: true };

describe("canEnterStage", () => {
  it("allows entering any non-code stage regardless of blocking risk or draft state", () => {
    const stages: WorkflowStage[] = ["understand", "evidence", "qa", "draft"];
    for (const stage of stages) {
      expect(canEnterStage(stage, withRiskNoDraft)).toEqual({ ok: true });
    }
  });

  it("allows entering code when there is no blocking risk and a draft is confirmed", () => {
    expect(canEnterStage("code", noRiskWithDraft)).toEqual({ ok: true });
  });

  it("blocks entering code when blocking risk exists, with an actionable reason", () => {
    const decision = canEnterStage("code", withRiskWithDraft);
    expect(decision.ok).toBe(false);
    if (!decision.ok) {
      expect(decision.reason).toContain("2");
      expect(decision.reason).toMatch(/风险/);
    }
  });

  it("blocks entering code when no draft is confirmed even without blocking risk", () => {
    const decision = canEnterStage("code", noRiskNoDraft);
    expect(decision.ok).toBe(false);
    if (!decision.ok) {
      expect(decision.reason).toMatch(/草稿/);
    }
  });

  it("blocks entering code when blocking risk takes precedence over missing draft", () => {
    // 两个条件都不满足时，阻塞风险的原因应优先提示（更紧急）
    const decision = canEnterStage("code", withRiskNoDraft);
    expect(decision.ok).toBe(false);
    if (!decision.ok) {
      expect(decision.reason).toMatch(/风险/);
    }
  });
});
