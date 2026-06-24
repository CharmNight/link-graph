import { formatResultEvidenceReference, resultEvidenceLevelLabel } from "../labels";
import type { ResultEvidenceFinding } from "../types";

/** EvidenceFindingsSection 组件的入参。 */
interface EvidenceFindingsSectionProps {
  /** 证据发现列表。 */
  findings: ResultEvidenceFinding[];
}

/**
 * 证据发现列表区块。
 *
 * 在 QA 结果摘要中展示每条关键结论及其证据引用：
 * - 每条发现显示声明文本与证据级别；
 * - 引用列表展示具体代码位置（节点 ID 或文件路径 + 行号）。
 *
 * 空列表不渲染任何内容。
 */
export function EvidenceFindingsSection({ findings }: EvidenceFindingsSectionProps) {
  if (findings.length === 0) {
    return null;
  }

  return (
    <section className="answer-section">
      <strong>关键结论与证据</strong>
      <div className="answer-list">
        {findings.map((finding) => (
          <article key={finding.id} className="preview-card">
            <div className="preview-head">
              <strong>{finding.claim}</strong>
              <span className="toolbar-chip">{resultEvidenceLevelLabel(finding.evidenceLevel)}</span>
            </div>
            {finding.references.length > 0 ? (
              <ul className="answer-list">
                {finding.references.map((reference, index) => (
                  <li
                    key={`${finding.id}-${reference.nodeId ?? reference.filePath ?? "reference"}-${index}`}
                    className="muted"
                  >
                    {formatResultEvidenceReference(reference)}
                  </li>
                ))}
              </ul>
            ) : (
              // 无引用时给出明确提示，避免空白
              <p className="muted">当前结论未附带引用位置。</p>
            )}
          </article>
        ))}
      </div>
    </section>
  );
}
