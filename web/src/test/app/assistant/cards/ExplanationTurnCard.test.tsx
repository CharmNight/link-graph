import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { ExplanationTurnCard } from "../../../../app/assistant/cards/ExplanationTurnCard";
import type { AssistantTurn } from "../../../../app/types";

function baseTurn(overrides: Partial<AssistantTurn> = {}): AssistantTurn {
  return {
    turnId: "turn-explanation",
    kind: "EXPLANATION",
    createdAtEpochMillis: 2,
    context: {
      selectedNodeIds: [],
      selectedDiffItemIds: [],
      analysisDisplayMode: "FLOWCHART",
      currentSceneId: "WORKSPACE_FLOWCHART",
      selectedMethodSignature: null,
      scopeLabel: "OrderController.submit",
    },
    explanation: {
      source: "LOCAL_RULE",
      granularity: "BUSINESS",
      promptPreview: null,
      warnings: [],
      steps: [
        {
          stepId: "step-receive",
          title: "接收请求",
          granularity: "BUSINESS",
          kind: "METHOD_CALL",
          description: "OrderController.submit 接收 HTTP 请求并校验参数。",
          primaryNodeId: "method:order-controller-submit",
          evidence: [],
          followUpQuestions: [],
          downstreamTargets: [],
        },
        {
          stepId: "step-persist",
          title: "持久化订单",
          granularity: "BUSINESS",
          kind: "METHOD_CALL",
          description: "OrderRepository.save 把订单写入数据库。",
          primaryNodeId: "method:order-repository-save",
          evidence: [],
          followUpQuestions: [],
          downstreamTargets: [],
        },
      ],
    },
    ...overrides,
  } as AssistantTurn;
}

