// 资源关系图布局：使用 ELK layered + 分区把节点按泳道（CODE/INTEGRATION/DATA 等）组织。
// 主要场景：展示代码与各种外部资源（数据库、消息队列、配置中心等）之间的绑定关系，
// 让用户能从左到右看到"哪个方法访问了哪个资源"。
import type { LayoutOptions } from "elkjs/lib/elk-api";
import { executeElkLayout, resolveMeasuredNodeSize } from "../../reactflow/elkGraph";
import type { MeasuredLayoutRequest } from "../../reactflow/useMeasuredLayout";

/**
 * 资源关系图的 ELK 布局选项。
 *
 * 启用 partitioning：让 ELK 按"分区"约束节点位置（每个分区 = 一条泳道）。
 */
const RESOURCE_LAYOUT_OPTIONS: LayoutOptions = {
  "elk.algorithm": "layered",
  "org.eclipse.elk.direction": "RIGHT",
  "org.eclipse.elk.partitioning.activate": "true",
  "org.eclipse.elk.edgeRouting": "ORTHOGONAL",
  "org.eclipse.elk.layered.nodePlacement.strategy": "BRANDES_KOEPF",
  "org.eclipse.elk.layered.spacing.nodeNodeBetweenLayers": "180",
  "org.eclipse.elk.layered.spacing.edgeNodeBetweenLayers": "88",
  "org.eclipse.elk.spacing.nodeNode": "92",
  "org.eclipse.elk.layered.considerModelOrder.strategy": "NODES_AND_EDGES",
  "org.eclipse.elk.layered.crossingMinimization.forceNodeModelOrder": "true",
};

/** 资源泳道顺序：代码 → 集成 → 数据 → 配置 → 文档 → 辅助。 */
const RESOURCE_LANE_ORDER = ["CODE", "INTEGRATION", "DATA", "CONFIG", "DOC", "AUXILIARY"];
/** 泳道名 → 索引映射；用于 ELK partitioning。 */
const RESOURCE_LANE_INDEX = new Map(RESOURCE_LANE_ORDER.map((lane, index) => [lane, index]));

/** 取节点的资源泳道；缺失时默认 CODE。 */
function resourceLane(node: MeasuredLayoutRequest["nodes"][number]): string {
  return node.metadata?.["resource.lane"] ?? "CODE";
}

/**
 * 对资源关系图执行 ELK 布局。
 *
 * 关键策略：
 * - 按泳道把节点分配到不同 partition（让同泳道节点在同一行）；
 * - 锚点节点强制放第一层（让用户立即看到锚点）；
 * - 在 metadata 中保留 lane 信息，便于后续渲染层做泳道分隔。
 *
 * @param request 测量后布局请求
 * @return 带新位置与路由的节点/边
 */
export async function layoutResourceRelationView({
  nodes,
  edges,
  anchorNodeId,
  sizeSnapshot,
}: MeasuredLayoutRequest) {
  return executeElkLayout({
    mode: "RESOURCE_RELATION_VIEW",
    layoutOptions: RESOURCE_LAYOUT_OPTIONS,
    nodes: nodes.map((node) => {
      const lane = resourceLane(node);
      const laneIndex = RESOURCE_LANE_INDEX.get(lane) ?? RESOURCE_LANE_ORDER.length - 1;
      return {
        node,
        ...resolveMeasuredNodeSize(node, sizeSnapshot, "RESOURCE_RELATION_VIEW"),
        metadata: {
          "resource.lane": lane,
          "resource.laneIndex": String(laneIndex),
        },
        layoutOptions: {
          // 把节点分配到对应 partition
          "org.eclipse.elk.partitioning.partition": String(laneIndex),
          // 锚点节点：强制放在第一层
          ...(node.id === anchorNodeId
            ? { "org.eclipse.elk.layered.layering.layerConstraint": "FIRST" }
            : {}),
        },
      };
    }),
    edges: edges.map((edge) => ({ edge })),
  });
}
