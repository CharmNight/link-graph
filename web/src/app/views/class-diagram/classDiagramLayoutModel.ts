import {
  CLASS_DIAGRAM_COMPACT_NODE_CARD_WIDTH,
  classDiagramNodeCardWidth,
} from "../../graphNodeSizing";
import type { NodeMeasuredSize } from "../../graph/nodeSizeRegistry";
import type { GraphPosition, LinkGraphEdge, LinkGraphNode } from "../../types";
import type { OrthogonalRect, OrthogonalSide } from "../../reactflow/orthogonalEdgeRouting";
import {
  classDiagramRelationLabel,
  classDiagramRelationSortRank,
  classDiagramRenderedRelationRank,
} from "./classDiagramRelations";

/** 目标节点顶部端口标识，用于连线的目标侧锚定到节点顶边。 */
export const TOP_PORT = "target-top";
/** 源节点顶部端口标识，用于连线的源侧锚定到节点顶边。 */
export const SOURCE_TOP_PORT = "source-top";
/** 源节点底部端口标识，用于连线的源侧锚定到节点底边。 */
export const BOTTOM_PORT = "source-bottom";
/** 目标节点底部端口标识，用于连线的目标侧锚定到节点底边。 */
export const TARGET_BOTTOM_PORT = "target-bottom";
/** 目标节点左侧端口标识，用于连线的目标侧锚定到节点左边。 */
export const LEFT_PORT = "target-left";
/** 源节点左侧端口标识，用于连线的源侧锚定到节点左边。 */
export const SOURCE_LEFT_PORT = "source-left";
/** 源节点右侧端口标识，用于连线的源侧锚定到节点右边。 */
export const RIGHT_PORT = "source-right";
/** 目标节点右侧端口标识，用于连线的目标侧锚定到节点右边。 */
export const TARGET_RIGHT_PORT = "target-right";

