import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { WorkflowTaskbar } from "../../../app/components/WorkflowTaskbar";
import type { WorkflowStageStatus } from "../../../app/workflow/workflowStage";

const stageStates = {
  understand: "done",
  evidence: "blocked",
  qa: "running",
  draft: "idle",
  code: "failed",
} satisfies Record<string, WorkflowStageStatus>;

describe("WorkflowTaskbar", () => {
  it("renders current target, five stage buttons, counts and feedback", () => {
    render(
      <WorkflowTaskbar
        title="OrderController.submit"
        path="src/main/java/OrderController.java:8"
        activeStage="evidence"
        stageStates={stageStates}
        riskCount={2}
        draftCandidateCount={3}
        operationFeedback={{ level: "WARNING", message: "问答正在流式输出" }}
        primaryActionLabel="去问答"
        onStageChange={vi.fn()}
        onImportMermaid={vi.fn()}
        onExportMermaid={vi.fn()}
        onShowDiff={vi.fn()}
        onRequestSync={vi.fn()}
        onOpenSettings={vi.fn()}
        onPrimaryAction={vi.fn()}
      />,
    );

    expect(screen.getByRole("banner", { name: "链路任务栏" })).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "OrderController.submit" })).toBeInTheDocument();
    expect(screen.getByText("src/main/java/OrderController.java:8")).toBeInTheDocument();
    expect(screen.getByText("风险 2")).toBeInTheDocument();
    expect(screen.getByText("候选 3")).toBeInTheDocument();
    expect(screen.getByText("问答正在流式输出")).toBeInTheDocument();

    const stageStrip = screen.getByRole("list", { name: "工作流阶段" });
    expect(within(stageStrip).getAllByRole("button")).toHaveLength(5);
    expect(screen.getByRole("button", { name: /核验证据/ })).toHaveAttribute("aria-current", "step");
    expect(screen.getByRole("button", { name: /风险问答/ })).toHaveTextContent("进行中");
    expect(screen.getByRole("button", { name: /代码落地/ })).toHaveTextContent("失败");
  });

  it("delegates stage changes and task actions", async () => {
    const user = userEvent.setup();
    const onStageChange = vi.fn();
    const onPrimaryAction = vi.fn();
    const onRequestSync = vi.fn();
    const onShowDiff = vi.fn();
    const onImportMermaid = vi.fn();
    const onExportMermaid = vi.fn();
    const onOpenSettings = vi.fn();

    render(
      <WorkflowTaskbar
        title="链路审查"
        activeStage="understand"
        stageStates={stageStates}
        riskCount={0}
        draftCandidateCount={0}
        primaryActionLabel="链路讲解"
        onStageChange={onStageChange}
        onImportMermaid={onImportMermaid}
        onExportMermaid={onExportMermaid}
        onShowDiff={onShowDiff}
        onRequestSync={onRequestSync}
        onOpenSettings={onOpenSettings}
        onPrimaryAction={onPrimaryAction}
      />,
    );

    await user.click(screen.getByRole("button", { name: /核验证据/ }));
    await user.click(screen.getByRole("button", { name: "链路讲解" }));
    await user.click(screen.getByRole("button", { name: "同步预览" }));
    await user.click(screen.getByRole("button", { name: "对比代码" }));
    await user.click(screen.getByRole("button", { name: "导入 Mermaid" }));
    await user.click(screen.getByRole("button", { name: "导出 Mermaid" }));
    await user.click(screen.getByRole("button", { name: "设置" }));

    expect(onStageChange).toHaveBeenCalledWith("evidence");
    expect(onPrimaryAction).toHaveBeenCalledTimes(1);
    expect(onRequestSync).toHaveBeenCalledTimes(1);
    expect(onShowDiff).toHaveBeenCalledTimes(1);
    expect(onImportMermaid).toHaveBeenCalledTimes(1);
    expect(onExportMermaid).toHaveBeenCalledTimes(1);
    expect(onOpenSettings).toHaveBeenCalledTimes(1);
  });
});
