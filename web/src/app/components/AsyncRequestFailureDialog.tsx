import { Button } from "./Button";

interface AsyncRequestFailureDialogProps {
  open: boolean;
  title: string;
  message: string;
  detailMessage?: string | null;
  onClose: () => void;
}

export function AsyncRequestFailureDialog({
  open,
  title,
  message,
  detailMessage,
  onClose,
}: AsyncRequestFailureDialogProps) {
  if (!open) {
    return null;
  }

  return (
    <div className="modal-backdrop" role="presentation" onClick={onClose}>
      <section
        className="mermaid-import-dialog request-failure-dialog modal-dialog"
        role="dialog"
        aria-modal="true"
        aria-label="请求状态通知"
        onClick={(event) => event.stopPropagation()}
      >
        <div className="drawer-header modal-header">
          <div>
            <p className="eyebrow">请求状态</p>
            <h2>{title}</h2>
          </div>
          <Button onClick={onClose}>
            关闭提示
          </Button>
        </div>

        <div className="modal-body">
          <article className="panel-section">
            <strong>{message}</strong>
            {detailMessage ? <p className="muted">{detailMessage}</p> : null}
            <p className="muted">如果当前请求依赖远程 LLM，现阶段采用完整返回，不是流式输出。</p>
          </article>
        </div>

        <div className="panel-actions modal-footer">
          <Button variant="primary" onClick={onClose}>
            我知道了
          </Button>
        </div>
      </section>
    </div>
  );
}
