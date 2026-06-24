// 类图路由上下文：把节点按"泳道（lane）/列（column）/行（row）"组织，
// 并预计算各种边界框（lane 边界、列边界、障碍物矩形）供后续正交路由使用。
// 类图是结构化最复杂的视图：每个类节点都有自己的位置与尺寸，
// 关系边需要绕开节点不穿过；预计算这些边界让路由算法能快速判断"会不会撞"。
import type { NodeMeasuredSize } from "../../graph/nodeSizeRegistry";
import type { LinkGraphNode } from "../../types";
import {
  dataColumn,
  laneColumn,
  laneColumnBoundsKey,
  laneOf,
  mergeBounds,
  nodeBounds,
  nodeSortKey,
  nodeTop,
  numericMetadata,
  orthogonalRectForNode,
  type ClassDiagramLane,
  type ClassNodeBounds,
  type ClassRouteContext,
} from "./classDiagramLayoutModel";

/**
 * 计算节点在泳道内的排序 key。
 *
 * 多级排序：列 → 行 → 顶部 y → 节点排序键。
 * 用前置补零保证字典序等价于数值序。
 */
function laneNodeOrderKey(
  node: LinkGraphNode,
): string {
  const column = laneColumn(node);
  const row = numericMetadata(node.metadata?.["layout.row"]);
  const top = nodeTop(node);
  return `${String(column).padStart(2, "0")}|${String(row).padStart(3, "0")}|${String(Math.round(top)).padStart(8, "0")}|${nodeSortKey(node)}`;
}

/**
 * 构造类图路由上下文。
 *
 * 计算四类信息：
 * - laneNodes：按泳道分组的节点（已排序）；
 * - laneBounds：每个泳道的整体边界；
 * - laneColumnBounds：每个泳道内每列的边界；
 * - obstacleRects：所有节点的障碍物矩形（用于路由时避让）；
 * - dataBounds / dataColumnBounds：DATA 泳道特殊处理（多个 data 类按列分组）。
 *
 * 这些预算数据让后续正交路由算法可以 O(1) 查询各种边界，
 * 而不需要每次路由都重新计算。
 *
 * @param nodes 全部节点列表
 * @param sizeSnapshot 节点尺寸快照
 * @return 类图路由上下文
 */
export function buildClassRouteContext(
  nodes: LinkGraphNode[],
  sizeSnapshot: ReadonlyMap<string, NodeMeasuredSize>,
): ClassRouteContext {
  // 第一步：按泳道分组节点
  const laneNodes = new Map<ClassDiagramLane, LinkGraphNode[]>();
  nodes.forEach((node) => {
    const lane = laneOf(node);
    laneNodes.set(lane, [...(laneNodes.get(lane) ?? []), node]);
  });
  // 每个泳道内按 laneNodeOrderKey 排序
  laneNodes.forEach((bucket, lane) => {
    laneNodes.set(
      lane,
      [...bucket].sort((left, right) =>
        laneNodeOrderKey(left).localeCompare(laneNodeOrderKey(right)),
      ),
    );
  });

  // 第二步：计算各类边界
  const laneBounds = new Map<ClassDiagramLane, ClassNodeBounds>();
  const laneColumnBounds = new Map<string, ClassNodeBounds>();
  // DATA 泳道特殊：跨列聚合
  let dataBounds: ClassNodeBounds | null = null;
  const dataColumnBounds = new Map<number, ClassNodeBounds>();

  nodes.forEach((node) => {
    const lane = laneOf(node);
    const bounds = nodeBounds(node, sizeSnapshot);
    // 合并到泳道边界
    const existingLane = laneBounds.get(lane);
    if (existingLane) {
      laneBounds.set(lane, mergeBounds(existingLane, bounds));
    } else {
      laneBounds.set(lane, bounds);
    }
    // 合并到泳道内某列的边界
    const columnKey = laneColumnBoundsKey(lane, laneColumn(node));
    const existingColumn = laneColumnBounds.get(columnKey);
    if (existingColumn) {
      laneColumnBounds.set(columnKey, mergeBounds(existingColumn, bounds));
    } else {
      laneColumnBounds.set(columnKey, bounds);
    }
    // DATA 泳道：额外按 dataColumn 聚合
    if (lane === "DATA") {
      const column = dataColumn(node);
      dataBounds = dataBounds ? mergeBounds(dataBounds, bounds) : bounds;
      const existingDataColumn = dataColumnBounds.get(column);
      dataColumnBounds.set(column, existingDataColumn ? mergeBounds(existingDataColumn, bounds) : bounds);
    }
  });

  // 第三步：所有节点构造障碍物矩形
  const obstacleRects = nodes.map((node) => orthogonalRectForNode(node, sizeSnapshot));
  return { laneNodes, laneBounds, laneColumnBounds, dataBounds, dataColumnBounds, obstacleRects };
}

/**
 * 取路由上下文中的全部节点（按泳道顺序展平）。
 * 用于在路由前需要"全部节点"的场景。
 */
export function routeContextNodes(context: ClassRouteContext): LinkGraphNode[] {
  return Array.from(context.laneNodes.values()).flat();
}