// 常规 UML 节点高度兜底值，估算失败或缺失尺寸时使用。
export const FALLBACK_HEIGHT = 260;
// 紧凑模式 UML 节点的高度兜底值，更小尺寸以容纳更多侧边节点。
export const COMPACT_FALLBACK_HEIGHT = 104;
// 节点最小宽度，取自类图紧凑模式卡片宽度常量。
export const MIN_NODE_WIDTH = CLASS_DIAGRAM_COMPACT_NODE_CARD_WIDTH;
// 旧版横向间距常量，保留以兼容历史布局参数。
export const X_GAP = 500;
// 侧泳道（入向/出向/相关）与锚点之间的基础水平间距。
export const SIDE_LANE_BASE_GAP = MIN_NODE_WIDTH + 128;
// 顶部纵向间距，控制画布上侧留白与首行节点的起始 Y 坐标。
export const Y_GAP = 260;
// 同列内节点之间的纵向堆叠间距。
export const STACK_GAP = 92;
// 父类泳道堆叠底部到锚点顶部的间距，避免父类与锚点视觉粘连。
export const PARENT_ANCHOR_GAP = 148;
// 数据泳道相邻列之间的水平间距。
export const DATA_COLUMN_GAP = 112;
// 出向泳道相邻列之间的水平间距。
export const OUTGOING_COLUMN_GAP = 112;
// 入向泳道相邻列之间的水平间距。
export const INCOMING_COLUMN_GAP = 112;
// 相关泳道相邻列之间的水平间距。
export const RELATED_COLUMN_GAP = 112;
// 数据泳道多列布局时，每多一列在 Y 方向的错位量，形成阶梯视觉。
export const DATA_COLUMN_VERTICAL_STAGGER = 56;
// 数据泳道与上方锚点/相关泳道之间的纵向间距。
export const DATA_ANCHOR_GAP = 190;
// 数据泳道相对出向泳道的右向偏移，避免与出向连线重叠。
export const DATA_RIGHT_OFFSET = 128;
// 相关泳道相对数据泳道的额外水平间距。
export const RELATED_LANE_GAP = MIN_NODE_WIDTH + 128;
// 单条连线沿通道展开时各分段之间的最小间距。
export const ROUTE_GAP = 38;
// 多条平行连线沿泳道展开时通道之间的间距。
export const ROUTE_LANE_GAP = 58;
// 外圈连线路由相对画布外侧的水平留白。
export const OUTER_ROUTE_MARGIN_X = 140;
// 外圈连线路由相对画布外侧的垂直留白。
export const OUTER_ROUTE_MARGIN_Y = 118;
// 数据泳道顶部连线的额外纵向间距。
export const DATA_ROUTE_TOP_GAP = 54;
// 数据泳道顶部连线偏移上限，避免遮挡节点。
export const DATA_ROUTE_TOP_OFFSET_LIMIT = 96;
// 数据泳道底部连线偏移上限，避免遮挡节点。
export const DATA_ROUTE_BOTTOM_OFFSET_LIMIT = 180;
// 锚点路由源侧水平边距，决定连线从锚点出发的水平走廊宽度。
export const ANCHOR_ROUTE_SOURCE_MARGIN_X = 76;
// 锚点路由目标侧水平边距，决定连线到达相邻泳道的水平走廊宽度。
export const ANCHOR_ROUTE_TARGET_MARGIN_X = 56;
// 锚点路由中扇出连线之间的通道宽度。
export const ANCHOR_ROUTE_CHANNEL_GAP = 116;
// 画布原点，所有节点坐标的默认参考点。
export const ORIGIN: GraphPosition = { x: 128, y: 128 };
// 单侧节点扇出端口的总槽数（用于在节点边上均匀分布多个连线）。
export const SIDE_FANOUT_SLOT_COUNT = 7;
// 默认中心槽位号，用于单条连线或作为对称分布的中心参考。
export const SIDE_FANOUT_CENTER_SLOT = Math.floor(SIDE_FANOUT_SLOT_COUNT / 2);
// 边交叉优化算法的最大迭代轮数，超过则停止以避免无限循环。
export const EDGE_CROSSING_REPAIR_MAX_PASSES = 4;
// 边交叉优化中节点被穿透的惩罚值，越大越倾向于绕开节点。
export const EDGE_CROSSING_REPAIR_NODE_PENALTY = 100_000;
// 边交叉优化中连线相互交叉的惩罚值。
export const EDGE_CROSSING_REPAIR_CROSSING_PENALTY = 4_000;
// 边交叉优化中连线重叠的惩罚值。
export const EDGE_CROSSING_REPAIR_OVERLAP_PENALTY = 2_500;
// 边交叉优化中连线相对节点的最小净空距离。
export const EDGE_CROSSING_REPAIR_NODE_CLEARANCE = 18;
// 边交叉优化中相邻通道之间的间距。
export const EDGE_CROSSING_REPAIR_CHANNEL_GAP = 42;
// 边交叉优化中局部改善区域大小，控制每轮仅优化周边一定范围。
export const EDGE_CROSSING_REPAIR_LOCALITY_IMPROVEMENT = 8;
// 跨泳道次级轨道的横向间距。
export const CROSS_LANE_SECONDARY_RAIL_GAP = 56;
// 跨泳道次级轨道的步长，控制次级轨道偏移量。
export const CROSS_LANE_SECONDARY_RAIL_STEP = 24;
// 跨泳道次级连线残端的间距，避免残端与节点粘连。
export const CROSS_LANE_SECONDARY_STUB_GAP = 36;

/** 类图泳道类型，标识节点在类图中的语义角色与拓扑层级。 */
export type ClassDiagramLane = "PARENT" | "INCOMING" | "ANCHOR" | "OUTGOING" | "DATA" | "RELATED";

/** 泳道条目：将节点与其所在泳道、距锚点深度打包，便于布局排序与展示。 */
export interface ClassLaneEntry {
  node: LinkGraphNode;
  lane: ClassDiagramLane;
  depth: number;
}

