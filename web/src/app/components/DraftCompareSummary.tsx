import { draftCompareStatusLabel } from "../labels";
import type { DraftCompareProjection } from "../types";

interface DraftCompareSummaryProps {
  projection: DraftCompareProjection;
}

export function DraftCompareSummary({ projection }: DraftCompareSummaryProps) {
  const statusSet = new Set([
    ...Object.values(projection.nodeStatuses),
    ...Object.values(projection.edgeStatuses),
  ]);
  const hasRemovedElements = statusSet.has("REMOVED");
  const summaryText = hasRemovedElements
    ? "当前对比会把修改后的真实节点与被移除的幽灵节点一起投到画布上。"
    : "当前对比会直接高亮修改后的真实节点和连线。";

  return (
    <section className="canvas-reading-compare" aria-label="草稿对比摘要">
      <div className="canvas-reading-compare-head">
        <strong>{projection.entryTitle}</strong>
        <span className="muted">{summaryText}</span>
      </div>
      <div className="canvas-reading-flags">
        {Array.from(statusSet).map((status) => (
          <span key={status} className={`canvas-reading-flag is-draft-compare-${status.toLowerCase()}`}>
            {draftCompareStatusLabel(status)}
          </span>
        ))}
        <span className="canvas-reading-flag is-info">命中节点 {projection.summary.visibleNodeCount}</span>
        {projection.summary.visibleEdgeCount > 0 ? (
          <span className="canvas-reading-flag is-info">命中连线 {projection.summary.visibleEdgeCount}</span>
        ) : null}
        {projection.summary.hiddenNodeCount > 0 ? (
          <span className="canvas-reading-flag is-warning">视图外节点 {projection.summary.hiddenNodeCount}</span>
        ) : null}
        {projection.summary.hiddenEdgeCount > 0 ? (
          <span className="canvas-reading-flag is-warning">视图外连线 {projection.summary.hiddenEdgeCount}</span>
        ) : null}
      </div>
    </section>
  );
}
