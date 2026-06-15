import type { AssistantTurn, ResultEvidenceReference } from "../../types";
import type { AssistantArtifactAccess } from "../assistantArtifacts";
import { AssistantFailureNotice } from "./AssistantFailureNotice";
import { AssistantPromptDisclosure } from "./AssistantPromptDisclosure";
import { AssistantQuestionAnswer, AssistantTurnHeader } from "./AssistantTurnFrame";
import { EvidenceFindings } from "./QaTurnCard";

interface CheckTurnCardProps extends AssistantArtifactAccess {
  turn: AssistantTurn;
  turnIndex?: number;
  isLatest?: boolean;
  onRevealReference: (reference: ResultEvidenceReference) => void;
}

export function CheckTurnCard({
  turn,
  turnIndex,
  isLatest = false,
  onRevealReference,
  resolveArtifactText,
  onRequestArtifact,
}: CheckTurnCardProps) {
  const result = turn.check;
  return (
    <article className="assistant-turn-card assistant-turn-check">
      <AssistantTurnHeader turn={turn} turnIndex={turnIndex} isLatest={isLatest} />
      {!result && turn.failure ? (
        <AssistantFailureNotice failure={turn.failure} />
      ) : !result ? (
        <p className="muted assistant-result-text">检查结果尚未返回。</p>
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
    </article>
  );
}
