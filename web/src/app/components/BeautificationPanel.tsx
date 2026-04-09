import { beautificationBoundaryDescription, llmResultSourceLabel } from "../labels";
import type { AsyncRequestState, GraphBeautificationResult } from "../types";
import { AsyncRequestBanner, resolveEffectiveRequestState } from "./AsyncRequestBanner";
import { ArtifactTextDisclosure } from "./ArtifactTextDisclosure";
import { EvidenceFindingsSection } from "./EvidenceFindingsSection";

interface BeautificationPanelProps {
  result?: GraphBeautificationResult | null;
  requestState?: AsyncRequestState | null;
  isRequesting?: boolean;
  requestError?: string | null;
  resolveArtifactText?: (artifactId: string) => string | null;
  onRequestArtifact?: (artifactId: string) => void;
  onRequestBeautification?: () => void;
}

export function BeautificationPanel({
  result,
  requestState,
  isRequesting = false,
  requestError,
  resolveArtifactText,
  onRequestArtifact,
  onRequestBeautification,
}: BeautificationPanelProps) {
  const effectiveRequestState = resolveEffectiveRequestState(requestState, isRequesting, requestError);
  const beautifying = effectiveRequestState?.phase === "RUNNING";
  const beautificationError =
    effectiveRequestState?.phase === "FAILED" || effectiveRequestState?.phase === "TIMED_OUT"
      ? effectiveRequestState.errorMessage ?? null
      : null;

  return (
    <section className="side-panel beautification-panel">
      <p className="eyebrow">讲解结果</p>
      <h2>链路讲解</h2>

      {!result ? (
        <div className="preview-list">
          <article className="preview-card">
            <AsyncRequestBanner requestState={effectiveRequestState} />
            <p className="muted">
              {beautifying
                ? "正在生成链路讲解，请稍候。"
                : beautificationError ? "当前请求失败，可查看上方状态并按需重试。" : "当前只是打开了“讲解”页签，还没有真正生成讲解。"}
            </p>
            <p className="muted">讲解会优先读取当前画布与当前方法视图，不会继续沿用上一轮结果。</p>
            {!beautifying && onRequestBeautification ? (
              <div className="panel-actions">
                <button type="button" className="primary-button" onClick={onRequestBeautification}>
                  {beautificationError ? "重试生成讲解" : "开始生成讲解"}
                </button>
              </div>
            ) : null}
          </article>
        </div>
      ) : (
        <div className="preview-list">
          <article className="preview-card">
            <div className="preview-head">
              <strong>{result.summaryTitle}</strong>
              <span className="risk-pill risk-low">{llmResultSourceLabel(result.source)}</span>
            </div>
            <p className="muted">来源 {llmResultSourceLabel(result.source)}</p>
            <p>{result.summary}</p>
            <div className="answer-section">
              <strong>真实性边界</strong>
              <p className="muted">{beautificationBoundaryDescription(result.source)}</p>
            </div>
            <EvidenceFindingsSection findings={result.findings ?? []} />
            {result.warnings.length > 0 ? (
              <div className="warning-list">
                {result.warnings.map((warning) => (
                  <p key={warning} className="muted">
                    {warning}
                  </p>
                ))}
              </div>
            ) : null}
            <ArtifactTextDisclosure
              buttonLabel="查看调试用提示词"
              expandedLabel="隐藏调试用提示词"
              artifactId={result.promptPreviewArtifactId ?? null}
              text={result.promptPreview ?? (result.promptPreviewArtifactId ? resolveArtifactText?.(result.promptPreviewArtifactId) : null)}
              onRequestArtifact={onRequestArtifact}
            />
          </article>

          {result.sections.map((section) => (
            <article key={section.id} className="preview-card">
              <strong>{section.title}</strong>
              <p>{section.content}</p>
            </article>
          ))}
        </div>
      )}
    </section>
  );
}
