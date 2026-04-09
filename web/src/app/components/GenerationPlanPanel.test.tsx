import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { GenerationPlanPanel } from "./GenerationPlanPanel";

describe("GenerationPlanPanel", () => {
  it("offers a direct generate action in the empty state", async () => {
    const user = userEvent.setup();
    const events: string[] = [];

    render(<GenerationPlanPanel plan={null} isRequesting={false} onRequestGeneratePlan={() => events.push("generate-plan")} />);

    expect(screen.getByText("尚未生成计划。点击下方按钮创建实现大纲。")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "生成计划" }));

    expect(events).toEqual(["generate-plan"]);
  });

  it("shows a loading state and explains that planning uses the current working graph", () => {
    render(<GenerationPlanPanel plan={null} isRequesting={true} onRequestGeneratePlan={() => undefined} />);

    expect(screen.getByText("正在生成计划，请稍候。")).toBeInTheDocument();
    expect(screen.getByText("本次计划会基于当前工作图，而不是历史事实快照。")).toBeInTheDocument();
  });

  it("shows the failure reason and allows retrying plan generation", async () => {
    const user = userEvent.setup();
    const events: string[] = [];

    render(
      <GenerationPlanPanel
        plan={null}
        isRequesting={false}
        requestError="生成计划失败：HTTP 503"
        onRequestGeneratePlan={() => events.push("retry-plan")}
      />,
    );

    expect(screen.getByText("生成计划失败：HTTP 503")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "重试生成计划" }));

    expect(events).toEqual(["retry-plan"]);
  });

  it("requests prompt preview on demand when the plan only carries an artifact reference", async () => {
    const user = userEvent.setup();
    const events: string[] = [];

    render(
      <GenerationPlanPanel
        plan={{
          source: "REMOTE",
          summary: "补齐 DTO 与 service 接线。",
          warnings: [],
          promptPreview: null,
          promptPreviewArtifactId: "artifact:plan-prompt",
          items: [],
        } as any}
        isRequesting={false}
        resolveArtifactText={() => null}
        onRequestArtifact={(artifactId) => events.push(`artifact:${artifactId}`)}
        onRequestGeneratePlan={() => events.push("retry-plan")}
      />,
    );

    await user.click(screen.getByRole("button", { name: "查看调试用提示词" }));

    expect(events).toEqual(["artifact:artifact:plan-prompt"]);
  });
});
