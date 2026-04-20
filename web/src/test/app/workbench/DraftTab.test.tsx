import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { DraftTab } from "../../../app/workbench/DraftTab";
import type { DraftWorkbenchViewState } from "../../../app/types";

function draftStateFixture(): DraftWorkbenchViewState {
  return {
    compareMode: "after",
    selectedEntryId: "draft-note-1",
    draftState: {
      draftChanges: [
        {
          entryId: "draft-change-1",
          kind: "CHANGE",
          title: "修改上传条件判断",
          sourceChangeId: "change-condition",
          targetStepIds: [],
          targetNodeIds: ["flow-action:condition"],
          beforeState: "if (a > 10)",
          afterState: "if (a < 100)",
          reason: "业务条件写反了。",
          impactSummary: "影响主流程分支。",
          claimType: "CODE_FACT",
          evidence: [],
        },
      ],
      draftNotes: [
        {
          entryId: "draft-note-1",
          kind: "NOTE",
          title: "上传目录说明",
          sourceChangeId: null,
          targetStepIds: ["step-read-upload-dir"],
          targetNodeIds: ["method:upload-file"],
          beforeState: null,
          afterState: "上传目录来自租户配置。",
          reason: "讲解中手动加入。",
          impactSummary: "",
          claimType: "EXPLANATION_NOTE",
          evidence: [],
        },
      ],
    },
  };
}

