import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { CandidateChangeCard } from "../../../../app/assistant/cards/CandidateChangeCard";
import type { CandidateDraftChange } from "../../../../app/types";

function baseChange(overrides: Partial<CandidateDraftChange> = {}): CandidateDraftChange {
  return {
    changeId: "change-add-fallback",
    status: "PENDING_CONFIRMATION",
    title: "补齐失败补偿路径",
    targetStepIds: [],
    targetNodeIds: ["method:submit-order"],
    reason: "当前分支缺少失败处理",
    impactSummary: "影响订单提交失败分支",
    ...overrides,
  };
}

describe("CandidateChangeCard", () => {
  it("renders nothing when there are no candidate changes", () => {
    const { container } = render(<CandidateChangeCard changes={[]} />);
    expect(container.firstChild).toBeNull();
  });

  it("renders each change title with impact summary when provided", () => {
    render(
      <CandidateChangeCard
        changes={[
          baseChange(),
          baseChange({
            changeId: "change-add-logging",
            title: "补充失败日志埋点",
            impactSummary: "在订单提交失败时记录日志",
          }),
        ]}
      />,
    );

    expect(screen.getByText("补齐失败补偿路径")).toBeInTheDocument();
    expect(screen.getByText("影响订单提交失败分支")).toBeInTheDocument();
    expect(screen.getByText("补充失败日志埋点")).toBeInTheDocument();
    expect(screen.getByText("在订单提交失败时记录日志")).toBeInTheDocument();
    expect(screen.getByText("2 项")).toBeInTheDocument();
  });

  it("falls back to reason when impact summary is empty", () => {
    render(
      <CandidateChangeCard
        changes={[baseChange({ impactSummary: "" })]}
      />,
    );

    expect(screen.getByText("当前分支缺少失败处理")).toBeInTheDocument();
  });

  it("does not render confirm button when onConfirmCandidateChange is missing", () => {
    render(<CandidateChangeCard changes={[baseChange()]} />);

    expect(screen.queryByRole("button", { name: /确认候选变更/ })).not.toBeInTheDocument();
  });

  it("invokes onConfirmCandidateChange with the changeId when the confirm button is clicked", async () => {
    const user = userEvent.setup();
    const onConfirm = vi.fn();

    render(
      <CandidateChangeCard
        changes={[baseChange()]}
        onConfirmCandidateChange={onConfirm}
      />,
    );

    await user.click(screen.getByRole("button", { name: "确认候选变更：补齐失败补偿路径" }));

    expect(onConfirm).toHaveBeenCalledWith("change-add-fallback");
    expect(onConfirm).toHaveBeenCalledTimes(1);
  });

  it("confirms each change independently when multiple are listed", async () => {
    const user = userEvent.setup();
    const onConfirm = vi.fn();

    render(
      <CandidateChangeCard
        changes={[
          baseChange(),
          baseChange({
            changeId: "change-add-logging",
            title: "补充失败日志埋点",
          }),
        ]}
        onConfirmCandidateChange={onConfirm}
      />,
    );

    await user.click(screen.getByRole("button", { name: "确认候选变更：补充失败日志埋点" }));
    await user.click(screen.getByRole("button", { name: "确认候选变更：补齐失败补偿路径" }));

    expect(onConfirm).toHaveBeenNthCalledWith(1, "change-add-logging");
    expect(onConfirm).toHaveBeenNthCalledWith(2, "change-add-fallback");
  });
});
