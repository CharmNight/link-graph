import { formatResultEvidenceReference, stepKindLabel } from "../../labels";
import type { AssistantTurn, ResultEvidenceReference } from "../../types";
import { assistantTurnKindLabel } from "../assistantModels";

interface ExplanationTurnCardProps {
  turn: AssistantTurn;
  onRevealReference: (reference: ResultEvidenceReference) => void;
  onFollowUpStep?: (stepId: string, question?: string) => void;
  onReturnToPrevious?: () => void;
  canReturnToPrevious?: boolean;
}

export function ExplanationTurnCard({
  turn,
  onRevealReference,
  onFollowUpStep,
  onReturnToPrevious,
  canReturnToPrevious = false,
}: ExplanationTurnCardProps) {
  const result = turn.explanation;
  return (
    <article className="assistant-turn-card assistant-turn-explanation">
      <div className="assistant-turn-head">
        <span className="assistant-turn-kind">{assistantTurnKindLabel(turn.kind)}</span>
        <span className="muted">{turn.context.scopeLabel}</span>
      </div>
      {!result ? (
        <p className="muted">讲解结果尚未返回。</p>
      ) : (
        <div className="assistant-card-flow">
          <div className="panel-actions">
            {canReturnToPrevious && onReturnToPrevious ? (
              <button type="button" className="ghost-button compact" onClick={onReturnToPrevious}>
                返回上一段讲解
              </button>
            ) : null}
          </div>
          {result.steps.map((step) => (
            <section key={step.stepId} className="assistant-evidence-block">
              <div className="preview-head">
                <strong>{step.title}</strong>
                <span className="badge">{stepKindLabel(step.kind)}</span>
              </div>
              <p>{step.description}</p>
              {step.evidence.length > 0 ? (
                <div className="assistant-evidence-list">
                  {step.evidence.map((finding) => (
                    <div key={finding.id} className="assistant-evidence-item">
                      <strong>{finding.claim}</strong>
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
                  ))}
                </div>
              ) : null}
              {step.followUpQuestions[0] && onFollowUpStep ? (
                <button
                  type="button"
                  className="ghost-button compact"
                  onClick={() => onFollowUpStep(step.stepId, step.followUpQuestions[0])}
                >
                  继续追问
                </button>
              ) : null}
            </section>
          ))}
        </div>
      )}
    </article>
  );
}
