import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { CodeDraftPanel } from "../../../app/components/CodeDraftPanel";
import themeCss from "../../../app/theme.css?raw";

function eligibilityDecisionFixture(overrides?: Partial<any>) {
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
  it("offers a direct generate-plan action before any plan exists", async () => {
    const user = userEvent.setup();
    const events: string[] = [];

    render(
      <CodeDraftPanel
        drafts={[]}
        warnings={[]}
        isRequesting={false}
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

  it("requires confirmed draft changes before draft generation can start", async () => {
    const user = userEvent.setup();
    const events: string[] = [];

    render(
      <CodeDraftPanel
        drafts={[]}
        warnings={[]}
        isRequesting={false}
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
        isRequesting={false}
        source={null}
        promptPreview={null}
        writeReport={null}
        hasPlan={true}
        onOpenDraftWorkbench={() => events.push("open-draft")}
        onOpenAuditWorkbench={() => events.push("open-audit")}
        onRequestPlan={() => events.push("request-plan")}
        onRequestDrafts={() => events.push("request-drafts")}
        onWriteDrafts={() => events.push("write-all")}
        onWriteSingleDraft={() => undefined}
        onOpenDraft={() => undefined}
      />,
    );

    expect(screen.getByText("代码阶段准入状态尚未就绪。")).toBeInTheDocument();
    expect(screen.getByText("当前还没有收到代码阶段的统一准入决策，请先回到问答链路等待状态同步完成。")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "前往问答风险" }));
    expect(events).toEqual(["open-audit"]);
  });

  it("offers a direct generate-drafts action once a plan already exists", async () => {
    const user = userEvent.setup();
    const events: string[] = [];

    render(
      <CodeDraftPanel
        drafts={[]}
        warnings={[]}
        isRequesting={false}
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

  it("shows a loading state and clarifies that code drafts do not directly rewrite the graph", () => {
    render(
      <CodeDraftPanel
        drafts={[]}
        warnings={[]}
        isRequesting={true}
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

  it("shows the failure reason and allows retrying draft generation", async () => {
    const user = userEvent.setup();
    const events: string[] = [];

    render(
      <CodeDraftPanel
        drafts={[]}
        warnings={[]}
        isRequesting={false}
        requestError="生成代码 diff 失败：HTTP 503"
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
        isRequesting={false}
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

  it("uses a dedicated scroll body so long draft output is not clipped inside the workbench panel", () => {
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
        isRequesting={false}
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

    expect(container.querySelector(".code-draft-panel > .side-panel-scroll-body")).not.toBeNull();
    expect(themeCss).toMatch(
      /\.code-draft-panel\s*\{[^}]*display:\s*grid;[^}]*grid-template-rows:\s*auto\s+minmax\(0,\s*1fr\);[^}]*min-height:\s*0;[^}]*overflow:\s*hidden;/s,
    );
    expect(themeCss).toMatch(/\.side-panel-scroll-body\s*\{[^}]*min-height:\s*0;[^}]*overflow:\s*auto;/s);
    expect(themeCss).toMatch(/\.prompt-preview\s*\{[^}]*white-space:\s*pre-wrap;[^}]*overflow-wrap:\s*anywhere;[^}]*word-break:\s*break-word;/s);
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
        isRequesting={false}
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
        isRequesting={false}
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
        isRequesting={false}
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
