import { describe, expect, it } from "vitest";
import { canEnterStage, type StageGateContext } from "../../../app/workflow/stageGatePolicy";
import type { WorkflowStage } from "../../../app/workflow/workflowStage";

const noRisk: StageGateContext = { blockingRiskCount: 0 };
const withRisk: StageGateContext = { blockingRiskCount: 2 };

describe("canEnterStage", () => {
  it("allows entering any non-code stage regardless of blocking risk", () => {
    const stages: WorkflowStage[] = ["understand", "evidence", "qa", "draft"];
    for (const stage of stages) {
      expect(canEnterStage(stage, withRisk)).toEqual({ ok: true });
    }
  });

  it("allows entering code when there is no blocking risk", () => {
    expect(canEnterStage("code", noRisk)).toEqual({ ok: true });
  });

  it("blocks entering code when blocking risk exists, with an actionable reason", () => {
    const decision = canEnterStage("code", withRisk);
    expect(decision.ok).toBe(false);
    if (!decision.ok) {
      expect(decision.reason).toContain("2");
      expect(decision.reason).toMatch(/风险/);
    }
  });
});
