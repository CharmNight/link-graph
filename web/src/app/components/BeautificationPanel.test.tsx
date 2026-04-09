import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { vi } from "vitest";
import { BeautificationPanel } from "./BeautificationPanel";

describe("BeautificationPanel", () => {
  it("shows an explicit generate action before any explanation is generated", async () => {
    const user = userEvent.setup();
    const onRequestBeautification = vi.fn();

    render(
      <BeautificationPanel
        result={null}
        isRequesting={false}
        onRequestBeautification={onRequestBeautification}
      />,
    );

    expect(screen.getByText("链路讲解")).toBeInTheDocument();
    expect(screen.getByText("当前只是打开了“讲解”页签，还没有真正生成讲解。")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "开始生成讲解" })).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "开始生成讲解" }));
    expect(onRequestBeautification).toHaveBeenCalledTimes(1);
  });

  it("shows a loading state while a new explanation is being generated", () => {
    render(<BeautificationPanel result={null} isRequesting={true} />);

    expect(screen.getByText("正在生成链路讲解，请稍候。")).toBeInTheDocument();
    expect(screen.getByText("讲解会优先读取当前画布与当前方法视图，不会继续沿用上一轮结果。")).toBeInTheDocument();
  });

  it("shows the failure reason and allows retrying explanation generation", async () => {
    const user = userEvent.setup();
    const onRequestBeautification = vi.fn();

    render(
      <BeautificationPanel
        result={null}
        isRequesting={false}
        requestError="生成链路讲解失败：HTTP 503"
        onRequestBeautification={onRequestBeautification}
      />,
    );

    expect(screen.getByText("生成链路讲解失败：HTTP 503")).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "重试生成讲解" }));
    expect(onRequestBeautification).toHaveBeenCalledTimes(1);
  });

  it("renders beautification summary sections and keeps the raw prompt collapsed by default", async () => {
    const user = userEvent.setup();

    render(
      <BeautificationPanel
        isRequesting={false}
        result={{
          source: "MOCK",
          summaryTitle: "当前方法讲解",
          summary: "先看当前方法，再看跨方法扩展。",
          sections: [
            {
              id: "current-method",
              title: "当前方法内部",
              content: "先做参数处理，再调用下游方法。",
            },
          ],
          findings: [
            {
              id: "direct-place-draft",
              claim: "当前方法直接调用了 placeDraft。",
              evidenceLevel: "DIRECT_SOURCE",
              references: [
                {
                  nodeId: "method:order-service-place",
                  filePath: "/tmp/OrderService.java",
                  startLine: 12,
                  endLine: 14,
                },
              ],
            },
          ],
          promptPreview: "beautification prompt preview",
          warnings: ["当前结果来自占位实现。"],
        }}
      />,
    );

    expect(screen.getByText("当前方法讲解")).toBeInTheDocument();
    expect(screen.getByText("先看当前方法，再看跨方法扩展。")).toBeInTheDocument();
    expect(screen.getByText("当前方法内部")).toBeInTheDocument();
    expect(screen.getByText("先做参数处理，再调用下游方法。")).toBeInTheDocument();
    expect(screen.getByText("当前结果来自占位实现。")).toBeInTheDocument();
    expect(screen.getByText("来源 本地规则")).toBeInTheDocument();
    expect(screen.getByText("真实性边界")).toBeInTheDocument();
    expect(screen.getByText("关键结论与证据")).toBeInTheDocument();
    expect(screen.getByText("当前方法直接调用了 placeDraft。")).toBeInTheDocument();
    expect(screen.getByText("直接源码")).toBeInTheDocument();
    expect(screen.getByText("/tmp/OrderService.java:12-14")).toBeInTheDocument();
    expect(
      screen.getByText("当前结果来自本地规则整理，只覆盖当前画布、图关系和已采集源码片段，不等于完整源码真值。"),
    ).toBeInTheDocument();
    expect(screen.queryByText("beautification prompt preview")).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "查看调试用提示词" })).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "查看调试用提示词" }));
    expect(screen.getByText("beautification prompt preview")).toBeInTheDocument();
  });
});
