import { render, screen, within } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { AssistantContextBar } from "../../../app/assistant/AssistantContextBar";
import type { AssistantContextSnapshot } from "../../../app/types";

const quotaManagerSignature =
  "kafka.server.ClientRequestQuotaManager.ClientRequestQuotaManager(org.apache.kafka.server.config.ClientQuotaManagerConfig,org.apache.kafka.common.metrics.Metrics,org.apache.kafka.common.utils.Time,java.lang.String,java.util.Optional):kafka.server.ClientRequestQuotaManager";

function context(overrides: Partial<AssistantContextSnapshot> = {}): AssistantContextSnapshot {
  return {
    selectedNodeIds: ["method:quota-manager"],
    selectedDiffItemIds: [],
    analysisDisplayMode: "FLOWCHART",
    currentSceneId: "WORKSPACE_FLOWCHART",
    selectedMethodSignature: quotaManagerSignature,
    scopeLabel: quotaManagerSignature,
    ...overrides,
  };
}

describe("AssistantContextBar", () => {
  it("summarizes duplicate long method context and keeps the full signature in details", () => {
    render(<AssistantContextBar context={context()} />);

    const region = screen.getByRole("region", { name: "下一次发送上下文" });
    const chips = within(region).getAllByText((_, element) =>
      element?.classList.contains("assistant-context-chip") ?? false
    );

    expect(within(region).getByText("ClientRequestQuotaManager.ClientRequestQuotaManager")).toBeInTheDocument();
    expect(within(region).getByText("查看完整方法签名")).toBeInTheDocument();
    expect(within(region).getByText(quotaManagerSignature)).toHaveClass("assistant-context-signature");
    expect(chips.map((chip) => chip.textContent)).toEqual([
      "流程图",
      "ClientRequestQuotaManager.ClientRequestQuotaManager",
      "节点 1",
      "改动 0",
    ]);
  });

  it("does not render a signature details block when no method signature is available", () => {
    render(
      <AssistantContextBar
        context={context({
          selectedMethodSignature: null,
          scopeLabel: "OrderController.submit",
        })}
      />,
    );

    const region = screen.getByRole("region", { name: "下一次发送上下文" });
    expect(within(region).getByText("OrderController.submit")).toHaveClass("assistant-context-chip");
    expect(within(region).queryByText("查看完整方法签名")).not.toBeInTheDocument();
  });

  it("uses class-diagram wording and hides change count in class-diagram context", () => {
    render(
      <AssistantContextBar
        context={context({
          selectedNodeIds: ["class:quota-manager"],
          selectedDiffItemIds: ["change:stale"],
          analysisDisplayMode: "CLASS_DIAGRAM",
          currentSceneId: "WORKSPACE_CLASS_DIAGRAM",
          selectedMethodSignature: null,
          scopeLabel: "ClientRequestQuotaManager",
        })}
      />,
    );

    const region = screen.getByRole("region", { name: "下一次类图发送上下文" });
    const chips = within(region).getAllByText((_, element) =>
      element?.classList.contains("assistant-context-chip") ?? false
    );

    expect(within(region).getByText("AI 类图工作台")).toBeInTheDocument();
    expect(within(region).getByRole("heading", { name: "下一次类图发送上下文" })).toBeInTheDocument();
    expect(within(region).getByText("只影响底部下一次提交，历史回答保留各自上下文。")).toBeInTheDocument();
    expect(chips.map((chip) => chip.textContent)).toEqual([
      "类图",
      "ClientRequestQuotaManager",
      "类图节点 1",
    ]);
    expect(within(region).queryByText("改动 1")).not.toBeInTheDocument();
  });
});