/** 路由通道：描述某条连线在其方向上扇出的第几条/共几条，用于均匀分布。 */
export interface RouteChannel {
  index: number;
  count: number;
}

/** 边的两端（源/目标）路由通道信息。 */
export interface EdgeRouteChannels {
  source: RouteChannel;
  target: RouteChannel;
}

/** 类图节点的矩形边界，用于布局碰撞检测与边线路由。 */
export interface ClassNodeBounds {
  left: number;
  right: number;
  top: number;
  bottom: number;
}

/** 路由上下文：聚合各泳道的节点/边界/列边界/障碍物，作为边线路由算法的输入。 */
export interface ClassRouteContext {
  laneNodes: Map<ClassDiagramLane, LinkGraphNode[]>;
  laneBounds: Map<ClassDiagramLane, ClassNodeBounds>;
  laneColumnBounds: Map<string, ClassNodeBounds>;
  dataBounds: ClassNodeBounds | null;
  dataColumnBounds: Map<number, ClassNodeBounds>;
  obstacleRects: OrthogonalRect[];
}

/** 用泳道与列号生成稳定的 key，便于在 Map 中索引"某泳道第 N 列"的边界。 */
export function laneColumnBoundsKey(lane: ClassDiagramLane, column: number): string {
  return `${lane}:${column}`;
}

/** 一维坐标轴上的区间，描述起止坐标，用于相交/重叠检测。 */
export interface AxisInterval {
  start: number;
  end: number;
}

/** 泳道在水平方向的排序权重，越小越靠左（用于交叉优化算法对齐泳道顺序）。 */
export const LANE_RANK: Record<ClassDiagramLane, number> = {
  PARENT: 0,
  INCOMING: 1,
  ANCHOR: 2,
  OUTGOING: 3,
  DATA: 4,
  RELATED: 5,
};

/** 各泳道允许的最大列数；超出会按此上限钳制，避免单泳道水平方向过度展开。 */
export const LANE_COLUMN_LIMIT: Record<ClassDiagramLane, number> = {
  PARENT: 1,
  INCOMING: 3,
  ANCHOR: 1,
  OUTGOING: 3,
  DATA: 2,
  RELATED: 3,
};

/** 根据泳道返回多列之间的水平间距常量；PARENT/ANCHOR 单列故返回 0。 */
export function laneColumnGap(lane: ClassDiagramLane): number {
  switch (lane) {
    case "DATA":
      return DATA_COLUMN_GAP;
    case "INCOMING":
      return INCOMING_COLUMN_GAP;
    case "OUTGOING":
      return OUTGOING_COLUMN_GAP;
    case "RELATED":
      return RELATED_COLUMN_GAP;
    default:
      return 0;
  }
}

/** 计算指定泳道在水平方向占用的总宽度：列数 * 节点宽 + 列间距，并按上限钳制。 */
export function laneColumnWidth(lane: ClassDiagramLane, columnCount: number): number {
  if (columnCount <= 0) {
    return 0;
  }
  const limit = Math.min(columnCount, LANE_COLUMN_LIMIT[lane]);
  if (limit <= 1) {
    return MIN_NODE_WIDTH;
  }
  return limit * MIN_NODE_WIDTH + (limit - 1) * laneColumnGap(lane);
}

/** 取边的关系排序权重（小权重在前），用于在节点排序中确定稳定次序。 */
export function relationRank(edge: LinkGraphEdge): number {
  return classDiagramRelationSortRank(edge);
}

/** 取边在渲染阶段的排序权重，用于绘制顺序与叠层显示控制。 */
export function renderedEdgeRank(edge: LinkGraphEdge): number {
  return classDiagramRenderedRelationRank(edge);
}

/** 生成节点的稳定排序键（标题/签名/ID），保证布局排序可复现。 */
export function nodeSortKey(node: LinkGraphNode): string {
  return `${node.title}|${node.signature ?? ""}|${node.id}`;
}

