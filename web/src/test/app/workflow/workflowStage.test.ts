import { describe, expect, it } from "vitest";
import {
  WORKFLOW_STAGE_DEFINITIONS,
  type WorkbenchTab,
  type WorkflowStage,
  workbenchTabToWorkflowStage,
  workflowStageToWorkbenchTab,
  workflowStageStatusLabel,
} from "../../../app/workflow/workflowStage";

describe("workflowStage", () => {
  it("maps legacy workbench tabs to workflow stages", () => {
    expect(workbenchTabToWorkflowStage("explanation")).toBe("understand");
    expect(workbenchTabToWorkflowStage("audit")).toBe("qa");
    expect(workbenchTabToWorkflowStage("draft")).toBe("draft");
    expect(workbenchTabToWorkflowStage("code")).toBe("code");
  });

  it("maps workflow stages back to legacy tabs when a tab exists", () => {
    expect(workflowStageToWorkbenchTab("understand")).toBe("explanation");
    expect(workflowStageToWorkbenchTab("evidence")).toBeNull();
    expect(workflowStageToWorkbenchTab("qa")).toBe("audit");
    expect(workflowStageToWorkbenchTab("draft")).toBe("draft");
    expect(workflowStageToWorkbenchTab("code")).toBe("code");
  });

  it("keeps five ordered workflow stage definitions", () => {
    const orderedStages: WorkflowStage[] = WORKFLOW_STAGE_DEFINITIONS.map((stage) => stage.id);
    const legacyTabs = orderedStages
      .map((stage) => workflowStageToWorkbenchTab(stage))
      .filter((tab): tab is WorkbenchTab => tab != null);

    expect(orderedStages).toEqual(["understand", "evidence", "qa", "draft", "code"]);
    expect(legacyTabs).toEqual(["explanation", "audit", "draft", "code"]);
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
