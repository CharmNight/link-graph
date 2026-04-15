import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { CodeDraftPanel } from "./CodeDraftPanel";
import themeCss from "../theme.css?raw";

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
        hasConfirmedDraftChanges={true}
        onOpenDraftWorkbench={() => events.push("open-draft")}
        onRequestPlan={() => events.push("request-plan")}
        onRequestDrafts={() => events.push("request-drafts")}
        onWriteDrafts={() => events.push("write-all")}
        onWriteSingleDraft={() => undefined}
        onOpenDraft={() => undefined}
      />,
    );

    expect(screen.getByText("还没有代码草稿。请先生成计划，再继续生成草稿。")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "先生成计划" }));

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
        hasConfirmedDraftChanges={false}
        onOpenDraftWorkbench={() => events.push("open-draft")}
        onRequestPlan={() => events.push("request-plan")}
        onRequestDrafts={() => events.push("request-drafts")}
        onWriteDrafts={() => events.push("write-all")}
        onWriteSingleDraft={() => undefined}
        onOpenDraft={() => undefined}
      />,
    );

    expect(screen.getByText("请先确认至少一条草稿变更，再生成代码草稿。")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "前往草稿层" }));

    expect(events).toEqual(["open-draft"]);
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
        hasConfirmedDraftChanges={true}
        onOpenDraftWorkbench={() => events.push("open-draft")}
        onRequestPlan={() => events.push("request-plan")}
        onRequestDrafts={() => events.push("request-drafts")}
        onWriteDrafts={() => events.push("write-all")}
        onWriteSingleDraft={() => undefined}
        onOpenDraft={() => undefined}
      />,
    );

    expect(screen.getByText("还没有代码草稿。当前已经有实现计划，可以直接继续生成草稿。")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "生成草稿" }));

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
        hasConfirmedDraftChanges={true}
        onOpenDraftWorkbench={() => undefined}
        onRequestPlan={() => undefined}
        onRequestDrafts={() => undefined}
        onWriteDrafts={() => undefined}
        onWriteSingleDraft={() => undefined}
        onOpenDraft={() => undefined}
      />,
    );

    expect(screen.getByText("正在生成代码草稿，请稍候。")).toBeInTheDocument();
    expect(screen.getByText("代码草稿只会生成文件内容，不会自动把结果写回当前画布。")).toBeInTheDocument();
  });

  it("shows the failure reason and allows retrying draft generation", async () => {
    const user = userEvent.setup();
    const events: string[] = [];

    render(
      <CodeDraftPanel
        drafts={[]}
        warnings={[]}
        isRequesting={false}
        requestError="生成代码草稿失败：HTTP 503"
        source={null}
        promptPreview={null}
        writeReport={null}
        hasPlan={true}
        hasConfirmedDraftChanges={true}
        onOpenDraftWorkbench={() => undefined}
        onRequestPlan={() => events.push("request-plan")}
        onRequestDrafts={() => events.push("retry-drafts")}
        onWriteDrafts={() => events.push("write-all")}
        onWriteSingleDraft={() => undefined}
        onOpenDraft={() => undefined}
      />,
    );

    expect(screen.getByText("生成代码草稿失败：HTTP 503")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "重试生成草稿" }));

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
        hasConfirmedDraftChanges={true}
        onOpenDraftWorkbench={() => events.push("open-draft")}
        onRequestPlan={() => events.push("request-plan")}
        onRequestDrafts={() => events.push("request-drafts")}
        onWriteDrafts={() => events.push("write-all")}
        onWriteSingleDraft={() => undefined}
        onOpenDraft={() => undefined}
      />,
    );

    await user.click(screen.getByRole("button", { name: "查看正文" }));

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
        hasConfirmedDraftChanges={true}
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
        hasConfirmedDraftChanges={true}
        onOpenDraftWorkbench={() => undefined}
        onRequestPlan={() => undefined}
        onRequestDrafts={() => undefined}
        onWriteDrafts={() => undefined}
        onWriteSingleDraft={() => undefined}
        onOpenDraft={() => undefined}
      />,
    );

    expect(screen.getByText("结构化改写 1 条，写回时会走本地 scope-safe apply。")).toBeInTheDocument();
    expect(screen.getByText("替换方法代码块 · CommonController.java")).toBeInTheDocument();
    expect(screen.queryByText(/REPLACE_METHOD_BLOCK/)).not.toBeInTheDocument();
    expect(screen.queryByText(/scope-C1_add_explicit_file_precheck/)).not.toBeInTheDocument();
  });
});