/** 生成边的稳定排序键：关系权重+标签+源/目标+ID，保证同组边顺序稳定。 */
export function edgeSortKey(edge: LinkGraphEdge): string {
  return `${relationRank(edge)}|${classDiagramRelationLabel(edge)}|${edge.source}|${edge.target}|${edge.id}`;
}

/** 简易字符串哈希（djb2 变体），返回 32 位无符号整数，用于稳定伪随机分配。 */
export function hashText(value: string): number {
  let hash = 0;
  for (let index = 0; index < value.length; index += 1) {
    hash = ((hash * 31) + value.charCodeAt(index)) >>> 0;
  }
  return hash;
}

/** 将数值钳制到 [min, max] 区间内，避免超出布局阈值。 */
export function clamp(value: number, min: number, max: number): number {
  return Math.min(max, Math.max(min, value));
}

/** 统计多行文本（如字段/方法清单）的非空行数，用于估算节点高度。 */
function metadataLines(value?: string | null): number {
  return (value ?? "")
    .split("\n")
    .map((line) => line.trim())
    .filter(Boolean)
    .length;
}

/** 将字符串形式的元数据解析为非负有限数字，失败回退为 0。 */
export function numericMetadata(value?: string | null): number {
  const parsed = Number(value ?? "0");
  return Number.isFinite(parsed) ? Math.max(0, parsed) : 0;
}

/** 估算节点注释（文档/Javadoc）占用的可见行数，按显式行与折行宽度取较大者。 */
function estimateCommentLines(node: LinkGraphNode): number {
  const comment = node.doc ?? node.metadata?.["uml.comment"] ?? node.metadata?.["jvm.class.docComment"];
  if (!comment?.trim()) {
    return 0;
  }
  const explicitLines = comment.split("\n").filter((line) => line.trim()).length;
  const wrappedLines = Math.ceil(comment.trim().length / 58);
  return clamp(Math.max(explicitLines, wrappedLines, 1), 1, 3);
}

/** 根据 UML 区块（字段/方法）行数估算该区块高度，最多显示 5 行。 */
function estimateCompartmentHeight(lineCount: number): number {
  const visibleLines = clamp(lineCount, 1, 5);
  return 16 + visibleLines * 17;
}

/** 判断节点是否使用紧凑布局：通过传入泳道、显式标记或展示角色综合判断。 */
function isCompactNode(node: LinkGraphNode, lane?: ClassDiagramLane): boolean {
  if (lane) {
    return lane !== "ANCHOR";
  }
  if (node.metadata?.["presentation.compact"] === "true") {
    return true;
  }
  if (node.metadata?.["presentation.compact"] === "false") {
    return false;
  }
  const presentationRole = node.metadata?.["presentation.role"];
  if (presentationRole) {
    return presentationRole !== "ANCHOR";
  }
  const layoutDirection = lane ?? node.metadata?.["layout.direction"];
  return layoutDirection !== "ANCHOR";
}

/** 估算紧凑模式节点的高度：基础高度 + 详情行（方法/签名/位置/包名）的高度。 */
function estimateCompactNodeHeight(node: LinkGraphNode): number {
  const methodLines = metadataLines(node.metadata?.["uml.method.items"]);
  const detailLineCount = methodLines > 0 || node.signature || node.location || node.metadata?.["architecture.package"] ? 1 : 0;
  const estimated = 74 + detailLineCount * 18;
  return clamp(estimated, 88, 132);
}

