import { generationSourceLabel, riskLabel } from "../../labels";
import type { AssistantTurn } from "../../types";
import { assistantTurnKindLabel } from "../assistantModels";

interface GenerationTurnCardProps {
  turn: AssistantTurn;
  discussionQuestionDraft: string;
  onDiscussionQuestionDraftChange?: (value: string) => void;
  onSubmitDiscussion?: () => void;
  onRequestGenerationPlan: () => void;
  onRequestCodeDrafts: () => void;
  onWriteCodeDrafts: () => void;
  onWriteSingleCodeDraft?: (draftId: string) => void;
  onOpenNativeDiff?: (draftId: string) => void;
  onOpenDraft?: (targetPath: string) => void;
}

export function GenerationTurnCard({
  turn,
  discussionQuestionDraft,
  onDiscussionQuestionDraftChange,
  onSubmitDiscussion,
  onRequestGenerationPlan,
  onRequestCodeDrafts,
  onWriteCodeDrafts,
  onWriteSingleCodeDraft,
  onOpenNativeDiff,
  onOpenDraft,
}: GenerationTurnCardProps) {
  const plan = turn.generationPlan;
  const drafts = turn.codeDrafts ?? [];
  const discussionMessages = turn.generationDiscussionSession?.messages ?? [];
  return (
    <article className="assistant-turn-card assistant-turn-generation">
      <div className="assistant-turn-head">
        <span className="assistant-turn-kind">{assistantTurnKindLabel(turn.kind)}</span>
        <span className="muted">{turn.context.scopeLabel}</span>
      </div>
      {!plan ? (
        <div className="assistant-card-flow">
          <p className="muted">生成代码会先整理实现建议，确认后再生成代码草稿。</p>
          <button type="button" className="primary-button" onClick={onRequestGenerationPlan}>
            生成实现建议
          </button>
        </div>
      ) : (
        <div className="assistant-card-flow">
          <section className="assistant-evidence-block">
            <div className="preview-head">
              <strong>{plan.summary}</strong>
              <span className="badge">{generationSourceLabel(plan.source)}</span>
            </div>
            {plan.items.map((item) => (
              <div key={item.id} className="assistant-evidence-item">
                <strong>{item.title}</strong>
                <p>{item.description}</p>
                <span className={`risk-pill risk-${item.risk.toLowerCase()}`}>{riskLabel(item.risk)}</span>
                {item.targetPath ? <p className="muted">{item.targetPath}</p> : null}
              </div>
            ))}
          </section>
          <section className="assistant-evidence-block">
            <strong>实现建议追问</strong>
            {discussionMessages.map((message) => (
              <p key={message.messageId} className={message.role === "USER" ? "assistant-question" : "muted"}>
                {message.content}
              </p>
            ))}
            {onDiscussionQuestionDraftChange && onSubmitDiscussion ? (
              <>
                <label className="sr-only" htmlFor={`generation-discussion-${turn.turnId}`}>追问实现建议</label>
                <textarea
                  id={`generation-discussion-${turn.turnId}`}
                  rows={2}
                  value={discussionQuestionDraft}
                  placeholder="追问这份实现建议"
                  onChange={(event) => onDiscussionQuestionDraftChange(event.target.value)}
                />
                <button
                  type="button"
                  className="ghost-button compact"
                  disabled={discussionQuestionDraft.trim().length === 0}
                  onClick={onSubmitDiscussion}
                >
                  追问建议
                </button>
              </>
            ) : null}
          </section>
          {drafts.length === 0 ? (
            <button type="button" className="primary-button" onClick={onRequestCodeDrafts}>
              生成 diff
            </button>
          ) : (
            <section className="assistant-evidence-block">
              <div className="preview-head">
                <strong>代码草稿</strong>
                <button type="button" className="primary-button" onClick={onWriteCodeDrafts}>
                  写入全部
                </button>
              </div>
              {drafts.map((draft) => (
                <div key={draft.id} className="assistant-evidence-item">
                  <strong>{draft.title}</strong>
                  <p className="muted">{draft.targetPath}</p>
                  <div className="panel-actions">
                    {onWriteSingleCodeDraft ? (
                      <button type="button" className="ghost-button compact" onClick={() => onWriteSingleCodeDraft(draft.id)}>
                        写入
                      </button>
                    ) : null}
                    {onOpenNativeDiff ? (
                      <button type="button" className="ghost-button compact" onClick={() => onOpenNativeDiff(draft.id)}>
                        打开 diff
                      </button>
                    ) : null}
                    {onOpenDraft ? (
                      <button type="button" className="ghost-button compact" onClick={() => onOpenDraft(draft.targetPath)}>
                        打开源码
                      </button>
                    ) : null}
                  </div>
                </div>
              ))}
              <button type="button" className="ghost-button compact" onClick={onRequestCodeDrafts}>
                重新生成 diff
              </button>
            </section>
          )}
        </div>
      )}
    </article>
  );
}
