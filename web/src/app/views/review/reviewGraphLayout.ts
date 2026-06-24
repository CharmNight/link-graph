// 审查图布局：使用 ELK layered 算法从左到右排列变更影响面。
// 主要场景：用户做了改动后，展示改动影响到的下游节点（方法/资源等），
// 让审查者能快速看到"这次改动会波及哪些位置"。
import type { LayoutOptions } from "elkjs/lib/elk-api";
import { executeElkLayout, resolveMeasuredNodeSize } from "../../reactflow/elkGraph";
import type { MeasuredLayoutRequest } from "../../reactflow/useMeasuredLayout";

/**
 * 审查图的 ELK 布局选项。
 *
 * - layered 算法 + RIGHT 方向：节点从左到右排列；
 * - ORTHOGONAL 边路由：折线连接；
 * - BRANDES_KOEPF 节点放置策略：紧凑且可读；
 * - 较大的间距：让审查者能清晰看到节点之间的关系。
 */
const REVIEW_GRAPH_LAYOUT_OPTIONS: LayoutOptions = {
  "elk.algorithm": "layered",
  "org.eclipse.elk.direction": "RIGHT",
  "org.eclipse.elk.edgeRouting": "ORTHOGONAL",
  "org.eclipse.elk.layered.nodePlacement.strategy": "BRANDES_KOEPF",
  "org.eclipse.elk.layered.spacing.nodeNodeBetweenLayers": "190",
  "org.eclipse.elk.layered.spacing.edgeNodeBetweenLayers": "92",
  "org.eclipse.elk.spacing.nodeNode": "88",
  "org.eclipse.elk.layered.considerModelOrder.strategy": "NODES_AND_EDGES",
};

/**
 * 对审查图执行 ELK 布局。
 *
 * @param request 测量后布局请求（含节点/边/尺寸快照）
 * @return 带新位置与路由的节点/边
 */
export async function layoutReviewGraphView({
  nodes,
  edges,
  sizeSnapshot,
}: MeasuredLayoutRequest) {
  return executeElkLayout({
    mode: "REVIEW_GRAPH",
    layoutOptions: REVIEW_GRAPH_LAYOUT_OPTIONS,
    nodes: nodes.map((node) => ({
      node,
      ...resolveMeasuredNodeSize(node, sizeSnapshot, "REVIEW_GRAPH"),
    })),
    edges: edges.map((edge) => ({ edge })),
  });
}
