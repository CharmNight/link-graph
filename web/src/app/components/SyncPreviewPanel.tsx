import type { SyncPreviewItem } from "../types";
import { riskLabel } from "../labels";

interface SyncPreviewPanelProps {
  items: SyncPreviewItem[];
}

export function SyncPreviewPanel({ items }: SyncPreviewPanelProps) {
  return (
    <section className="side-panel sync-panel">
      <p className="eyebrow">同步预览</p>
      <h2>计划中的代码变更</h2>
      <div className="preview-list">
        {items.map((item) => (
          <article key={item.id} className="preview-card">
            <div className="preview-head">
              <strong>{item.title}</strong>
              <span className={`risk-pill risk-${item.risk.toLowerCase()}`}>{riskLabel(item.risk)}</span>
            </div>
            <p>{item.description}</p>
          </article>
        ))}
      </div>
    </section>
  );
}
