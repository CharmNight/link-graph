import type { CSSProperties } from "react";
import {
  draftClaimTypeDescription,
  draftClaimTypeLabel,
  formatResultEvidenceReference,
  investigationLeadStatusLabel,
  resultEvidenceLevelLabel,
} from "../labels";
import type { AuditInvestigationLead } from "../types";

interface InvestigationLeadListProps {
  leads: AuditInvestigationLead[];
  selectedLeadId?: string | null;
  onSelectLead: (leadId: string) => void;
  onInvestigateLead: (leadId: string) => void;
  compact?: boolean;
  style?: CSSProperties;
  showTitle?: boolean;
}

export function InvestigationLeadList({
  leads,
  selectedLeadId,
  onSelectLead,
  onInvestigateLead,
  compact = false,
  style,
  showTitle = true,
}: InvestigationLeadListProps) {
  const selectedLead = leads.find((lead) => lead.leadId === selectedLeadId) ?? leads[0] ?? null;

  return (
    <section
      className={
        leads.length > 0
          ? compact ? "workbench-candidate-list compact" : "workbench-candidate-list"
          : "workbench-candidate-list is-empty"
      }
      style={style}
    >
      {showTitle ? (
        <div className="workbench-section-title-row">
          <h3>风险线索</h3>
          <span className="badge">{leads.length}</span>
        </div>
      ) : null}
      {leads.length > 0 ? (
        <div className={compact ? "workbench-candidate-content compact" : "workbench-candidate-content"}>
          <div className="workbench-candidate-selector" aria-label="风险线索列表">
            {leads.map((lead) => (
              <button
                key={lead.leadId}
                type="button"
                aria-pressed={selectedLead?.leadId === lead.leadId}
                className={selectedLead?.leadId === lead.leadId ? "workbench-candidate-tab active" : "workbench-candidate-tab"}
                onClick={() => onSelectLead(lead.leadId)}
                title={lead.title}
                aria-label={`风险线索：${lead.title}`}
              >
                <span className="workbench-candidate-tab-title">{lead.title}</span>
                <span className="workbench-status-pill">{investigationLeadStatusLabel(lead.status)}</span>
              </button>
            ))}
          </div>
          <div className="workbench-candidate-detail-pane">
            {selectedLead ? (
              <article className="workbench-candidate-card active">
                <div className="workbench-candidate-head">
                  <strong>{selectedLead.title}</strong>
                  <span className="workbench-status-pill">{investigationLeadStatusLabel(selectedLead.status)}</span>
                </div>
                <div className="workbench-candidate-meta">
                  <span className="toolbar-chip" title={draftClaimTypeDescription(selectedLead.claimType)}>
                    {draftClaimTypeLabel(selectedLead.claimType)}
                  </span>
                  <span className="muted">当前只能作为线索继续取证，不能直接进入草稿。</span>
                </div>
                <p>{selectedLead.summary || "当前没有补充说明。"}</p>
                <section className="workbench-candidate-evidence">
                  <strong>已观察到的证据</strong>
                  {selectedLead.evidence.length > 0 ? (
                    <ul className="answer-list">
                      {selectedLead.evidence.map((finding) => (
                        <li key={finding.id} className="workbench-candidate-evidence-item">
                          <strong>{resultEvidenceLevelLabel(finding.evidenceLevel)}</strong>
                          <span>{finding.claim}</span>
                          {finding.references.length > 0 ? (
                            <span className="muted">
                              {finding.references.map((reference) => formatResultEvidenceReference(reference)).join(" · ")}
                            </span>
                          ) : null}
                        </li>
                      ))}
                    </ul>
                  ) : (
                    <p className="muted">当前没有附带可追溯证据。</p>
                  )}
                </section>
                <dl className="workbench-before-after">
                  <div>
                    <dt>证据缺口</dt>
                    <dd>{selectedLead.evidenceGap || "未标注"}</dd>
                  </div>
                  <div>
                    <dt>建议下一问</dt>
                    <dd>{selectedLead.recommendedQuestion || "未标注"}</dd>
                  </div>
                </dl>
                <button
                  type="button"
                  className="primary-button"
                  onClick={() => onInvestigateLead(selectedLead.leadId)}
                >
                  继续取证
                </button>
              </article>
            ) : null}
          </div>
        </div>
      ) : (
        <div className="workbench-empty-card">
          <strong>当前没有风险线索</strong>
          <p className="muted">如果证据不足但值得继续追问，审计结果会在这里显示为风险线索。</p>
        </div>
      )}
    </section>
  );
}
