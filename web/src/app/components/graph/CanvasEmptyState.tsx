interface CanvasEmptyStateProps {
  /** Shown while layout is pending (nodes exist but aren't measured/positioned yet). */
  loadingTitle?: string;
  /** Shown when the graph genuinely has no nodes. */
  idleTitle: string;
  /** When true, the loading title wins over the idle title. */
  isLoading?: boolean;
}

/**
 * Shared empty state for every graph canvas. Replaces the per-view duplicated
 * `<div className="canvas-empty-state">…</div>` pair (loading + idle) with a
 * single component so copy and markup stay consistent.
 */
export function CanvasEmptyState({
  loadingTitle = "正在整理画布",
  idleTitle,
  isLoading = false,
}: CanvasEmptyStateProps) {
  return (
    <div className="canvas-empty-state">
      <strong>{isLoading ? loadingTitle : idleTitle}</strong>
    </div>
  );
}
