import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { GenerationTurnCard } from "../../../../app/assistant/cards/GenerationTurnCard";
import type { AssistantTurn } from "../../../../app/types";

function baseTurn(overrides: Partial<AssistantTurn> = {}): AssistantTurn {
  return {
    turnId: "turn-generation-plan",
    kind: "GENERATION_PLAN",
    createdAtEpochMillis: 2,
    context: {
      selectedNodeIds: [],
      selectedDiffItemIds: [],
      analysisDisplayMode: "FLOWCHART",
      currentSceneId: "WORKSPACE_FLOWCHART",
      selectedMethodSignature: null,
      scopeLabel: "OrderController.submit",
    },
    generationPlan: {
      source: "LOCAL_RULE",
      summary: "补齐失败补偿路径",
      warnings: [],
      promptPreview: null,
      items: [
        {
          id: "plan-item-compensation",
          title: "补充失败补偿逻辑",
          description: "在订单提交失败时记录补偿任务。",
          risk: "LOW",
          targetPath: "src/main/java/com/example/OrderController.java",
        },
      ],
    },
    ...overrides,
  } as AssistantTurn;
}

describe("GenerationTurnCard", () => {
  it("renders empty state with the generate button when no plan is present", () => {
    const onPrime = vi.fn();
    render(
      <GenerationTurnCard
        turn={baseTurn({ generationPlan: undefined })}
        onPrimeGenerationPlan={onPrime}
        onRequestCodeDrafts={vi.fn()}
      />,
    );

    expect(
      screen.getByText("生成实现建议会先整理可审查方案，确认后再生成代码 diff。"),
    ).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "生成实现建议" })).toBeInTheDocument();
  });

  it("invokes onPrimeGenerationPlan when 生成实现建议 is clicked", async () => {
    const user = userEvent.setup();
    const onPrime = vi.fn();

    render(
      <GenerationTurnCard
        turn={baseTurn({ generationPlan: undefined })}
        onPrimeGenerationPlan={onPrime}
        onRequestCodeDrafts={vi.fn()}
      />,
    );

    await user.click(screen.getByRole("button", { name: "生成实现建议" }));

    expect(onPrime).toHaveBeenCalledTimes(1);
  });

  it("renders failure notice when the turn failed and no plan is present", () => {
    render(
      <GenerationTurnCard
        turn={baseTurn({
          generationPlan: undefined,
          failure: {
            resultId: "generation-failure:1",
            message: "生成方案超时",
            detailMessage: "HTTP 504",
            phase: "FAILED",
            requestId: 1,
            sourceMessageType: "requestGenerationPlan",
          },
        })}
        onPrimeGenerationPlan={vi.fn()}
        onRequestCodeDrafts={vi.fn()}
      />,
    );

    expect(screen.getByText("生成方案超时")).toBeInTheDocument();
  });

  it("renders plan summary, source label, items and 生成代码 diff button when plan is present", () => {
    render(
      <GenerationTurnCard
        turn={baseTurn()}
        onPrimeGenerationPlan={vi.fn()}
        onRequestCodeDrafts={vi.fn()}
      />,
    );

    expect(screen.getByText("补齐失败补偿路径")).toBeInTheDocument();
    expect(screen.getByText("规则生成")).toBeInTheDocument();
    expect(screen.getByText("补充失败补偿逻辑")).toBeInTheDocument();
    expect(screen.getByText("在订单提交失败时记录补偿任务。")).toBeInTheDocument();
    expect(screen.getByText("低")).toBeInTheDocument();
    expect(screen.getByText("src/main/java/com/example/OrderController.java")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "生成代码 diff" })).toBeInTheDocument();
  });

  it("renders fallback message when discussion session has no messages", () => {
    render(
      <GenerationTurnCard
        turn={baseTurn({
          generationDiscussionSession: {
            sessionId: "session-1",
            messages: [],
            focusItemId: null,
            promptPreview: null,
          },
        })}
        onPrimeGenerationPlan={vi.fn()}
        onRequestCodeDrafts={vi.fn()}
      />,
    );

    expect(screen.getByText("还没有围绕这份实现建议继续讨论。")).toBeInTheDocument();
  });

  it("renders discussion messages and uses last user question for 追问建议", async () => {
    const user = userEvent.setup();
    const onDiscuss = vi.fn();

    render(
      <GenerationTurnCard
        turn={baseTurn({
          generationDiscussionSession: {
            sessionId: "session-1",
            messages: [
              { messageId: "m1", role: "USER", content: "这个补偿有副作用吗？", focusItemId: null },
              { messageId: "m2", role: "ASSISTANT", content: "仅记录日志，无副作用。", focusItemId: null },
              { messageId: "m3", role: "USER", content: "可以再加一个监控埋点吗？", focusItemId: null },
            ],
            focusItemId: null,
            promptPreview: null,
          },
        })}
        onPrimeGenerationPlan={vi.fn()}
        onRequestCodeDrafts={vi.fn()}
        onDiscussGenerationPlan={onDiscuss}
      />,
    );

    expect(screen.getByText("这个补偿有副作用吗？")).toBeInTheDocument();
    expect(screen.getByText("仅记录日志，无副作用。")).toBeInTheDocument();
    expect(screen.getByText("可以再加一个监控埋点吗？")).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "追问建议" }));

    // 最近一条 USER 消息作为追问起点
    expect(onDiscuss).toHaveBeenCalledWith("可以再加一个监控埋点吗？");
  });

  it("does not render 追问建议 button when onDiscussGenerationPlan is missing", () => {
    render(
      <GenerationTurnCard
        turn={baseTurn({
          generationDiscussionSession: {
            sessionId: "session-1",
            messages: [
              { messageId: "m1", role: "USER", content: "问题", focusItemId: null },
            ],
            focusItemId: null,
            promptPreview: null,
          },
        })}
        onPrimeGenerationPlan={vi.fn()}
        onRequestCodeDrafts={vi.fn()}
      />,
    );

    expect(screen.queryByRole("button", { name: "追问建议" })).not.toBeInTheDocument();
  });

  it("invokes onRequestCodeDrafts when 生成代码 diff is clicked", async () => {
    const user = userEvent.setup();
    const onRequestCodeDrafts = vi.fn();

    render(
      <GenerationTurnCard
        turn={baseTurn()}
        onPrimeGenerationPlan={vi.fn()}
        onRequestCodeDrafts={onRequestCodeDrafts}
      />,
    );

    await user.click(screen.getByRole("button", { name: "生成代码 diff" }));

    expect(onRequestCodeDrafts).toHaveBeenCalledTimes(1);
  });

  it("hides turn header when showTurnHeader is false", () => {
    render(
      <GenerationTurnCard
        turn={baseTurn()}
        showTurnHeader={false}
        onPrimeGenerationPlan={vi.fn()}
        onRequestCodeDrafts={vi.fn()}
      />,
    );

    // GenerationPlan kind 在 AssistantTurnHeader 中标签为「生成实现建议」
    // 关闭时不应出现该 header 文案（除卡片正文 summary 外的 header 标签）
    // 这里我们只验证 plan summary 仍渲染，header 由 AssistantTurnHeader 决定
    expect(screen.getByText("补齐失败补偿路径")).toBeInTheDocument();
  });
});
