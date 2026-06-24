import type { RequestFailureNotice } from "../controllers/bridgeCommandTypes";
import { AsyncRequestFailureDialog } from "./AsyncRequestFailureDialog";
import { MermaidImportDialog } from "./MermaidImportDialog";

/** AppDialogs 组件的入参。 */
interface AppDialogsProps {
  /** Mermaid 导入弹窗是否打开。 */
  importDialogOpen: boolean;
  /** Mermaid 草稿文本。 */
  mermaidDraft: string;
  /** 当前请求失败通知；为 null 时不展示失败弹窗。 */
  requestFailureNotice: RequestFailureNotice | null;
  /** Mermaid 草稿变化回调。 */
  onMermaidDraftChange: (value: string) => void;
  /** 取消 Mermaid 导入的回调。 */
  onCancelImport: () => void;
  /** 确认 Mermaid 导入的回调。 */
  onConfirmImport: () => void;
  /** 关闭请求失败弹窗的回调。 */
  onCloseRequestFailure: () => void;
}

/**
 * 应用级弹窗集合。
 *
 * 把应用中所有模态弹窗（Mermaid 导入、请求失败通知）集中到一个组件，
 * 让 App.tsx 不必分别处理每个弹窗的状态与渲染。
 * 两个弹窗独立开关，互不影响。
 */
export function AppDialogs({
  importDialogOpen,
  mermaidDraft,
  requestFailureNotice,
  onMermaidDraftChange,
  onCancelImport,
  onConfirmImport,
  onCloseRequestFailure,
}: AppDialogsProps) {
  return (
    <>
      <MermaidImportDialog
        open={importDialogOpen}
        value={mermaidDraft}
        onChange={onMermaidDraftChange}
        onCancel={onCancelImport}
        onConfirm={onConfirmImport}
      />

      <AsyncRequestFailureDialog
        // 通知对象非空时打开
        open={requestFailureNotice !== null}
        title={requestFailureNotice?.title ?? ""}
        message={requestFailureNotice?.message ?? ""}
        detailMessage={requestFailureNotice?.detailMessage ?? null}
        onClose={onCloseRequestFailure}
      />
    </>
  );
}
