import type { AsyncRequestState, GenerationPlan, StageEligibilityDecision } from "../types";
import { AsyncRequestBanner, resolveEffectiveRequestState } from "./AsyncRequestBanner";
import { generationSourceLabel, normalizeWorkbenchWording, riskLabel } from "../labels";
import { ArtifactTextDisclosure } from "./ArtifactTextDisclosure";

interface GenerationPlanPanelProps {
  plan?: GenerationPlan | null;
  requestState?: AsyncRequestState | null;
  isRequesting?: boolean;
  requestError?: string | null;
  eligibilityDecision?: StageEligibilityDecision | null;
  draftVersion?: number | null;
  generationPlanDraftVersion?: number | null;
  resolveArtifactText?: (artifactId: string) => string | null;
  onRequestArtifact?: (artifactId: string) => void;
  onOpenDraftWorkbench: () => void;
  onOpenAuditWorkbench?: () => void;
  onRequestGeneratePlan: () => void;
}

export function GenerationPlanPanel({
  plan,
  requestState,
  isRequesting = false,
  requestError,
  eligibilityDecision,
  draftVersion = null,
  generationPlanDraftVersion = null,
  resolveArtifactText,
  onRequestArtifact,
  onOpenDraftWorkbench,
  onOpenAuditWorkbench,
  onRequestGeneratePlan,
}: GenerationPlanPanelProps) {
  const effectiveRequestState = resolveEffectiveRequestState(requestState, isRequesting, requestError);
  const planning = effectiveRequestState?.phase === "RUNNING";
  const planningError = effectiveRequestState?.phase === "FAILED" || effectiveRequestState?.phase === "TIMED_OUT"
    ? effectiveRequestState.errorMessage ?? null
    : null;
  const awaitingEligibilityDecision = eligibilityDecision == null;
  const canRequestPlan = eligibilityDecision?.allowed === true;
  const blockedByRisk = Boolean(eligibilityDecision && !eligibilityDecision.allowed && eligibilityDecision.blockingThreadIds.length > 0);
  const stalePlan = plan != null
    && draftVersion != null
    && generationPlanDraftVersion != null
    && generationPlanDraftVersion < draftVersion;
  const staleMessage = stalePlan
    ? `当前实现建议基于草稿 v${generationPlanDraftVersion} 生成，当前草稿已更新到 v${draftVersion}，请先刷新实现建议。`
    : null;
  const primaryMessage = normalizeWorkbenchWording(planning
    ? "正在生成实现建议，请稍候。"
    : planningError
      ? "当前请求失败，可查看上方状态并按需重试。"
      : eligibilityDecision?.message
        ?? "实现建议阶段准入状态尚未就绪。");
  const secondaryMessage = normalizeWorkbenchWording(eligibilityDecision?.detailMessage
    || (awaitingEligibilityDecision
      ? "当前还没有收到实现建议阶段的统一准入决策，请先回到问答链路等待状态同步完成。"
      : "本次实现建议会基于当前工作图，而不是历史事实快照。"));

  return (
    <section className="side-panel generation-plan-panel">
      <p className="eyebrow">实现建议</p>
      <h2>先整理实现路径</h2>
      {generationPlanDraftVersion != null ? <p className="muted">基于草稿 v{generationPlanDraftVersion} 生成</p> : null}

      <div className="side-panel-scroll-body m-scrollbar">
        {staleMessage ? (
          <article className="preview-card code-diff-stale-banner">
            <p className="muted">{staleMessage}</p>
          </article>
        ) : null}

        {!plan ? (
          <article className="preview-card">
            <AsyncRequestBanner requestState={effectiveRequestState} />
            <p className="muted">{primaryMessage}</p>
            <p className="muted">{secondaryMessage}</p>
            {!planning ? (
              <div className="panel-actions">
                {canRequestPlan ? (
                  <button type="button" className="primary-button" onClick={onRequestGeneratePlan}>
                    {planningError ? "重试生成实现建议" : "生成实现建议"}
                  </button>
                ) : awaitingEligibilityDecision || blockedByRisk ? (
                  <button
                    type="button"
                    className="primary-button"
                    onClick={onOpenAuditWorkbench}
                    disabled={!onOpenAuditWorkbench}
                  >
                    前往问答风险
                  </button>
                ) : (
                  <button type="button" className="primary-button" onClick={onOpenDraftWorkbench}>
                    前往草稿层
                  </button>
                )}
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
              <ArtifactTextDisclosure
                buttonLabel="查看生成提示词"
                expandedLabel="隐藏生成提示词"
                artifactId={plan.promptPreviewArtifactId ?? null}
                text={plan.promptPreview ?? (plan.promptPreviewArtifactId ? resolveArtifactText?.(plan.promptPreviewArtifactId) : null)}
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
          </div>
        )}
      </div>
    </section>
  );
}
