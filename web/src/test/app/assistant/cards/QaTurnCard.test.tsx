import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { QaTurnCard } from "../../../../app/assistant/cards/QaTurnCard";
import type { AssistantTurn, QaRequestRecoveryState } from "../../../../app/types";

function baseTurn(overrides: Partial<AssistantTurn> = {}): AssistantTurn {
  return {
    turnId: "turn-qa",
    kind: "QA",
    createdAtEpochMillis: 1,
    context: {
      selectedNodeIds: [],
      selectedDiffItemIds: [],
      analysisDisplayMode: "FLOWCHART",
      currentSceneId: "WORKSPACE_FLOWCHART",
      selectedMethodSignature: null,
      scopeLabel: "OrderController.submit",
    },
    qa: {
      source: "LOCAL_RULE",
      question: "这个方法会影响哪里？",
      answer: "会影响订单提交流程。",
      promptPreview: null,
      findings: [],
      candidateChanges: [],
      newCandidateChanges: [],
      warnings: [],
    },
    ...overrides,
  } as AssistantTurn;
}

function failedRecovery(): QaRequestRecoveryState {
  return {
    lastFailedRequest: {
      requestId: "qa-req-42",
      kind: "ASK",
      question: "为什么这里会失败？",
      mode: "AUTO",
      selectedNodeIds: ["method:submit-order"],
    },
  };
}

describe("QaTurnCard", () => {
  it("renders placeholder text when result has not returned", () => {
    render(
      <QaTurnCard
        turn={baseTurn({ qa: undefined })}
        onRetryLastQaRequest={vi.fn()}
        onEditFailedQaRequest={vi.fn()}
        onRevealReference={vi.fn()}
      />,
    );

    expect(screen.getByText("问答结果尚未返回。")).toBeInTheDocument();
  });

  it("renders failure notice when the turn failed and no result is present", () => {
    render(
      <QaTurnCard
        turn={baseTurn({
          qa: undefined,
          failure: {
            resultId: "qa-failure:1",
            message: "上游超时",
            detailMessage: "HTTP 504",
            phase: "FAILED",
            requestId: 1,
            sourceMessageType: "requestAssistantTask",
          },
        })}
        onRetryLastQaRequest={vi.fn()}
        onEditFailedQaRequest={vi.fn()}
        onRevealReference={vi.fn()}
      />,
    );

    expect(screen.getByText("上游超时")).toBeInTheDocument();
    expect(screen.getByText("HTTP 504")).toBeInTheDocument();
  });

  it("renders the question and answer when the qa result is present", () => {
    render(
      <QaTurnCard
        turn={baseTurn()}
        onRetryLastQaRequest={vi.fn()}
        onEditFailedQaRequest={vi.fn()}
        onRevealReference={vi.fn()}
      />,
    );

    expect(screen.getByText("这个方法会影响哪里？")).toBeInTheDocument();
    expect(screen.getByText("会影响订单提交流程。")).toBeInTheDocument();
  });

  it("does not render evidence section when findings are empty", () => {
    render(
      <QaTurnCard
        turn={baseTurn()}
        onRetryLastQaRequest={vi.fn()}
        onEditFailedQaRequest={vi.fn()}
        onRevealReference={vi.fn()}
      />,
    );

    expect(screen.queryByText("证据引用")).not.toBeInTheDocument();
  });

  it("renders each evidence finding with its references", () => {
    render(
      <QaTurnCard
        turn={baseTurn({
          qa: {
            ...baseTurn().qa!,
            findings: [
              {
                id: "finding-call-site",
                claim: "OrderController.submit 调用了 OrderService.commit",
                evidenceLevel: "DIRECT_SOURCE",
                references: [
                  {
                    nodeId: "method:order-service-commit",
                    filePath: "src/main/java/com/example/OrderController.java",
                    startLine: 12,
                    endLine: 18,
                  },
                ],
              },
            ],
          },
        })}
        onRetryLastQaRequest={vi.fn()}
        onEditFailedQaRequest={vi.fn()}
        onRevealReference={vi.fn()}
      />,
    );

    expect(screen.getByText("证据引用")).toBeInTheDocument();
    expect(screen.getByText("OrderController.submit 调用了 OrderService.commit")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /OrderController\.java:12-18/ })).toBeInTheDocument();
  });

  it("invokes onRevealReference when an evidence reference is clicked", async () => {
    const user = userEvent.setup();
    const onRevealReference = vi.fn();

    render(
      <QaTurnCard
        turn={baseTurn({
          qa: {
            ...baseTurn().qa!,
            findings: [
              {
                id: "finding-call-site",
                claim: "OrderController.submit 调用了 OrderService.commit",
                evidenceLevel: "DIRECT_SOURCE",
                references: [
                  {
                    nodeId: "method:order-service-commit",
                    filePath: "src/main/java/com/example/OrderController.java",
                    startLine: 12,
                    endLine: 18,
                  },
                ],
              },
            ],
          },
        })}
        onRetryLastQaRequest={vi.fn()}
        onEditFailedQaRequest={vi.fn()}
        onRevealReference={onRevealReference}
      />,
    );

    await user.click(screen.getByRole("button", { name: /OrderController\.java:12-18/ }));

    expect(onRevealReference).toHaveBeenCalledWith({
      nodeId: "method:order-service-commit",
      filePath: "src/main/java/com/example/OrderController.java",
      startLine: 12,
      endLine: 18,
    });
  });

  it("does not render recovery actions when no failed request is recorded", () => {
    render(
      <QaTurnCard
        turn={baseTurn()}
        onRetryLastQaRequest={vi.fn()}
        onEditFailedQaRequest={vi.fn()}
        onRevealReference={vi.fn()}
      />,
    );

    expect(screen.queryByRole("button", { name: "直接重试" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "修改后重试" })).not.toBeInTheDocument();
  });

  it("invokes onRetryLastQaRequest and onEditFailedQaRequest when their buttons are clicked", async () => {
    const user = userEvent.setup();
    const onRetry = vi.fn();
    const onEdit = vi.fn();

    render(
      <QaTurnCard
        turn={baseTurn()}
        recoveryState={failedRecovery()}
        onRetryLastQaRequest={onRetry}
        onEditFailedQaRequest={onEdit}
        onRevealReference={vi.fn()}
      />,
    );

    await user.click(screen.getByRole("button", { name: "直接重试" }));
    await user.click(screen.getByRole("button", { name: "修改后重试" }));

    expect(onRetry).toHaveBeenCalledTimes(1);
    expect(onEdit).toHaveBeenCalledTimes(1);
  });
});
