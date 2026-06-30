import { undoLastDraftPatchApply } from "../api";
import type { AnalysisDisplayMode, OperationFeedback } from "../types";
import type { useWorkbenchCommandController } from "./useWorkbenchCommandController";
import type { useBridgeCommandController } from "./useBridgeCommandController";
import type { useAppBridgeController } from "./useAppBridgeController";
import type { Dispatch, SetStateAction } from "react";

/**
 * 工作台 chrome 层（toolbar / 大纲 / 导入对话框 / diff 模式切换）的事件 handler 集合。
 *
 * 抽出自 App.tsx 的 5 个内联 handler：
 * - handleRequestAnalysisDisplayMode：切换图视图分析模式（含 REVIEW_GRAPH → qa stage 联动）
 * - handleOpenImportMermaid：打开导入对话框
 * - handleConfirmImportMermaidDraft：校验 mermaid 非空后调用 bridge confirm
 * - handleExpandOverflowNode：展开溢出节点时补上节点标题
 * - handleUndoDraftPatchApply：通过 bridge 请求回退上次草稿应用
 */
export interface WorkbenchChromeActionsArgs {
  // 图状态
  nodes: { id: string; title?: string }[];
  selectedNodeId: string | null;
  diffTargetItemIds: string[];
  diffItems: { id: string }[];
  mermaidDraft: string;
  // 外部回调
  bridgeCommands: ReturnType<typeof useBridgeCommandController>;
  workbenchCommands: ReturnType<typeof useWorkbenchCommandController>;
  handleConfirmImportMermaid: ReturnType<typeof useAppBridgeController>["handleConfirmImportMermaid"];
  setActiveWorkflowStage: (stage: "understand" | "qa" | "draft" | "code") => void;
  // 状态更新器
  setMermaidDraft: Dispatch<SetStateAction<string>>;
  setImportDialogOpen: Dispatch<SetStateAction<boolean>>;
  setOperationFeedback: Dispatch<SetStateAction<OperationFeedback | null>>;
}

export interface WorkbenchChromeActions {
  handleRequestAnalysisDisplayMode: (displayMode: AnalysisDisplayMode) => void;
  handleOpenImportMermaid: () => void;
  handleConfirmImportMermaidDraft: () => void;
  handleExpandOverflowNode: (nodeId: string) => void;
  handleUndoDraftPatchApply: () => void;
}

export function useWorkbenchChromeActions(args: WorkbenchChromeActionsArgs): WorkbenchChromeActions {
  const {
    nodes,
    selectedNodeId,
    diffTargetItemIds,
    diffItems,
    mermaidDraft,
    bridgeCommands,
    workbenchCommands,
    handleConfirmImportMermaid,
    setActiveWorkflowStage,
    setMermaidDraft,
    setImportDialogOpen,
    setOperationFeedback,
  } = args;

  function handleRequestAnalysisDisplayMode(displayMode: AnalysisDisplayMode) {
    if (displayMode === "REVIEW_GRAPH") {
      setActiveWorkflowStage("qa");
    }
    const reviewGraphDiffItemIds = diffTargetItemIds.length > 0
      ? diffTargetItemIds
      : selectedNodeId && diffItems.some((item) => item.id === selectedNodeId)
        ? [selectedNodeId]
        : [];
    workbenchCommands.handleRequestAnalysisDisplayMode(displayMode, reviewGraphDiffItemIds);
  }

  function handleOpenImportMermaid() {
    setMermaidDraft("");
    setImportDialogOpen(true);
  }

  function handleConfirmImportMermaidDraft() {
    const mermaid = mermaidDraft.trim();
    if (mermaid.length === 0) {
      setOperationFeedback({
        level: "WARNING",
        message: "请输入 Mermaid 内容后再导入。",
      });
      return;
    }
    handleConfirmImportMermaid(mermaid);
  }

  function handleExpandOverflowNode(nodeId: string) {
    const nodeTitle = nodes.find((node) => node.id === nodeId)?.title ?? nodeId;
    workbenchCommands.handleExpandOverflowNode(nodeId, nodeTitle);
  }

  function handleUndoDraftPatchApply() {
    bridgeCommands.runBridgeCommand("回退草稿应用", () => undoLastDraftPatchApply(), {
      successFeedback: {
        level: "INFO",
        message: "已请求回退上次草稿应用。",
      },
    });
  }

  return {
    handleRequestAnalysisDisplayMode,
    handleOpenImportMermaid,
    handleConfirmImportMermaidDraft,
    handleExpandOverflowNode,
    handleUndoDraftPatchApply,
  };
}
