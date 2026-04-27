import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { DraftTab } from "../../../app/workbench/DraftTab";
import type { DraftCompareProjection, DraftWorkbenchViewState } from "../../../app/types";
import themeCss from "../../../app/theme.css?raw";

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

function draftCompareProjectionFixture(): DraftCompareProjection {
  return {
    entryId: "draft-change-1",
    entryTitle: "修改上传条件判断",
    compareGraph: { nodes: [], edges: [] },
    nodeStatuses: {
      "flow-action:condition": "MODIFIED",
      "flow-action:guard": "ADDED",
      "ghost:draft-change-1:flow-action:old-branch": "REMOVED",
    },
    edgeStatuses: {
      "edge-condition-true": "MODIFIED",
      "edge-new-guard": "ADDED",
    },
    summary: {
      scopeNodeCount: 3,
      visibleNodeCount: 3,
      visibleEdgeCount: 2,
      hiddenNodeCount: 1,
      hiddenEdgeCount: 0,
    },
  };
}

describe("DraftTab", () => {

  it("keeps the tab root on the CSS grid contract instead of Uno display or overflow utilities", () => {
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

    const tab = container.querySelector(".workbench-tab");
    const body = container.querySelector(".workbench-tab-body");
    expect(tab).not.toBeNull();
    expect(tab).not.toHaveClass("block");
    expect(tab).not.toHaveClass("overflow-auto");
    expect(body).not.toBeNull();
    expect(body).not.toHaveClass("block");
    expect(body).not.toHaveClass("overflow-auto");
  });

  it("lets draft rows expand into the workbench scroll owner instead of clipping generated follow-up sections", () => {
    expect(themeCss).toMatch(/\.draft-layout\s*\{[^}]*align-content:\s*start;[^}]*overflow:\s*visible;/s);
    expect(themeCss).toMatch(/\.workbench-draft-sidebar\s*\{[^}]*display:\s*flex;[^}]*flex-direction:\s*column;[^}]*overflow:\s*visible;/s);
    expect(themeCss).toMatch(/\.draft-layout\s+\.workbench-section-card\.expanded\s*\{[^}]*flex:\s*0\s+0\s+auto;[^}]*min-height:\s*auto;/s);
    expect(themeCss).toMatch(/\.workbench-draft-main\s*\{[^}]*display:\s*flex;[^}]*flex-direction:\s*column;[^}]*overflow:\s*visible;/s);
  });

  it("keeps draft detail below the tab header instead of letting content be covered", () => {
    expect(themeCss).toMatch(/\.draft-tab\s*\{[^}]*grid-template-rows:\s*auto\s+auto;/s);
    expect(themeCss).toMatch(/\.draft-layout\s*\{[^}]*min-height:\s*0;[^}]*align-content:\s*start;[^}]*overflow:\s*visible;/s);
    expect(themeCss).toMatch(/@container\s*\(max-width:\s*620px\)\s*\{[\s\S]*\.draft-layout\s*\{[\s\S]*grid-template-columns:\s*1fr;[\s\S]*grid-template-rows:\s*auto\s+auto;[\s\S]*align-items:\s*stretch;/);
    expect(themeCss).toMatch(/@container\s*\(max-width:\s*620px\)\s*\{[\s\S]*\.workbench-draft-sidebar,\s*\.workbench-draft-main\s*\{[\s\S]*min-height:\s*auto;/);
    expect(themeCss).toMatch(/\.draft-layout\s+\.workbench-section-card-body\s*\{[^}]*flex:\s*0\s+0\s+auto;[^}]*min-height:\s*auto;/s);
    expect(themeCss).toMatch(/\.draft-layout\s+\.workbench-section-card-body\s*>\s*\.workbench-draft-section\s*\{[^}]*min-height:\s*auto;/s);
    expect(themeCss).toMatch(/\.workbench-draft-main\s*\{[^}]*overflow:\s*visible;/s);
  });

  it("expands the selected draft note list so note context is visible", () => {
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
    expect(screen.getByRole("button", { name: "收起草稿说明项" })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "草稿验证" })).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "草稿条目：上传目录说明" })).toBeInTheDocument();
    expect(screen.queryByText("草稿验证状态正在同步，当前先以草稿内容作为后续生成的唯一输入。")).not.toBeInTheDocument();
  });

  it("renders draft changes above draft notes", async () => {
    const user = userEvent.setup();
    const { container } = render(
      <DraftTab
        state={{ ...draftStateFixture(), selectedEntryId: "draft-change-1" }}
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

  it("turns the draft pane into a flow-change navigator in compare mode", () => {
    render(
      <DraftTab
        state={{
          ...draftStateFixture(),
          compareMode: "compare",
          selectedEntryId: "draft-change-1",
        }}
        draftCompareProjection={draftCompareProjectionFixture()}
        onToggleCompare={vi.fn()}
        onSelectEntry={vi.fn()}
        onLocateChangeNode={vi.fn()}
        onUnconfirmChange={vi.fn()}
        onOpenNote={vi.fn()}
        onLocateNoteNode={vi.fn()}
        resolveNodeTitle={() => "OrderController.submit"}
      />,
    );

    expect(screen.getByText("当前显示：流程变化")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "切回修改后流程" })).toBeInTheDocument();
    expect(screen.getByText("当前条目正在展示流程变化，可直接核对节点与连线的新增、删除和修改。"))
      .toBeInTheDocument();

    const changeButton = screen.getByRole("button", { name: "草稿条目：修改上传条件判断" });
    expect(within(changeButton).getByText("流程变化")).toBeInTheDocument();
    expect(within(changeButton).getByText("草稿修改 1")).toBeInTheDocument();
    expect(within(changeButton).getByText("草稿新增 1")).toBeInTheDocument();
    expect(within(changeButton).getByText("草稿删除 1")).toBeInTheDocument();
    expect(within(changeButton).getByText("命中连线 2")).toBeInTheDocument();

    expect(screen.getByText("流程变化摘要")).toBeInTheDocument();
    expect(screen.getByText("命中节点 3 / 范围节点 3")).toBeInTheDocument();
    expect(screen.getAllByText("视图外节点 1").length).toBeGreaterThanOrEqual(1);
  });

  it("renders confirmed intent-only changes without fake before after placeholders and keeps compare entry available", () => {
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
                title: "收紧删除条件并补 filePath 存在校验",
                beforeState: null,
                afterState: null,
                reason: "当前源码片段已直接锚定到本轮修改请求涉及的位置。",
                impactSummary: "已具备直接源码证据，可继续进入精确代码 diff 生成。",
                editScopes: [
                  {
                    scopeId: "scope-change-intent-1",
                    targetNodeId: "flow-action:condition",
                    filePath: "src/main/java/com/example/CommonController.java",
                    language: "JAVA",
                    symbolKind: "METHOD",
                    symbolSignature: "CommonController.fileDownload(java.lang.String,java.lang.Boolean)",
                    startLine: 42,
                    endLine: 88,
                    allowedChangeKinds: ["REPLACE_METHOD_BLOCK"],
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
        resolveNodeTitle={() => "CommonController.fileDownload"}
      />,
    );

    expect(screen.getByText("变更意图")).toBeInTheDocument();
    expect(screen.getByText("当前阶段已完成源码定位，但还没有生成具体代码 diff。")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "切换链路对比" })).toBeEnabled();
    expect(screen.getByText("当前条目已锁定变更范围，可切到链路对比查看受影响节点与关系。")).toBeInTheDocument();
    expect(screen.queryByText("未提供")).not.toBeInTheDocument();
    expect(screen.getByText("src/main/java/com/example/CommonController.java:42-88")).toBeInTheDocument();
    expect(screen.getByText("CommonController.fileDownload(java.lang.String,java.lang.Boolean)")).toBeInTheDocument();
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

  it("keeps implementation suggestion status in draft without rendering the migrated analysis detail", () => {
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

    expect(screen.getByText("草稿版本 v3")).toBeInTheDocument();
    expect(screen.getByText("实现建议：最新（v3）")).toBeInTheDocument();
    expect(screen.getByText("代码 diff：待刷新（v2）")).toBeInTheDocument();
    expect(screen.queryByText("先修改 OrderController.submit，再补上传目录分支。")).not.toBeInTheDocument();
    expect(screen.queryByText("任务：修改 OrderController.submit")).not.toBeInTheDocument();
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

    expect(screen.getByText("当前显示：修改后流程")).toBeInTheDocument();

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

    expect(screen.getByText("当前显示：流程变化")).toBeInTheDocument();
    const compareLabel = screen.getByText("修改前");
    const nodeSection = screen.getByText("涉及节点");
    const comparePosition = container.textContent?.indexOf(compareLabel.textContent ?? "") ?? -1;
    const nodePosition = container.textContent?.indexOf(nodeSection.textContent ?? "") ?? -1;
    expect(comparePosition).toBeGreaterThanOrEqual(0);
    expect(nodePosition).toBeGreaterThan(comparePosition);
  });

  it("keeps the draft compare controls compact instead of stretching across the header", () => {
    render(
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

    const compareStatus = screen.getByText("当前显示：修改后流程");
    const compareAction = screen.getByRole("button", { name: "查看流程变化" });
    expect(compareStatus).toHaveClass("workbench-compare-mode");
    expect(compareAction).toHaveClass("workbench-compare-action");
    expect(compareAction).not.toHaveClass("workbench-compare-mode");
    expect(themeCss).toMatch(/\.workbench-draft-head-actions\s*\{[^}]*align-items:\s*flex-start;[^}]*height:\s*auto;/s);
  });

  it("does not keep the migrated implementation analysis inside the draft tab", () => {
    const { container } = render(
      <DraftTab
        state={draftStateFixture()}
        implementationSuggestion={{
          status: "FRESH",
          source: "REMOTE",
          summary: "这是一段足够长的实现建议摘要，用来验证实现建议区域会跟随外层工作台自然铺开，而不是自己出现内部滚动条。",
          warnings: [
            "这是一个较长的实现建议提示，用来验证实现建议区内部内容会自动换行。",
          ],
          items: [
            {
              id: "plan-item-1",
              title: "处理一个特别长的建议标题，验证在工作台里不会撑出横向滚动",
              description: "建议描述同样写长一点，确保布局在结果出现后保持自适应。",
              risk: "MEDIUM",
              targetPath: "src/main/java/com/example/really/long/path/UploadServiceImplementation.java",
            },
          ],
          promptPreview: "prompt",
          promptPreviewArtifactId: null,
          generationPlanDraftVersion: 1,
        }}
        draftVersion={1}
        onToggleCompare={vi.fn()}
        onSelectEntry={vi.fn()}
        onLocateChangeNode={vi.fn()}
        onUnconfirmChange={vi.fn()}
        onOpenNote={vi.fn()}
        onLocateNoteNode={vi.fn()}
        resolveNodeTitle={(nodeId) => nodeId}
      />,
    );

    expect(container.querySelector(".workbench-draft-implementation-suggestion")).toBeNull();
    expect(container.querySelector(".generation-plan-panel")).toBeNull();
    expect(screen.queryByText("这是一段足够长的实现建议摘要，用来验证实现建议区域会跟随外层工作台自然铺开，而不是自己出现内部滚动条。")).not.toBeInTheDocument();
  });

  it("auto-expands the draft note list when the selected draft entry is a note", () => {
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

    expect(screen.getByRole("button", { name: "收起草稿说明项" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "草稿条目：上传目录说明" })).toBeInTheDocument();
  });

  it("wraps long draft state snippets so draft information is not visually lost", () => {
    expect(themeCss).toMatch(/\.workbench-draft-single-state\s*\{[^}]*min-width:\s*0;[^}]*max-width:\s*100%;[^}]*overflow-wrap:\s*anywhere;[^}]*word-break:\s*break-word;/s);
    expect(themeCss).toMatch(/\.workbench-before-after dd\s*\{[^}]*min-width:\s*0;[^}]*max-width:\s*100%;[^}]*overflow-wrap:\s*anywhere;/s);
  });

});
