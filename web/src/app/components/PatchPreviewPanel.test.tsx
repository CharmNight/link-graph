import { render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { PatchPreviewPanel } from "./PatchPreviewPanel";

describe("PatchPreviewPanel", () => {
  it("explains that previewed patch operations are draft suggestions instead of facts", () => {
    render(
      <PatchPreviewPanel
        patch={{
          summary: "远程建议补一个默认兜底说明节点。",
          operations: [
            {
              id: "op-1",
              action: "ADD_NODE",
              elementKind: "NODE",
              elementId: "doc:fallback",
              title: "新增说明节点",
              summary: "补默认兜底说明",
              node: {
                id: "doc:fallback",
                type: "DOC_PAGE",
                title: "默认兜底说明",
                inputs: [],
                outputs: [],
                certainty: "LLM_SUGGESTED",
                bindingStatus: "DESIGN_ONLY",
                sourceTag: "DRAFT_AI",
              },
            },
          ],
          addedNodeIds: ["doc:fallback"],
          removedNodeIds: [],
          addedEdgeIds: [],
          removedEdgeIds: [],
        }}
        onApplySelected={vi.fn()}
        onClearPreview={vi.fn()}
        onUndoLastApply={vi.fn()}
        onRestoreAuditPreview={vi.fn()}
        onRestoreDiffPreview={vi.fn()}
        onRestoreLastAppliedPreview={vi.fn()}
      />,
    );

    expect(screen.getByText("待应用图变更")).toBeInTheDocument();
    expect(
      screen.getByText("这里展示的是建议写回当前工作图的草稿 patch，不是已经确认的代码事实。"),
    ).toBeInTheDocument();
  });

  it("shows the suggestion classification for each draft operation", () => {
    render(
      <PatchPreviewPanel
        patch={{
          summary: "远程建议补一个运行时边界提示。",
          operations: [
            {
              id: "op-risk",
              action: "ADD_NODE",
              elementKind: "NODE",
              elementId: "doc:risk",
              title: "运行时边界提示",
              summary: "提示 principals/realmNames 为空时的异常风险。",
              metadata: {
                "draft.claimType": "RISK_HINT",
              },
              node: {
                id: "doc:risk",
                type: "DOC_PAGE",
                title: "运行时边界提示",
                inputs: [],
                outputs: [],
                certainty: "LLM_SUGGESTED",
                bindingStatus: "DESIGN_ONLY",
                sourceTag: "DRAFT_AI",
                metadata: {
                  "draft.claimType": "RISK_HINT",
                },
              },
            },
          ],
          addedNodeIds: ["doc:risk"],
          removedNodeIds: [],
          addedEdgeIds: [],
          removedEdgeIds: [],
        }}
        onApplySelected={vi.fn()}
        onClearPreview={vi.fn()}
        onUndoLastApply={vi.fn()}
        onRestoreAuditPreview={vi.fn()}
        onRestoreDiffPreview={vi.fn()}
        onRestoreLastAppliedPreview={vi.fn()}
      />,
    );

    expect(screen.getByText("归类：风险提示")).toBeInTheDocument();
    expect(screen.getByText("表示这条草稿是在提醒边界或异常风险，不是已发生事实。")).toBeInTheDocument();
  });
});
