import type { MermaidIssue } from "../types";
import { issueCategoryLabel } from "../labels";

interface IssuePanelProps {
  items: MermaidIssue[];
}

function buildLocationHint(item: MermaidIssue): string[] {
  const hints: string[] = [];
  if (item.line != null) {
    hints.push(`第 ${item.line} 行`);
  }
  if (item.nodeId) {
    hints.push(`节点 ${item.nodeId}`);
  }
  if (item.edgeId) {
    hints.push(`边 ${item.edgeId}`);
  }
  return hints;
}

export function IssuePanel({ items }: IssuePanelProps) {
  return (
    <section className="side-panel issue-panel">
      <p className="eyebrow">Mermaid 问题</p>
      <h2>校验结果</h2>
      <div className="preview-list">
        {items.map((item, index) => {
          const locationHints = buildLocationHint(item);
          return (
            <article key={`${item.code}-${item.nodeId ?? item.edgeId ?? index}`} className="preview-card">
              <div className="preview-head">
                <strong>{item.code}</strong>
                <span className={`risk-pill risk-${item.category.toLowerCase()}`}>{issueCategoryLabel(item.category)}</span>
              </div>
              <p>{item.message}</p>
              {locationHints.length > 0 ? <p className="muted">{locationHints.join(" | ")}</p> : null}
            </article>
          );
        })}
      </div>
    </section>
  );
}
