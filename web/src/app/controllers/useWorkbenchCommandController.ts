import {
  applyCodeDrafts,
  exportMermaid,
  requestAnalysisDisplayMode,
  requestCodeDraftsAsync,
  requestDraftNavigation,
  requestExpandOverflowNode,
  requestGenerationPlanAsync,
  requestOpenSettings,
  requestSyncPreview,
  showDiffMode,
} from "../api";
import type {
  AnalysisDisplayMode,
  AsyncRequestState,
  GeneratedCodeDraft,
} from "../types";
import type { useBridgeCommandController } from "./useBridgeCommandController";

interface UseWorkbenchCommandControllerArgs {
  setGenerationPlan: (nextPlan: null) => void;
  setGenerationPlanRequestState: (nextState: AsyncRequestState) => void;
  setGeneratedCodeDrafts: (drafts: GeneratedCodeDraft[]) => void;
  setGeneratedCodeDraftWarnings: (warnings: string[]) => void;
  setGeneratedCodeDraftSource: (source: null) => void;
  setGeneratedCodeDraftPromptPreview: (preview: null) => void;
  setGeneratedCodeDraftPromptPreviewArtifactId: (artifactId: null) => void;
  setGeneratedCodeDraftWriteReport: (report: null) => void;
  setCodeDraftRequestState: (nextState: AsyncRequestState) => void;
  bridgeCommands: Pick<
    ReturnType<typeof useBridgeCommandController>,
    "runBridgeCommand" | "submitAsyncBridgeCommand"
  >;
}

export function useWorkbenchCommandController({
  setGenerationPlan,
  setGenerationPlanRequestState,
  setGeneratedCodeDrafts,
  setGeneratedCodeDraftWarnings,
  setGeneratedCodeDraftSource,
  setGeneratedCodeDraftPromptPreview,
  setGeneratedCodeDraftPromptPreviewArtifactId,
  setGeneratedCodeDraftWriteReport,
  setCodeDraftRequestState,
  bridgeCommands,
}: UseWorkbenchCommandControllerArgs) {
  function handleRequestAnalysisDisplayMode(displayMode: AnalysisDisplayMode) {
    bridgeCommands.runBridgeCommand("切换展示模式", () => requestAnalysisDisplayMode(displayMode));
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
    bridgeCommands.runBridgeCommand("代码对比", () => showDiffMode(), {
      successFeedback: {
        level: "INFO",
        message: "已打开代码对比。",
      },
    });
  }

  function handleRequestSyncPreview() {
    bridgeCommands.runBridgeCommand("同步预览", () => requestSyncPreview(), {
      successFeedback: {
        level: "INFO",
        message: "已打开同步预览。",
      },
    });
  }

  function handleRequestGenerationPlan() {
    bridgeCommands.submitAsyncBridgeCommand("实现计划", () => requestGenerationPlanAsync(), {
      applyRejectedRequestState: setGenerationPlanRequestState,
      applySubmittedRequestState: (requestState) => {
        setGenerationPlan(null);
        setGenerationPlanRequestState(requestState);
      },
      successFeedback: {
        level: "INFO",
        message: "已请求生成实现建议。",
      },
    });
  }

  function handleRequestCodeDrafts() {
    bridgeCommands.submitAsyncBridgeCommand("代码草稿", () => requestCodeDraftsAsync(), {
      applyRejectedRequestState: setCodeDraftRequestState,
      applySubmittedRequestState: (requestState) => {
        setGeneratedCodeDrafts([]);
        setGeneratedCodeDraftWarnings([]);
        setGeneratedCodeDraftSource(null);
        setGeneratedCodeDraftPromptPreview(null);
        setGeneratedCodeDraftPromptPreviewArtifactId(null);
        setGeneratedCodeDraftWriteReport(null);
        setCodeDraftRequestState(requestState);
      },
      successFeedback: {
        level: "INFO",
        message: "已请求生成代码 diff。",
      },
    });
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

  return {
    handleRequestAnalysisDisplayMode,
    handleExportMermaid,
    handleShowDiffMode,
    handleRequestSyncPreview,
    handleRequestGenerationPlan,
    handleRequestCodeDrafts,
    handleOpenSettings,
    handleWriteDrafts,
    handleOpenDraft,
    handleExpandOverflowNode,
  };
}
