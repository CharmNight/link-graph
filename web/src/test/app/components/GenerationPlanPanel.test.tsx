import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { GenerationPlanPanel } from "../../../app/components/GenerationPlanPanel";
import themeCss from "../../../app/theme.css?raw";

function eligibilityDecisionFixture(overrides?: Partial<any>) {
  return {
    target: "PLAN",
    stageLabel: "实现计划",
    allowed: true,
    message: "当前可以继续生成实现建议。",
    detailMessage: "风险线程已完成人工决策。",
    blockingThreadIds: [],
    unresolvedThreadIds: [],
    ...overrides,
  };
}

describe("GenerationPlanPanel", () => {
  it("offers a direct generate action in the empty state using implementation-suggestion wording", async () => {
    const user = userEvent.setup();
    const events: string[] = [];

    render(
      <GenerationPlanPanel
        plan={null}
        isRequesting={false}
        eligibilityDecision={eligibilityDecisionFixture()}
        onOpenDraftWorkbench={() => events.push("open-draft")}
        onRequestGeneratePlan={() => events.push("generate-plan")}
      />,
    );

    expect(screen.getByText("当前可以继续生成实现建议。")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "生成实现建议" }));

    expect(events).toEqual(["generate-plan"]);
  });

  it("requires confirmed draft changes before implementation suggestions can start", async () => {
    const user = userEvent.setup();
    const events: string[] = [];

    render(
      <GenerationPlanPanel
        plan={null}
        isRequesting={false}
        eligibilityDecision={eligibilityDecisionFixture({
          allowed: false,
          message: "生成实现建议前请先确认至少一条草稿变更。",
          detailMessage: "当前草稿层为空。先在问答结果中确认候选变更，使草稿层承载已确认的修改目标，再继续生成。",
        })}
        onOpenDraftWorkbench={() => events.push("open-draft")}
        onRequestGeneratePlan={() => events.push("generate-plan")}
      />,
    );

    expect(screen.getByText("生成实现建议前请先确认至少一条草稿变更。")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "前往草稿层" }));

    expect(events).toEqual(["open-draft"]);
  });

  it("blocks plan generation when the eligibility decision is missing instead of falling back to local draft state", async () => {
    const user = userEvent.setup();
    const events: string[] = [];

    render(
      <GenerationPlanPanel
        plan={null}
        isRequesting={false}
        onOpenDraftWorkbench={() => events.push("open-draft")}
        onOpenAuditWorkbench={() => events.push("open-audit")}
        onRequestGeneratePlan={() => events.push("generate-plan")}
      />,
    );

    expect(screen.getByText("实现建议阶段准入状态尚未就绪。")).toBeInTheDocument();
    expect(screen.getByText("当前还没有收到实现建议阶段的统一准入决策，请先回到问答链路等待状态同步完成。")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "前往问答风险" }));

    expect(events).toEqual(["open-audit"]);
  });

  it("shows a loading state and explains that implementation suggestions use the current working graph", () => {
    render(
      <GenerationPlanPanel
        plan={null}
        isRequesting={true}
        eligibilityDecision={eligibilityDecisionFixture()}
        onOpenDraftWorkbench={() => undefined}
        onRequestGeneratePlan={() => undefined}
      />,
    );

    expect(screen.getByText("正在生成实现建议，请稍候。")).toBeInTheDocument();
    expect(screen.getByText("风险线程已完成人工决策。")).toBeInTheDocument();
  });

  it("shows the failure reason and allows retrying implementation suggestions", async () => {
    const user = userEvent.setup();
    const events: string[] = [];

    render(
      <GenerationPlanPanel
        plan={null}
        isRequesting={false}
        requestError="生成实现建议失败：HTTP 503"
        eligibilityDecision={eligibilityDecisionFixture()}
        onOpenDraftWorkbench={() => undefined}
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
        isRequesting={false}
        eligibilityDecision={eligibilityDecisionFixture()}
        onOpenDraftWorkbench={() => undefined}
        resolveArtifactText={() => null}
        onRequestArtifact={(artifactId) => events.push(`artifact:${artifactId}`)}
        onRequestGeneratePlan={() => events.push("retry-plan")}
      />,
    );

    await user.click(screen.getByRole("button", { name: "查看生成提示词" }));

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
        isRequesting={false}
        draftVersion={3}
        generationPlanDraftVersion={2}
        eligibilityDecision={eligibilityDecisionFixture()}
        onOpenDraftWorkbench={() => undefined}
        onRequestGeneratePlan={() => undefined}
      />,
    );

    expect(screen.getByText("当前实现建议基于草稿 v2 生成，当前草稿已更新到 v3，请先刷新实现建议。")).toBeInTheDocument();
    expect(screen.getByText("基于草稿 v2 生成")).toBeInTheDocument();
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
        eligibilityDecision={eligibilityDecisionFixture()}
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