/** 估算 UML 节点高度：紧凑模式走简化估测，否则按字段/方法/注释区块累加。 */
export function estimateUmlNodeHeight(node: LinkGraphNode, lane?: ClassDiagramLane): number {
  if (isCompactNode(node, lane)) {
    return estimateCompactNodeHeight(node);
  }
  const fieldLines = metadataLines(node.metadata?.["uml.field.items"]) +
    (numericMetadata(node.metadata?.["uml.field.hiddenCount"]) > 0 ? 1 : 0);
  const methodLines = metadataLines(node.metadata?.["uml.method.items"]) +
    (numericMetadata(node.metadata?.["uml.method.hiddenCount"]) > 0 ? 1 : 0);
  const commentLines = estimateCommentLines(node);
  const commentHeight = commentLines > 0 ? 18 + commentLines * 17 : 0;
  const estimated = 76 + commentHeight + estimateCompartmentHeight(fieldLines) + estimateCompartmentHeight(methodLines);
  return clamp(estimated, FALLBACK_HEIGHT, 380);
}

/**
 * 返回节点的最终尺寸：优先使用实测尺寸，否则用估算尺寸兜底，
 * 并取两者较大值，避免实测尺寸小于最小展示需求。
 */
export function measuredNodeSize(
  node: LinkGraphNode,
  sizeSnapshot: ReadonlyMap<string, NodeMeasuredSize>,
  lane?: ClassDiagramLane,
) {
  const measured = sizeSnapshot.get(node.id);
  const estimatedHeight = estimateUmlNodeHeight(node, lane);
  const estimatedWidth = lane === "ANCHOR"
    ? classDiagramNodeCardWidth()
    : classDiagramNodeCardWidth(node);
  return {
    width: Math.max(measured?.width ?? estimatedWidth, estimatedWidth),
    height: Math.max(measured?.height ?? estimatedHeight, estimatedHeight),
  };
}

/** 从节点元数据读取其所属泳道，缺失时默认归入 RELATED 泳道。 */
export function laneOf(node: LinkGraphNode): ClassDiagramLane {
  return (node.metadata?.["layout.direction"] as ClassDiagramLane | undefined) ?? "RELATED";
}

/** 读取节点的列号（用于数据泳道多列布局），非法值钳制为 0。 */
export function dataColumn(node: LinkGraphNode): number {
  const parsed = Number(node.metadata?.["layout.column"] ?? "0");
  return Number.isFinite(parsed) ? Math.max(0, Math.round(parsed)) : 0;
}

/** 读取节点在列内的行号，非法值钳制为 0。 */
export function dataRow(node: LinkGraphNode): number {
  const parsed = Number(node.metadata?.["layout.row"] ?? "0");
  return Number.isFinite(parsed) ? Math.max(0, Math.round(parsed)) : 0;
}

/** 判定节点在数据泳道中属于外侧（第 0 列为 left，其余为 right）。 */
export function dataOuterSide(node: LinkGraphNode): "left" | "right" {
  return dataColumn(node) === 0 ? "left" : "right";
}

/** 根据源/目标节点相对位置，决定源节点连线应从远离目标的一侧（左/右）出发。 */
export function dataOuterSideAwayFromTarget(source: LinkGraphNode, target: LinkGraphNode): "left" | "right" {
  if (target.position && source.position) {
    return target.position.x >= source.position.x ? "right" : "left";
  }
  return dataOuterSide(source);
}

/** 读取节点所在列号：数据泳道专用 dataColumn，其他泳道读取通用列元数据。 */
export function laneColumn(node: LinkGraphNode): number {
  if (laneOf(node) === "DATA") {
    return dataColumn(node);
  }
  const parsed = Number(node.metadata?.["layout.column"] ?? "0");
  return Number.isFinite(parsed) ? Math.max(0, Math.round(parsed)) : 0;
}

/** 节点顶边 Y 坐标，缺失位置时回退到画布原点 Y。 */
export function nodeTop(node: LinkGraphNode) {
  return node.position?.y ?? ORIGIN.y;
}

/** 节点底边 Y 坐标，等于顶边 Y + 节点测量高度。 */
export function nodeBottom(node: LinkGraphNode, sizeSnapshot: ReadonlyMap<string, NodeMeasuredSize>) {
  return (node.position?.y ?? ORIGIN.y) + measuredNodeSize(node, sizeSnapshot).height;
}

