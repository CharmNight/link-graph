import type { CandidateDraftChange } from "../../types";

interface CandidateChangeCardProps {
  title?: string;
  changes: CandidateDraftChange[];
  onConfirmCandidateChange?: (changeId: string) => void;
}

export function CandidateChangeCard({
  title = "候选草稿变更",
  changes,
  onConfirmCandidateChange,
}: CandidateChangeCardProps) {
  if (changes.length === 0) {
    return null;
  }
  return (
    <article className="assistant-turn-card assistant-turn-candidate-change">
      <div className="assistant-turn-head">
        <span className="assistant-turn-kind">{title}</span>
        <span className="muted">{changes.length} 项</span>
      </div>
      <div className="assistant-card-flow">
        {changes.map((change) => (
          <div key={change.changeId} className="assistant-evidence-item">
            <strong className="assistant-result-text">{change.title}</strong>
            <p className="muted assistant-result-text">{change.impactSummary || change.reason}</p>
            {onConfirmCandidateChange ? (
              <button
                type="button"
                className="ghost-button compact assistant-wrap-token"
                aria-label={`确认候选变更：${change.title}`}
                onClick={() => onConfirmCandidateChange(change.changeId)}
              >
                确认进草稿
              </button>
            ) : null}
          </div>
        ))}
      </div>
    </article>
  );
}
