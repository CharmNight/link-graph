import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { GenerationPlanPanel } from "./GenerationPlanPanel";
import themeCss from "../theme.css?raw";

describe("GenerationPlanPanel", () => {
  it("offers a direct generate action in the empty state", async () => {
    const user = userEvent.setup();
    const events: string[] = [];

    render(
      <GenerationPlanPanel
        plan={null}
        isRequesting={false}
        hasConfirmedDraftChanges={true}
        onOpenDraftWorkbench={() => events.push("open-draft")}
        onRequestGeneratePlan={() => events.push("generate-plan")}
      />,
    );

    expect(screen.getByText("尚未生成计划。点击下方按钮创建实现大纲。")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "生成计划" }));

    expect(events).toEqual(["generate-plan"]);
  });

  it("requires confirmed draft changes before plan generation can start", async () => {
    const user = userEvent.setup();
    const events: string[] = [];

    render(
      <GenerationPlanPanel
        plan={null}
        isRequesting={false}
        hasConfirmedDraftChanges={false}
        onOpenDraftWorkbench={() => events.push("open-draft")}
        onRequestGeneratePlan={() => events.push("generate-plan")}
      />,
    );

    expect(screen.getByText("请先确认至少一条草稿变更，再生成计划。")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "前往草稿层" }));

    expect(events).toEqual(["open-draft"]);
  });

  it("shows a loading state and explains that planning uses the current working graph", () => {
    render(
      <GenerationPlanPanel
        plan={null}
        isRequesting={true}
        hasConfirmedDraftChanges={true}
        onOpenDraftWorkbench={() => undefined}
        onRequestGeneratePlan={() => undefined}
      />,
    );

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
        hasConfirmedDraftChanges={true}
        onOpenDraftWorkbench={() => undefined}
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
        hasConfirmedDraftChanges={true}
        onOpenDraftWorkbench={() => undefined}
        resolveArtifactText={() => null}
        onRequestArtifact={(artifactId) => events.push(`artifact:${artifactId}`)}
        onRequestGeneratePlan={() => events.push("retry-plan")}
      />,
    );

    await user.click(screen.getByRole("button", { name: "查看调试用提示词" }));

    expect(events).toEqual(["artifact:artifact:plan-prompt"]);
  });

  it("uses a dedicated scroll body so long plans are not clipped inside the workbench panel", () => {
    const { container } = render(
      <GenerationPlanPanel
        plan={{
          source: "REMOTE",
          summary: "补齐 DTO 与 service 接线。",
          warnings: ["提示 1", "提示 2"],
          promptPreview: "prompt",
          promptPreviewArtifactId: null,
          items: [
            {
              id: "item-1",
              title: "处理上传路径",
              description: "在 base url 解析后补路径分支。",
              targetPath: "src/main/java/com/example/UploadService.java",
              risk: "MEDIUM",
            },
          ],
        } as any}
        isRequesting={false}
        hasConfirmedDraftChanges={true}
        onOpenDraftWorkbench={() => undefined}
        onRequestGeneratePlan={() => undefined}
      />,
    );

    expect(container.querySelector(".generation-plan-panel > .side-panel-scroll-body")).not.toBeNull();
    expect(themeCss).toMatch(
      /\.generation-plan-panel\s*\{[^}]*display:\s*grid;[^}]*grid-template-rows:\s*auto\s+auto\s+minmax\(0,\s*1fr\);[^}]*min-height:\s*0;[^}]*overflow:\s*hidden;/s,
    );
    expect(themeCss).toMatch(/\.side-panel-scroll-body\s*\{[^}]*min-height:\s*0;[^}]*overflow:\s*auto;/s);
  });
});