/** 节点左边 X 坐标，缺失位置时回退到画布原点 X。 */
export function nodeLeft(node: LinkGraphNode) {
  return node.position?.x ?? ORIGIN.x;
}

/** 节点右边 X 坐标，等于左边 X + 节点测量宽度。 */
export function nodeRight(node: LinkGraphNode, sizeSnapshot: ReadonlyMap<string, NodeMeasuredSize>) {
  return (node.position?.x ?? ORIGIN.x) + measuredNodeSize(node, sizeSnapshot).width;
}

/** 节点垂直中心 Y 坐标，常用于水平连线的对齐计算。 */
export function nodeCenterY(
  node: LinkGraphNode,
  sizeSnapshot: ReadonlyMap<string, NodeMeasuredSize>,
): number {
  return nodeTop(node) + measuredNodeSize(node, sizeSnapshot).height / 2;
}

/** 节点水平中心 X 坐标，常用于垂直连线的对齐计算。 */
export function nodeCenterX(
  node: LinkGraphNode,
  sizeSnapshot: ReadonlyMap<string, NodeMeasuredSize>,
): number {
  return nodeLeft(node) + measuredNodeSize(node, sizeSnapshot).width / 2;
}

/** 将两个边界合并为外接矩形，第一个为空时直接返回第二个。 */
export function mergeBounds(left: ClassNodeBounds | null, right: ClassNodeBounds): ClassNodeBounds {
  if (!left) {
    return right;
  }
  return {
    left: Math.min(left.left, right.left),
    right: Math.max(left.right, right.right),
    top: Math.min(left.top, right.top),
    bottom: Math.max(left.bottom, right.bottom),
  };
}

/** 计算节点的外接矩形边界（左右上下）。 */
export function nodeBounds(
  node: LinkGraphNode,
  sizeSnapshot: ReadonlyMap<string, NodeMeasuredSize>,
): ClassNodeBounds {
  return {
    left: nodeLeft(node),
    right: nodeRight(node, sizeSnapshot),
    top: nodeTop(node),
    bottom: nodeBottom(node, sizeSnapshot),
  };
}

/** 将边界四边各向外扩展 padding，用于生成路由净空区或碰撞缓冲。 */
export function expandBounds(bounds: ClassNodeBounds, padding: number): ClassNodeBounds {
  return {
    left: bounds.left - padding,
    right: bounds.right + padding,
    top: bounds.top - padding,
    bottom: bounds.bottom + padding,
  };
}

/** 把节点转换为正交路由算法可消费的矩形结构。 */
export function orthogonalRectForNode(
  node: LinkGraphNode,
  sizeSnapshot: ReadonlyMap<string, NodeMeasuredSize>,
): OrthogonalRect {
  const size = measuredNodeSize(node, sizeSnapshot);
  return {
    id: node.id,
    x: nodeLeft(node),
    y: nodeTop(node),
    width: size.width,
    height: size.height,
  };
}

/** 从路由结构的第一段提取路径点序列（起点 + 中间拐点 + 终点）。 */
export function routeSectionPoints(route: LinkGraphEdge["route"]): GraphPosition[] {
  const section = route?.sections[0];
  return section ? [section.startPoint, ...(section.bendPoints ?? []), section.endPoint] : [];
}

/** 从边对象提取完整的路径点序列（封装 routeSectionPoints）。 */
export function routePoints(edge: LinkGraphEdge): GraphPosition[] {
  return routeSectionPoints(edge.route);
}

