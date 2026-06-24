import { draftCompareStatusLabel } from "../labels";
import type { DraftCompareProjection, DraftCompareStatus } from "../types";

/**
 * 草稿流变化摘要：把投影结果压缩为面向 UI 的统计信息。
 */
export interface DraftFlowChangeSummary {
  /** 按状态（修改/新增/删除）统计的节点数量。 */
  statusCounts: Partial<Record<DraftCompareStatus, number>>;
  /** 可见节点数。 */
  visibleNodeCount: number;
  /** 整体范围节点数（包含隐藏）。 */
  scopeNodeCount: number;
  /** 可见边数。 */
  visibleEdgeCount: number;
  /** 视图外（被隐藏）的节点数。 */
  hiddenNodeCount: number;
  /** 视图外的边数。 */
  hiddenEdgeCount: number;
}

/** 单个变化提示条（pill）的展示数据。 */
export interface DraftFlowChangePill {
  /** 展示文案，例如 "草稿修改 3"。 */
  label: string;
  /** 关联的草稿状态；非状态条（例如命中连线）可为空。 */
  status?: DraftCompareStatus;
  /** 色调：info 表示中性提示，warning 表示需要注意。 */
  tone?: "info" | "warning";
}

/** 状态在 UI 上的展示顺序：修改在前，新增次之，删除最后。 */
const STATUS_ORDER: DraftCompareStatus[] = ["MODIFIED", "ADDED", "REMOVED"];

/**
 * 把投影结果构造为变化摘要对象。
 * 入参为空（投影尚未生成）时返回 null，调用方据此决定是否渲染摘要区。
 */
export function buildDraftFlowChangeSummary(
  projection?: DraftCompareProjection | null,
): DraftFlowChangeSummary | null {
  if (!projection) {
    return null;
  }
  // 按状态聚合节点数
  const statusCounts: Partial<Record<DraftCompareStatus, number>> = {};
  for (const status of Object.values(projection.nodeStatuses)) {
    statusCounts[status] = (statusCounts[status] ?? 0) + 1;
  }
  return {
    statusCounts,
    visibleNodeCount: projection.summary.visibleNodeCount,
    scopeNodeCount: projection.summary.scopeNodeCount,
    visibleEdgeCount: projection.summary.visibleEdgeCount,
    hiddenNodeCount: projection.summary.hiddenNodeCount,
    hiddenEdgeCount: projection.summary.hiddenEdgeCount,
  };
}

/**
 * 把摘要转换为 UI 上展示的提示条列表。
 * 顺序：先按 STATUS_ORDER 列出状态条（数量为 0 的跳过），
 * 再附加"命中连线"和"视图外节点/边"等辅助提示。
 */
export function draftFlowChangePills(summary?: DraftFlowChangeSummary | null): DraftFlowChangePill[] {
  if (!summary) {
    return [];
  }
  // 状态条：只展示数量大于 0 的状态
  const statusPills = STATUS_ORDER.flatMap((status) => {
    const count = summary.statusCounts[status] ?? 0;
    return count > 0 ? [{ label: `${draftCompareStatusLabel(status)} ${count}`, status }] : [];
  });
  return [
    ...statusPills,
    // 命中连线：中性提示，让用户感知边的存在
    ...(summary.visibleEdgeCount > 0 ? [{ label: `命中连线 ${summary.visibleEdgeCount}`, tone: "info" as const }] : []),
    // 视图外节点/边：警告，提示用户可能漏看了内容
    ...(summary.hiddenNodeCount > 0 ? [{ label: `视图外节点 ${summary.hiddenNodeCount}`, tone: "warning" as const }] : []),
    ...(summary.hiddenEdgeCount > 0 ? [{ label: `视图外连线 ${summary.hiddenEdgeCount}`, tone: "warning" as const }] : []),
  ];
}

/**
 * 给单个提示条生成 className。
 * 状态条使用 `is-draft-compare-{status}` 类，让 CSS 按状态上色；
 * 非状态条按 tone 上色，默认 info。
 */
export function draftFlowChangePillClassName(pill: DraftFlowChangePill): string {
  if (pill.status) {
    return `canvas-reading-flag is-draft-compare-${pill.status.toLowerCase()}`;
  }
  return `canvas-reading-flag is-${pill.tone ?? "info"}`;
}
