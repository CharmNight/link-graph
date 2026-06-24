/** CanvasEmptyState 组件的入参。 */
interface CanvasEmptyStateProps {
  /** 布局进行中（有节点但尚未测量/定位）时展示的文案。 */
  loadingTitle?: string;
  /** 图确实没有节点时展示的文案。 */
  idleTitle: string;
  /** 为 true 时用 loadingTitle 覆盖 idleTitle。 */
  isLoading?: boolean;
}

/**
 * 画布空态组件：统一的空/加载占位。
 *
 * 替换之前每个视图各自写的 `<div className="canvas-empty-state">…</div>`，
 * 让空态文案和标记结构在全应用保持一致。
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
