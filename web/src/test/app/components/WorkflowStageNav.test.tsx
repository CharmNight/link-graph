import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { WorkflowStageNav } from "../../../app/components/WorkflowStageNav";
import type { StageStatusEntry } from "../../../app/workflow/stageStatusModel";

function makeEntries(overrides: Partial<Record<string, Partial<StageStatusEntry>>> = {}): StageStatusEntry[] {
  const base: Array<{ stage: StageStatusEntry["stage"]; label: string }> = [
    { stage: "understand", label: "理解链路" },
    { stage: "evidence", label: "核验证据" },
    { stage: "qa", label: "风险问答" },
    { stage: "draft", label: "草稿确认" },
    { stage: "code", label: "代码落地" },
  ];
  return base.map((b, index) => ({
    stage: b.stage,
    index: index + 1,
    label: b.label,
    status: "idle",
    meta: null,
    ...overrides[b.stage],
  }));
}

describe("WorkflowStageNav", () => {
  it("renders all five stages as a navigation with order numbers", () => {
    render(<WorkflowStageNav entries={makeEntries()} onSelectStage={vi.fn()} />);

    expect(screen.getByRole("navigation", { name: "工作流阶段" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /理解链路/ })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /代码落地/ })).toBeInTheDocument();
    // idle 阶段显示顺序编号，不是 ✓
    expect(screen.getByRole("button", { name: /理解链路/ })).toHaveTextContent("1");
  });

  it("marks the active stage with aria-current=step", () => {
    const entries = makeEntries({ qa: { status: "active", meta: "2 处待确认" } });
    render(<WorkflowStageNav entries={entries} onSelectStage={vi.fn()} />);

    const qaButton = screen.getByRole("button", { name: /风险问答/ });
    expect(qaButton).toHaveAttribute("aria-current", "step");
    expect(qaButton).toHaveTextContent("2 处待确认");
  });

  it("renders a check mark for done stages instead of the number", () => {
    const entries = makeEntries({ understand: { status: "done", meta: "讲解已生成" } });
    render(<WorkflowStageNav entries={entries} onSelectStage={vi.fn()} />);

    const understandButton = screen.getByRole("button", { name: /理解链路/ });
    expect(understandButton).toHaveTextContent("✓");
    expect(understandButton).not.toHaveTextContent("1");
  });

  it("shows a blocked flag and remains clickable when a stage is blocked", () => {
    const entries = makeEntries({ code: { status: "blocked", meta: "1 处阻塞风险" } });
    render(<WorkflowStageNav entries={entries} onSelectStage={vi.fn()} />);

    const codeButton = screen.getByRole("button", { name: /代码落地/ });
    expect(codeButton).toHaveTextContent("阻塞");
    expect(codeButton).toBeEnabled();
  });

  it("invokes onSelectStage with the stage id when clicked", async () => {
    const user = userEvent.setup();
    const onSelectStage = vi.fn();
    render(<WorkflowStageNav entries={makeEntries()} onSelectStage={onSelectStage} />);

    await user.click(screen.getByRole("button", { name: /草稿确认/ }));
    expect(onSelectStage).toHaveBeenCalledWith("draft");
  });
});
