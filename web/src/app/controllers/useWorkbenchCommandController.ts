import {
  applyCodeDrafts,
  exportMermaid,
  requestAnalysisDisplayMode,
  requestArchitectureGraph,
  requestPackageDependencyGraph,
  requestCodeDraftsAsync,
  requestClassDiagram,
  requestDraftNavigation,
  requestExpandOverflowNode,
  requestExpandInvocation,
  requestGenerationPlanAsync,
  requestGenerationPlanDiscussionAsync,
  requestRemoveInvocationExpansion,
  requestOpenSettings,
  requestReviewGraph,
  requestSyncPreview,
  showDiffMode,
} from "../api";
import type { AnalysisDisplayMode, IndexedClassDiagramOptions, IndexedReviewGraphOptions } from "../types";
import type { useBridgeCommandController } from "./useBridgeCommandController";

interface UseWorkbenchCommandControllerArgs {
  bridgeCommands: Pick<
    ReturnType<typeof useBridgeCommandController>,
    "runBridgeCommand" | "submitAsyncBridgeCommand"
  >;
  availability?: IndexedGraphAvailability;
}

interface IndexedGraphAvailability {
  architectureGraphLoaded: boolean;
  classDiagramLoaded: boolean;
  reviewGraphLoaded: boolean;
}

