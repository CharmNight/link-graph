import type { AssistantTurn, ResultEvidenceReference, RiskResolutionStatus } from "../../types";
import { assistantTurnKindLabel } from "../assistantModels";
import { EvidenceFindings } from "./QaTurnCard";

interface CheckTurnCardProps {
  turn: AssistantTurn;
  onRevealReference: (reference: ResultEvidenceReference) => void;
  onConfirmCandidateChange?: (changeId: string) => void;
  onInvestigateThread?: (threadId: string) => void;
  onResolveThread?: (threadId: string, status: RiskResolutionStatus) => void;
}

export function CheckTurnCard({
  turn,
  onRevealReference,
  onConfirmCandidateChange,
  onInvestigateThread,
  onResolveThread,
}: CheckTurnCardProps) {
  const result = turn.check;
  return (
    <article className="assistant-turn-card assistant-turn-check">
      <div className="assistant-turn-head">
        <span className="assistant-turn-kind">{assistantTurnKindLabel(turn.kind)}</span>
        <span className="muted">{turn.context.scopeLabel}</span>
      </div>
      {!result ? (
        <p className="muted">检查结果尚未返回。</p>
      ) : (
        <div className="assistant-card-flow">
          <p className="assistant-question">{result.question}</p>
          <p>{result.answer}</p>
          <EvidenceFindings findings={result.findings} onRevealReference={onRevealReference} />
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
          {result.candidateChanges.length > 0 ? (
            <section className="assistant-evidence-block">
              <strong>建议草稿项</strong>
              {result.candidateChanges.map((change) => (
                <div key={change.changeId} className="assistant-evidence-item">
                  <strong>{change.title}</strong>
                  <p className="muted">{change.impactSummary || change.reason}</p>
                  {onConfirmCandidateChange ? (
                    <button type="button" className="ghost-button compact" onClick={() => onConfirmCandidateChange(change.changeId)}>
                      确认进草稿
                    </button>
                  ) : null}
                </div>
              ))}
            </section>
          ) : null}
        </div>
      )}
    </article>
  );
}
