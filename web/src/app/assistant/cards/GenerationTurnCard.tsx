import { generationSourceLabel, riskLabel } from "../../labels";
import type { AssistantTurn } from "../../types";
import type { AssistantArtifactAccess } from "../assistantArtifacts";
import { AssistantFailureNotice } from "./AssistantFailureNotice";
import { AssistantPromptDisclosure } from "./AssistantPromptDisclosure";
import { AssistantTurnHeader } from "./AssistantTurnFrame";

interface GenerationTurnCardProps extends AssistantArtifactAccess {
  turn: AssistantTurn;
  turnIndex?: number;
  isLatest?: boolean;
  showTurnHeader?: boolean;
  onPrimeGenerationPlan: () => void;
  onRequestCodeDrafts: () => void;
  onDiscussGenerationPlan?: (question?: string) => void;
}

export function GenerationTurnCard({
  turn,
  turnIndex,
  isLatest = false,
  showTurnHeader = true,
  onPrimeGenerationPlan,
  onRequestCodeDrafts,
  onDiscussGenerationPlan,
  resolveArtifactText,
  onRequestArtifact,
}: GenerationTurnCardProps) {
  const plan = turn.generationPlan;
  const discussionMessages = turn.generationDiscussionSession?.messages ?? [];
  const lastUserQuestion = [...discussionMessages].reverse().find((message) => message.role === "USER")?.content;
  return (
    <article className="assistant-turn-card assistant-turn-generation">
      {showTurnHeader ? <AssistantTurnHeader turn={turn} turnIndex={turnIndex} isLatest={isLatest} /> : null}
      {!plan && turn.failure ? (
        <AssistantFailureNotice failure={turn.failure} />
      ) : !plan ? (
        <div className="assistant-card-flow">
          <p className="muted assistant-result-text">生成实现建议会先整理可审查方案，确认后再生成代码 diff。</p>
          <button type="button" className="primary-button" onClick={onPrimeGenerationPlan}>
            生成实现建议
          </button>
        </div>
      ) : (
        <div className="assistant-card-flow">
          <section className="assistant-evidence-block">
            <div className="preview-head">
              <strong className="assistant-result-text">{plan.summary}</strong>
              <span className="badge">{generationSourceLabel(plan.source)}</span>
            </div>
            {plan.items.map((item) => (
              <div key={item.id} className="assistant-evidence-item">
                <strong className="assistant-result-text">{item.title}</strong>
                <p className="assistant-result-text">{item.description}</p>
                <span className={`risk-pill risk-${item.risk.toLowerCase()}`}>{riskLabel(item.risk)}</span>
                {item.targetPath ? <p className="muted assistant-result-text">{item.targetPath}</p> : null}
              </div>
            ))}
          </section>
          <AssistantPromptDisclosure
            promptPreview={plan.promptPreview}
            promptPreviewArtifactId={plan.promptPreviewArtifactId}
            resolveArtifactText={resolveArtifactText}
            onRequestArtifact={onRequestArtifact}
          />
          <section className="assistant-evidence-block">
            <strong>实现建议追问</strong>
            {discussionMessages.length === 0 ? (
              <p className="muted assistant-result-text">还没有围绕这份实现建议继续讨论。</p>
            ) : null}
            {discussionMessages.map((message) => (
              <p
                key={message.messageId}
                className={
                  message.role === "USER"
                    ? "assistant-question assistant-result-text"
                    : "muted assistant-result-text"
                }
              >
                {message.content}
              </p>
            ))}
            {onDiscussGenerationPlan ? (
              <button
                type="button"
                className="ghost-button compact assistant-wrap-token"
                onClick={() => onDiscussGenerationPlan(lastUserQuestion)}
              >
                追问建议
              </button>
            ) : null}
          </section>
          <AssistantPromptDisclosure
            promptPreview={turn.generationDiscussionSession?.promptPreview}
            promptPreviewArtifactId={turn.generationDiscussionSession?.promptPreviewArtifactId}
            resolveArtifactText={resolveArtifactText}
            onRequestArtifact={onRequestArtifact}
          />
          <button type="button" className="primary-button" onClick={onRequestCodeDrafts}>
            生成代码 diff
          </button>
        </div>
      )}
    </article>
  );
}
