import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { RiskThreadCard } from "../../../../app/assistant/cards/RiskThreadCard";
import type { InvestigationThread } from "../../../../app/types";

function baseThread(overrides: Partial<InvestigationThread> = {}): InvestigationThread {
  return {
    threadId: "thread-qa-fallback",
    status: "OPEN",
    title: "订单失败分支缺少补偿",
    targetStepIds: [],
    targetNodeIds: ["method:submit-order"],
    summary: "当前实现未处理 RemoteException 后的状态",
    evidenceGap: "缺少 OrderCompensator.invoke 的调用证据",
    recommendedQuestion: "OrderCompensator.invoke 是否在失败时被调用？",
    evidence: [],
    ...overrides,
  };
}

describe("RiskThreadCard", () => {
  it("renders nothing when there are no risk threads", () => {
    const { container } = render(<RiskThreadCard threads={[]} />);
    expect(container.firstChild).toBeNull();
  });

  it("renders each thread title with summary and evidence gap", () => {
    render(
      <RiskThreadCard
        threads={[
          baseThread(),
          baseThread({
            threadId: "thread-retry-budget",
            title: "重试预算超限未触发告警",
            summary: "RetryBudget.exhausted 后没有发指标",
            evidenceGap: "需要 MetricsCollector.record 的证据",
          }),
        ]}
      />,
    );

    expect(screen.getByText("订单失败分支缺少补偿")).toBeInTheDocument();
    expect(screen.getByText("当前实现未处理 RemoteException 后的状态")).toBeInTheDocument();
    expect(screen.getByText("缺少 OrderCompensator.invoke 的调用证据")).toBeInTheDocument();

    expect(screen.getByText("重试预算超限未触发告警")).toBeInTheDocument();
    expect(screen.getByText("RetryBudget.exhausted 后没有发指标")).toBeInTheDocument();

    expect(screen.getByText("2 项")).toBeInTheDocument();
  });

  it("does not render action buttons when neither callback is provided", () => {
    render(<RiskThreadCard threads={[baseThread()]} />);

    expect(screen.queryByRole("button", { name: /继续取证/ })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /暂挂/ })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /接受风险/ })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /排除风险/ })).not.toBeInTheDocument();
  });

  it("renders only the investigate button when onInvestigateThread is provided alone", () => {
    render(
      <RiskThreadCard
        threads={[baseThread()]}
        onInvestigateThread={vi.fn()}
      />,
    );

    expect(screen.getByRole("button", { name: "继续取证：订单失败分支缺少补偿" })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /暂挂/ })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /接受风险/ })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /排除风险/ })).not.toBeInTheDocument();
  });

  it("invokes onInvestigateThread with the threadId when investigate is clicked", async () => {
    const user = userEvent.setup();
    const onInvestigate = vi.fn();

    render(
      <RiskThreadCard
        threads={[baseThread()]}
        onInvestigateThread={onInvestigate}
      />,
    );

    await user.click(screen.getByRole("button", { name: "继续取证：订单失败分支缺少补偿" }));

    expect(onInvestigate).toHaveBeenCalledWith("thread-qa-fallback");
  });

  it("invokes onResolveThread with each resolution status when its button is clicked", async () => {
    const user = userEvent.setup();
    const onResolve = vi.fn();

    render(
      <RiskThreadCard
        threads={[baseThread()]}
        onResolveThread={onResolve}
      />,
    );

    await user.click(screen.getByRole("button", { name: "暂挂风险：订单失败分支缺少补偿" }));
    await user.click(screen.getByRole("button", { name: "接受风险：订单失败分支缺少补偿" }));
    await user.click(screen.getByRole("button", { name: "排除风险：订单失败分支缺少补偿" }));

    expect(onResolve).toHaveBeenNthCalledWith(1, "thread-qa-fallback", "DEFERRED");
    expect(onResolve).toHaveBeenNthCalledWith(2, "thread-qa-fallback", "ACCEPTED_RISK");
    expect(onResolve).toHaveBeenNthCalledWith(3, "thread-qa-fallback", "DISMISSED");
  });

  it("resolves each thread independently when multiple are listed", async () => {
    const user = userEvent.setup();
    const onResolve = vi.fn();

    render(
      <RiskThreadCard
        threads={[
          baseThread(),
          baseThread({
            threadId: "thread-retry-budget",
            title: "重试预算超限未触发告警",
          }),
        ]}
        onResolveThread={onResolve}
      />,
    );

    await user.click(screen.getByRole("button", { name: "暂挂风险：订单失败分支缺少补偿" }));
    await user.click(screen.getByRole("button", { name: "接受风险：重试预算超限未触发告警" }));

    expect(onResolve).toHaveBeenNthCalledWith(1, "thread-qa-fallback", "DEFERRED");
    expect(onResolve).toHaveBeenNthCalledWith(2, "thread-retry-budget", "ACCEPTED_RISK");
  });
});
