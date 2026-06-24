// React Flow 边渲染时与拖动状态相关的辅助逻辑。
// 主要场景：用户拖动节点时，受影响边的视觉需要变化——
// 已存路由应当被忽略（让边跟着节点位置实时变形），同时按选中状态调整 selected 标志。
import type { Edge } from "@xyflow/react";
import type { GraphPosition } from "../types";

/**
 * 判断边的任一端点是否落在给定节点集合中。
 * 用于"拖动节点时哪些边需要被影响"。
 */
function edgeTouchesNodeIds(edge: Edge, nodeIds: ReadonlySet<string>): boolean {
  return nodeIds.has(edge.source) || nodeIds.has(edge.target);
}

/**
 * 移除边上存储的路由数据。
 * 拖动期间不应使用旧路由（会让边显得"卡住"），所以需要剥离 route 字段。
 */
function edgeWithoutStoredRoute(edge: Edge): Edge {
  // 没有 data 或 data 中无 route 字段：原样返回（避免无意义的拷贝）
  if (!edge.data || !("route" in edge.data)) {
    return edge;
  }
  const data = { ...edge.data };
  delete data.route;
  return {
    ...edge,
    data,
  };
}

/**
 * 构造用于渲染的边列表：综合考虑拖动状态与选中状态。
 *
 * - 若边端点在拖动节点集合中：剥离存储路由（让边随节点变形）；
 * - 若边 ID 等于选中 ID：标记为 selected（保留原 selected 作为 fallback）。
 *
 * @param flowEdges 原始边列表
 * @param liveDragPositions 当前正在拖动的节点位置映射
 * @param selectedEdgeId 当前选中的边 ID
 * @return 处理后的边列表（同一份引用若无变化）
 */
export function buildRenderedFlowEdges(
  flowEdges: Edge[],
  liveDragPositions: Record<string, GraphPosition>,
  selectedEdgeId: string | null,
): Edge[] {
  const liveDragNodeIds = new Set(Object.keys(liveDragPositions));
  // 既无选中也无拖动：原样返回，避免无意义的 map
  if (!selectedEdgeId && liveDragNodeIds.size === 0) {
    return flowEdges;
  }
  return flowEdges.map((edge) => ({
    // 拖动相关边：剥离存储路由
    ...(liveDragNodeIds.size > 0 && edgeTouchesNodeIds(edge, liveDragNodeIds)
      ? edgeWithoutStoredRoute(edge)
      : edge),
    // 选中态：当前边为选中 ID 时强制 true，否则保留原值
    selected: edge.id === selectedEdgeId ? true : edge.selected,
  }));
}
