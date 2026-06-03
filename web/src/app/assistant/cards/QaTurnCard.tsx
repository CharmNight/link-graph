import { formatResultEvidenceReference, resultEvidenceLevelLabel } from "../../labels";
import type {
  AssistantTurn,
  QaRequestRecoveryState,
  ResultEvidenceReference,
  RiskResolutionStatus,
} from "../../types";
import { assistantTurnKindLabel } from "../assistantModels";

interface QaTurnCardProps {
  turn: AssistantTurn;
  recoveryState?: QaRequestRecoveryState | null;
  onRetryLastQaRequest: () => void;
  onEditFailedQaRequest: () => void;
  onRevealReference: (reference: ResultEvidenceReference) => void;
  onConfirmCandidateChange?: (changeId: string) => void;
  onInvestigateThread?: (threadId: string) => void;
  onResolveThread?: (threadId: string, status: RiskResolutionStatus) => void;
}

export function QaTurnCard({
  turn,
  recoveryState,
  onRetryLastQaRequest,
  onEditFailedQaRequest,
  onRevealReference,
  onConfirmCandidateChange,
  onInvestigateThread,
  onResolveThread,
}: QaTurnCardProps) {
  const result = turn.qa;
  const failedRequest = recoveryState?.lastFailedRequest ?? null;
  return (
    <article className="assistant-turn-card assistant-turn-qa">
      <div className="assistant-turn-head">
        <span className="assistant-turn-kind">{assistantTurnKindLabel(turn.kind)}</span>
        <span className="muted">{turn.context.scopeLabel}</span>
      </div>
      {!result ? (
        <p className="muted">问答结果尚未返回。</p>
      ) : (
        <div className="assistant-card-flow">
          <p className="assistant-question">{result.question}</p>
          <p>{result.answer}</p>
          <EvidenceFindings findings={result.findings} onRevealReference={onRevealReference} />
          {result.candidateChanges.length > 0 ? (
            <section className="assistant-evidence-block">
              <strong>候选草稿变更</strong>
              {result.candidateChanges.map((change) => (
                <div key={change.changeId} className="assistant-evidence-item">
                  <strong>{change.title}</strong>
                  <p className="muted">{change.impactSummary || change.reason}</p>
                  {onConfirmCandidateChange ? (
                    <button
                      type="button"
                      className="ghost-button compact"
                      onClick={() => onConfirmCandidateChange(change.changeId)}
                    >
                      确认进草稿
                    </button>
                  ) : null}
                </div>
              ))}
            </section>
          ) : null}
          {(result.investigationThreads ?? []).length > 0 ? (
            <section className="assistant-evidence-block">
              <strong>风险线程</strong>
              {(result.investigationThreads ?? []).map((thread) => (
                <div key={thread.threadId} className="assistant-evidence-item">
                  <strong>{thread.title}</strong>
                  <p className="muted">{thread.summary}</p>
                  <div className="panel-actions">
                    {onInvestigateThread ? (
                      <button type="button" className="ghost-button compact" onClick={() => onInvestigateThread(thread.threadId)}>
                        继续取证
                      </button>
                    ) : null}
                    {onResolveThread ? (
                      <>
                        <button type="button" className="ghost-button compact" onClick={() => onResolveThread(thread.threadId, "DEFERRED")}>
                          暂挂
                        </button>
                        <button type="button" className="ghost-button compact" onClick={() => onResolveThread(thread.threadId, "ACCEPTED_RISK")}>
                          接受风险
                        </button>
                        <button type="button" className="ghost-button compact" onClick={() => onResolveThread(thread.threadId, "DISMISSED")}>
                          排除
                        </button>
                      </>
                    ) : null}
                  </div>
                </div>
              ))}
            </section>
          ) : null}
        </div>
      )}
      {failedRequest ? (
        <div className="assistant-recovery-actions">
          <button type="button" className="ghost-button compact" onClick={onRetryLastQaRequest}>
            直接重试
          </button>
          <button type="button" className="ghost-button compact" onClick={onEditFailedQaRequest}>
            修改后重试
          </button>
        </div>
      ) : null}
    </article>
  );
}

export function EvidenceFindings({
  findings,
  onRevealReference,
}: {
  findings: NonNullable<AssistantTurn["qa"]>["findings"];
  onRevealReference: (reference: ResultEvidenceReference) => void;
}) {
  if (findings.length === 0) {
    return null;
  }
  return (
    <section className="assistant-evidence-block">
      <strong>证据引用</strong>
      {findings.map((finding) => (
        <div key={finding.id} className="assistant-evidence-item">
          <span className="badge">{resultEvidenceLevelLabel(finding.evidenceLevel)}</span>
          <p>{finding.claim}</p>
          <div className="panel-actions">
            {finding.references.map((reference, index) => (
              <button
                key={`${finding.id}:${index}`}
                type="button"
                className="ghost-button compact"
                onClick={() => onRevealReference(reference)}
              >
                {formatResultEvidenceReference(reference)}
              </button>
            ))}
          </div>
        </div>
      ))}
    </section>
  );
}
