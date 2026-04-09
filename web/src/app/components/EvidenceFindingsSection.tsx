import { formatResultEvidenceReference, resultEvidenceLevelLabel } from "../labels";
import type { ResultEvidenceFinding } from "../types";

interface EvidenceFindingsSectionProps {
  findings: ResultEvidenceFinding[];
}

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
              <p className="muted">当前结论未附带引用位置。</p>
            )}
          </article>
        ))}
      </div>
    </section>
  );
}
