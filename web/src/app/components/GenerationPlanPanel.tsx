import type { AsyncRequestState, GenerationPlan } from "../types";
import { AsyncRequestBanner, resolveEffectiveRequestState } from "./AsyncRequestBanner";
import { generationSourceLabel, riskLabel } from "../labels";
import { ArtifactTextDisclosure } from "./ArtifactTextDisclosure";

interface GenerationPlanPanelProps {
  plan?: GenerationPlan | null;
  requestState?: AsyncRequestState | null;
  isRequesting?: boolean;
  requestError?: string | null;
  hasConfirmedDraftChanges?: boolean;
  resolveArtifactText?: (artifactId: string) => string | null;
  onRequestArtifact?: (artifactId: string) => void;
  onOpenDraftWorkbench: () => void;
  onRequestGeneratePlan: () => void;
}

export function GenerationPlanPanel({
  plan,
  requestState,
  isRequesting = false,
  requestError,
  hasConfirmedDraftChanges = true,
  resolveArtifactText,
  onRequestArtifact,
  onOpenDraftWorkbench,
  onRequestGeneratePlan,
}: GenerationPlanPanelProps) {
  const effectiveRequestState = resolveEffectiveRequestState(requestState, isRequesting, requestError);
  const planning = effectiveRequestState?.phase === "RUNNING";
  const planningError = effectiveRequestState?.phase === "FAILED" || effectiveRequestState?.phase === "TIMED_OUT"
    ? effectiveRequestState.errorMessage ?? null
    : null;

  return (
    <section className="side-panel generation-plan-panel">
      <p className="eyebrow">生成计划</p>
      <h2>Mermaid 到代码</h2>

      <div className="side-panel-scroll-body m-scrollbar">
        {!plan ? (
          <article className="preview-card">
            <AsyncRequestBanner requestState={effectiveRequestState} />
            <p className="muted">
              {planning
                ? "正在生成计划，请稍候。"
                : planningError
                  ? "当前请求失败，可查看上方状态并按需重试。"
                  : hasConfirmedDraftChanges
                    ? "尚未生成计划。点击下方按钮创建实现大纲。"
                    : "请先确认至少一条草稿变更，再生成计划。"}
            </p>
            <p className="muted">
              {hasConfirmedDraftChanges
                ? "本次计划会基于当前工作图，而不是历史事实快照。"
                : "先在草稿层确认候选变更，让实现计划围绕已确认的修改目标展开。"}
            </p>
            {!planning ? (
              <div className="panel-actions">
                {hasConfirmedDraftChanges ? (
                  <button type="button" className="primary-button" onClick={onRequestGeneratePlan}>
                    {planningError ? "重试生成计划" : "生成计划"}
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
              {plan.warnings.length > 0 ? (
                <div className="warning-list">
                  {plan.warnings.map((warning) => (
                    <p key={warning} className="muted">
                      {warning}
                    </p>
                  ))}
                </div>
              ) : null}
              <ArtifactTextDisclosure
                buttonLabel="查看调试用提示词"
                expandedLabel="隐藏调试用提示词"
                artifactId={plan.promptPreviewArtifactId ?? null}
                text={plan.promptPreview ?? (plan.promptPreviewArtifactId ? resolveArtifactText?.(plan.promptPreviewArtifactId) : null)}
                onRequestArtifact={onRequestArtifact}
              />
            </article>

            {plan.items.map((item) => (
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
