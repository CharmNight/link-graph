import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { useState } from "react";
import { describe, expect, it } from "vitest";
import type { BridgeInvocationResult } from "../api";
import type { AsyncRequestState, OperationFeedback } from "../types";
import { useBridgeCommandController } from "./useBridgeCommandController";

function Harness({
  result,
}: {
  result: BridgeInvocationResult;
}) {
  const [requestState, setRequestState] = useState<AsyncRequestState | null>(null);
  const [operationFeedback, setOperationFeedback] = useState<OperationFeedback | null>(null);
  const [notice, setNotice] = useState<{ title: string; message: string; detailMessage?: string | null } | null>(
    null,
  );
  const controller = useBridgeCommandController({
    setOperationFeedback,
    setRequestFailureNotice: setNotice,
  });

  return (
    <div>
      <button
        type="button"
        onClick={() => {
          controller.submitAsyncBridgeCommand("源码跳转", () => result, {
            applySubmittedRequestState: setRequestState,
            applyRejectedRequestState: setRequestState,
            successFeedback: {
              level: "INFO",
              message: "正在定位源码：OrderController.submit",
            },
          });
        }}
      >
        提交异步命令
      </button>
      <button
        type="button"
        onClick={() => {
          controller.runBridgeCommand("节点选中同步", () => result, {
            announceFailure: false,
            failureFeedbackLevel: "WARNING",
            failureMessage: "当前节点选择未同步到 IDE。",
          });
        }}
      >
        提交同步命令
      </button>

      <output data-testid="request-phase">{requestState?.phase ?? "NONE"}</output>
      <output data-testid="feedback-level">{operationFeedback?.level ?? "NONE"}</output>
      <output data-testid="feedback-message">{operationFeedback?.message ?? "NONE"}</output>
      <output data-testid="notice-title">{notice?.title ?? "NONE"}</output>
    </div>
  );
}

describe("useBridgeCommandController", () => {
  it("records a submitted async request only after the bridge accepts the command", async () => {
    const user = userEvent.setup();
    render(<Harness result={{ ok: true }} />);

    await user.click(screen.getByRole("button", { name: "提交异步命令" }));

    expect(screen.getByTestId("request-phase").textContent).toBe("RUNNING");
    expect(screen.getByTestId("feedback-message").textContent).toBe("正在定位源码：OrderController.submit");
    expect(screen.getByTestId("notice-title").textContent).toBe("NONE");
  });

  it("publishes failure feedback instead of fake success when the bridge rejects the command", async () => {
    const user = userEvent.setup();
    render(
      <Harness
        result={{
          ok: false,
          message: "IDE bridge 尚未就绪，本次请求没有发出。",
          detailMessage: "JCEF 页面与 IDEA 后端连接尚未建立，请等待页面初始化完成后重试。",
        }}
      />,
    );

    await user.click(screen.getByRole("button", { name: "提交异步命令" }));

    expect(screen.getByTestId("request-phase").textContent).toBe("FAILED");
    expect(screen.getByTestId("feedback-message").textContent).toBe("IDE bridge 尚未就绪，本次请求没有发出。");
    expect(screen.getByTestId("notice-title").textContent).toBe("源码跳转请求未发出");
    expect(screen.queryByText("正在定位源码：OrderController.submit")).not.toBeInTheDocument();
  });

  it("supports feedback-only handling for non-blocking bridge sync commands", async () => {
    const user = userEvent.setup();
    render(
      <Harness
        result={{
          ok: false,
          message: "IDE bridge 尚未就绪，本次请求没有发出。",
          detailMessage: "JCEF 页面与 IDEA 后端连接尚未建立，请等待页面初始化完成后重试。",
        }}
      />,
    );

    await user.click(screen.getByRole("button", { name: "提交同步命令" }));

    expect(screen.getByTestId("feedback-level").textContent).toBe("WARNING");
    expect(screen.getByTestId("feedback-message").textContent).toBe("当前节点选择未同步到 IDE。");
    expect(screen.getByTestId("notice-title").textContent).toBe("NONE");
  });
});
