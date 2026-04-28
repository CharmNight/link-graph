import type { DraftValidationState } from "../types";

interface DraftValidationPanelProps {
  validationState?: DraftValidationState | null;
  onOpenAuditWorkbench?: () => void;
}

export function DraftValidationPanel({
  validationState,
  onOpenAuditWorkbench,
}: DraftValidationPanelProps) {
  if (!validationState) {
    return (
      <article className="preview-card">
        <strong>草稿验证</strong>
        <p className="muted">草稿验证状态正在同步，当前先以草稿内容作为后续生成的唯一输入。</p>
      </article>
    );
  }

  const needsReview = validationState.status === "REVIEW_REQUIRED";
  const canContinueAudit = needsReview && typeof onOpenAuditWorkbench === "function";

  return (
    <article className="preview-card">
      <div className="preview-head">
        <strong>草稿验证</strong>
        <span className={`risk-pill ${needsReview ? "risk-high" : "risk-low"}`}>
          {needsReview ? "待处理" : validationState.status === "EMPTY" ? "待补充" : "已就绪"}
        </span>
      </div>
      <p>{validationState.message}</p>
      {validationState.detailMessage ? <p className="muted">{validationState.detailMessage}</p> : null}

      {validationState.unresolvedThreads.length > 0 ? (
        <div className="warning-list">
          {validationState.unresolvedThreads.map((thread) => (
            <div key={thread.threadId}>
              <strong>{thread.title}</strong>
              {thread.summary ? <p className="muted">{thread.summary}</p> : null}
              {thread.recommendedQuestion ? <p className="muted">建议追问：{thread.recommendedQuestion}</p> : null}
            </div>
          ))}
        </div>
      ) : null}

      {canContinueAudit ? (
        <div className="panel-actions">
          <button type="button" className="primary-button" onClick={onOpenAuditWorkbench}>
            继续风险取证
          </button>
        </div>
      ) : null}
    </article>
  );
}
