import type { RequestFailureNotice } from "../controllers/bridgeCommandTypes";
import { AsyncRequestFailureDialog } from "./AsyncRequestFailureDialog";
import { MermaidImportDialog } from "./MermaidImportDialog";

interface AppDialogsProps {
  importDialogOpen: boolean;
  mermaidDraft: string;
  requestFailureNotice: RequestFailureNotice | null;
  onMermaidDraftChange: (value: string) => void;
  onCancelImport: () => void;
  onConfirmImport: () => void;
  onCloseRequestFailure: () => void;
}

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
        open={requestFailureNotice !== null}
        title={requestFailureNotice?.title ?? ""}
        message={requestFailureNotice?.message ?? ""}
        detailMessage={requestFailureNotice?.detailMessage ?? null}
        onClose={onCloseRequestFailure}
      />
    </>
  );
}
