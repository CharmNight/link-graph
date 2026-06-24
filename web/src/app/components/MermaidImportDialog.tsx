import { Button } from "./Button";

/** MermaidImportDialog 组件的入参。 */
interface MermaidImportDialogProps {
  /** 是否显示。 */
  open: boolean;
  /** 当前输入的 Mermaid 文本。 */
  value: string;
  /** 文本变化回调。 */
  onChange: (value: string) => void;
  /** 取消回调（关闭弹窗）。 */
  onCancel: () => void;
  /** 确认导入回调。 */
  onConfirm: () => void;
}

/**
 * 页内 Mermaid 导入弹窗。
 *
 * JCEF 中直接依赖 window.prompt 交互不稳定（被浏览器/IDE 拦截、行为不一致），
 * 因此改为页内 Mermaid 导入弹层，提供更稳定的输入体验。
 *
 * 行为：
 * - 点击背景关闭弹窗；
 * - 点击弹窗内容阻止冒泡（避免误关闭）；
 * - 提供取消与确认两个底部按钮。
 */
export function MermaidImportDialog({
  open,
  value,
  onChange,
  onCancel,
  onConfirm,
}: MermaidImportDialogProps) {
  // 未打开时不渲染任何 DOM
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
        // 阻止点击事件冒泡到背景，避免内容点击意外关闭
        onClick={(event) => event.stopPropagation()}
      >
        <div className="drawer-header modal-header">
          <div>
            <p className="eyebrow">Mermaid</p>
            <h2>导入 Mermaid</h2>
          </div>
          <Button onClick={onCancel}>
            取消
          </Button>
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
          <Button variant="primary" onClick={onConfirm}>
            确认导入
          </Button>
          <Button onClick={onCancel}>
            关闭
          </Button>
        </div>
      </section>
    </div>
  );
}
