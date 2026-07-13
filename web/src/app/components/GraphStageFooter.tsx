import {
  analysisDisplayModeLabel,
  confidenceLabel,
  draftCompareStatusLabel,
  provenanceLabel,
} from "../labels";
import { Chip } from "./Chip";
import type {
  AnalysisDisplayMode,
  DraftCompareProjection,
  GraphProvenance,
  IndexedGraphSummary,
  LinkGraphDocument,
} from "../types";
import type { CodeDiffStatus } from "./hybridDerivations";

/** GraphStageFooter 组件的入参。 */
interface GraphStageFooterProps {
  /** 当前展示模式。 */
  analysisDisplayMode: AnalysisDisplayMode;
  /** 当前视图的实际图（用于抽取节点标签等）。 */
  activeViewGraph: LinkGraphDocument;
  /** 完整节点数（来自上层统计）。 */
  fullNodeCount: number;
  /** 是否处于讲解聚焦状态。 */
  hasExplanationFocus: boolean;
  /** 草稿变更涉及的节点数。 */
  draftChangedNodeCount: number;
  /** 草稿比对投影；可空。 */
  draftCompareProjection?: DraftCompareProjection | null;
  /** 索引图摘要；仅索引模式相关。 */
  indexedSummary?: IndexedGraphSummary | null;
  /** 代码 diff 状态。 */
  codeDiffStatus: CodeDiffStatus;
}

/**
 * 图谱视图底部的图例 / 统计栏。
 *
 * 默认展示：模式标签 + 节点数 + 新鲜度（索引模式下）。
 * 详情折叠区展示更多遥测：来源标签、确定性、差异状态等。
 * 这种"主信息 + 可展开详情"的布局让默认视图保持简洁，需要时再展开看细节。
 */
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
  // 收集视图节点上的所有来源标签（去重）
  const provenances = Array.from(new Set(
    activeViewGraph.nodes
      .map((node) => node.provenance)
      .filter((tag): tag is GraphProvenance => tag != null),
  ));
  // 合并节点与边的草稿比对状态（去重）
  const compareStatuses = draftCompareProjection == null
    ? []
    : Array.from(new Set([
      ...Object.values(draftCompareProjection.nodeStatuses),
      ...Object.values(draftCompareProjection.edgeStatuses),
    ]));
  const visibleNodeCount = activeViewGraph.nodeCount ?? activeViewGraph.nodes.length;
  // 完整节点数取较大值，避免出现"3 / 2"这种倒挂
  const resolvedFullNodeCount = Math.max(fullNodeCount, visibleNodeCount);
  // 节点数标签按是否索引模式分支
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

  // 详情级遥测：放在折叠 <details> 内，让默认页脚保持简洁（模式 + 节点数 + 新鲜度）。
  // 这些是诊断信息而非主信息。
  const hasDetailPills = provenances.length > 0
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
          <Chip variant="status-pill">{confidenceLabel("VERIFIED")}</Chip>
          <Chip variant="status-pill">{confidenceLabel("INFERRED")}</Chip>
          <Chip variant="status-pill">{confidenceLabel("SUGGESTED")}</Chip>
          {provenances.map((tag) => (
            <Chip key={tag} variant="status-pill">{provenanceLabel(tag)}</Chip>
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

/** 判断是否为索引模式（架构图/类图/审查图）。 */
function isIndexedGraphDisplayMode(mode: AnalysisDisplayMode): boolean {
  return mode === "ARCHITECTURE_GRAPH" || mode === "CLASS_DIAGRAM" || mode === "REVIEW_GRAPH";
}

/** 生成索引图的节点数标签。包含窗口节点、候选节点、窗口外节点与各层来源分布。 */
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

/** 把 visibleLayerCounts 转为可读的"项目 N · 三方 M · JDK K ..."摘要。 */
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

/** 把单条 visibilityReason 转为带计数的可读字符串。 */
function indexedVisibilityReasonLabel(reason: NonNullable<IndexedGraphSummary["visibilityReasons"]>[number]): string {
  const counts = [
    reason.nodeCount != null && reason.nodeCount > 0 ? `节点 ${reason.nodeCount}` : null,
    reason.edgeCount != null && reason.edgeCount > 0 ? `关系 ${reason.edgeCount}` : null,
  ].filter((entry): entry is string => entry != null);
  return counts.length > 0 ? `${reason.label}（${counts.join(" / ")}）` : reason.label;
}

/** 把索引新鲜度转为面向用户的标签。 */
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

/** 把索引缓存状态代码转为可读说明。 */
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

/** 把代码 diff 状态转为可读文案。 */
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
