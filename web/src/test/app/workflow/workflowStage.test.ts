import { describe, expect, it } from "vitest";
import {
  WORKFLOW_STAGE_DEFINITIONS,
  type WorkflowStage,
  workflowStageStatusLabel,
} from "../../../app/workflow/workflowStage";

describe("workflowStage", () => {
  it("keeps five ordered workflow stage definitions", () => {
    const orderedStages: WorkflowStage[] = WORKFLOW_STAGE_DEFINITIONS.map((stage) => stage.id);

    expect(orderedStages).toEqual(["understand", "evidence", "qa", "draft", "code"]);
    expect(WORKFLOW_STAGE_DEFINITIONS.map((stage) => stage.label)).toEqual([
      "理解链路",
      "核验证据",
      "风险问答",
      "草稿确认",
      "代码落地",
    ]);
  });

  it("labels idle workflow stages as not started instead of pending work", () => {
    expect(workflowStageStatusLabel("idle")).toBe("未开始");
  });
});
