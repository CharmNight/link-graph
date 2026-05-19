import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { GenerationPlanPanel } from "../../../app/components/GenerationPlanPanel";
import themeCss from "../../../app/theme.css?raw";

describe("GenerationPlanPanel", () => {
  it("offers a direct generate action in the empty state without waiting for plan eligibility", async () => {
    const user = userEvent.setup();
    const events: string[] = [];

    render(
      <GenerationPlanPanel
        plan={null}
        onRequestGeneratePlan={() => events.push("generate-plan")}
      />,
    );

    expect(screen.getByText("实现建议会基于当前草稿快照生成。")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "生成实现建议" }));

    expect(events).toEqual(["generate-plan"]);
  });

  it("shows a backend-authored running state and explains that implementation suggestions use the current draft snapshot", () => {
    render(
      <GenerationPlanPanel
        plan={null}
        requestState={{
          phase: "RUNNING",
          scene: "实现计划",
          statusMessage: "已提交实现建议请求",
          detailMessage: "等待后端确认执行方式与执行阶段。",
          errorMessage: null,
        }}
        onRequestGeneratePlan={() => undefined}
      />,
    );

    expect(screen.getByText("正在生成实现建议，请稍候。")).toBeInTheDocument();
    expect(screen.getByText("实现建议会基于当前草稿快照生成。")).toBeInTheDocument();
  });

  it("renders suggestion-scoped follow-up controls instead of redirecting back to qa", async () => {
    const user = userEvent.setup();
    const events: string[] = [];

    render(
      <GenerationPlanPanel
        plan={{
          source: "REMOTE",
          summary: "补齐 DTO 与 service 接线。",
          warnings: [],
          promptPreview: null,
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
        discussionQuestionDraft="这里为什么建议改 UploadService？"
        discussionSession={{
          sessionId: "plan-discussion-1",
          messages: [],
          focusItemId: "item-1",
        } as any}
        onDiscussionQuestionDraftChange={(value) => events.push(`draft:${value}`)}
        onSubmitDiscussion={() => events.push("submit-discussion")}
        onRequestGeneratePlan={() => events.push("generate-plan")}
      />,
    );

    expect(screen.getByText("继续追问这份实现建议")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "追问建议" }));

    expect(events).toContain("submit-discussion");
  });

  it("shows the backend failure reason and allows retrying implementation suggestions", async () => {
    const user = userEvent.setup();
    const events: string[] = [];

    render(
      <GenerationPlanPanel
        plan={null}
        requestState={{
          phase: "FAILED",
          scene: "实现计划",
          statusMessage: "实现建议失败",
          errorMessage: "生成实现建议失败：HTTP 503",
        }}
        onRequestGeneratePlan={() => events.push("retry-plan")}
      />,
    );

    expect(screen.getByText("生成实现建议失败：HTTP 503")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "重试生成实现建议" }));

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
        resolveArtifactText={() => null}
        onRequestArtifact={(artifactId) => events.push(`artifact:${artifactId}`)}
        onRequestGeneratePlan={() => events.push("retry-plan")}
      />,
    );

    await user.click(screen.getByRole("button", { name: "查看提示词" }));

    expect(events).toEqual(["artifact:artifact:plan-prompt"]);
  });

  it("marks stale implementation suggestions against the latest draft version", () => {
    render(
      <GenerationPlanPanel
        plan={{
          source: "REMOTE",
          summary: "补齐 DTO 与 service 接线。",
          warnings: [],
          promptPreview: null,
          promptPreviewArtifactId: null,
          items: [],
        } as any}
        draftVersion={3}
        generationPlanDraftVersion={2}
        onRequestGeneratePlan={() => undefined}
      />,
    );

    expect(screen.getByText("当前实现建议基于草稿 v2 生成，当前草稿已更新到 v3，请先刷新实现建议。")).toBeInTheDocument();
    expect(screen.getByText("基于草稿 v2 生成")).toBeInTheDocument();
  });

  it("renders a natural-flow body so implementation suggestions no longer create an inner scroll area", () => {
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
        onRequestGeneratePlan={() => undefined}
      />,
    );

    expect(container.querySelector(".generation-plan-panel > .generation-plan-flow-body")).not.toBeNull();
    expect(themeCss).toMatch(
      /\.generation-plan-panel\s*\{[^}]*display:\s*grid;[^}]*gap:\s*12px;[^}]*min-width:\s*0;[^}]*min-height:\s*0;/s,
    );
    expect(themeCss).toMatch(
      /\.generation-plan-flow-body\s*\{[^}]*min-height:\s*0;[^}]*display:\s*grid;[^}]*overflow:\s*visible;/s,
    );
  });

  it("lets implementation suggestions expand naturally so the workbench owns scrolling", () => {
    const { container } = render(
      <GenerationPlanPanel
        plan={{
          source: "REMOTE",
          summary: "这是一段很长的实现建议摘要，用来验证在结果渲染后不会出现横向滚动，也不会在实现建议内部再套一层纵向滚动。",
          warnings: ["这是一个很长的警告提示，用来验证内容会在卡片内部自然换行，而不是把横向滚动条顶出来。"],
          promptPreview: "这是一个很长的提示词内容，用来验证实现建议面板内部不再自带滚动区域，而是跟随外层工作台自然铺开。",
          promptPreviewArtifactId: null,
          items: [
            {
              id: "item-1",
              title: "处理一个特别长的目标标题，验证标题与风险徽标并排时不会撑出横向滚动区域",
              description: "这里的说明文字也故意写长一点，确保在实现建议输出完成后，整个卡片内容会自动换行并由外层工作台统一滚动。",
              targetPath: "src/main/java/com/example/really/long/path/UploadServiceImplementation.java",
              risk: "MEDIUM",
            },
          ],
        } as any}
        discussionQuestionDraft="为什么建议先改这里？如果这里不改，有没有更小范围的替代方案？"
        onRequestGeneratePlan={() => undefined}
      />,
    );

    expect(container.querySelector(".generation-plan-panel > .generation-plan-flow-body")).not.toBeNull();
    expect(themeCss).toMatch(
      /\.generation-plan-panel\s*\{[^}]*display:\s*grid;[^}]*gap:\s*12px;[^}]*min-width:\s*0;[^}]*min-height:\s*0;/s,
    );
    expect(themeCss).toMatch(
      /\.generation-plan-flow-body\s*\{[^}]*display:\s*grid;[^}]*gap:\s*12px;[^}]*overflow:\s*visible;[^}]*padding-right:\s*0;/s,
    );
    expect(themeCss).toMatch(
      /\.generation-plan-panel\s+\.preview-head\s*\{[^}]*flex-wrap:\s*wrap;/s,
    );
    expect(themeCss).toMatch(
      /\.generation-plan-panel\s+\.preview-head\s*>\s*\*\s*\{[^}]*min-width:\s*0;[^}]*overflow-wrap:\s*anywhere;/s,
    );
  });
});