describe("DraftTab", () => {
  it("expands the core draft modules by default and keeps note list collapsed as a compact title row", () => {
    render(
      <DraftTab
        state={draftStateFixture()}
        onToggleCompare={vi.fn()}
        onSelectEntry={vi.fn()}
        onLocateChangeNode={vi.fn()}
        onUnconfirmChange={vi.fn()}
        onOpenNote={vi.fn()}
        onLocateNoteNode={vi.fn()}
        resolveNodeTitle={(nodeId) => nodeId}
      />,
    );

    expect(screen.getByRole("button", { name: "收起草稿变更项" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "收起草稿说明详情" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "草稿说明项" })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "草稿条目：上传目录说明" })).not.toBeInTheDocument();
  });

  it("renders draft changes above draft notes", async () => {
    const user = userEvent.setup();
    const { container } = render(
      <DraftTab
        state={draftStateFixture()}
        onToggleCompare={vi.fn()}
        onSelectEntry={vi.fn()}
        onLocateChangeNode={vi.fn()}
        onUnconfirmChange={vi.fn()}
        onOpenNote={vi.fn()}
        onLocateNoteNode={vi.fn()}
        resolveNodeTitle={(nodeId) => nodeId}
      />,
    );

    expect(screen.getByText("草稿变更项")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "草稿条目：修改上传条件判断" })).toBeInTheDocument();
    expect(screen.getByText("草稿说明项")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "草稿条目：上传目录说明" })).not.toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "草稿说明项" }));
    expect(screen.getByRole("button", { name: "草稿条目：上传目录说明" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "说明项无需前后对比" })).toBeDisabled();
    expect(container.querySelector(".workbench-tab-body.draft-layout")).not.toBeNull();
  });

  it("lets the reader reopen a draft note in context", async () => {
    const user = userEvent.setup();
    const onOpenNote = vi.fn();

    render(
      <DraftTab
        state={draftStateFixture()}
        onToggleCompare={vi.fn()}
        onSelectEntry={vi.fn()}
        onLocateChangeNode={vi.fn()}
        onUnconfirmChange={vi.fn()}
        onOpenNote={onOpenNote}
        onLocateNoteNode={vi.fn()}
        resolveNodeTitle={(nodeId) => nodeId}
      />,
    );

    await user.click(screen.getByRole("button", { name: "打开草稿说明：上传目录说明" }));

    expect(onOpenNote).toHaveBeenCalledWith("draft-note-1");
  });

  it("exposes a dedicated locate action for draft notes", async () => {
    const user = userEvent.setup();
    const onLocateNoteNode = vi.fn();

    render(
      <DraftTab
        state={draftStateFixture()}
        onToggleCompare={vi.fn()}
        onSelectEntry={vi.fn()}
        onLocateChangeNode={vi.fn()}
        onUnconfirmChange={vi.fn()}
        onOpenNote={vi.fn()}
        onLocateNoteNode={onLocateNoteNode}
        resolveNodeTitle={(nodeId) => nodeId}
      />,
    );

    await user.click(screen.getByRole("button", { name: "定位草稿说明对应节点：上传目录说明" }));

    expect(onLocateNoteNode).toHaveBeenCalledWith("draft-note-1");
  });

  it("shows the selected draft change detail and exposes a locate action", async () => {
    const user = userEvent.setup();
    const onLocateChangeNode = vi.fn();
    const onSelectEntry = vi.fn();

    render(
      <DraftTab
        state={{
          ...draftStateFixture(),
          selectedEntryId: "draft-change-1",
        }}
        onToggleCompare={vi.fn()}
        onSelectEntry={onSelectEntry}
        onLocateChangeNode={onLocateChangeNode}
        onUnconfirmChange={vi.fn()}
        onOpenNote={vi.fn()}
        onLocateNoteNode={vi.fn()}
        resolveNodeTitle={() => "OrderController.submit"}
      />,
    );

    expect(screen.getByText("修改后")).toBeInTheDocument();
    expect(screen.queryByText("修改前")).not.toBeInTheDocument();
    expect(screen.getByText("影响范围：影响主流程分支。")).toBeInTheDocument();
    expect(screen.getByText("OrderController.submit")).toBeInTheDocument();
    expect(screen.getByText("这条草稿只有读证据，尚未授权现有文件精确写回。")).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "定位草稿变更对应节点：修改上传条件判断" }));

    expect(onLocateChangeNode).toHaveBeenCalledWith("draft-change-1");

    await user.click(screen.getByRole("button", { name: "草稿说明项" }));
    await user.click(screen.getByRole("button", { name: "草稿条目：上传目录说明" }));

    expect(onSelectEntry).toHaveBeenCalledWith("draft-note-1");
  });

  it("shows the exact modified state preview in the draft change list instead of only keeping the prose title", () => {
    render(
      <DraftTab
        state={{
          ...draftStateFixture(),
          selectedEntryId: "draft-change-1",
          draftState: {
            ...draftStateFixture().draftState,
            draftChanges: [
              {
                ...draftStateFixture().draftState.draftChanges[0],
                title: "将删除条件收紧为显式 true 判断",
                beforeState: "if (delete)",
                afterState: "if (delete == true)",
              },
            ],
          },
        }}
        onToggleCompare={vi.fn()}
        onSelectEntry={vi.fn()}
        onLocateChangeNode={vi.fn()}
        onUnconfirmChange={vi.fn()}
        onOpenNote={vi.fn()}
        onLocateNoteNode={vi.fn()}
        resolveNodeTitle={() => "CommonController.fileDownload"}
      />,
    );

    const changeButton = screen.getByRole("button", { name: "草稿条目：将删除条件收紧为显式 true 判断" });
    expect(changeButton).toBeInTheDocument();
    expect(within(changeButton).getByText("if (delete == true)")).toBeInTheDocument();
  });

  it("shows exact write scopes when a confirmed draft change is scope-authorized", () => {
    render(
      <DraftTab
        state={{
          ...draftStateFixture(),
          selectedEntryId: "draft-change-1",
          draftState: {
            ...draftStateFixture().draftState,
            draftChanges: [
              {
                ...draftStateFixture().draftState.draftChanges[0],
                editScopes: [
                  {
                    scopeId: "scope-change-1",
                    targetNodeId: "flow-action:condition",
                    filePath: "src/main/java/com/example/OrderController.java",
                    language: "JAVA",
                    symbolKind: "METHOD",
                    symbolSignature: "OrderController.submit(java.lang.String)",
                    startLine: 18,
                    endLine: 30,
                    allowedChangeKinds: ["REPLACE_SYMBOL_BODY"],
                    supportingFindingIds: ["finding-condition"],
                  },
                ],
              },
            ],
          },
        }}
        onToggleCompare={vi.fn()}
        onSelectEntry={vi.fn()}
        onLocateChangeNode={vi.fn()}
        onUnconfirmChange={vi.fn()}
        onOpenNote={vi.fn()}
        onLocateNoteNode={vi.fn()}
        resolveNodeTitle={() => "OrderController.submit"}
      />,
    );

    expect(screen.getByText("已授权 1 个精确写回范围。")).toBeInTheDocument();
    expect(screen.getByText("src/main/java/com/example/OrderController.java:18-30")).toBeInTheDocument();
    expect(screen.getByText("OrderController.submit(java.lang.String)")).toBeInTheDocument();
  });

  it("shows the selected note detail so jumping into draft after recording a note is actionable", () => {
    render(
      <DraftTab
        state={draftStateFixture()}
        onToggleCompare={vi.fn()}
        onSelectEntry={vi.fn()}
        onLocateChangeNode={vi.fn()}
        onUnconfirmChange={vi.fn()}
        onOpenNote={vi.fn()}
        onLocateNoteNode={vi.fn()}
        resolveNodeTitle={() => "CommonController.uploadFile"}
      />,
    );

    expect(screen.getByText("草稿说明详情")).toBeInTheDocument();
    expect(screen.getByText("上传目录来自租户配置。")).toBeInTheDocument();
    expect(screen.getByText("CommonController.uploadFile")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "打开草稿说明：上传目录说明" })).toBeInTheDocument();
  });

  it("renders compare mode for draft changes instead of leaving the toggle disconnected", () => {
    render(
      <DraftTab
        state={{
          ...draftStateFixture(),
          compareMode: "compare",
          selectedEntryId: "draft-change-1",
        }}
        onToggleCompare={vi.fn()}
        onSelectEntry={vi.fn()}
        onLocateChangeNode={vi.fn()}
        onUnconfirmChange={vi.fn()}
        onOpenNote={vi.fn()}
        onLocateNoteNode={vi.fn()}
        resolveNodeTitle={() => "OrderController.submit"}
      />,
    );

    const detailCard = screen.getByText("草稿变更详情").closest("section");
    expect(detailCard).not.toBeNull();
    expect(within(detailCard as HTMLElement).getByText("修改前")).toBeInTheDocument();
    expect(within(detailCard as HTMLElement).getByText("if (a > 10)")).toBeInTheDocument();
    expect(within(detailCard as HTMLElement).getByText("修改后")).toBeInTheDocument();
    expect(within(detailCard as HTMLElement).getByText("if (a < 100)")).toBeInTheDocument();
  });

  it("renders an implementation suggestion section inside draft instead of requiring a standalone plan page", () => {
    render(
      <DraftTab
        {...({
          state: {
            ...draftStateFixture(),
            selectedEntryId: "draft-change-1",
          },
          draftVersion: 3,
          codeDiffStatus: "STALE",
          codeDiffDraftVersion: 2,
          implementationSuggestion: {
            status: "FRESH",
            source: "MOCK",
            summary: "先修改 OrderController.submit，再补上传目录分支。",
            warnings: [],
            promptPreview: null,
            promptPreviewArtifactId: null,
            generationPlanDraftVersion: 3,
            items: [
              {
                id: "impl-1",
                title: "修改 OrderController.submit",
                description: "补失败分支并保留当前主路径。",
                targetPath: "src/main/java/com/example/OrderController.java",
                risk: "MEDIUM",
              },
            ],
          },
        } as any)}
        onToggleCompare={vi.fn()}
        onSelectEntry={vi.fn()}
        onLocateChangeNode={vi.fn()}
        onUnconfirmChange={vi.fn()}
        onOpenNote={vi.fn()}
        onLocateNoteNode={vi.fn()}
        resolveNodeTitle={() => "OrderController.submit"}
      />,
    );

    expect(screen.getByText("实现建议")).toBeInTheDocument();
    expect(screen.getByText("草稿版本 v3")).toBeInTheDocument();
    expect(screen.getByText("实现建议：最新（v3）")).toBeInTheDocument();
    expect(screen.getByText("代码 diff：待刷新（v2）")).toBeInTheDocument();
    expect(screen.getByText("先修改 OrderController.submit，再补上传目录分支。")).toBeInTheDocument();
    expect(screen.getByText("任务：修改 OrderController.submit")).toBeInTheDocument();
  });

  it("makes the compare-state change visible near the top of the draft pane", () => {
    const { container, rerender } = render(
      <DraftTab
        state={{
          ...draftStateFixture(),
          selectedEntryId: "draft-change-1",
        }}
        onToggleCompare={vi.fn()}
        onSelectEntry={vi.fn()}
        onLocateChangeNode={vi.fn()}
        onUnconfirmChange={vi.fn()}
        onOpenNote={vi.fn()}
        onLocateNoteNode={vi.fn()}
        resolveNodeTitle={() => "OrderController.submit"}
      />,
    );

    expect(screen.getByText("当前显示：修改后")).toBeInTheDocument();

    rerender(
      <DraftTab
        state={{
          ...draftStateFixture(),
          compareMode: "compare",
          selectedEntryId: "draft-change-1",
        }}
        onToggleCompare={vi.fn()}
        onSelectEntry={vi.fn()}
        onLocateChangeNode={vi.fn()}
        onUnconfirmChange={vi.fn()}
        onOpenNote={vi.fn()}
        onLocateNoteNode={vi.fn()}
        resolveNodeTitle={() => "OrderController.submit"}
      />,
    );

    expect(screen.getByText("当前显示：前后对比")).toBeInTheDocument();
    const compareLabel = screen.getByText("修改前");
    const nodeSection = screen.getByText("涉及节点");
    const comparePosition = container.textContent?.indexOf(compareLabel.textContent ?? "") ?? -1;
    const nodePosition = container.textContent?.indexOf(nodeSection.textContent ?? "") ?? -1;
    expect(comparePosition).toBeGreaterThanOrEqual(0);
    expect(nodePosition).toBeGreaterThan(comparePosition);
  });
});
