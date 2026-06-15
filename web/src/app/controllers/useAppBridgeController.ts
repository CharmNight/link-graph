import {
  applySingleCodeDraft,
  importMermaid,
  openCodeDraftNativeDiff,
  requestArtifactContent,
} from "../api";
import type { useBridgeCommandController } from "./useBridgeCommandController";

interface UseAppBridgeControllerArgs {
  bridgeCommands: Pick<
    ReturnType<typeof useBridgeCommandController>,
    "runBridgeCommand" | "submitAsyncBridgeCommand"
  >;
  setImportDialogOpen: (open: boolean) => void;
}

export function useAppBridgeController({
  bridgeCommands,
  setImportDialogOpen,
}: UseAppBridgeControllerArgs) {
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

  function handleWriteSingleCodeDraft(draftId: string) {
    bridgeCommands.runBridgeCommand("写入单个代码草稿", () => applySingleCodeDraft(draftId));
  }

  function handleOpenCodeDraftNativeDiff(draftId: string) {
    bridgeCommands.runBridgeCommand("打开代码草稿原生 Diff", () => openCodeDraftNativeDiff(draftId));
  }

  function handleRequestArtifact(artifactId: string) {
    bridgeCommands.runBridgeCommand("加载按需内容", () => requestArtifactContent([artifactId]), {
      announceFailure: false,
    });
  }

  return {
    handleConfirmImportMermaid,
    handleWriteSingleCodeDraft,
    handleOpenCodeDraftNativeDiff,
    handleRequestArtifact,
  };
}
