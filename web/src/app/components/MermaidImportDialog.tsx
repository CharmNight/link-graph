interface MermaidImportDialogProps {
  open: boolean;
  value: string;
  onChange: (value: string) => void;
  onCancel: () => void;
  onConfirm: () => void;
}

/**
 * JCEF 中直接依赖 window.prompt 交互不稳定，因此改为页内 Mermaid 导入弹层。
 */
export function MermaidImportDialog({
  open,
  value,
  onChange,
  onCancel,
  onConfirm,
}: MermaidImportDialogProps) {
  if (!open) {
    return null;
  }

  return (
    <div className="modal-backdrop" role="presentation" onClick={onCancel}>
      <section
        className="mermaid-import-dialog modal-dialog"
        role="dialog"
        aria-modal="true"
        aria-label="导入 Mermaid"
        onClick={(event) => event.stopPropagation()}
      >
        <div className="drawer-header modal-header">
          <div>
            <p className="eyebrow">Mermaid</p>
            <h2>导入 Mermaid</h2>
          </div>
          <button type="button" className="ghost-button" onClick={onCancel}>
            取消
          </button>
        </div>

        <div className="modal-body">
          <label>
            Mermaid 内容
            <textarea
              aria-label="Mermaid 内容"
              className="prompt-preview"
              value={value}
              placeholder={"graph TD\nA[Controller] --> B[Service]"}
              onChange={(event) => onChange(event.target.value)}
            />
          </label>
        </div>

        <div className="panel-actions modal-footer">
          <button type="button" className="primary-button" onClick={onConfirm}>
            确认导入
          </button>
          <button type="button" className="ghost-button" onClick={onCancel}>
            关闭
          </button>
        </div>
      </section>
    </div>
  );
}
