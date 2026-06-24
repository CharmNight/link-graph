import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { ChangeTray } from "../../../app/components/ChangeTray";

describe("ChangeTray", () => {
  it("shows empty state when there are no pending changes or diffs", () => {
    render(
      <ChangeTray
        pendingCandidateCount={0}
        confirmedDraftCount={0}
        blockingRiskCount={0}
        syncStatusLabel="暂无待应用变更"
        codeDiffStatus="MISSING"
        canApply={false}
        canRevert={false}
        onOpenDraft={vi.fn()}
        onOpenDraftCompare={vi.fn()}
        onOpenCode={vi.fn()}
        onApply={vi.fn()}
        onRevert={vi.fn()}
      />,
    );

    expect(screen.getByRole("contentinfo", { name: "变更托盘" })).toBeInTheDocument();
    expect(screen.getByText("暂无待应用变更")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "改动列表" })).toBeEnabled();
    expect(screen.getByRole("button", { name: "写入工程" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "回退" })).toBeDisabled();
  });

  it("renders status counts and delegates tray actions", async () => {
    const user = userEvent.setup();
    const onOpenDraft = vi.fn();
    const onOpenDraftCompare = vi.fn();
    const onOpenCode = vi.fn();
    const onApply = vi.fn();
    const onRevert = vi.fn();

    render(
      <ChangeTray
        pendingCandidateCount={2}
        confirmedDraftCount={1}
        blockingRiskCount={1}
        syncStatusLabel="上次写入 2 个文件"
        codeDiffStatus="FRESH"
        canApply
        canRevert
        onOpenDraft={onOpenDraft}
        onOpenDraftCompare={onOpenDraftCompare}
        onOpenCode={onOpenCode}
        onApply={onApply}
        onRevert={onRevert}
      />,
    );

    expect(screen.getByText("候选 2")).toBeInTheDocument();
    expect(screen.getByText("草稿 1")).toBeInTheDocument();
    expect(screen.getByText("阻塞 1")).toBeInTheDocument();
    expect(screen.getByText("Diff 已就绪")).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "改动列表" }));
    await user.click(screen.getByRole("button", { name: "查看流程变化" }));
    await user.click(screen.getByRole("button", { name: "进入代码" }));
    await user.click(screen.getByRole("button", { name: "写入工程" }));
    await user.click(screen.getByRole("button", { name: "回退" }));

    expect(onOpenDraft).toHaveBeenCalledTimes(1);
    expect(onOpenDraftCompare).toHaveBeenCalledTimes(1);
    expect(onOpenCode).toHaveBeenCalledTimes(1);
    expect(onApply).toHaveBeenCalledTimes(1);
    expect(onRevert).toHaveBeenCalledTimes(1);
  });
});
