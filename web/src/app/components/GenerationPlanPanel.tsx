import type {
  AsyncRequestState,
  GenerationPlan,
  GenerationPlanDiscussionSession,
} from "../types";
import { AsyncRequestBanner, resolveEffectiveRequestState } from "./AsyncRequestBanner";
import { generationSourceLabel, riskLabel } from "../labels";
import { RequestPromptDisclosure } from "./RequestPromptDisclosure";

interface GenerationPlanPanelProps {
  plan?: GenerationPlan | null;
  requestState?: AsyncRequestState | null;
  draftVersion?: number | null;
  generationPlanDraftVersion?: number | null;
  resolveArtifactText?: (artifactId: string) => string | null;
  onRequestArtifact?: (artifactId: string) => void;
  onRequestGeneratePlan: () => void;
  discussionQuestionDraft?: string;
  discussionSession?: GenerationPlanDiscussionSession | null;
  discussionRequestState?: AsyncRequestState | null;
  onDiscussionQuestionDraftChange?: (value: string) => void;
  onSubmitDiscussion?: () => void;
}

export function GenerationPlanPanel({
  plan,
  requestState,
  draftVersion = null,
  generationPlanDraftVersion = null,
  resolveArtifactText,
  onRequestArtifact,
  onRequestGeneratePlan,
  discussionQuestionDraft = "",
  discussionSession = null,
  discussionRequestState,
  onDiscussionQuestionDraftChange,
  onSubmitDiscussion,
}: GenerationPlanPanelProps) {
  const effectiveRequestState = resolveEffectiveRequestState(requestState);
  const planning = effectiveRequestState?.phase === "RUNNING";
  const planningError = effectiveRequestState?.phase === "FAILED" || effectiveRequestState?.phase === "TIMED_OUT"
    ? effectiveRequestState.errorMessage ?? null
    : null;
  const effectiveDiscussionRequestState = resolveEffectiveRequestState(discussionRequestState);
  const discussing = effectiveDiscussionRequestState?.phase === "RUNNING";
  const stalePlan = plan != null
    && draftVersion != null
    && generationPlanDraftVersion != null
    && generationPlanDraftVersion < draftVersion;
  const staleMessage = stalePlan
    ? `当前实现建议基于草稿 v${generationPlanDraftVersion} 生成，当前草稿已更新到 v${draftVersion}，请先刷新实现建议。`
    : null;
  const primaryMessage = planning
    ? "正在生成实现建议，请稍候。"
    : planningError
      ? "当前请求失败，请查看上方状态并按需重试。"
      : "实现建议会基于当前草稿快照生成。";
  const discussionMessages = discussionSession?.messages ?? [];
  const canSubmitDiscussion = Boolean(
    plan
      && !discussing
      && onSubmitDiscussion
      && discussionQuestionDraft.trim().length > 0,
  );

  return (
    <section className="side-panel generation-plan-panel">
      <p className="eyebrow">实现建议</p>
      <h2>先整理实现路径</h2>
      {generationPlanDraftVersion != null ? <p className="muted">基于草稿 v{generationPlanDraftVersion} 生成</p> : null}

      <div className="generation-plan-flow-body">
        {staleMessage ? (
          <article className="preview-card code-diff-stale-banner">
            <p className="muted">{staleMessage}</p>
          </article>
        ) : null}

        {!plan ? (
          <article className="preview-card">
            <AsyncRequestBanner requestState={effectiveRequestState} />
            <p className="muted">{primaryMessage}</p>
            {planning ? <p className="muted">实现建议会基于当前草稿快照生成。</p> : null}
            {!planning ? (
              <div className="panel-actions">
                <button type="button" className="primary-button" onClick={onRequestGeneratePlan}>
                  {planningError ? "重试生成实现建议" : "生成实现建议"}
                </button>
              </div>
            ) : null}
          </article>
        ) : (
          <div className="preview-list">
            <article className="preview-card">
              <div className="preview-head">
                <strong>{plan.summary}</strong>
                <span className="risk-pill risk-low">{generationSourceLabel(plan.source)}</span>
              </div>
              {(plan.warnings ?? []).length > 0 ? (
                <div className="warning-list">
                  {(plan.warnings ?? []).map((warning) => (
                    <p key={warning} className="muted">
                      {warning}
                    </p>
                  ))}
                </div>
              ) : null}
              <RequestPromptDisclosure
                promptPreview={plan.promptPreview ?? null}
                promptPreviewArtifactId={plan.promptPreviewArtifactId ?? null}
                promptPreviewAvailable={Boolean(plan.promptPreview?.trim() || plan.promptPreviewArtifactId)}
                resolveArtifactText={resolveArtifactText}
                onRequestArtifact={onRequestArtifact}
              />
            </article>

            {(plan.items ?? []).map((item) => (
              <article key={item.id} className="preview-card">
                <div className="preview-head">
                  <strong>任务：{item.title}</strong>
                  <span className={`risk-pill risk-${item.risk.toLowerCase()}`}>{riskLabel(item.risk)}</span>
                </div>
                <p>{item.description}</p>
                {item.targetPath ? <p className="muted">目标：{item.targetPath}</p> : null}
              </article>
            ))}

            <article className="preview-card">
              <div className="preview-head">
                <strong>继续追问这份实现建议</strong>
                {discussionSession?.focusItemId ? <span className="badge">聚焦 {discussionSession.focusItemId}</span> : null}
              </div>
              <p className="muted">如果你对某条建议有异议或需要展开理由，直接在这里追问，不再跳回问答页。</p>
              <AsyncRequestBanner requestState={effectiveDiscussionRequestState} />
              <RequestPromptDisclosure
                promptPreview={discussionSession?.promptPreview ?? null}
                promptPreviewArtifactId={discussionSession?.promptPreviewArtifactId ?? null}
                promptPreviewAvailable={Boolean(
                  discussionSession?.promptPreview?.trim() || discussionSession?.promptPreviewArtifactId,
                )}
                resolveArtifactText={resolveArtifactText}
                onRequestArtifact={onRequestArtifact}
              />
              {discussionMessages.length > 0 ? (
                <div className="warning-list">
                  {discussionMessages.map((message) => (
                    <div key={message.messageId}>
                      <strong>{message.role === "USER" ? "你" : "建议问答"}</strong>
                      <p className="muted">{message.content}</p>
                    </div>
                  ))}
                </div>
              ) : null}
              <label className="sr-only" htmlFor="generation-plan-discussion-question">
                追问实现建议
              </label>
              <textarea
                id="generation-plan-discussion-question"
                className="prompt-preview"
                rows={3}
                value={discussionQuestionDraft}
                placeholder="例如：为什么建议改这里？有没有更小的改法？"
                onChange={(event) => onDiscussionQuestionDraftChange?.(event.target.value)}
              />
              <div className="panel-actions">
                <button
                  type="button"
                  className="primary-button"
                  onClick={() => onSubmitDiscussion?.()}
                  disabled={!canSubmitDiscussion}
                >
                  追问建议
                </button>
              </div>
            </article>
          </div>
        )}
      </div>
    </section>
  );
}
