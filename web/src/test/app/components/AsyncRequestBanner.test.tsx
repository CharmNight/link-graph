import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { AsyncRequestBanner } from "../../../app/components/AsyncRequestBanner";

describe("AsyncRequestBanner", () => {
  it("describes streaming requests as preview-oriented while they are still running", () => {
    render(
      <AsyncRequestBanner
        requestState={{
          phase: "RUNNING",
          statusMessage: "正在等待远程 LLM 链路讲解响应",
          detailMessage: "当前采用流式输出，界面会持续追加预览。",
          errorMessage: null,
          streaming: true,
          fallbackUsed: false,
          requestId: 7,
          scene: "链路讲解",
          executionMode: "REMOTE_READY",
          providerLabel: "通用 OpenAI Responses",
          model: "gpt-5.4",
          endpointSummary: "example.com/v1/responses",
          promptPreviewAvailable: true,
        }}
      />,
    );

    expect(screen.getByText("流式预览")).toBeInTheDocument();
    expect(screen.getByText("结果后可查看")).toBeInTheDocument();
  });

  it("keeps prompt preview marked as viewable after a request has finished", () => {
    render(
      <AsyncRequestBanner
        requestState={{
          phase: "SUCCEEDED",
          statusMessage: "链路讲解已生成。",
          detailMessage: "远程 LLM 已完成流式输出，并已落地最终结构化结果。",
          errorMessage: null,
          streaming: true,
          fallbackUsed: false,
          requestId: 8,
          scene: "链路讲解",
          executionMode: "REMOTE_READY",
          providerLabel: "通用 OpenAI Responses",
          model: "gpt-5.4",
          endpointSummary: "example.com/v1/responses",
          promptPreviewAvailable: true,
        }}
      />,
    );

    expect(screen.getByText("流式预览")).toBeInTheDocument();
    expect(screen.getByText("可查看")).toBeInTheDocument();
  });

  it("can collapse telemetry details so request metadata does not crowd out the main answer area", async () => {
    const user = userEvent.setup();

    render(
      <AsyncRequestBanner
        telemetryCollapsedByDefault
        requestState={{
          phase: "SUCCEEDED",
          statusMessage: "问答完成，已生成待确认变更。",
          detailMessage: "远程 LLM 已完成流式输出，并已落地最终结构化结果。",
          errorMessage: null,
          streaming: true,
          fallbackUsed: false,
          requestId: 8,
          scene: "问答",
          executionMode: "REMOTE_READY",
          providerLabel: "通用 OpenAI Responses",
          model: "gpt-5.4",
          endpointSummary: "example.com/v1/responses",
          promptPreviewAvailable: true,
        }}
      />,
    );

    expect(screen.getByText("问答完成，已生成待确认变更。")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "展开请求详情" })).toBeInTheDocument();
    expect(screen.queryByText("请求")).not.toBeInTheDocument();
    expect(screen.queryByText("提供方")).not.toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "展开请求详情" }));

    expect(screen.getByRole("button", { name: "收起请求详情" })).toBeInTheDocument();
    expect(document.querySelector(".request-state-banner-details")).not.toBeNull();
    expect(screen.getByText("请求")).toBeInTheDocument();
    expect(screen.getByText("提供方")).toBeInTheDocument();
    expect(screen.getByText("可查看")).toBeInTheDocument();
  });

  it("keeps the status title in a dedicated layout slot so it does not collide with the expand button", () => {
    const { container } = render(
      <AsyncRequestBanner
        telemetryCollapsedByDefault
        requestState={{
          phase: "SUCCEEDED",
          statusMessage: "问答完成，已生成待确认变更。",
          detailMessage: "远程 LLM 已完成流式输出，并已落地最终结构化结果。",
          errorMessage: null,
          streaming: true,
          fallbackUsed: false,
          requestId: 8,
          scene: "问答",
          executionMode: "REMOTE_READY",
          providerLabel: "通用 OpenAI Responses",
          model: "gpt-5.4",
          endpointSummary: "example.com/v1/responses",
          promptPreviewAvailable: true,
        }}
      />,
    );

    expect(container.querySelector(".request-state-banner-head")).not.toBeNull();
    expect(container.querySelector(".request-state-banner-title")).not.toBeNull();
    expect(screen.getByRole("button", { name: "展开请求详情" })).toBeInTheDocument();
  });

  it("falls back to a scene-specific success title when the request finished without an explicit status message", () => {
    render(
      <AsyncRequestBanner
        requestState={{
          phase: "SUCCEEDED",
          scene: "链路讲解",
          finishedAtEpochMillis: 18,
          errorMessage: null,
        }}
      />,
    );

    expect(screen.getByText("链路讲解完成，已更新步骤列表")).toBeInTheDocument();
  });
});
