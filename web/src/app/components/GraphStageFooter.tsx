import {
  analysisDisplayModeLabel,
  certaintyLabel,
  draftCompareStatusLabel,
  sourceTagLabel,
} from "../labels";
import { Chip } from "./Chip";
import type {
  AnalysisDisplayMode,
  DraftCompareProjection,
  GraphSourceTag,
  IndexedGraphSummary,
  LinkGraphDocument,
} from "../types";
import type { CodeDiffStatus } from "./hybridDerivations";

interface GraphStageFooterProps {
  analysisDisplayMode: AnalysisDisplayMode;
  activeViewGraph: LinkGraphDocument;
  fullNodeCount: number;
  hasExplanationFocus: boolean;
  draftChangedNodeCount: number;
  draftCompareProjection?: DraftCompareProjection | null;
  indexedSummary?: IndexedGraphSummary | null;
  codeDiffStatus: CodeDiffStatus;
}

export function GraphStageFooter({
  analysisDisplayMode,
  activeViewGraph,
  fullNodeCount,
  hasExplanationFocus,
  draftChangedNodeCount,
  draftCompareProjection = null,
  indexedSummary = null,
  codeDiffStatus,
}: GraphStageFooterProps) {
  const sourceTags = Array.from(new Set(
    activeViewGraph.nodes
      .map((node) => node.sourceTag)
      .filter((tag): tag is GraphSourceTag => tag != null),
  ));
  const compareStatuses = draftCompareProjection == null
    ? []
    : Array.from(new Set([
      ...Object.values(draftCompareProjection.nodeStatuses),
      ...Object.values(draftCompareProjection.edgeStatuses),
    ]));
  const visibleNodeCount = activeViewGraph.nodeCount ?? activeViewGraph.nodes.length;
  const resolvedFullNodeCount = Math.max(fullNodeCount, visibleNodeCount);
  const nodeCountLabel = isIndexedGraphDisplayMode(analysisDisplayMode)
    ? indexedGraphNodeCountLabel(indexedSummary)
    : `节点 ${visibleNodeCount} / ${resolvedFullNodeCount}`;
  const indexedFreshnessLabel = isIndexedGraphDisplayMode(analysisDisplayMode)
    ? indexedGraphFreshnessLabel(indexedSummary)
    : null;
  const indexedCacheLabel = isIndexedGraphDisplayMode(analysisDisplayMode)
    ? indexedGraphCacheLabel(indexedSummary)
    : null;
  const visibilityReasons = indexedSummary?.visibilityReasons ?? [];

  // Detail-tier telemetry: only rendered inside a collapsed <details> so the
  // default footer stays scannable (mode + node count + freshness). These are
  // diagnostics, not primary information.
  const hasDetailPills = sourceTags.length > 0
    || hasExplanationFocus
    || draftChangedNodeCount > 0
    || compareStatuses.length > 0
    || Boolean(indexedCacheLabel)
    || visibilityReasons.length > 0;

  return (
    <footer className="graph-stage-footer" aria-label="图谱图例">
      <Chip variant="status-pill">{analysisDisplayModeLabel(analysisDisplayMode)}</Chip>
      <Chip variant="status-pill">{nodeCountLabel}</Chip>
      {indexedFreshnessLabel ? <Chip variant="status-pill">{indexedFreshnessLabel}</Chip> : null}

      {hasDetailPills ? (
        <details className="graph-stage-footer-details">
          <summary>详情</summary>
          <Chip variant="status-pill">{certaintyLabel("PROVEN")}</Chip>
          <Chip variant="status-pill">{certaintyLabel("RULE_INFERRED")}</Chip>
          <Chip variant="status-pill">{certaintyLabel("LLM_SUGGESTED")}</Chip>
          {sourceTags.map((tag) => (
            <Chip key={tag} variant="status-pill">{sourceTagLabel(tag)}</Chip>
          ))}
          {hasExplanationFocus ? <Chip variant="app-pill">讲解焦点</Chip> : null}
          {draftChangedNodeCount > 0 ? <Chip variant="app-pill">草稿变更 {draftChangedNodeCount}</Chip> : null}
          {compareStatuses.map((status) => (
            <Chip key={status} variant="app-pill">{draftCompareStatusLabel(status)}</Chip>
          ))}
          {indexedCacheLabel ? <Chip variant="status-pill">{indexedCacheLabel}</Chip> : null}
          {visibilityReasons.map((reason) => (
            <Chip key={reason.code} variant="status-pill">
              {indexedVisibilityReasonLabel(reason)}
            </Chip>
          ))}
          <Chip variant="status-pill">代码 diff {codeDiffStatusLabel(codeDiffStatus)}</Chip>
        </details>
      ) : (
        <Chip variant="status-pill">代码 diff {codeDiffStatusLabel(codeDiffStatus)}</Chip>
      )}
    </footer>
  );
}

