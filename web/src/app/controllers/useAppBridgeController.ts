import {
  applySingleCodeDraft,
  importMermaid,
  openCodeDraftNativeDiff,
  requestArtifactContent,
  requestDiffReviewAsync,
  updateWorkbenchSectionPreference,
} from "../api";
import type { WorkbenchSectionId } from "../types";
import type { useBridgeCommandController } from "./useBridgeCommandController";

interface UseAppBridgeControllerArgs {
  artifactContents: Record<string, string>;
  bridgeCommands: Pick<
    ReturnType<typeof useBridgeCommandController>,
    "runBridgeCommand" | "submitAsyncBridgeCommand"
  >;
  setImportDialogOpen: (open: boolean) => void;
}

export function useAppBridgeController({
  artifactContents,
  bridgeCommands,
  setImportDialogOpen,
}: UseAppBridgeControllerArgs) {
  function resolveArtifactText(artifactId: string): string | null {
    return artifactContents[artifactId] ?? null;
  }

  function handleRequestArtifact(artifactId: string) {
    if (artifactContents[artifactId]) {
      return;
    }
    requestArtifactContent([artifactId]);
  }

  function handleWorkbenchSectionPreferenceChange(sectionId: WorkbenchSectionId, expanded: boolean) {
    updateWorkbenchSectionPreference(sectionId, expanded);
  }

  function handleConfirmImportMermaid(mermaid: string) {
    bridgeCommands.runBridgeCommand("导入 Mermaid", () => importMermaid(mermaid), {
      onAccepted: () => {
        setImportDialogOpen(false);
      },
      successFeedback: {
        level: "INFO",
        message: "已提交 Mermaid 导入请求，正在校验链路图。",
      },
    });
  }

  function handleRequestDiffReview(question: string, diffTargetItemIds: string[]) {
    bridgeCommands.submitAsyncBridgeCommand("差异问答", () => requestDiffReviewAsync(question, diffTargetItemIds), {
      successFeedback: {
        level: "INFO",
        message: diffTargetItemIds.length > 0
          ? "已提交焦点差异问答请求，正在生成解释和修订草稿。"
          : "已提交差异问答请求，正在生成解释和修订草稿。",
      },
    });
  }

  function handleWriteSingleCodeDraft(draftId: string) {
    bridgeCommands.runBridgeCommand("写入单个代码草稿", () => applySingleCodeDraft(draftId));
  }

  function handleOpenCodeDraftNativeDiff(draftId: string) {
    bridgeCommands.runBridgeCommand("打开代码草稿原生 Diff", () => openCodeDraftNativeDiff(draftId));
  }

  return {
    resolveArtifactText,
    handleRequestArtifact,
    handleWorkbenchSectionPreferenceChange,
    handleConfirmImportMermaid,
    handleRequestDiffReview,
    handleWriteSingleCodeDraft,
    handleOpenCodeDraftNativeDiff,
  };
}
