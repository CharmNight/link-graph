import { useState } from "react";
import type { AsyncRequestState, DiffItem } from "../types";
import { diffStatusLabel } from "../labels";
import type { GraphPatchResult, LinkGraphDocument } from "../types";
import { AsyncRequestBanner, resolveEffectiveRequestState } from "./AsyncRequestBanner";
import { PatchResultSummary } from "./PatchResultSummary";

interface DiffPanelProps {
  items: DiffItem[];
  selectedItemIds: string[];
  onSelectItem: (itemId: string) => void;
  factGraph?: LinkGraphDocument | null;
  designBaseline?: LinkGraphDocument | null;
  result?: GraphPatchResult | null;
  requestState?: AsyncRequestState | null;
  resolveArtifactText?: (artifactId: string) => string | null;
  onRequestArtifact?: (artifactId: string) => void;
  onRequestReview: (question: string) => void;
  onOpenPatchPreview: () => void;
}

export function DiffPanel({
  items,
  selectedItemIds,
  onSelectItem,
  factGraph,
  designBaseline,
  result,
  requestState,
  resolveArtifactText,
  onRequestArtifact,
  onRequestReview,
  onOpenPatchPreview,
}: DiffPanelProps) {
  const [question, setQuestion] = useState("");
  const effectiveRequestState = resolveEffectiveRequestState(requestState);
  const reviewing = effectiveRequestState?.phase === "RUNNING";
  const reviewError =
    effectiveRequestState?.phase === "FAILED" || effectiveRequestState?.phase === "TIMED_OUT"
      ? effectiveRequestState.errorMessage ?? null
      : null;
  const patchSummary = result?.patch
    ? `准备写回 ${result.patch.addedNodeIds.length} 个节点 / ${result.patch.addedEdgeIds.length} 条连线草稿`
    : null;
  const designNodeCount = designBaseline?.nodeCount ?? designBaseline?.nodes.length ?? 0;
  const factNodeCount = factGraph?.nodeCount ?? factGraph?.nodes.length ?? 0;
  const focusedItems = items.filter((item) => selectedItemIds.includes(item.id));

  return (
    <section className="side-panel diff-panel">
      <p className="eyebrow">链路差异</p>
      <h2>代码与 Mermaid</h2>
      <div className="panel-section">
        <div className="preview-head">
          <strong>1. 对比说明</strong>
          <span className="toolbar-chip">设计基线 vs 事实链路</span>
        </div>
        <p className="muted">当前对比对象：设计基线 Mermaid vs 代码事实链路</p>
        <div className="layer-stat-grid">
          <GraphStat title="设计基线" count={designNodeCount} truncated={Boolean(designBaseline?.truncated)} />
          <GraphStat title="代码事实" count={factNodeCount} truncated={Boolean(factGraph?.truncated)} />
        </div>
      </div>

      <div className="panel-section">
        <strong>2. 确定性差异</strong>
        {focusedItems.length > 0 ? (
          <p className="muted">当前焦点：{focusedItems.map((item) => item.title).join("、")}</p>
        ) : (
          <p className="muted">未指定差异焦点，问答将围绕整个差异集合展开。</p>
        )}
        <div className="preview-list">
          {items.map((item) => (
            <article
              key={item.id}
              className={`preview-card${selectedItemIds.includes(item.id) ? " preview-card-active" : ""}`}
            >
              <div className="preview-head">
                <strong>{item.title}</strong>
                <span className={`risk-pill risk-${item.status.toLowerCase()}`}>{diffStatusLabel(item.status)}</span>
              </div>
              <p>{item.description}</p>
              <button type="button" className="ghost-button" onClick={() => onSelectItem(item.id)}>
                定位 {item.id}
              </button>
            </article>
          ))}
        </div>
      </div>

      <div className="panel-section">
        <strong>3. 差异问答</strong>
        <AsyncRequestBanner requestState={effectiveRequestState} />
        {reviewing ? <p className="muted">正在生成差异解释，请稍候。</p> : null}
        {reviewError && !result && !reviewing ? <p className="muted">当前请求失败，请查看上方状态并按需重试。</p> : null}
        <label className="form-stack">
          差异问题
          <textarea
            aria-label="差异问题"
            value={question}
            onChange={(event) => setQuestion(event.target.value)}
            placeholder="请输入希望 AI 解释或修订的差异问题"
            rows={4}
          />
        </label>

        <div className="panel-actions">
          <button
            type="button"
            className="primary-button"
            onClick={() => onRequestReview(question.trim())}
            disabled={question.trim().length === 0 || reviewing}
          >
            {reviewing ? "差异问答中..." : reviewError && !result ? "重试差异问答" : "继续差异问答"}
          </button>
          {result?.patch ? (
            <button type="button" className="ghost-button" onClick={onOpenPatchPreview}>
              查看并写入修订草稿
            </button>
          ) : null}
        </div>
      </div>

      {result ? (
        <>
          {patchSummary ? <p className="muted">{patchSummary}</p> : null}
          <PatchResultSummary
            title="4. 差异解释"
            result={result}
            resolveArtifactText={resolveArtifactText}
            onRequestArtifact={onRequestArtifact}
          />
        </>
      ) : null}
    </section>
  );
}

function GraphStat({ title, count, truncated }: { title: string; count: number; truncated?: boolean }) {
  return (
    <article className="layer-stat-card">
      <strong>{title}</strong>
      <span className="muted">{count} 个节点{truncated ? "，详情已折叠" : ""}</span>
    </article>
  );
}
