import type { GraphHiddenBucket, GraphPresentationLane, GraphViewPresentation } from "../types";

export function sortedPresentationLanes(lanes: GraphPresentationLane[]): GraphPresentationLane[] {
  return [...lanes].sort((left, right) => left.order - right.order || left.id.localeCompare(right.id));
}

export function formatHiddenBuckets(buckets: GraphHiddenBucket[]): string[] {
  return buckets
    .filter((bucket) => bucket.count > 0)
    .sort((left, right) => left.label.localeCompare(right.label) || left.id.localeCompare(right.id))
    .map((bucket) => `${bucket.label} ${bucket.count}`);
}

export function visibleGraphCountLabel(
  _presentation: GraphViewPresentation,
  visibleCount: number,
  fullCount: number,
): string {
  const total = fullCount > 0 ? fullCount : visibleCount;
  return `${visibleCount} / ${total}`;
}
