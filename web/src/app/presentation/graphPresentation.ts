import type { GraphHiddenBucket, GraphPresentationLane, GraphViewPresentation } from "../types";

/**
 * 把泳道按展示顺序排序。
 * 主排序：order 升序；次排序：id 字典序，保证多次刷新顺序稳定。
 */
export function sortedPresentationLanes(lanes: GraphPresentationLane[]): GraphPresentationLane[] {
  return [...lanes].sort((left, right) => left.order - right.order || left.id.localeCompare(right.id));
}

/**
 * 把隐藏桶列表格式化为面向用户的字符串列表。
 * 过滤掉计数为 0 的桶；按标签 + id 排序；输出形如 "标签 数量"。
 */
export function formatHiddenBuckets(buckets: GraphHiddenBucket[]): string[] {
  return buckets
    .filter((bucket) => bucket.count > 0)
    .sort((left, right) => left.label.localeCompare(right.label) || left.id.localeCompare(right.id))
    .map((bucket) => `${bucket.label} ${bucket.count}`);
}

/**
 * 生成"可见数 / 总数"形式的规模标签。
 * 总数为 0 时退化为"可见数 / 可见数"，避免出现"x / 0"这种无意义展示。
 *
 * @param _presentation 当前视图呈现规范（暂未使用，保留参数便于将来扩展）
 * @param visibleCount 可见节点数
 * @param fullCount 完整节点数
 */
export function visibleGraphCountLabel(
  _presentation: GraphViewPresentation,
  visibleCount: number,
  fullCount: number,
): string {
  const total = fullCount > 0 ? fullCount : visibleCount;
  return `${visibleCount} / ${total}`;
}
