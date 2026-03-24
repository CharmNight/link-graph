import type { DiffItem } from "../types";

interface DiffPanelProps {
  items: DiffItem[];
}

export function DiffPanel({ items }: DiffPanelProps) {
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
          </article>
        ))}
      </div>
    </section>
  );
}
