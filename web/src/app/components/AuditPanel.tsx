import { useEffect, useState } from "react";
import { sourceTagLabel } from "../labels";
import type { AsyncRequestState, GraphPatchResult, LinkGraphDocument } from "../types";
import { AsyncRequestBanner, resolveEffectiveRequestState } from "./AsyncRequestBanner";
import { PatchResultSummary } from "./PatchResultSummary";

interface AuditPanelProps {
  selectedNodeIds: string[];
  selectedNodeTitle?: string | null;
  factGraph?: LinkGraphDocument | null;
  draftGraph?: LinkGraphDocument | null;
  designBaseline?: LinkGraphDocument | null;
  result?: GraphPatchResult | null;
  requestState?: AsyncRequestState | null;
  initialQuestion?: string;
  resolveArtifactText?: (artifactId: string) => string | null;
  onRequestArtifact?: (artifactId: string) => void;
  onRequestAudit: (question: string) => void;
  onOpenPatchPreview: () => void;
}

interface GraphLayerSummary {
  nodeCount: number;
  edgeCount: number;
  truncated: boolean;
}

function resolveGraphLayerSummary(
  document: LinkGraphDocument | null | undefined,
  selectedNodeIds: string[],
): GraphLayerSummary | null {
  if (!document) {
    return null;
  }
  if (selectedNodeIds.length === 0) {
    return {
      nodeCount: document.nodeCount ?? document.nodes.length,
      edgeCount: document.edgeCount ?? document.edges.length,
      truncated: Boolean(document.truncated),
    };
  }

  const selectedNodeIdSet = new Set(selectedNodeIds);
  const scopeNodeIds = new Set<string>();
  document.nodes.forEach((node) => {
    if (selectedNodeIdSet.has(node.id)) {
      scopeNodeIds.add(node.id);
    }
  });
  document.edges.forEach((edge) => {
    if (selectedNodeIdSet.has(edge.source) || selectedNodeIdSet.has(edge.target)) {
      scopeNodeIds.add(edge.source);
      scopeNodeIds.add(edge.target);
    }
  });
  if (scopeNodeIds.size === 0) {
    return {
      nodeCount: 0,
      edgeCount: 0,
      truncated: false,
    };
  }

  return {
    nodeCount: document.nodes.filter((node) => scopeNodeIds.has(node.id)).length,
    edgeCount: document.edges.filter(
      (edge) => scopeNodeIds.has(edge.source) && scopeNodeIds.has(edge.target),
    ).length,
    truncated: false,
  };
}

function GraphLayerStat({
  title,
  summary,
  fallback,
}: {
  title: string;
  summary?: GraphLayerSummary | null;
  fallback: string;
}) {
  const suffix = summary?.truncated ? "，详情已按统计模式折叠" : "";

  return (
    <article className="layer-stat-card">
      <strong>{title}</strong>
      <span className="muted">
        {summary ? `${summary.nodeCount} 节点 / ${summary.edgeCount} 连线${suffix}` : fallback}
      </span>
    </article>
  );
}

export function AuditPanel({
  selectedNodeIds,
  selectedNodeTitle,
  factGraph,
  draftGraph,
  designBaseline,
  result,
  requestState,
  initialQuestion = "",
  resolveArtifactText,
  onRequestArtifact,
  onRequestAudit,
  onOpenPatchPreview,
}: AuditPanelProps) {
  const [question, setQuestion] = useState(initialQuestion);
  useEffect(() => {
    setQuestion(initialQuestion);
  }, [initialQuestion]);
  const factGraphSummary = resolveGraphLayerSummary(factGraph, selectedNodeIds);
  const draftGraphSummary = resolveGraphLayerSummary(draftGraph, selectedNodeIds);
  const designBaselineSummary = resolveGraphLayerSummary(designBaseline, selectedNodeIds);

  const scopeText = selectedNodeIds.length > 0 ? `当前范围：${selectedNodeTitle ?? selectedNodeIds.join(", ")}` : "当前范围：整个链路";
  const patchSummary = result?.patch
    ? `准备写回 ${result.patch.addedNodeIds.length} 个节点 / ${result.patch.addedEdgeIds.length} 条连线草稿`
    : null;
  const effectiveRequestState = resolveEffectiveRequestState(requestState);
  const isRequesting = effectiveRequestState?.phase === "RUNNING";
  const requestError =
    effectiveRequestState?.phase === "FAILED" || effectiveRequestState?.phase === "TIMED_OUT"
      ? effectiveRequestState.errorMessage ?? null
    : null;

  return (
    <section className="side-panel audit-panel">
      <p className="eyebrow">审计问答</p>
      <h2>链路审计</h2>
      <div className="panel-section">
        <div className="preview-head">
          <strong>1. 审计范围</strong>
          <span className="toolbar-chip">{selectedNodeIds.length > 0 ? "局部范围" : "整图范围"}</span>
        </div>
        <p className="muted">{scopeText}</p>
        <div className="layer-stat-grid">
          <GraphLayerStat title={sourceTagLabel("FACT")} summary={factGraphSummary} fallback="尚未生成事实图" />
          <GraphLayerStat title="草稿层" summary={draftGraphSummary} fallback="当前还没有草稿图" />
          <GraphLayerStat title={sourceTagLabel("DESIGN_BASELINE")} summary={designBaselineSummary} fallback="尚未导入设计基线" />
        </div>
      </div>

      <div className="panel-section">
        <strong>2. 审计问题</strong>
        <AsyncRequestBanner requestState={effectiveRequestState} />
        {requestError && !result ? <p className="muted">{requestError}</p> : null}
        <label className="form-stack">
          审计问题
          <textarea
            aria-label="审计问题"
            value={question}
            onChange={(event) => setQuestion(event.target.value)}
            placeholder={
              selectedNodeIds.length > 0
                ? `例如：请审计 ${selectedNodeTitle ?? selectedNodeIds.join(", ")} 是否遗漏关键业务链路`
                : "例如：请审计整个链路图，指出可能遗漏的业务链路或隐式约束"
            }
            rows={4}
          />
        </label>

        <div className="panel-actions">
          <button
            type="button"
            className="primary-button"
            onClick={() => onRequestAudit(question.trim())}
            disabled={question.trim().length === 0 || isRequesting}
          >
            {isRequesting ? "审计中..." : requestError && !result ? "重试审计" : "开始审计"}
          </button>
          {result?.patch ? (
            <button type="button" className="ghost-button" onClick={onOpenPatchPreview}>
              查看并写入审计草稿
            </button>
          ) : null}
        </div>
      </div>

      {result ? (
        <>
          {patchSummary ? <p className="muted">{patchSummary}</p> : null}
          <PatchResultSummary
            title="3. 审计回答"
            result={result}
            resolveArtifactText={resolveArtifactText}
            onRequestArtifact={onRequestArtifact}
          />
        </>
      ) : (
        <p className="muted">这里会展示审计回答，并把新增/修订节点先生成到草稿预览，不会直接改动事实图。</p>
      )}
    </section>
  );
}
