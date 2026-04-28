import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { AsyncRequestBanner } from "../../../app/components/AsyncRequestBanner";

describe("AsyncRequestBanner", () => {
  it("keeps streaming preview text collapsed by default so raw LLM payloads do not flood panels", async () => {
    const user = userEvent.setup();

    render(
      <AsyncRequestBanner
        requestState={{
          phase: "RUNNING",
          statusMessage: "正在等待远程 LLM 实现建议生成响应",
          detailMessage: "当前采用流式输出，界面会持续追加预览。",
          errorMessage: null,
          streaming: true,
          previewText: "{\"summary\":\"调整 fileDownload 的删除分支判断\",\"items\":[{\"id\":\"x\"}]}",
          requestId: 10,
          scene: "实现计划",
          executionMode: "REMOTE_READY",
          promptPreviewAvailable: true,
        }}
      />,
    );

    expect(screen.getByText("正在等待远程 LLM 实现建议生成响应")).toBeInTheDocument();
    expect(screen.getByText("当前采用流式输出，界面会持续追加预览。")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "展开请求详情" })).toBeInTheDocument();
    expect(screen.queryByText(/调整 fileDownload 的删除分支判断/)).not.toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "展开请求详情" }));

    expect(screen.getByText(/调整 fileDownload 的删除分支判断/)).toBeInTheDocument();
  });

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
    expect(screen.queryByText("远程 LLM 已完成流式输出，并已落地最终结构化结果。")).not.toBeInTheDocument();
  });

  it("keeps failure detail messages visible so operators still get actionable error context", () => {
    render(
      <AsyncRequestBanner
        requestState={{
          phase: "FAILED",
          statusMessage: "问答失败",
          detailMessage: "请求已重试 3 次，最后一次连接上游超时。",
          errorMessage: "连接上游超时。",
          streaming: false,
          fallbackUsed: false,
          promptPreviewAvailable: false,
        }}
      />,
    );

    expect(screen.getByText("问答失败")).toBeInTheDocument();
    expect(screen.getByText("请求已重试 3 次，最后一次连接上游超时。")).toBeInTheDocument();
  });

  it("collapses multiline failure detail to a short summary when telemetry is collapsed by default", async () => {
    const user = userEvent.setup();

    render(
      <AsyncRequestBanner
        telemetryCollapsedByDefault
        requestState={{
          phase: "FAILED",
          statusMessage: "代码 diff 失败",
          detailMessage: [
            "返回内容未通过结构化校验，自动修复重试仍失败。",
            "首次解析错误：payload is required",
            "首次返回片段：{\"summary\":\"...\"}",
          ].join("\n"),
          errorMessage: "生成代码 diff 失败。",
          streaming: false,
          fallbackUsed: false,
          requestId: 9,
          scene: "代码 diff",
          executionMode: "REMOTE_READY",
          providerLabel: "通用 OpenAI Responses",
          model: "gpt-5.4",
          endpointSummary: "example.com/v1/responses",
          promptPreviewAvailable: true,
        }}
      />,
    );

    expect(screen.getByText("返回内容未通过结构化校验，自动修复重试仍失败。")).toBeInTheDocument();
    expect(screen.queryByText(/首次解析错误：payload is required/)).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "展开请求详情" })).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "展开请求详情" }));

    expect(screen.getByText(/首次解析错误：payload is required/)).toBeInTheDocument();
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
