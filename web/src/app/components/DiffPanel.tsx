import type { DiffItem } from "../types";

interface DiffPanelProps {
  items: DiffItem[];
  onSelectItem: (itemId: string) => void;
}

export function DiffPanel({ items, onSelectItem }: DiffPanelProps) {
  return (
    <section className="side-panel diff-panel">
      <p className="eyebrow">Graph Diff</p>
      <h2>Code vs Mermaid</h2>
      <div className="preview-list">
        {items.map((item) => (
          <article key={item.id} className="preview-card">
            <div className="preview-head">
              <strong>{item.title}</strong>
              <span className={`risk-pill risk-${item.status.toLowerCase()}`}>{item.status}</span>
            </div>
            <p>{item.description}</p>
            <button type="button" className="ghost-button" onClick={() => onSelectItem(item.id)}>
              Focus {item.id}
            </button>
          </article>
        ))}
      </div>
    </section>
  );
}
