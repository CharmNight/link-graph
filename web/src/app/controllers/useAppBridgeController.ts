// 应用级桥接命令控制器。
// 把几个不归属特定视图的桥接命令（Mermaid 导入、代码草稿写入/diff、按需加载 artifact）
// 封装为统一的回调集合，供 App 组件使用。
import {
  applySingleCodeDraft,
  importMermaid,
  openCodeDraftNativeDiff,
  requestArtifactContent,
} from "../api";
import type { useBridgeCommandController } from "./useBridgeCommandController";

/** useAppBridgeController 的入参。 */
interface UseAppBridgeControllerArgs {
  /** 桥接命令控制器（用到 runBridgeCommand 与 submitAsyncBridgeCommand）。 */
  bridgeCommands: Pick<
    ReturnType<typeof useBridgeCommandController>,
    "runBridgeCommand" | "submitAsyncBridgeCommand"
  >;
  /** 控制导入弹窗开/关的回调。 */
  setImportDialogOpen: (open: boolean) => void;
}

/**
 * 应用桥接控制器 Hook。
 *
 * 封装四个桥接命令：
 * - 导入 Mermaid：提交后关闭弹窗并给成功反馈；
 * - 写入单个代码草稿；
 * - 打开代码草稿原生 Diff 视图；
 * - 按需加载 artifact 内容（失败时不弹错误，静默处理）。
 */
export function useAppBridgeController({
  bridgeCommands,
  setImportDialogOpen,
}: UseAppBridgeControllerArgs) {
  /** 提交 Mermaid 导入请求。成功后关闭弹窗。 */
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

  /** 写入单个代码草稿（按 draftId）。 */
  function handleWriteSingleCodeDraft(draftId: string) {
    bridgeCommands.runBridgeCommand("写入单个代码草稿", () => applySingleCodeDraft(draftId));
  }

  /** 打开代码草稿的原生 Diff 视图。 */
  function handleOpenCodeDraftNativeDiff(draftId: string) {
    bridgeCommands.runBridgeCommand("打开代码草稿原生 Diff", () => openCodeDraftNativeDiff(draftId));
  }

  /** 按需加载 artifact 内容（失败时静默，不弹错误框）。 */
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
