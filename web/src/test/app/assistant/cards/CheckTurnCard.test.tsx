import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { CheckTurnCard } from "../../../../app/assistant/cards/CheckTurnCard";
import type { AssistantTurn } from "../../../../app/types";

function baseTurn(overrides: Partial<AssistantTurn> = {}): AssistantTurn {
  return {
    turnId: "turn-check",
    kind: "CHECK_RESULT",
    createdAtEpochMillis: 3,
    context: {
      selectedNodeIds: [],
      selectedDiffItemIds: [],
      analysisDisplayMode: "REVIEW_GRAPH",
      currentSceneId: "WORKSPACE_REVIEW_GRAPH",
      selectedMethodSignature: null,
      scopeLabel: "OrderController.submit",
    },
    check: {
      source: "LOCAL_RULE",
      question: "当前改动是否需要补测试？",
      answer: "需要补充订单提交失败分支的测试。",
      promptPreview: null,
      findings: [],
      candidateChanges: [],
      newCandidateChanges: [],
      warnings: [],
    },
    ...overrides,
  } as AssistantTurn;
}

describe("CheckTurnCard", () => {
  it("renders placeholder text when result has not returned", () => {
    render(
      <CheckTurnCard
        turn={baseTurn({ check: undefined })}
        onRevealReference={vi.fn()}
      />,
    );

    expect(screen.getByText("检查结果尚未返回。")).toBeInTheDocument();
  });

  it("renders failure notice when the turn failed and no result is present", () => {
    render(
      <CheckTurnCard
        turn={baseTurn({
          check: undefined,
          failure: {
            resultId: "check-failure:1",
            message: "检查超时",
            detailMessage: "HTTP 504",
            phase: "FAILED",
            requestId: 1,
            sourceMessageType: "requestCheck",
          },
        })}
        onRevealReference={vi.fn()}
      />,
    );

    expect(screen.getByText("检查超时")).toBeInTheDocument();
    expect(screen.getByText("HTTP 504")).toBeInTheDocument();
  });

  it("renders the question and answer when the check result is present", () => {
    render(
      <CheckTurnCard
        turn={baseTurn()}
        onRevealReference={vi.fn()}
      />,
    );

    expect(screen.getByText("当前改动是否需要补测试？")).toBeInTheDocument();
    expect(screen.getByText("需要补充订单提交失败分支的测试。")).toBeInTheDocument();
  });

  it("does not render evidence section when findings are empty", () => {
    render(
      <CheckTurnCard
        turn={baseTurn()}
        onRevealReference={vi.fn()}
      />,
    );

    expect(screen.queryByText("证据引用")).not.toBeInTheDocument();
  });

  it("renders each evidence finding with its references", () => {
    render(
      <CheckTurnCard
        turn={baseTurn({
          check: {
            ...baseTurn().check!,
            findings: [
              {
                id: "finding-test-evidence",
                claim: "OrderService.submit 未覆盖失败分支",
                evidenceLevel: "DIRECT_SOURCE",
                references: [
                  {
                    nodeId: "method:order-service-submit",
                    filePath: "src/main/java/com/example/OrderService.java",
                    startLine: 22,
                    endLine: 30,
                  },
                ],
              },
            ],
          },
        })}
        onRevealReference={vi.fn()}
      />,
    );

    expect(screen.getByText("证据引用")).toBeInTheDocument();
    expect(screen.getByText("OrderService.submit 未覆盖失败分支")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /OrderService\.java:22-30/ })).toBeInTheDocument();
  });

  it("invokes onRevealReference when an evidence reference is clicked", async () => {
    const user = userEvent.setup();
    const onRevealReference = vi.fn();

    render(
      <CheckTurnCard
        turn={baseTurn({
          check: {
            ...baseTurn().check!,
            findings: [
              {
                id: "finding-test-evidence",
                claim: "OrderService.submit 未覆盖失败分支",
                evidenceLevel: "DIRECT_SOURCE",
                references: [
                  {
                    nodeId: "method:order-service-submit",
                    filePath: "src/main/java/com/example/OrderService.java",
                    startLine: 22,
                    endLine: 30,
                  },
                ],
              },
            ],
          },
        })}
        onRevealReference={onRevealReference}
      />,
    );

    await user.click(screen.getByRole("button", { name: /OrderService\.java:22-30/ }));

    expect(onRevealReference).toHaveBeenCalledWith({
      nodeId: "method:order-service-submit",
      filePath: "src/main/java/com/example/OrderService.java",
      startLine: 22,
      endLine: 30,
    });
  });
});
