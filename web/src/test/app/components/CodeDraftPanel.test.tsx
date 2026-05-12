import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { CodeDraftPanel } from "../../../app/components/CodeDraftPanel";
import type { StageEligibilityDecision } from "../../../app/types";
import themeCss from "../../../app/theme.css?raw";

function eligibilityDecisionFixture(
  overrides: Partial<StageEligibilityDecision> = {},
): StageEligibilityDecision {
  return {
    target: "CODE",
    stageLabel: "代码草稿",
    allowed: true,
    message: "当前可以继续生成代码 diff。",
    detailMessage: "风险线程已完成人工决策。",
    blockingThreadIds: [],
    unresolvedThreadIds: [],
    ...overrides,
  };
}

describe("CodeDraftPanel", () => {
  it("places draft validation and implementation suggestions above the code diff module", () => {
    const { container } = render(
      <CodeDraftPanel
        {...({
          draftValidationState: {
            status: "READY",
            message: "草稿验证已通过，可以进入代码阶段。",
            detailMessage: null,
            unresolvedThreadIds: [],
            unresolvedThreads: [],
          },
          implementationSuggestion: {
            status: "FRESH",
            source: "REMOTE",
            summary: "先整理 CommonController.fileDownload 的实现路径。",
            warnings: [],
            promptPreview: null,
            promptPreviewArtifactId: null,
            generationPlanDraftVersion: 3,
            items: [
              {
                id: "plan-file-download",
                title: "补删除前置校验",
                description: "先确认 filePath 存在，再执行删除分支。",
                targetPath: "src/main/java/com/example/CommonController.java",
                risk: "MEDIUM",
              },
            ],
          },
        } as any)}
        drafts={[]}
        warnings={[]}
        source={null}
        promptPreview={null}
        writeReport={null}
        hasPlan={true}
        eligibilityDecision={eligibilityDecisionFixture()}
        onOpenDraftWorkbench={() => undefined}
        onRequestPlan={() => undefined}
        onRequestDrafts={() => undefined}
        onWriteDrafts={() => undefined}
        onWriteSingleDraft={() => undefined}
        onOpenDraft={() => undefined}
      />,
    );

    const text = container.textContent ?? "";
    expect(text.indexOf("草稿验证")).toBeGreaterThanOrEqual(0);
    expect(text.indexOf("实现建议")).toBeGreaterThanOrEqual(0);
    expect(text.indexOf("草稿验证")).toBeLessThan(text.indexOf("代码 diff 工作台"));
    expect(text.indexOf("实现建议")).toBeLessThan(text.indexOf("代码 diff 工作台"));
    expect(screen.getByText("先整理 CommonController.fileDownload 的实现路径。")).toBeInTheDocument();
  });

  it("offers a direct generate-plan action before any plan exists", async () => {
    const user = userEvent.setup();
    const events: string[] = [];

    render(
      <CodeDraftPanel
        drafts={[]}
        warnings={[]}
        source={null}
        promptPreview={null}
        writeReport={null}
        hasPlan={false}
        eligibilityDecision={eligibilityDecisionFixture()}
        onOpenDraftWorkbench={() => events.push("open-draft")}
        onRequestPlan={() => events.push("request-plan")}
        onRequestDrafts={() => events.push("request-drafts")}
        onWriteDrafts={() => events.push("write-all")}
        onWriteSingleDraft={() => undefined}
        onOpenDraft={() => undefined}
      />,
    );

    expect(screen.getByText("当前可以继续生成代码 diff。")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "先生成实现建议" }));

    expect(events).toEqual(["request-plan"]);
  });

  it("renders disabled code draft source with existing wording", () => {
    render(
      <CodeDraftPanel
        drafts={[]}
        warnings={[]}
        source="DISABLED"
        promptPreview={null}
        writeReport={null}
        hasPlan={true}
        eligibilityDecision={eligibilityDecisionFixture()}
        onOpenDraftWorkbench={() => undefined}
        onRequestPlan={() => undefined}
        onRequestDrafts={() => undefined}
        onWriteDrafts={() => undefined}
        onWriteSingleDraft={() => undefined}
        onOpenDraft={() => undefined}
      />,
    );

    expect(screen.getByText("来源 未启用")).toBeInTheDocument();
  });

  it("requires confirmed draft changes before draft generation can start", async () => {
    const user = userEvent.setup();
    const events: string[] = [];

    render(
      <CodeDraftPanel
        drafts={[]}
        warnings={[]}
        source={null}
        promptPreview={null}
        writeReport={null}
        hasPlan={true}
        eligibilityDecision={eligibilityDecisionFixture({
          allowed: false,
          message: "生成代码 diff 前请先确认至少一条草稿变更。",
          detailMessage: "当前草稿层为空。先在问答结果中确认候选变更，使草稿层承载已确认的修改目标，再继续生成。",
        })}
        onOpenDraftWorkbench={() => events.push("open-draft")}
        onRequestPlan={() => events.push("request-plan")}
        onRequestDrafts={() => events.push("request-drafts")}
        onWriteDrafts={() => events.push("write-all")}
        onWriteSingleDraft={() => undefined}
        onOpenDraft={() => undefined}
      />,
    );

    expect(screen.getByText("生成代码 diff 前请先确认至少一条草稿变更。")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "前往草稿层" }));

    expect(events).toEqual(["open-draft"]);
  });

  it("blocks draft generation when the eligibility decision is missing instead of falling back to local draft state", async () => {
    const user = userEvent.setup();
    const events: string[] = [];

    render(
      <CodeDraftPanel
        drafts={[]}
        warnings={[]}
        source={null}
        promptPreview={null}
        writeReport={null}
        hasPlan={true}
        onOpenDraftWorkbench={() => events.push("open-draft")}
        onRequestPlan={() => events.push("request-plan")}
        onRequestDrafts={() => events.push("request-drafts")}
        onWriteDrafts={() => events.push("write-all")}
        onWriteSingleDraft={() => undefined}
        onOpenDraft={() => undefined}
      />,
    );

    expect(screen.getByText("代码阶段准入状态尚未就绪。")).toBeInTheDocument();
    expect(screen.getByText("请先回到草稿层完成验证状态同步，再决定是否生成代码 diff。")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "打开草稿验证区" }));
    expect(events).toEqual(["open-draft"]);
  });

  it("routes blocked code generation back to draft validation instead of audit risk", async () => {
    const user = userEvent.setup();
    const events: string[] = [];

    render(
      <CodeDraftPanel
        drafts={[]}
        warnings={[]}
        source={null}
        promptPreview={null}
        writeReport={null}
        hasPlan={true}
        eligibilityDecision={eligibilityDecisionFixture({
          allowed: false,
          message: "生成代码 diff 前请先处理草稿中的待验证风险。",
          detailMessage: "当前草稿仍有待处理风险，请先在草稿验证区完成确认或继续取证。",
          blockingThreadIds: ["thread-risk-1"],
        })}
        onOpenDraftWorkbench={() => events.push("open-draft")}
        onRequestPlan={() => events.push("request-plan")}
        onRequestDrafts={() => events.push("request-drafts")}
        onWriteDrafts={() => events.push("write-all")}
        onWriteSingleDraft={() => undefined}
        onOpenDraft={() => undefined}
      />,
    );

    expect(screen.getByRole("button", { name: "处理阻塞风险" })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "回到草稿验证" })).not.toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "处理阻塞风险" }));

    expect(events).toEqual(["open-draft"]);
  });

  it("offers a direct generate-drafts action once a plan already exists", async () => {
    const user = userEvent.setup();
    const events: string[] = [];

    render(
      <CodeDraftPanel
        drafts={[]}
        warnings={[]}
        source={null}
        promptPreview={null}
        writeReport={null}
        hasPlan={true}
        eligibilityDecision={eligibilityDecisionFixture()}
        onOpenDraftWorkbench={() => events.push("open-draft")}
        onRequestPlan={() => events.push("request-plan")}
        onRequestDrafts={() => events.push("request-drafts")}
        onWriteDrafts={() => events.push("write-all")}
        onWriteSingleDraft={() => undefined}
        onOpenDraft={() => undefined}
      />,
    );

    expect(screen.getByText("当前可以继续生成代码 diff。")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "生成代码 diff" }));

    expect(events).toEqual(["request-drafts"]);
  });

  it("renders safely when eligibility detail is absent for an allowed code stage", () => {
    render(
      <CodeDraftPanel
        drafts={[]}
        warnings={[]}
        source={null}
        promptPreview={null}
        writeReport={null}
        hasPlan={true}
        eligibilityDecision={eligibilityDecisionFixture({
          detailMessage: null,
        })}
        onOpenDraftWorkbench={() => undefined}
        onRequestPlan={() => undefined}
        onRequestDrafts={() => undefined}
        onWriteDrafts={() => undefined}
        onWriteSingleDraft={() => undefined}
        onOpenDraft={() => undefined}
      />,
    );

    expect(screen.getByText("当前可以继续生成代码 diff。")).toBeInTheDocument();
    expect(screen.queryByText("风险线程已完成人工决策。")).not.toBeInTheDocument();
  });

  it("shows a backend-authored running state and clarifies that code drafts do not directly rewrite the graph", () => {
    render(
      <CodeDraftPanel
        drafts={[]}
        warnings={[]}
        requestState={{
          phase: "RUNNING",
          scene: "代码草稿",
          statusMessage: "已提交代码草稿请求",
          detailMessage: "等待后端确认执行方式与执行阶段。",
          errorMessage: null,
        }}
        source={null}
        promptPreview={null}
        writeReport={null}
        hasPlan={true}
        eligibilityDecision={eligibilityDecisionFixture()}
        onOpenDraftWorkbench={() => undefined}
        onRequestPlan={() => undefined}
        onRequestDrafts={() => undefined}
        onWriteDrafts={() => undefined}
        onWriteSingleDraft={() => undefined}
        onOpenDraft={() => undefined}
      />,
    );

    expect(screen.getByText("正在生成代码 diff，请稍候。")).toBeInTheDocument();
    expect(screen.getByText("代码阶段优先展示改了什么，完整正文只作为次级查看。")).toBeInTheDocument();
  });

  it("does not mislabel an in-flight or failed request as local-rule output when the source is still unknown", () => {
    render(
      <CodeDraftPanel
        drafts={[]}
        warnings={[]}
        requestState={{
          phase: "RUNNING",
          scene: "代码草稿",
          statusMessage: "已提交代码草稿请求",
          detailMessage: "等待后端确认执行方式与执行阶段。",
          errorMessage: null,
        }}
        source={null}
        promptPreview={null}
        writeReport={null}
        hasPlan={true}
        eligibilityDecision={eligibilityDecisionFixture()}
        onOpenDraftWorkbench={() => undefined}
        onRequestPlan={() => undefined}
        onRequestDrafts={() => undefined}
        onWriteDrafts={() => undefined}
        onWriteSingleDraft={() => undefined}
        onOpenDraft={() => undefined}
      />,
    );

    expect(screen.queryByText("来源 本地规则")).not.toBeInTheDocument();
  });

  it("shows the backend failure reason and allows retrying draft generation", async () => {
    const user = userEvent.setup();
    const events: string[] = [];

    render(
      <CodeDraftPanel
        drafts={[]}
        warnings={[]}
        requestState={{
          phase: "FAILED",
          scene: "代码草稿",
          statusMessage: "代码 diff 失败",
          errorMessage: "生成代码 diff 失败：HTTP 503",
        }}
        source={null}
        promptPreview={null}
        writeReport={null}
        hasPlan={true}
        eligibilityDecision={eligibilityDecisionFixture()}
        onOpenDraftWorkbench={() => undefined}
        onRequestPlan={() => events.push("request-plan")}
        onRequestDrafts={() => events.push("retry-drafts")}
        onWriteDrafts={() => events.push("write-all")}
        onWriteSingleDraft={() => undefined}
        onOpenDraft={() => undefined}
      />,
    );

    expect(screen.getByText("生成代码 diff 失败：HTTP 503")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "重试生成 diff" }));

    expect(events).toEqual(["retry-drafts"]);
  });

  it("keeps verbose failure diagnostics collapsed so the empty workbench does not get flooded by raw payload text", async () => {
    const user = userEvent.setup();

    render(
      <CodeDraftPanel
        drafts={[]}
        warnings={[]}
        requestState={{
          phase: "FAILED",
          statusMessage: "代码 diff 失败",
          errorMessage: "未生成任何可用代码 diff。",
          detailMessage: [
            "返回内容未通过结构化校验，自动修复重试仍失败。",
            "首次解析错误：payload is required",
            "首次返回片段：{\"summary\":\"...\"}",
          ].join("\n"),
          requestId: 11,
          scene: "代码 diff 生成",
          executionMode: "REMOTE_READY",
          providerLabel: "通用 OpenAI Responses",
          model: "gpt-5.4",
          endpointSummary: "example.com/v1/responses",
          promptPreviewAvailable: true,
        } as any}
        source={null}
        promptPreview={null}
        writeReport={null}
        hasPlan={true}
        eligibilityDecision={eligibilityDecisionFixture()}
        onOpenDraftWorkbench={() => undefined}
        onRequestPlan={() => undefined}
        onRequestDrafts={() => undefined}
        onWriteDrafts={() => undefined}
        onWriteSingleDraft={() => undefined}
        onOpenDraft={() => undefined}
      />,
    );

    expect(screen.getByText("返回内容未通过结构化校验，自动修复重试仍失败。")).toBeInTheDocument();
    expect(screen.queryByText(/首次解析错误：payload is required/)).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "展开请求详情" })).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "展开请求详情" }));

    expect(screen.getByText(/首次解析错误：payload is required/)).toBeInTheDocument();
  });

  it("requests draft content on demand when the transport only includes artifact references", async () => {
    const user = userEvent.setup();
    const events: string[] = [];

    render(
      <CodeDraftPanel
        drafts={[
          {
            id: "draft-1",
            sourceNodeId: "class:order-draft-dto",
            title: "OrderDraftDto.java",
            targetPath: "src/main/java/com/example/OrderDraftDto.java",
            warnings: [],
            content: null,
            contentArtifactId: "artifact:draft-1",
          } as any,
        ]}
        warnings={[]}
        source={null}
        promptPreview={null}
        promptPreviewArtifactId={null}
        resolveArtifactText={() => null}
        onRequestArtifact={(artifactId) => events.push(`artifact:${artifactId}`)}
        writeReport={null}
        hasPlan={true}
        eligibilityDecision={eligibilityDecisionFixture()}
        onOpenDraftWorkbench={() => events.push("open-draft")}
        onRequestPlan={() => events.push("request-plan")}
        onRequestDrafts={() => events.push("request-drafts")}
        onWriteDrafts={() => events.push("write-all")}
        onWriteSingleDraft={() => undefined}
        onOpenDraft={() => undefined}
      />,
    );

    await user.click(screen.getByRole("button", { name: "查看完整内容" }));

    expect(events).toEqual(["artifact:artifact:draft-1"]);
  });

  it("uses the shared prompt disclosure for generated code prompt artifacts", async () => {
    const user = userEvent.setup();
    const events: string[] = [];

    render(
      <CodeDraftPanel
        drafts={[]}
        warnings={[]}
        source={null}
        promptPreview={null}
        promptPreviewArtifactId="artifact:code-prompt"
        resolveArtifactText={() => null}
        onRequestArtifact={(artifactId) => events.push(`artifact:${artifactId}`)}
        writeReport={null}
        hasPlan={true}
        eligibilityDecision={eligibilityDecisionFixture()}
        onOpenDraftWorkbench={() => undefined}
        onRequestPlan={() => undefined}
        onRequestDrafts={() => undefined}
        onWriteDrafts={() => undefined}
        onWriteSingleDraft={() => undefined}
        onOpenDraft={() => undefined}
      />,
    );

    await user.click(screen.getByRole("button", { name: "查看提示词" }));

    expect(events).toEqual(["artifact:artifact:code-prompt"]);
  });

  it("uses independent scroll panes for the code file list and diff detail inside the stage workbench", () => {
    const { container } = render(
      <CodeDraftPanel
        drafts={[
          {
            id: "draft-1",
            sourceNodeId: "method:upload-file",
            title: "UploadService.java",
            targetPath: "src/main/java/com/example/UploadService.java",
            warnings: [],
            content: "class UploadService {}",
            contentArtifactId: null,
          } as any,
        ]}
        warnings={["提示 1", "提示 2"]}
        source="REMOTE"
        promptPreview="prompt"
        promptPreviewArtifactId={null}
        writeReport={null}
        hasPlan={true}
        eligibilityDecision={eligibilityDecisionFixture()}
        onOpenDraftWorkbench={() => undefined}
        onRequestPlan={() => undefined}
        onRequestDrafts={() => undefined}
        onWriteDrafts={() => undefined}
        onWriteSingleDraft={() => undefined}
        onOpenDraft={() => undefined}
      />,
    );

    expect(container.querySelector(".workbench-tab.code-draft-panel > .side-panel-scroll-body.workbench-page-flow")).not.toBeNull();
    expect(themeCss).toMatch(
      /\.workbench-shell\s*\{[^}]*overflow:\s*hidden;/s,
    );
    expect(themeCss).toMatch(/\.workbench-panel-body\s*\{[^}]*overflow-y:\s*auto;[^}]*overflow-x:\s*hidden;/s);
    expect(themeCss).toMatch(/\.stage-workbench-panel\s+\.workbench-panel-body\s*\{[^}]*overflow:\s*hidden;/s);
    expect(themeCss).toMatch(/\.code-draft-panel\s*\{[^}]*grid-template-rows:\s*auto\s+auto\s+auto;[^}]*align-content:\s*start;/s);
    expect(themeCss).toMatch(/\.stage-workbench-panel\s+\.code-draft-panel\s*\{[^}]*grid-template-rows:\s*minmax\(0,\s*0\.42fr\)\s+auto\s+minmax\(0,\s*0\.58fr\);[^}]*overflow:\s*hidden;/s);
    expect(themeCss).toMatch(/\.stage-workbench-panel\s+\.code-stage-analysis-stack\s*\{[^}]*min-height:\s*0;[^}]*position:\s*relative;[^}]*overflow-y:\s*auto;[^}]*overflow-x:\s*hidden;/s);
    expect(themeCss).toMatch(
      /\.code-draft-panel\s*>\s*\.side-panel-scroll-body\s*\{[^}]*min-height:\s*auto;[^}]*overflow:\s*visible;/s,
    );
    expect(themeCss).toMatch(/\.stage-workbench-panel\s+\.code-draft-panel\s*>\s*\.side-panel-scroll-body\s*\{[^}]*min-height:\s*0;/s);
    expect(themeCss).toMatch(/\.stage-workbench-panel\s+\.code-draft-panel\s*>\s*\.code-diff-scroll-region\s*\{[^}]*display:\s*flex;[^}]*flex-direction:\s*column;[^}]*overflow:\s*hidden;/s);
    expect(themeCss).toMatch(/\.stage-workbench-panel\s+\.code-diff-scroll-region\s*>\s*\*\s*\{[^}]*flex:\s*0\s+0\s+auto;/s);
    expect(themeCss).toMatch(/\.stage-workbench-panel\s+\.code-diff-layout\s*\{[^}]*flex:\s*1\s+1\s+auto;[^}]*height:\s*auto;[^}]*overflow:\s*hidden;/s);
    expect(themeCss).toMatch(/\.stage-workbench-panel\s+\.code-diff-file-list,\s*\.stage-workbench-panel\s+\.code-diff-detail\s*\{[^}]*min-height:\s*0;[^}]*overflow-y:\s*auto;[^}]*overflow-x:\s*hidden;/s);
    expect(themeCss).toMatch(/\.prompt-preview\s*\{[^}]*white-space:\s*pre-wrap;[^}]*overflow-wrap:\s*anywhere;[^}]*word-break:\s*break-word;/s);
  });

  it("collapses the code diff split layout based on workbench panel width instead of the full viewport width", () => {
    expect(themeCss).toMatch(
      /@container\s*\(max-width:\s*720px\)\s*\{[^}]*\.code-diff-layout\s*\{[^}]*grid-template-columns:\s*1fr;/s,
    );
  });

  it("keeps code diff payload blocks wrapped inside the panel instead of exposing a horizontal scrollbar", () => {
    expect(themeCss).toMatch(/\.code-diff-detail\s*\{[^}]*min-width:\s*0;/s);
    expect(themeCss).toMatch(/\.code-diff-operation-card\s*\{[^}]*min-width:\s*0;/s);
    expect(themeCss).toMatch(
      /\.code-diff-payload\s*\{[^}]*max-width:\s*100%;[^}]*overflow-x:\s*hidden;[^}]*overflow-wrap:\s*anywhere;[^}]*word-break:\s*break-word;/s,
    );
  });

  it("wraps long diff headers, paths, and file labels so the detail pane cannot widen the panel", () => {
    expect(themeCss).toMatch(/\.code-diff-layout\s*>\s*\*\s*\{[^}]*min-width:\s*0;/s);
    expect(themeCss).toMatch(/\.code-diff-file-list\s*\{[^}]*min-width:\s*0;/s);
    expect(themeCss).toMatch(/\.code-diff-file-button\s*\{[^}]*min-width:\s*0;/s);
    expect(themeCss).toMatch(
      /\.code-diff-file-name,\s*\.code-diff-file-meta\s*\{[^}]*min-width:\s*0;[^}]*max-width:\s*100%;[^}]*overflow-wrap:\s*anywhere;[^}]*word-break:\s*break-word;/s,
    );
    expect(themeCss).toMatch(
      /\.code-diff-detail\s+\.preview-head,\s*\.code-diff-detail\s+\.panel-actions\s*\{[^}]*min-width:\s*0;[^}]*flex-wrap:\s*wrap;/s,
    );
    expect(themeCss).toMatch(
      /\.code-diff-detail\s+\.preview-head\s*>\s*\*,\s*\.code-diff-detail\s+\.muted\s*\{[^}]*min-width:\s*0;[^}]*max-width:\s*100%;[^}]*overflow-wrap:\s*anywhere;[^}]*word-break:\s*break-word;/s,
    );
  });

  it("keeps prompt preview blocks expanded while code diff columns own their own scrolling", () => {
    expect(themeCss).toMatch(/\.workbench-shell\s*\{[^}]*overflow:\s*hidden;/s);
    expect(themeCss).toMatch(/\.workbench-panel-body\s*\{[^}]*overflow-y:\s*auto;[^}]*overflow-x:\s*hidden;/s);
    expect(themeCss).toMatch(
      /\.code-draft-panel\s*>\s*\.side-panel-scroll-body\s*\{[^}]*min-height:\s*auto;[^}]*overflow:\s*visible;/s,
    );
    expect(themeCss).toMatch(/\.stage-workbench-panel\s+\.code-draft-panel\s*>\s*\.code-diff-scroll-region\s*\{[^}]*overflow:\s*hidden;/s);
    expect(themeCss).toMatch(/\.stage-workbench-panel\s+\.code-stage-analysis-stack\s*\{[^}]*overflow-y:\s*auto;/s);
    expect(themeCss).toMatch(/\.stage-workbench-panel\s+\.code-diff-file-list,\s*\.stage-workbench-panel\s+\.code-diff-detail\s*\{[^}]*overflow-y:\s*auto;/s);
    expect(themeCss).toMatch(/@container\s*\(max-width:\s*620px\)\s*\{[\s\S]*\.stage-workbench-panel\s+\.code-diff-layout\s*\{[\s\S]*grid-template-rows:\s*minmax\(0,\s*0\.8fr\)\s+minmax\(0,\s*1\.2fr\);/);
    expect(themeCss).toMatch(
      /\.generation-plan-panel\s+\.prompt-preview\s*\{[^}]*overflow-x:\s*hidden;[^}]*\}[\s\S]*\.code-draft-panel\s+\.prompt-preview,\s*\.code-draft-panel\s+\.generation-plan-panel\s+\.prompt-preview,\s*\.code-draft-panel\s+\.code-diff-payload\s*\{[^}]*min-height:\s*auto;[^}]*overflow:\s*visible;/s,
    );
  });

  it("shows user-facing structured edit summaries instead of raw internal operation codes", () => {
    render(
      <CodeDraftPanel
        drafts={[
          {
            id: "draft-1",
            sourceNodeId: "method:upload-file",
            title: "CommonController.java",
            targetPath: "src/main/java/com/ruoyi/web/controller/common/CommonController.java",
            warnings: [],
            content: "class CommonController {}",
            contentArtifactId: null,
            editOperations: [
              {
                operationId: "op-1",
                filePath: "src/main/java/com/ruoyi/web/controller/common/CommonController.java",
                scopeId: "scope-C1_add_explicit_file_precheck",
                kind: "REPLACE_METHOD_BLOCK",
                payload: "...",
                warnings: [],
              },
            ],
          } as any,
        ]}
        warnings={[]}
        source="REMOTE"
        promptPreview={null}
        promptPreviewArtifactId={null}
        writeReport={null}
        hasPlan={true}
        eligibilityDecision={eligibilityDecisionFixture()}
        onOpenDraftWorkbench={() => undefined}
        onRequestPlan={() => undefined}
        onRequestDrafts={() => undefined}
        onWriteDrafts={() => undefined}
        onWriteSingleDraft={() => undefined}
        onOpenDraft={() => undefined}
      />,
    );

    expect(screen.getByText("预计改动 1 处")).toBeInTheDocument();
    expect(screen.getByText("替换方法代码块 · CommonController.java")).toBeInTheDocument();
    expect(screen.queryByText(/REPLACE_METHOD_BLOCK/)).not.toBeInTheDocument();
    expect(screen.queryByText(/scope-C1_add_explicit_file_precheck/)).not.toBeInTheDocument();
  });

  it("renders prepared patch previews and exposes a native diff action", async () => {
    const user = userEvent.setup();
    const events: string[] = [];

    render(
      <CodeDraftPanel
        drafts={[
          {
            id: "draft-1",
            sourceNodeId: "method:file-download",
            title: "CommonController.java",
            targetPath: "src/main/java/com/example/CommonController.java",
            warnings: [],
            content: null,
            contentArtifactId: null,
            editOperations: [
              {
                operationId: "op-1",
                filePath: "src/main/java/com/example/CommonController.java",
                scopeId: "scope-1",
                kind: "REPLACE_METHOD_BODY",
                payload: "{ return baseUrl.trim(); }",
                warnings: [],
              },
            ],
            preparedEdits: [
              {
                operationId: "op-1",
                filePath: "src/main/java/com/example/CommonController.java",
                scopeId: "scope-1",
                kind: "REPLACE_METHOD_BODY",
                targetSymbolSignature: "com.example.CommonController.fileDownload(java.lang.String):java.lang.String",
                startOffset: 120,
                endOffset: 146,
                beforeText: "{\n    return baseUrl;\n}",
                afterText: "{\n    return baseUrl.trim();\n}",
                warnings: [],
              },
            ],
          } as any,
        ]}
        warnings={[]}
        source="REMOTE"
        promptPreview={null}
        promptPreviewArtifactId={null}
        writeReport={null}
        hasPlan={true}
        eligibilityDecision={eligibilityDecisionFixture()}
        onOpenDraftWorkbench={() => undefined}
        onRequestPlan={() => undefined}
        onRequestDrafts={() => undefined}
        onWriteDrafts={() => undefined}
        onWriteSingleDraft={() => undefined}
        onOpenNativeDiff={(draftId) => events.push(`native-diff:${draftId}`)}
        onOpenDraft={() => undefined}
      />,
    );

    expect(screen.getByText("修改前")).toBeInTheDocument();
    expect(screen.getByText("修改后")).toBeInTheDocument();
    expect(screen.getByText((content) => content.includes("return baseUrl.trim();"))).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "查看真实 Diff" }));

    expect(events).toEqual(["native-diff:draft-1"]);
  });

  it("renders a file list with selected diff detail instead of full-content-first cards", async () => {
    const user = userEvent.setup();

    render(
      <CodeDraftPanel
        drafts={[
          {
            id: "draft-1",
            sourceNodeId: "method:file-download",
            title: "CommonController.java",
            targetPath: "src/main/java/com/example/CommonController.java",
            warnings: [],
            content: "class CommonController {}",
            contentArtifactId: null,
            editOperations: [
              {
                operationId: "op-1",
                filePath: "src/main/java/com/example/CommonController.java",
                scopeId: "scope-1",
                kind: "REPLACE_METHOD_BLOCK",
                payload: "...",
                warnings: [],
              },
            ],
          } as any,
          {
            id: "draft-2",
            sourceNodeId: "class:order-draft-dto",
            title: "OrderDraftDto.java",
            targetPath: "src/main/java/com/example/OrderDraftDto.java",
            warnings: [],
            content: "class OrderDraftDto {}",
            contentArtifactId: null,
            editOperations: [
              {
                operationId: "op-2",
                filePath: "src/main/java/com/example/OrderDraftDto.java",
                scopeId: "scope-2",
                kind: "CREATE_FILE",
                payload: "...",
                warnings: [],
              },
            ],
          } as any,
        ]}
        warnings={[]}
        source="REMOTE"
        promptPreview={null}
        promptPreviewArtifactId={null}
        writeReport={null}
        hasPlan={true}
        eligibilityDecision={eligibilityDecisionFixture()}
        onOpenDraftWorkbench={() => undefined}
        onRequestPlan={() => undefined}
        onRequestDrafts={() => undefined}
        onWriteDrafts={() => undefined}
        onWriteSingleDraft={() => undefined}
        onOpenDraft={() => undefined}
      />,
    );

    expect(screen.getByRole("heading", { name: "代码 diff 工作台" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "选择代码 diff 文件：CommonController.java" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "选择代码 diff 文件：OrderDraftDto.java" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "写入当前文件" })).toBeInTheDocument();
    expect(screen.getByText("src/main/java/com/example/CommonController.java")).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "选择代码 diff 文件：OrderDraftDto.java" }));

    expect(screen.getByText("src/main/java/com/example/OrderDraftDto.java")).toBeInTheDocument();
  });

  it("marks stale code diff results and disables write actions until regenerated", () => {
    render(
      <CodeDraftPanel
        drafts={[
          {
            id: "draft-1",
            sourceNodeId: "method:file-download",
            title: "CommonController.java",
            targetPath: "src/main/java/com/example/CommonController.java",
            warnings: [],
            content: "class CommonController {}",
            contentArtifactId: null,
            editOperations: [
              {
                operationId: "op-1",
                filePath: "src/main/java/com/example/CommonController.java",
                scopeId: "scope-1",
                kind: "REPLACE_METHOD_BLOCK",
                payload: "...",
                warnings: [],
              },
            ],
          } as any,
        ]}
        warnings={[]}
        source="REMOTE"
        promptPreview={null}
        promptPreviewArtifactId={null}
        writeReport={null}
        hasPlan={true}
        draftVersion={3}
        generatedCodeDraftVersion={2}
        eligibilityDecision={eligibilityDecisionFixture()}
        onOpenDraftWorkbench={() => undefined}
        onRequestPlan={() => undefined}
        onRequestDrafts={() => undefined}
        onWriteDrafts={() => undefined}
        onWriteSingleDraft={() => undefined}
        onOpenDraft={() => undefined}
      />,
    );

    expect(screen.getByText("当前代码 diff 基于草稿 v2 生成，当前草稿已更新到 v3，请先重新生成。")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "重新生成 diff" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "写入全部" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "写入当前文件" })).toBeDisabled();
  });
});
