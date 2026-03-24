import type { SyncPreviewItem } from "../types";

interface SyncPreviewPanelProps {
  items: SyncPreviewItem[];
}

export function SyncPreviewPanel({ items }: SyncPreviewPanelProps) {
  return (
    <section className="side-panel sync-panel">
      <p className="eyebrow">Sync Preview</p>
      <h2>Planned Code Changes</h2>
      <div className="preview-list">
        {items.map((item) => (
          <article key={item.id} className="preview-card">
            <div className="preview-head">
              <strong>{item.title}</strong>
              <span className={`risk-pill risk-${item.risk.toLowerCase()}`}>{item.risk}</span>
            </div>
            <p>{item.description}</p>
          </article>
        ))}
      </div>
    </section>
  );
}