export function useWorkbenchCommandController({
  bridgeCommands,
  availability = {
    architectureGraphLoaded: false,
    classDiagramLoaded: false,
    reviewGraphLoaded: false,
  },
}: UseWorkbenchCommandControllerArgs) {
  function handleRequestAnalysisDisplayMode(displayMode: AnalysisDisplayMode, selectedDiffItemIds: string[] = []) {
    if (displayMode === "ARCHITECTURE_GRAPH") {
      if (availability.architectureGraphLoaded) {
        bridgeCommands.runBridgeCommand("切换展示模式", () => requestAnalysisDisplayMode(displayMode));
        return;
      }
      bridgeCommands.runBridgeCommand("加载架构图", () => requestArchitectureGraph());
      return;
    }
    if (displayMode === "CLASS_DIAGRAM") {
      if (availability.classDiagramLoaded) {
        bridgeCommands.runBridgeCommand("切换展示模式", () => requestAnalysisDisplayMode(displayMode));
        return;
      }
      bridgeCommands.runBridgeCommand("加载类图", () => requestClassDiagram());
      return;
    }
    if (displayMode === "REVIEW_GRAPH") {
      if (availability.reviewGraphLoaded && selectedDiffItemIds.length === 0) {
        bridgeCommands.runBridgeCommand("切换展示模式", () => requestAnalysisDisplayMode(displayMode));
        return;
      }
      bridgeCommands.runBridgeCommand("加载 Review Graph", () => requestReviewGraph(selectedDiffItemIds));
      return;
    }
    bridgeCommands.runBridgeCommand("切换展示模式", () => requestAnalysisDisplayMode(displayMode));
  }

  function handleRequestClassDiagram(scopeNodeId?: string | null) {
    bridgeCommands.runBridgeCommand("加载类图", () => requestClassDiagram(scopeNodeId ?? null));
  }

  function handleRequestClassDiagramWithOptions(
    scopeNodeId: string | null | undefined,
    classDiagram: Partial<IndexedClassDiagramOptions>,
  ) {
    bridgeCommands.runBridgeCommand("加载类图", () => requestClassDiagram(scopeNodeId ?? null, { classDiagram }));
  }

  function handleRequestPackageDependencyGraph(
    packageName?: string | null,
    options: { includeExternalLibraries?: boolean; includeJdk?: boolean } = {},
  ) {
    bridgeCommands.runBridgeCommand("加载包依赖", () => requestPackageDependencyGraph(packageName ?? null, options), {
      successFeedback: {
        level: "INFO",
        message: "正在加载包依赖视图。",
      },
    });
  }

  function handleRequestArchitectureGraph(options: { includeExternalLibraries?: boolean; includeJdk?: boolean } = {}) {
    bridgeCommands.runBridgeCommand("加载架构图", () => requestArchitectureGraph(options));
  }

  function handleRequestReviewGraphWithOptions(
    selectedDiffItemIds: string[] = [],
    review: Partial<IndexedReviewGraphOptions>,
  ) {
    bridgeCommands.runBridgeCommand("加载 Review Graph", () => requestReviewGraph(selectedDiffItemIds, { review }));
  }

  function handleExportMermaid() {
    bridgeCommands.runBridgeCommand("导出 Mermaid", () => exportMermaid(), {
      successFeedback: {
        level: "INFO",
        message: "已请求导出 Mermaid。",
      },
    });
  }

  function handleShowDiffMode() {
    bridgeCommands.runBridgeCommand("代码对比", () => showDiffMode());
  }

  function handleRequestSyncPreview() {
    bridgeCommands.runBridgeCommand("同步预览", () => requestSyncPreview());
  }

  function handleRequestGenerationPlan() {
    bridgeCommands.submitAsyncBridgeCommand("实现计划", () => requestGenerationPlanAsync(), {
      successFeedback: {
        level: "INFO",
        message: "已请求生成实现建议。",
      },
    });
  }

  function handleRequestCodeDrafts() {
    bridgeCommands.submitAsyncBridgeCommand("代码草稿", () => requestCodeDraftsAsync(), {
      successFeedback: {
        level: "INFO",
        message: "已请求生成代码 diff。",
      },
    });
  }

  function handleRequestGenerationPlanDiscussion(question: string, focusItemId?: string | null) {
    bridgeCommands.submitAsyncBridgeCommand(
      "实现建议追问",
      () => requestGenerationPlanDiscussionAsync(question, focusItemId),
      {
        successFeedback: {
          level: "INFO",
          message: "已提交实现建议追问。",
        },
      },
    );
  }

  function handleOpenSettings() {
    bridgeCommands.runBridgeCommand("打开设置", () => requestOpenSettings(), {
      successFeedback: {
        level: "INFO",
        message: "正在打开 IDE 设置...",
      },
    });
  }

  function handleWriteDrafts() {
    bridgeCommands.runBridgeCommand("写入代码 diff", () => applyCodeDrafts());
  }

  function handleOpenDraft(targetPath: string) {
    bridgeCommands.runBridgeCommand("打开草稿文件", () => requestDraftNavigation(targetPath));
  }

  function handleExpandOverflowNode(nodeId: string, nodeTitle: string) {
    bridgeCommands.runBridgeCommand("展开链路分支", () => requestExpandOverflowNode(nodeId), {
      successFeedback: {
        level: "INFO",
        message: `已请求继续展开链路：${nodeTitle}`,
      },
    });
  }

  function handleExpandInvocation(nodeId: string) {
    bridgeCommands.runBridgeCommand("展开调用方法", () => requestExpandInvocation(nodeId), {
      successFeedback: {
        level: "INFO",
        message: "已请求展开被调方法。",
      },
    });
  }

  function handleRemoveInvocationExpansion(expansionId: string) {
    bridgeCommands.runBridgeCommand("移除调用展开", () => requestRemoveInvocationExpansion(expansionId), {
      successFeedback: {
        level: "INFO",
        message: "已请求移除调用展开内容。",
      },
    });
  }

  return {
    handleRequestAnalysisDisplayMode,
    handleRequestArchitectureGraph,
    handleRequestClassDiagram,
    handleRequestClassDiagramWithOptions,
    handleRequestPackageDependencyGraph,
    handleRequestReviewGraphWithOptions,
    handleExportMermaid,
    handleShowDiffMode,
    handleRequestSyncPreview,
    handleRequestGenerationPlan,
    handleRequestGenerationPlanDiscussion,
    handleRequestCodeDrafts,
    handleOpenSettings,
    handleWriteDrafts,
    handleOpenDraft,
    handleExpandOverflowNode,
    handleExpandInvocation,
    handleRemoveInvocationExpansion,
  };
}