/** 简化路径点：先按 0.5 像素阈值去重，再合并共线（垂直/水平）的中间点。 */
export function simplifyRoutePoints(points: GraphPosition[]): GraphPosition[] {
  const deduped: GraphPosition[] = [];
  points.forEach((point) => {
    const previous = deduped[deduped.length - 1];
    if (!previous || Math.abs(previous.x - point.x) > 0.5 || Math.abs(previous.y - point.y) > 0.5) {
      deduped.push({ x: Math.round(point.x), y: Math.round(point.y) });
    }
  });
  if (deduped.length <= 2) {
    return deduped;
  }
  const simplified: GraphPosition[] = [deduped[0]!];
  for (let index = 1; index < deduped.length - 1; index += 1) {
    const previous = simplified[simplified.length - 1]!;
    const current = deduped[index]!;
    const next = deduped[index + 1]!;
    const sameVertical = Math.abs(previous.x - current.x) <= 0.5 && Math.abs(current.x - next.x) <= 0.5;
    const sameHorizontal = Math.abs(previous.y - current.y) <= 0.5 && Math.abs(current.y - next.y) <= 0.5;
    if (sameVertical || sameHorizontal) {
      continue;
    }
    simplified.push(current);
  }
  simplified.push(deduped[deduped.length - 1]!);
  return simplified;
}

/** 将简化后的路径点序列重新封装为边对象使用的 route 结构（带起止点与可选拐点）。 */
export function routeFromPoints(points: GraphPosition[]): LinkGraphEdge["route"] {
  const simplified = simplifyRoutePoints(points);
  return {
    sections: [
      {
        startPoint: simplified[0]!,
        bendPoints: simplified.length > 2 ? simplified.slice(1, -1) : undefined,
        endPoint: simplified[simplified.length - 1]!,
      },
    ],
  };
}

/** 计算路径点序列的曼哈顿总长度（横纵之和），用于排序与最短路径选择。 */
export function routeLength(points: GraphPosition[]): number {
  return points.slice(1).reduce((total, point, index) => {
    const previous = points[index]!;
    return total + Math.abs(point.x - previous.x) + Math.abs(point.y - previous.y);
  }, 0);
}

/** 从端口标识中剥离 "-N" 后缀（槽位号），返回基础端口名。 */
export function portBase(port: string): string {
  return port.replace(/-\d+$/, "");
}

/** 从端口标识中解析槽位号；不存在则返回 null，并钳制到合法槽位范围内。 */
export function portSlot(port: string): number | null {
  const match = /-(\d+)$/.exec(port);
  if (!match) {
    return null;
  }
  const parsed = Number(match[1]);
  return Number.isFinite(parsed) ? clamp(Math.round(parsed), 0, SIDE_FANOUT_SLOT_COUNT - 1) : null;
}

/** 判断端口是否位于节点的水平边（左/右），用于决定路由方向。 */
export function isHorizontalPort(port: string): boolean {
  const basePort = portBase(port);
  return basePort === LEFT_PORT || basePort === RIGHT_PORT || basePort === SOURCE_LEFT_PORT || basePort === TARGET_RIGHT_PORT;
}

/** 根据端口名返回连线源侧应使用的正交方向（上/下/左/右）。 */
export function sourceSideForPort(port: string): OrthogonalSide {
  switch (portBase(port)) {
    case SOURCE_TOP_PORT:
    case TOP_PORT:
      return "top";
    case BOTTOM_PORT:
    case TARGET_BOTTOM_PORT:
      return "bottom";
    case SOURCE_LEFT_PORT:
    case LEFT_PORT:
      return "left";
    case RIGHT_PORT:
    case TARGET_RIGHT_PORT:
    default:
      return "right";
  }
}

/** 根据端口名返回连线目标侧应使用的正交方向（上/下/左/右）。 */
export function targetSideForPort(port: string): OrthogonalSide {
  switch (portBase(port)) {
    case SOURCE_TOP_PORT:
    case TOP_PORT:
      return "top";
    case BOTTOM_PORT:
    case TARGET_BOTTOM_PORT:
      return "bottom";
    case SOURCE_LEFT_PORT:
    case LEFT_PORT:
      return "left";
    case RIGHT_PORT:
    case TARGET_RIGHT_PORT:
    default:
      return "right";
  }
}