function isIndexedGraphDisplayMode(mode: AnalysisDisplayMode): boolean {
  return mode === "ARCHITECTURE_GRAPH" || mode === "CLASS_DIAGRAM" || mode === "REVIEW_GRAPH";
}

function indexedGraphNodeCountLabel(summary: IndexedGraphSummary | null): string {
  if (summary == null) {
    return "indexed 统计未返回";
  }
  return [
    `窗口节点 ${summary.visibleNodeCount}`,
    `候选节点 ${summary.candidateNodeCount}`,
    `窗口外 ${summary.hiddenNodeCount}`,
    `窗口来源 ${indexedVisibleLayerSummary(summary)}`,
  ].join(" / ");
}

function indexedVisibleLayerSummary(summary: IndexedGraphSummary): string {
  const counts = summary.visibleLayerCounts;
  if (counts == null) {
    return "未统计";
  }
  const entries: Array<[string, number | undefined]> = [
    ["项目", counts.projectSource],
    ["三方", counts.externalLibrary],
    ["JDK", counts.jdk],
    ["资源", counts.resource],
    ["聚合", counts.aggregate],
  ];
  return entries
    .filter(([, value]) => value != null && value > 0)
    .map(([label, value]) => `${label} ${value}`)
    .join(" · ") || "0";
}

function indexedVisibilityReasonLabel(reason: NonNullable<IndexedGraphSummary["visibilityReasons"]>[number]): string {
  const counts = [
    reason.nodeCount != null && reason.nodeCount > 0 ? `节点 ${reason.nodeCount}` : null,
    reason.edgeCount != null && reason.edgeCount > 0 ? `关系 ${reason.edgeCount}` : null,
  ].filter((entry): entry is string => entry != null);
  return counts.length > 0 ? `${reason.label}（${counts.join(" / ")}）` : reason.label;
}

function indexedGraphFreshnessLabel(summary: IndexedGraphSummary | null): string | null {
  const freshness = summary?.freshness;
  if (freshness == null) {
    return null;
  }
  if (freshness.state === "STALE") {
    const pending = freshness.pendingFileCount > 0
      ? `待刷新 ${freshness.pendingFileCount} 个文件`
      : freshness.dirtyReason
        ? freshness.dirtyReason
        : "等待刷新";
    return `索引 STALE：${pending}`;
  }
  if (freshness.state === "BUILDING") {
    return "索引 BUILDING：正在构建";
  }
  return `索引 ${freshness.state}`;
}

function indexedGraphCacheLabel(summary: IndexedGraphSummary | null): string | null {
  const cacheState = summary?.cacheState?.trim();
  if (!cacheState) {
    return null;
  }
  switch (cacheState) {
    case "REUSED_FULL_INDEX":
    case "HIT":
      return "索引缓存：复用完整索引";
    case "REPROJECT_CACHED":
      return "索引缓存：复用索引重新投影";
    case "CACHE_MISS":
      return "索引缓存：重新构建";
    case "FORCE_REBUILD":
      return "索引缓存：强制重建";
    default:
      return `索引缓存：${cacheState}`;
  }
}

function codeDiffStatusLabel(status: CodeDiffStatus): string {
  switch (status) {
    case "RUNNING":
      return "生成中";
    case "FRESH":
      return "已生成";
    case "STALE":
      return "已过期";
    case "FAILED":
      return "失败";
    case "MISSING":
    default:
      return "未生成";
  }
}