describe("ExplanationTurnCard", () => {
  it("renders placeholder text when result has not returned", () => {
    render(
      <ExplanationTurnCard
        turn={baseTurn({ explanation: undefined })}
        onRevealReference={vi.fn()}
      />,
    );

    expect(screen.getByText("讲解结果尚未返回。")).toBeInTheDocument();
  });

  it("renders failure notice when the turn failed and no result is present", () => {
    render(
      <ExplanationTurnCard
        turn={baseTurn({
          explanation: undefined,
          failure: {
            resultId: "explanation-failure:1",
            message: "讲解超时",
            detailMessage: "HTTP 504",
            phase: "FAILED",
            requestId: 1,
            sourceMessageType: "requestExplanation",
          },
        })}
        onRevealReference={vi.fn()}
      />,
    );

    expect(screen.getByText("讲解超时")).toBeInTheDocument();
  });

  it("renders each step title and description", () => {
    render(
      <ExplanationTurnCard
        turn={baseTurn()}
        onRevealReference={vi.fn()}
      />,
    );

    expect(screen.getByText("接收请求")).toBeInTheDocument();
    expect(screen.getByText("OrderController.submit 接收 HTTP 请求并校验参数。")).toBeInTheDocument();
    expect(screen.getByText("持久化订单")).toBeInTheDocument();
    expect(screen.getByText("OrderRepository.save 把订单写入数据库。")).toBeInTheDocument();
  });

  it("marks the selected step as active via class name", () => {
    const { container } = render(
      <ExplanationTurnCard
        turn={baseTurn()}
        selectedStepId="step-persist"
        onRevealReference={vi.fn()}
      />,
    );

    const activeBlocks = container.querySelectorAll(".assistant-evidence-block.active");
    expect(activeBlocks.length).toBe(1);
    expect(activeBlocks[0]?.querySelector("strong")?.textContent).toBe("持久化订单");
  });

  it("invokes onSelectStep when step button is clicked", async () => {
    const user = userEvent.setup();
    const onSelectStep = vi.fn();

    render(
      <ExplanationTurnCard
        turn={baseTurn()}
        selectedStepId={null}
        onSelectStep={onSelectStep}
        onRevealReference={vi.fn()}
      />,
    );

    await user.click(screen.getByRole("button", { name: /接收请求/ }));

    expect(onSelectStep).toHaveBeenCalledWith("step-receive");
  });

  it("renders 返回上一讲解 button only when canReturnToPrevious and callback are provided", () => {
    const { rerender } = render(
      <ExplanationTurnCard
        turn={baseTurn()}
        canReturnToPrevious={false}
        onReturnToPrevious={vi.fn()}
        onRevealReference={vi.fn()}
      />,
    );

    expect(screen.queryByRole("button", { name: /返回上一讲解/ })).not.toBeInTheDocument();

    rerender(
      <ExplanationTurnCard
        turn={baseTurn()}
        canReturnToPrevious={true}
        onReturnToPrevious={vi.fn()}
        onRevealReference={vi.fn()}
      />,
    );

    expect(screen.getByRole("button", { name: /返回上一讲解/ })).toBeInTheDocument();
  });

  it("invokes onReturnToPrevious with previousSessionLabel aria", async () => {
    const user = userEvent.setup();
    const onReturn = vi.fn();

    render(
      <ExplanationTurnCard
        turn={baseTurn()}
        canReturnToPrevious={true}
        previousSessionLabel="订单提交流程"
        onReturnToPrevious={onReturn}
        onRevealReference={vi.fn()}
      />,
    );

    const button = screen.getByRole("button", { name: "返回上一讲解：订单提交流程" });
    expect(button).toBeInTheDocument();

    await user.click(button);

    expect(onReturn).toHaveBeenCalledTimes(1);
  });

  it("renders history trail buttons when historyTrail and onOpenHistory are provided", async () => {
    const user = userEvent.setup();
    const onOpenHistory = vi.fn();

    render(
      <ExplanationTurnCard
        turn={baseTurn()}
        historyTrail={["订单提交流程", "当前链路讲解"]}
        onOpenHistory={onOpenHistory}
        onRevealReference={vi.fn()}
      />,
    );

    // 历史路径剔除最后一项（当前节点），只剩 1 个可点击项
    const historyButton = screen.getByRole("button", { name: "讲解历史：订单提交流程" });
    expect(historyButton).toBeInTheDocument();

    await user.click(historyButton);

    expect(onOpenHistory).toHaveBeenCalledWith(0);
  });

  it("does not render history trail when historyTrail has only current entry", () => {
    render(
      <ExplanationTurnCard
        turn={baseTurn()}
        historyTrail={["当前链路讲解"]}
        onOpenHistory={vi.fn()}
        onRevealReference={vi.fn()}
      />,
    );

    expect(screen.queryByLabelText("讲解历史路径")).not.toBeInTheDocument();
  });

  it("renders granularity options and invokes onGranularityChange", async () => {
    const user = userEvent.setup();
    const onGranularityChange = vi.fn();

    render(
      <ExplanationTurnCard
        turn={baseTurn()}
        currentGranularity="BUSINESS"
        onGranularityChange={onGranularityChange}
        onRevealReference={vi.fn()}
      />,
    );

    const businessButton = screen.getByRole("button", { name: "按业务级重新解释" });
    // 当前粒度用 class "active" 标记（不使用 aria-pressed）
    expect(businessButton).toHaveClass("active");
    expect(businessButton.querySelector(".workbench-granularity-current")).toBeInTheDocument();

    const methodCallButton = screen.getByRole("button", { name: "按方法调用级重新解释" });
    expect(methodCallButton).toBeInTheDocument();

    await user.click(methodCallButton);

    expect(onGranularityChange).toHaveBeenCalledWith("METHOD_CALL");
  });

  it("disables granularity buttons when granularityRequestRunning is true", async () => {
    const user = userEvent.setup();
    const onGranularityChange = vi.fn();

    render(
      <ExplanationTurnCard
        turn={baseTurn()}
        currentGranularity="BUSINESS"
        granularityRequestRunning={true}
        onGranularityChange={onGranularityChange}
        onRevealReference={vi.fn()}
      />,
    );

    expect(screen.getByText("正在生成回答，完成后可重新选择粒度。")).toBeInTheDocument();

    const methodCallButton = screen.getByRole("button", { name: "按方法调用级重新解释" });
    expect(methodCallButton).toBeDisabled();

    await user.click(methodCallButton);

    expect(onGranularityChange).not.toHaveBeenCalled();
  });

  it("renders follow-up question button when step has followUpQuestions and onFollowUpStep provided", async () => {
    const user = userEvent.setup();
    const onFollowUpStep = vi.fn();

    render(
      <ExplanationTurnCard
        turn={baseTurn({
          explanation: {
            ...baseTurn().explanation!,
            steps: [
              {
                ...baseTurn().explanation!.steps[0],
                followUpQuestions: ["为什么需要校验？"],
              },
              baseTurn().explanation!.steps[1],
            ],
          },
        })}
        onFollowUpStep={onFollowUpStep}
        onRevealReference={vi.fn()}
      />,
    );

    const followUpButton = screen.getByRole("button", { name: "继续追问" });
    await user.click(followUpButton);

    expect(onFollowUpStep).toHaveBeenCalledWith("step-receive", "为什么需要校验？");
  });

  it("renders evidence references and invokes onRevealReference when clicked", async () => {
    const user = userEvent.setup();
    const onRevealReference = vi.fn();

    render(
      <ExplanationTurnCard
        turn={baseTurn({
          explanation: {
            ...baseTurn().explanation!,
            steps: [
              {
                ...baseTurn().explanation!.steps[0],
                evidence: [
                  {
                    id: "evidence-1",
                    claim: "OrderController.submit 入参为 OrderRequest",
                    evidenceLevel: "DIRECT_SOURCE",
                    references: [
                      {
                        nodeId: "method:order-controller-submit",
                        filePath: "src/main/java/com/example/OrderController.java",
                        startLine: 12,
                        endLine: 18,
                      },
                    ],
                  },
                ],
              },
              baseTurn().explanation!.steps[1],
            ],
          },
        })}
        onRevealReference={onRevealReference}
      />,
    );

    await user.click(screen.getByRole("button", { name: /OrderController\.java:12-18/ }));

    expect(onRevealReference).toHaveBeenCalledWith({
      nodeId: "method:order-controller-submit",
      filePath: "src/main/java/com/example/OrderController.java",
      startLine: 12,
      endLine: 18,
    });
  });
});