/**
 * 根据索引和总条数计算扇出端口槽位号：
 * 1 条走中心；2/3 条对称分布；更多则按槽数均匀插值。
 */
export function sideFanoutSlot(index: number, count: number): number {
  if (count <= 1) {
    return SIDE_FANOUT_CENTER_SLOT;
  }
  if (count === 2) {
    return index <= 0 ? SIDE_FANOUT_CENTER_SLOT - 1 : SIDE_FANOUT_CENTER_SLOT + 1;
  }
  if (count === 3) {
    return [1, SIDE_FANOUT_CENTER_SLOT, SIDE_FANOUT_SLOT_COUNT - 2][clamp(index, 0, 2)] ?? SIDE_FANOUT_CENTER_SLOT;
  }
  return clamp(Math.round((index * (SIDE_FANOUT_SLOT_COUNT - 1)) / (count - 1)), 0, SIDE_FANOUT_SLOT_COUNT - 1);
}

/** 给基础端口名附加槽位号，生成完整的端口标识，并钳制到合法范围。 */
export function portWithSlot(basePort: string, slot: number): string {
  return `${basePort}-${clamp(slot, 0, SIDE_FANOUT_SLOT_COUNT - 1)}`;
}

/** 计算通道在 [0,1] 区间内的相对位置比例，用于均匀分布连线。 */
export function routeChannelRatio(channel: RouteChannel): number {
  return (clamp(channel.index, 0, channel.count - 1) + 1) / (channel.count + 1);
}

/** 根据通道比例在 [minX, maxX] 范围内计算连线 X 坐标；单条时附带轻微偏移以错开。 */
export function fanoutChannelX(minX: number, maxX: number, channel: RouteChannel, pairOffset: number): number {
  if (maxX <= minX) {
    return Math.round((minX + maxX) / 2);
  }
  const channelX = minX + (maxX - minX) * routeChannelRatio(channel);
  const singleRouteNudge = channel.count <= 1 ? pairOffset / 3 : 0;
  return clamp(Math.round(channelX + singleRouteNudge), minX, maxX);
}

/** 反转通道索引方向（从右侧计数），用于对称布局时镜像通道分配。 */
export function reversedRouteChannel(channel: RouteChannel): RouteChannel {
  return {
    index: Math.max(0, channel.count - channel.index - 1),
    count: channel.count,
  };
}

/**
 * 根据端口与节点尺寸计算连线锚点的具体坐标：
 * 顶/底/左/右端口分别锚定到对应边，并通过 slot 偏移避开同边多连线重叠。
 */
export function portPoint(
  node: LinkGraphNode,
  port: string,
  sizeSnapshot: ReadonlyMap<string, NodeMeasuredSize>,
  offset: number,
): GraphPosition {
  const size = measuredNodeSize(node, sizeSnapshot);
  const position = node.position ?? ORIGIN;
  const basePort = portBase(port);
  const slot = portSlot(port);
  const verticalSlotRatio = slot == null ? 0.5 : (slot + 1) / (SIDE_FANOUT_SLOT_COUNT + 1);
  const horizontalSlotRatio = verticalSlotRatio;
  switch (basePort) {
    case SOURCE_TOP_PORT:
    case TOP_PORT:
      return { x: position.x + size.width * horizontalSlotRatio + offset, y: position.y };
    case BOTTOM_PORT:
    case TARGET_BOTTOM_PORT:
      return { x: position.x + size.width * horizontalSlotRatio + offset, y: position.y + size.height };
    case SOURCE_LEFT_PORT:
    case LEFT_PORT:
      return { x: position.x, y: position.y + size.height * verticalSlotRatio + offset };
    case RIGHT_PORT:
    case TARGET_RIGHT_PORT:
    default:
      return { x: position.x + size.width, y: position.y + size.height * verticalSlotRatio + offset };
  }
}
