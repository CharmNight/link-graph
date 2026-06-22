import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { WorkflowTaskbar } from "../../../app/components/WorkflowTaskbar";

describe("WorkflowTaskbar", () => {
  it("renders current target, counts, feedback and a compact stage badge (no stage strip)", () => {
    render(
      <WorkflowTaskbar
        title="OrderController.submit"
        path="src/main/java/OrderController.java:8"
        activeStage="evidence"
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

    // 当前阶段降级为轻量 badge，只展示「现在在哪一步」
    const stageBadge = screen.getByRole("status");
    expect(stageBadge).toHaveTextContent("当前阶段 · 证据");

    // 旧的五段状态条彻底移除
    expect(screen.queryByRole("group", { name: "AI 工作状态" })).not.toBeInTheDocument();
    expect(screen.queryByText("AI 状态")).not.toBeInTheDocument();
    expect(screen.queryByText("理解代码")).not.toBeInTheDocument();
    expect(screen.queryByText("核验证据")).not.toBeInTheDocument();
    expect(screen.queryByText("代码问答")).not.toBeInTheDocument();
    expect(screen.queryByText("草稿确认")).not.toBeInTheDocument();
    expect(screen.queryByText("代码落地")).not.toBeInTheDocument();
    expect(document.querySelectorAll(".assistant-status-item")).toHaveLength(0);
  });

  it("reflects the active stage in the badge and delegates task actions", async () => {
    const user = userEvent.setup();
    const onPrimaryAction = vi.fn();
    const onRequestSync = vi.fn();
    const onShowDiff = vi.fn();
    const onImportMermaid = vi.fn();
    const onExportMermaid = vi.fn();
    const onOpenSettings = vi.fn();

    const { rerender } = render(
      <WorkflowTaskbar
        title="链路审查"
        activeStage="understand"
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
    expect(screen.getByRole("status")).toHaveTextContent("当前阶段 · 理解");

    // badge 是纯展示，不可点
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

    rerender(
      <WorkflowTaskbar
        title="链路审查"
        activeStage="code"
        riskCount={0}
        draftCandidateCount={0}
        primaryActionLabel="生成代码 diff"
        onImportMermaid={onImportMermaid}
        onExportMermaid={onExportMermaid}
        onShowDiff={onShowDiff}
        onRequestSync={onRequestSync}
        onOpenSettings={onOpenSettings}
        onPrimaryAction={onPrimaryAction}
      />,
    );
    expect(screen.getByRole("status")).toHaveTextContent("当前阶段 · 代码");
  });
});
