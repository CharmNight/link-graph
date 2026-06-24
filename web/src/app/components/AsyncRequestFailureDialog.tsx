import { Button } from "./Button";

/** AsyncRequestFailureDialog 组件的入参。 */
interface AsyncRequestFailureDialogProps {
  /** 是否显示。 */
  open: boolean;
  /** 弹窗标题。 */
  title: string;
  /** 主消息。 */
  message: string;
  /** 详细消息（可选）；用于补充上下文。 */
  detailMessage?: string | null;
  /** 关闭回调。 */
  onClose: () => void;
}

/**
 * 异步请求失败提示弹窗。
 *
 * 在异步请求失败时弹出，告知用户具体原因。
 * 与 [MermaidImportDialog] 类似的页内弹窗形态，避免使用 window.alert 等不稳定 API。
 */
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
        // 阻止点击事件冒泡到背景
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
