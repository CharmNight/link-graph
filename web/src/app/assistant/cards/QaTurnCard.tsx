import { formatResultEvidenceReference, resultEvidenceLevelLabel } from "../../labels";
import type {
  AssistantTurn,
  QaRequestRecoveryState,
  ResultEvidenceReference,
} from "../../types";
import type { AssistantArtifactAccess } from "../assistantArtifacts";
import { AssistantFailureNotice } from "./AssistantFailureNotice";
import { AssistantPromptDisclosure } from "./AssistantPromptDisclosure";
import { AssistantQuestionAnswer, AssistantTurnHeader } from "./AssistantTurnFrame";

interface QaTurnCardProps extends AssistantArtifactAccess {
  turn: AssistantTurn;
  turnIndex?: number;
  isLatest?: boolean;
  recoveryState?: QaRequestRecoveryState | null;
  onRetryLastQaRequest: () => void;
  onEditFailedQaRequest: () => void;
  onRevealReference: (reference: ResultEvidenceReference) => void;
}

export function QaTurnCard({
  turn,
  turnIndex,
  isLatest = false,
  recoveryState,
  onRetryLastQaRequest,
  onEditFailedQaRequest,
  onRevealReference,
  resolveArtifactText,
  onRequestArtifact,
}: QaTurnCardProps) {
  const result = turn.qa;
  const failedRequest = recoveryState?.lastFailedRequest ?? null;
  return (
    <article className="assistant-turn-card assistant-turn-qa">
      <AssistantTurnHeader turn={turn} turnIndex={turnIndex} isLatest={isLatest} />
      {!result && turn.failure ? (
        <AssistantFailureNotice failure={turn.failure} />
      ) : !result ? (
        <p className="muted assistant-result-text">问答结果尚未返回。</p>
      ) : (
        <div className="assistant-card-flow">
          <AssistantQuestionAnswer question={result.question} answer={result.answer} />
          <EvidenceFindings findings={result.findings} onRevealReference={onRevealReference} />
          <AssistantPromptDisclosure
            promptPreview={result.promptPreview}
            promptPreviewArtifactId={result.promptPreviewArtifactId}
            resolveArtifactText={resolveArtifactText}
            onRequestArtifact={onRequestArtifact}
          />
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
          <p className="assistant-result-text">{finding.claim}</p>
          <div className="panel-actions">
            {finding.references.map((reference, index) => (
              <button
                key={`${finding.id}:${index}`}
                type="button"
                className="ghost-button compact assistant-wrap-token"
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
