import { draftCompareStatusLabel } from "../labels";
import type { DraftCompareProjection } from "../types";

/** DraftCompareSummary 组件的入参。 */
interface DraftCompareSummaryProps {
  /** 草稿比对投影数据。 */
  projection: DraftCompareProjection;
}

/**
 * 草稿比对摘要卡片。
 *
 * 在画布上方展示当前草稿比对结果的概览：
 * - 标题 + 概述说明（是否包含被移除的"幽灵节点"）；
 * - 各状态标签（修改 / 新增 / 删除）+ 命中节点/边数 + 视图外数量。
 *
 * 让用户在不打开完整对比面板的情况下也能快速感知"草稿都改了什么"。
 */
export function DraftCompareSummary({ projection }: DraftCompareSummaryProps) {
  // 合并节点与边的状态，去重
  const statusSet = new Set([
    ...Object.values(projection.nodeStatuses),
    ...Object.values(projection.edgeStatuses),
  ]);
  const hasRemovedElements = statusSet.has("REMOVED");
  // 含被移除元素时给出额外说明（提示"画布上有幽灵节点"）
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
        {/* 各状态标签：按节点/边状态枚举生成 */}
        {Array.from(statusSet).map((status) => (
          <span key={status} className={`canvas-reading-flag is-draft-compare-${status.toLowerCase()}`}>
            {draftCompareStatusLabel(status)}
          </span>
        ))}
        {/* 命中节点：始终展示（即使为 0） */}
        <span className="canvas-reading-flag is-info">命中节点 {projection.summary.visibleNodeCount}</span>
        {/* 命中连线、视图外节点/边：大于 0 时展示 */}
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
