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
  it("renders current target, counts, feedback and a non-linear assistant status summary", () => {
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

    const assistantStatus = screen.getByRole("group", { name: "AI 工作状态" });
    expect(assistantStatus).toBeInTheDocument();
    expect(within(assistantStatus).getByText("AI 状态")).toBeInTheDocument();
    expect(within(assistantStatus).getByText("理解代码")).toBeInTheDocument();
    expect(within(assistantStatus).getByText("核验证据")).toBeInTheDocument();
    expect(within(assistantStatus).getByText("代码问答")).toBeInTheDocument();
    expect(within(assistantStatus).getByText("草稿确认")).toBeInTheDocument();
    expect(within(assistantStatus).getByText("代码落地")).toBeInTheDocument();
    expect(within(assistantStatus).getByText("完成")).toBeInTheDocument();
    expect(within(assistantStatus).getByText("阻塞")).toBeInTheDocument();
    expect(within(assistantStatus).getByText("进行中")).toBeInTheDocument();
    expect(within(assistantStatus).getByText("未开始")).toBeInTheDocument();
    expect(within(assistantStatus).getByText("失败")).toBeInTheDocument();
    expect(assistantStatus.querySelectorAll(".assistant-status-item")).toHaveLength(5);
    expect(assistantStatus.querySelector(".assistant-status-chip")).not.toBeInTheDocument();

    expect(screen.queryByRole("list", { name: "流程概览" })).not.toBeInTheDocument();
    expect(screen.queryByRole("list", { name: "工作流阶段" })).not.toBeInTheDocument();
    expect(within(assistantStatus).queryByText("1")).not.toBeInTheDocument();
    expect(within(assistantStatus).queryByText("2")).not.toBeInTheDocument();
    expect(within(assistantStatus).queryByText("3")).not.toBeInTheDocument();
    expect(within(assistantStatus).queryByText("4")).not.toBeInTheDocument();
    expect(within(assistantStatus).queryByText("5")).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /理解链路/ })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /核验证据/ })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /风险问答/ })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /草稿确认/ })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /代码落地/ })).not.toBeInTheDocument();
  });

  it("delegates task actions without making assistant status interactive", async () => {
    const user = userEvent.setup();
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
        onImportMermaid={onImportMermaid}
        onExportMermaid={onExportMermaid}
        onShowDiff={onShowDiff}
        onRequestSync={onRequestSync}
        onOpenSettings={onOpenSettings}
        onPrimaryAction={onPrimaryAction}
      />,
    );

    expect(screen.getByRole("group", { name: "AI 工作状态" })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /理解代码/ })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /代码问答/ })).not.toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "链路讲解" }));
    await user.click(screen.getByRole("button", { name: "同步预览" }));
    await user.click(screen.getByRole("button", { name: "对比代码" }));
    await user.click(screen.getByRole("button", { name: "导入 Mermaid" }));
    await user.click(screen.getByRole("button", { name: "导出 Mermaid" }));
    await user.click(screen.getByRole("button", { name: "设置" }));

    expect(onPrimaryAction).toHaveBeenCalledTimes(1);
    expect(onRequestSync).toHaveBeenCalledTimes(1);
    expect(onShowDiff).toHaveBeenCalledTimes(1);
    expect(onImportMermaid).toHaveBeenCalledTimes(1);
    expect(onExportMermaid).toHaveBeenCalledTimes(1);
    expect(onOpenSettings).toHaveBeenCalledTimes(1);
  });
});
