import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { CodeDraftPanel } from "./CodeDraftPanel";

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
});
