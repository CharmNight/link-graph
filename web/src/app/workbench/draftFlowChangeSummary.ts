import { draftCompareStatusLabel } from "../labels";
import type { DraftCompareProjection, DraftCompareStatus } from "../types";

export interface DraftFlowChangeSummary {
  statusCounts: Partial<Record<DraftCompareStatus, number>>;
  visibleNodeCount: number;
  scopeNodeCount: number;
  visibleEdgeCount: number;
  hiddenNodeCount: number;
  hiddenEdgeCount: number;
}

export interface DraftFlowChangePill {
  label: string;
  status?: DraftCompareStatus;
  tone?: "info" | "warning";
}

const STATUS_ORDER: DraftCompareStatus[] = ["MODIFIED", "ADDED", "REMOVED"];

export function buildDraftFlowChangeSummary(
  projection?: DraftCompareProjection | null,
): DraftFlowChangeSummary | null {
  if (!projection) {
    return null;
  }
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

export function draftFlowChangePills(summary?: DraftFlowChangeSummary | null): DraftFlowChangePill[] {
  if (!summary) {
    return [];
  }
  const statusPills = STATUS_ORDER.flatMap((status) => {
    const count = summary.statusCounts[status] ?? 0;
    return count > 0 ? [{ label: `${draftCompareStatusLabel(status)} ${count}`, status }] : [];
  });
  return [
    ...statusPills,
    ...(summary.visibleEdgeCount > 0 ? [{ label: `命中连线 ${summary.visibleEdgeCount}`, tone: "info" as const }] : []),
    ...(summary.hiddenNodeCount > 0 ? [{ label: `视图外节点 ${summary.hiddenNodeCount}`, tone: "warning" as const }] : []),
    ...(summary.hiddenEdgeCount > 0 ? [{ label: `视图外连线 ${summary.hiddenEdgeCount}`, tone: "warning" as const }] : []),
  ];
}

export function draftFlowChangePillClassName(pill: DraftFlowChangePill): string {
  if (pill.status) {
    return `canvas-reading-flag is-draft-compare-${pill.status.toLowerCase()}`;
  }
  return `canvas-reading-flag is-${pill.tone ?? "info"}`;
}
