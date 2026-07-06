import { Position } from "@xyflow/react";
import type { LayoutOptions } from "elkjs/lib/elk-api";
import { resolveFlowchartKind } from "../../flowchartKind";
import { flowchartNodeCardWidth } from "../../graphNodeSizing";
import { resolveMeasuredNodeSize, executeElkLayout, type ElkNodePortDefinition } from "../../reactflow/elkGraph";
import { buildOrthogonalEdgeRoute, type OrthogonalRect } from "../../reactflow/orthogonalEdgeRouting";
import { reanchorRouteEnd, reanchorRouteStart } from "../../reactflow/orthogonalRoute";
import type { MeasuredLayoutRequest } from "../../reactflow/useMeasuredLayout";
import type { GraphPosition, LinkGraphEdge, LinkGraphNode } from "../../types";
import {
  buildIncomingControlFlowIndex,
  buildMergeTargetPortLayout,
  buildOutgoingControlFlowIndex,
  flowchartDecisionPortPoint,
  flowchartMergeTargetPortId,
  hasExceptionControlFlowOutlet,
  isDecisionFallthroughEdge,
  type FlowchartMergeTargetPortCounts,
  type FlowchartDecisionPortId,
  resolveDecisionSourcePort,
  resolveDecisionTargetPort,
} from "./decisionPortGeometry";
import {
  buildFlowchartInvocationExpansionRegistry,
  type FlowchartInvocationExpansionEntry,
} from "./flowchartLayoutModel";

// ELK 分层布局的全局参数：自上而下流向、正交折线路由、Brandes-Koepf 节点对齐策略，
// 同时保留模型中节点与边的顺序以稳定输出，并按层次间距与节点间距生成舒展的画布。
const FLOWCHART_LAYOUT_OPTIONS: LayoutOptions = {
  "elk.algorithm": "layered",
  "org.eclipse.elk.direction": "DOWN",
  "org.eclipse.elk.edgeRouting": "ORTHOGONAL",
  "org.eclipse.elk.layered.nodePlacement.strategy": "BRANDES_KOEPF",
  "org.eclipse.elk.layered.spacing.nodeNodeBetweenLayers": "112",
  "org.eclipse.elk.layered.spacing.edgeNodeBetweenLayers": "48",
  "org.eclipse.elk.spacing.nodeNode": "64",
  "org.eclipse.elk.layered.considerModelOrder.strategy": "NODES_AND_EDGES",
  "org.eclipse.elk.layered.crossingMinimization.forceNodeModelOrder": "true",
};

// 普通节点（业务动作、方法调用等）的默认高度，决定 ELK 布局前的预估包围盒。
const DEFAULT_FLOWCHART_NODE_HEIGHT = 156;
// 调用展开泳道与主控流之间的横向间距，把被展开的子图整体推到画布右侧。
const EXPANSION_LANE_GAP = 160;
const EXPANSION_DEPTH_LANE_WIDTH = 420;
// 同一调用源下多个展开分组在纵向上的额外间隔，避免堆叠重叠。
const EXPANSION_SOURCE_GAP = 96;
// 调用边绕开障碍时与节点或既有折线之间保留的安全留白。
const CALL_EDGE_OBSTACLE_GAP = 48;
// 折线相交与障碍判断中允许的坐标容差，避免浮点抖动导致的误判。
const ROUTE_INTERSECTION_EPSILON = 1;

/** 依据节点类型返回节点高度：决策菱形需要更大的纵向空间，其他节点复用默认高度。 */
function nodeHeight(node: LinkGraphNode): number {
  return flowchartKind(node) === "DECISION" ? 228 : DEFAULT_FLOWCHART_NODE_HEIGHT;
}

/** 计算节点的几何包围盒，统一返回上下左右以及宽高，便于后续路由避障和碰撞检测。 */
function nodeBounds(node: LinkGraphNode) {
  const position = node.position ?? { x: 0, y: 0 };
  const width = flowchartNodeCardWidth(node);
  const height = nodeHeight(node);
  return {
    left: position.x,
    right: position.x + width,
    top: position.y,
    bottom: position.y + height,
    width,
    height,
  };
}

/** 包装节点类型解析逻辑，集中处理节点元数据缺失等边界情况。 */
function flowchartKind(node?: MeasuredLayoutRequest["nodes"][number]): string {
  return resolveFlowchartKind(node);
}

/** 将边标签规范化为大写无空白的统一形式，便于在条件分支判断时做匹配。 */
function normalizedFlowLabel(edge?: MeasuredLayoutRequest["edges"][number]): string {
  return edge?.label?.trim().toUpperCase() ?? "";
}

/** 提取边的流程角色元数据（循环体/回边/退出等），用于决定路由优先级与方向。 */
function flowEdgeRole(edge?: LinkGraphEdge): string {
  return edge?.metadata?.["flow.edgeRole"]?.toUpperCase() ?? "";
}

/** 读取节点所处的流程作用域类别（如循环前置/后置条件），用于推断边方向偏好。 */
function flowScopeCategory(node?: LinkGraphNode): string {
  return node?.metadata?.["flow.scopeCategory"] ?? "";
}

/**
 * 按节点类型产出 ELK 端口定义：决策节点提供左右多个出口以表达真假分支，
 * 汇聚节点根据左右入口计数动态生成多个目标端口，终端节点仅有顶部入口，
 * 其余普通节点则统一为顶部入口、右/下出口。
 */
function flowPortDefinitions(
  node: MeasuredLayoutRequest["nodes"][number],
  outgoingEdges?: LinkGraphEdge[],
  mergeTargetPortCounts?: FlowchartMergeTargetPortCounts,
): ElkNodePortDefinition[] {
  switch (flowchartKind(node)) {
    case "DECISION":
      return [
        { id: "target-top", side: "NORTH" },
        { id: "target-left", side: "WEST" },
        { id: "target-right", side: "EAST" },
        { id: "source-left", side: "WEST" },
        { id: "source-right", side: "EAST" },
        { id: "source-bottom", side: "SOUTH" },
      ];
    case "MERGE":
      return [
        { id: "target-top", side: "NORTH" },
        ...Array.from({ length: mergeTargetPortCounts?.leftCount ?? 1 }, (_, index) => ({
          id: flowchartMergeTargetPortId("left", index),
          side: "WEST" as const,
        })),
        ...Array.from({ length: mergeTargetPortCounts?.rightCount ?? 1 }, (_, index) => ({
          id: flowchartMergeTargetPortId("right", index),
          side: "EAST" as const,
        })),
        { id: "source-bottom", side: "SOUTH" },
      ];
    case "TERMINAL":
      return [{ id: "target-top", side: "NORTH" }];
    default:
      return [
        { id: "target-top", side: "NORTH" },
        { id: "source-right", side: "EAST" },
        { id: "source-bottom", side: "SOUTH" },
      ];
  }
}

/** 将决策节点的逻辑端口标识映射为 React Flow 使用的四向位置枚举，用于锚点重定向。 */
function decisionPortPosition(portId: FlowchartDecisionPortId): Position {
  switch (portId) {
    case "target-left":
    case "source-left":
      return Position.Left;
    case "target-right":
    case "source-right":
      return Position.Right;
    case "source-bottom":
      return Position.Bottom;
    case "target-top":
    default:
      return Position.Top;
  }
}

/**
 * 在 ELK 输出的折线基础上重新对齐决策节点的连接锚点：对从决策节点出去的边
 * 把起点投射到对应分支端口，对进入决策节点的边把终点投射到目标端口，
 * 让连线视觉上贴在决策菱形的真实分支位置而不是节点中心。
 */
function projectDecisionAttachmentPoint(
  edge: LinkGraphEdge,
  node: LinkGraphNode | undefined,
  oppositeNode: LinkGraphNode | undefined,
  size: { width: number; height: number } | undefined,
  outgoingControlFlowBySource: Map<string, LinkGraphEdge[]>,
  nodeIndex: Map<string, LinkGraphNode>,
): LinkGraphEdge {
  if (!node || flowchartKind(node) !== "DECISION" || !edge.route || !size || edge.route.sections.length === 0) {
    return edge;
  }
  if (edge.source === node.id) {
    const outgoingEdges = outgoingControlFlowBySource.get(edge.source);
    const sourcePort = resolveSourcePort(
      edge,
      "DECISION",
      node,
      oppositeNode,
      outgoingEdges,
      nodeIndex,
    ) as FlowchartDecisionPortId | undefined;
    if (sourcePort) {
      return {
        ...edge,
        route: reanchorRouteStart(
          edge.route,
          flowchartDecisionPortPoint(sourcePort, node, size),
          decisionPortPosition(sourcePort),
        ),
      };
    }
  }
  if (edge.target === node.id) {
    const targetPort = resolveTargetPort(edge, "DECISION", oppositeNode, node) as FlowchartDecisionPortId | undefined;
    if (targetPort) {
      return {
        ...edge,
        route: reanchorRouteEnd(
          edge.route,
          flowchartDecisionPortPoint(targetPort, node, size),
          decisionPortPosition(targetPort),
        ),
      };
    }
  }
  return edge;
}

/**
 * 推断一条控制流边应从源节点的哪个端口发出：终端节点没有出口，
 * 普通节点优先用底部出口、异常分支使用右侧出口，决策节点交给专用解析。
 */
function resolveSourcePort(
  edge: MeasuredLayoutRequest["edges"][number],
  sourceNodeKind: string,
  sourceNode?: LinkGraphNode,
  targetNode?: LinkGraphNode,
  outgoingEdges?: LinkGraphEdge[],
  nodeIndex?: Map<string, LinkGraphNode>,
): string | undefined {
  if (edge.sourceHandle) {
    return edge.sourceHandle;
  }
  if (edge.type !== "CONTROL_FLOW") {
    return undefined;
  }
  if (sourceNodeKind !== "DECISION") {
    if (sourceNodeKind === "TERMINAL") {
      return undefined;
    }
    const sourceNodeForPort = sourceNode ?? nodeIndex?.get(edge.source);
    if (normalizedFlowLabel(edge) === "EXCEPTION" && hasExceptionControlFlowOutlet(sourceNodeForPort, outgoingEdges)) {
      return "source-right";
    }
    return "source-bottom";
  }
  return resolveDecisionSourcePort(sourceNode, targetNode, {
    edge,
    outgoingEdges,
    nodeIndex,
  });
}

/**
 * 推断一条控制流边应进入目标节点的哪个端口：决策节点交给专用解析，
 * 普通节点使用顶部入口，汇聚节点按预先计算的端口或几何相对位置选择左/右入口。
 */
function resolveTargetPort(
  edge: MeasuredLayoutRequest["edges"][number],
  targetNodeKind: string,
  sourceNode?: LinkGraphNode,
  targetNode?: LinkGraphNode,
  outgoingEdges?: LinkGraphEdge[],
  nodeIndex?: Map<string, LinkGraphNode>,
  mergeTargetPort?: string,
): string | undefined {
  if (edge.targetHandle) {
    return edge.targetHandle;
  }
  if (edge.type !== "CONTROL_FLOW") {
    return undefined;
  }
  if (targetNodeKind === "DECISION") {
    return resolveDecisionTargetPort(sourceNode, targetNode, edge);
  }
  if (targetNodeKind !== "MERGE") {
    return "target-top";
  }
  if (mergeTargetPort) {
    return mergeTargetPort;
  }
  if (isDecisionFallthroughEdge(edge, outgoingEdges, nodeIndex)) {
    return "target-top";
  }
  if (sourceNode?.position && targetNode?.position) {
    if (sourceNode.position.x < targetNode.position.x - 1) {
      return flowchartMergeTargetPortId("left", 0);
    }
    if (sourceNode.position.x > targetNode.position.x + 1) {
      return flowchartMergeTargetPortId("right", 0);
    }
  }
  return "target-top";
}

/**
 * 将图谱节点整理成 ELK 布局所需的节点描述：解析实测尺寸、生成端口集合，
 * 把异常出口与汇聚端口计数写入元数据，并对锚点节点强制放置在首层以稳定整体走向。
 */
function buildLayoutNodes(
  nodes: MeasuredLayoutRequest["nodes"],
  anchorNodeId: string | null | undefined,
  sizeSnapshot: MeasuredLayoutRequest["sizeSnapshot"],
  nodeSizeIndex: Map<string, { width: number; height: number }>,
  outgoingControlFlowBySource: Map<string, LinkGraphEdge[]>,
  mergeTargetPortCountsByNode: Map<string, FlowchartMergeTargetPortCounts>,
) {
  return nodes.map((node) => {
    const size = resolveMeasuredNodeSize(node, sizeSnapshot, "FLOWCHART");
    nodeSizeIndex.set(node.id, size);
    const hasExceptionSource = hasExceptionControlFlowOutlet(node, outgoingControlFlowBySource.get(node.id));
    const mergeTargetPortCounts = mergeTargetPortCountsByNode.get(node.id);
    return {
      node,
      ...size,
      ports: flowPortDefinitions(node, outgoingControlFlowBySource.get(node.id), mergeTargetPortCounts),
      metadata: {
        ...(hasExceptionSource ? { "flowchart.hasExceptionSource": "true" } : {}),
        ...(mergeTargetPortCounts
          ? {
              "flowchart.mergeLeftTargetCount": String(mergeTargetPortCounts.leftCount),
              "flowchart.mergeRightTargetCount": String(mergeTargetPortCounts.rightCount),
            }
          : {}),
      },
      layoutOptions: {
        "org.eclipse.elk.portConstraints": "FIXED_SIDE",
        ...(node.id === anchorNodeId
          ? { "org.eclipse.elk.layered.layering.layerConstraint": "FIRST" }
          : {}),
      },
    };
  });
}

/**
 * 根据边的流程角色与目标节点所处循环类型，给定 ELK 方向优先级，
 * 让回环边倾向于向上回弯、循环体/退出边倾向向下，使循环结构在视觉上更清晰。
 */
function flowEdgeLayoutOptions(
  edge: LinkGraphEdge,
  nodeIndex: Map<string, LinkGraphNode>,
): LayoutOptions | undefined {
  const edgeRole = flowEdgeRole(edge);
  if (edgeRole === "LOOP_BACK") {
    return {
      "org.eclipse.elk.layered.priority.direction": "0",
    };
  }
  const targetScopeCategory = flowScopeCategory(nodeIndex.get(edge.target));
  if (targetScopeCategory === "LOOP_POST_TEST") {
    return {
      "org.eclipse.elk.layered.priority.direction": "12",
    };
  }
  if (edgeRole === "LOOP_BODY" || edgeRole === "LOOP_EXIT") {
    return {
      "org.eclipse.elk.layered.priority.direction": "10",
    };
  }
  return undefined;
}

/** 按位移向量平移一个二维坐标点，用于整体搬迁节点或折线节点。 */
function translatePoint(point: GraphPosition, delta: GraphPosition): GraphPosition {
  return {
    x: point.x + delta.x,
    y: point.y + delta.y,
  };
}

/** 按位移向量平移一条边所有折线段（起点、拐点、终点），保持内部形状不变地整体迁移。 */
function translateEdgeRoute(edge: LinkGraphEdge, delta: GraphPosition): LinkGraphEdge {
  if (!edge.route) {
    return edge;
  }
  return {
    ...edge,
    route: {
      sections: edge.route.sections.map((section) => ({
        startPoint: translatePoint(section.startPoint, delta),
        bendPoints: section.bendPoints?.map((point) => translatePoint(point, delta)),
        endPoint: translatePoint(section.endPoint, delta),
      })),
    },
  };
}

/** 计算一个调用展开分组内所有节点构成的最小包围盒，用于在右侧泳道中确定整体摆放位置。 */
function expansionNodeBounds(
  nodeIds: string[],
  nodeIndex: Map<string, LinkGraphNode>,
) {
  const groupNodes = nodeIds.map((nodeId) => nodeIndex.get(nodeId)).filter((node): node is LinkGraphNode => Boolean(node?.position));
  if (groupNodes.length === 0) {
    return null;
  }
  const bounds = groupNodes.map(nodeBounds);
  return {
    left: Math.min(...bounds.map((bound) => bound.left)),
    right: Math.max(...bounds.map((bound) => bound.right)),
    top: Math.min(...bounds.map((bound) => bound.top)),
    bottom: Math.max(...bounds.map((bound) => bound.bottom)),
  };
}

function invocationExpansionBlockNodeId(expansionId: string): string {
  return `expansion-block:${expansionId}`;
}

function expansionLayoutNodeIds(
  entry: FlowchartInvocationExpansionEntry,
  nodeIndex: Map<string, LinkGraphNode>,
): string[] {
  const syntheticNodeId = invocationExpansionBlockNodeId(entry.expansionId);
  if (nodeIndex.has(syntheticNodeId)) {
    return [syntheticNodeId];
  }
  return entry.ownedNodeIds.filter((nodeId) => nodeIndex.has(nodeId));
}

function expansionRootLayoutNodeId(
  entry: FlowchartInvocationExpansionEntry,
  layoutNodeIds: string[],
  nodeIndex: Map<string, LinkGraphNode>,
): string | null {
  if (nodeIndex.has(invocationExpansionBlockNodeId(entry.expansionId))) {
    return invocationExpansionBlockNodeId(entry.expansionId);
  }
  if (entry.rootNodeId && layoutNodeIds.includes(entry.rootNodeId)) {
    return entry.rootNodeId;
  }
  return layoutNodeIds[0] ?? null;
}

/** 节点包围盒类型，包含上下左右与宽高信息，复用 nodeBounds 的返回结构。 */
type FlowchartNodeBounds = ReturnType<typeof nodeBounds>;
/** 折线段类型，由起点与终点两个二维坐标构成，是路由相交判断的基本单位。 */
type RouteSegment = { startPoint: GraphPosition; endPoint: GraphPosition };

/**
 * 判断一条水平或垂直折线段是否落入给定节点包围盒内部，
 * 仅处理严格横平竖直的线段并对边界做小量容差以避免边贴边误判。
 */
function segmentIntersectsBounds(
  segment: { startPoint: GraphPosition; endPoint: GraphPosition },
  bounds: FlowchartNodeBounds,
): boolean {
  if (Math.abs(segment.startPoint.x - segment.endPoint.x) <= 0.5) {
    const x = segment.startPoint.x;
    if (x <= bounds.left + ROUTE_INTERSECTION_EPSILON || x >= bounds.right - ROUTE_INTERSECTION_EPSILON) {
      return false;
    }
    const top = Math.min(segment.startPoint.y, segment.endPoint.y);
    const bottom = Math.max(segment.startPoint.y, segment.endPoint.y);
    return Math.max(top, bounds.top) < Math.min(bottom, bounds.bottom);
  }
  if (Math.abs(segment.startPoint.y - segment.endPoint.y) <= 0.5) {
    const y = segment.startPoint.y;
    if (y <= bounds.top + ROUTE_INTERSECTION_EPSILON || y >= bounds.bottom - ROUTE_INTERSECTION_EPSILON) {
      return false;
    }
    const left = Math.min(segment.startPoint.x, segment.endPoint.x);
    const right = Math.max(segment.startPoint.x, segment.endPoint.x);
    return Math.max(left, bounds.left) < Math.min(right, bounds.right);
  }
  return true;
}

/** 合并折线中几乎重合的相邻点，避免重复坐标带来的退化线段与多余计算。 */
function compactRoutePoints(points: GraphPosition[]): GraphPosition[] {
  return points.reduce<GraphPosition[]>((compacted, point) => {
    const previous = compacted[compacted.length - 1];
    if (previous && Math.abs(previous.x - point.x) <= 0.5 && Math.abs(previous.y - point.y) <= 0.5) {
      return compacted;
    }
    compacted.push(point);
    return compacted;
  }, []);
}

/** 判断折线经过的所有线段中是否存在与任一节点包围盒相交的线段，用于校验路由是否穿越障碍。 */
function routePointsIntersectBounds(points: GraphPosition[], bounds: FlowchartNodeBounds[]): boolean {
  const compacted = compactRoutePoints(points);
  return compacted.slice(1).some((point, index) => {
    const segment = {
      startPoint: compacted[index]!,
      endPoint: point,
    };
    return bounds.some((candidate) => segmentIntersectsBounds(segment, candidate));
  });
}

/** 把折线坐标序列切分成连续的线段数组，便于逐段做相交判断。 */
function routeSegmentsFromPoints(points: GraphPosition[]): RouteSegment[] {
  const compacted = compactRoutePoints(points);
  return compacted.slice(1).map((point, index) => ({
    startPoint: compacted[index]!,
    endPoint: point,
  }));
}

/** 判断折线段是否近似垂直（X 坐标不变），用于区分线段朝向以选择合适的相交算法。 */
function isVerticalSegment(segment: RouteSegment): boolean {
  return Math.abs(segment.startPoint.x - segment.endPoint.x) <= 0.5;
}

/** 判断折线段是否近似水平（Y 坐标不变），与 isVerticalSegment 配合处理横竖相交。 */
function isHorizontalSegment(segment: RouteSegment): boolean {
  return Math.abs(segment.startPoint.y - segment.endPoint.y) <= 0.5;
}

/** 判断两条横竖正交的折线段是否在中间（不含端点）相交，用以统计路由之间的交叉次数。 */
function segmentsCross(left: RouteSegment, right: RouteSegment): boolean {
  if (isVerticalSegment(left) && isHorizontalSegment(right)) {
    const x = left.startPoint.x;
    const y = right.startPoint.y;
    const verticalTop = Math.min(left.startPoint.y, left.endPoint.y);
    const verticalBottom = Math.max(left.startPoint.y, left.endPoint.y);
    const horizontalLeft = Math.min(right.startPoint.x, right.endPoint.x);
    const horizontalRight = Math.max(right.startPoint.x, right.endPoint.x);
    return x > horizontalLeft + ROUTE_INTERSECTION_EPSILON
      && x < horizontalRight - ROUTE_INTERSECTION_EPSILON
      && y > verticalTop + ROUTE_INTERSECTION_EPSILON
      && y < verticalBottom - ROUTE_INTERSECTION_EPSILON;
  }
  if (isHorizontalSegment(left) && isVerticalSegment(right)) {
    return segmentsCross(right, left);
  }
  return false;
}

/** 判断一条折线是否与给定的一组折线段发生任何交叉，用于评估候选路由是否引入额外交叉。 */
function routePointsCrossSegments(points: GraphPosition[], segments: RouteSegment[]): boolean {
  return routeSegmentsFromPoints(points).some((candidateSegment) =>
    segments.some((segment) => segmentsCross(candidateSegment, segment)),
  );
}

/** 统计一条折线与给定折线段集合的交叉次数，用于在多条候选路由中选择交叉最少的方案。 */
function routeSegmentCrossCount(points: GraphPosition[], segments: RouteSegment[]): number {
  return routeSegmentsFromPoints(points).reduce(
    (count, candidateSegment) => count + segments.filter((segment) => segmentsCross(candidateSegment, segment)).length,
    0,
  );
}

/** 从一条已有边的路由信息中提取所有折线段，便于把已布好的边当作新路由的障碍处理。 */
function routeSegmentsFromEdge(edge: LinkGraphEdge): RouteSegment[] {
  if (!edge.route) {
    return [];
  }
  return edge.route.sections.flatMap((section) => routeSegmentsFromPoints([
    section.startPoint,
    ...(section.bendPoints ?? []),
    section.endPoint,
  ]));
}

/** 计算折线的曼哈顿总长度，作为候选路由排序的代价函数，越短越优。 */
function routePointCost(points: GraphPosition[]): number {
  const compacted = compactRoutePoints(points);
  return compacted.slice(1).reduce((total, point, index) => {
    const previous = compacted[index]!;
    return total + Math.abs(previous.x - point.x) + Math.abs(previous.y - point.y);
  }, 0);
}

/** 用一组坐标点重新构造边的正交折线（起点-拐点-终点单段形式）并绑上指定端口句柄。 */
function routeFromPoints(
  edge: LinkGraphEdge,
  points: GraphPosition[],
  sourceHandle = "source-right",
  targetHandle = "target-left",
): LinkGraphEdge {
  const compacted = compactRoutePoints(points);
  return routeWithHandles(
    edge,
    {
      sections: [{
        startPoint: compacted[0]!,
        bendPoints: compacted.slice(1, -1),
        endPoint: compacted[compacted.length - 1]!,
      }],
    },
    sourceHandle,
    targetHandle,
  );
}

/** 把已构造好的折线对象绑定到边并标注其端口句柄，是路由最终落地到边的统一入口。 */
function routeWithHandles(
  edge: LinkGraphEdge,
  route: NonNullable<LinkGraphEdge["route"]>,
  sourceHandle = "source-right",
  targetHandle = "target-left",
): LinkGraphEdge {
  return {
    ...edge,
    sourceHandle,
    targetHandle,
    route,
  };
}

/** 对一组数值做四舍五入并去重，用于在生成候选绕行 Y 坐标时获得有限且唯一的集合。 */
function uniqueNumbers(values: number[]): number[] {
  return Array.from(new Set(values.map((value) => Math.round(value))));
}

/** 判断节点包围盒是否与给定横向区间在 X 方向上重叠，作为筛选相关障碍的依据。 */
function boundsOverlapHorizontalRange(bounds: FlowchartNodeBounds, left: number, right: number): boolean {
  return Math.max(left, bounds.left) < Math.min(right, bounds.right);
}

/** 给出在指定横向通道内绕过节点障碍的 Y 候选值：要么从最上方障碍之上绕过，要么从最下方之下绕过。 */
function verticalDetourCandidates(bounds: FlowchartNodeBounds[], left: number, right: number): number[] {
  const overlappingBounds = bounds.filter((candidate) => boundsOverlapHorizontalRange(candidate, left, right));
  if (overlappingBounds.length === 0) {
    return [];
  }
  return [
    Math.min(...overlappingBounds.map((candidate) => candidate.top)) - CALL_EDGE_OBSTACLE_GAP,
    Math.max(...overlappingBounds.map((candidate) => candidate.bottom)) + CALL_EDGE_OBSTACLE_GAP,
  ];
}

/** 判断一条折线段是否落在给定横向区间内（垂直段看 X、水平段看区间相交），用于筛选相关线段。 */
function segmentOverlapsHorizontalRange(segment: RouteSegment, left: number, right: number): boolean {
  if (isVerticalSegment(segment)) {
    const x = segment.startPoint.x;
    return x > left + ROUTE_INTERSECTION_EPSILON && x < right - ROUTE_INTERSECTION_EPSILON;
  }
  const segmentLeft = Math.min(segment.startPoint.x, segment.endPoint.x);
  const segmentRight = Math.max(segment.startPoint.x, segment.endPoint.x);
  return Math.max(left, segmentLeft) < Math.min(right, segmentRight);
}

/** 在指定横向通道内对相交折线段给出可绕行的 Y 候选值，向线段上下各退开一个安全间距。 */
function segmentDetourCandidates(segments: RouteSegment[], left: number, right: number): number[] {
  return segments
    .filter((segment) => segmentOverlapsHorizontalRange(segment, left, right))
    .flatMap((segment) => {
      if (isVerticalSegment(segment)) {
        return [
          Math.min(segment.startPoint.y, segment.endPoint.y) - CALL_EDGE_OBSTACLE_GAP,
          Math.max(segment.startPoint.y, segment.endPoint.y) + CALL_EDGE_OBSTACLE_GAP,
        ];
      }
      return [
        segment.startPoint.y - CALL_EDGE_OBSTACLE_GAP,
        segment.startPoint.y + CALL_EDGE_OBSTACLE_GAP,
      ];
    });
}

/** 当障碍在通道内部都难以绕开时，给出从所有障碍整体之上/之下绕行的 Y 候选值作为兜底方案。 */
function outerDetourCandidates(bounds: FlowchartNodeBounds[], segments: RouteSegment[]): number[] {
  const candidateYs = [
    ...bounds.flatMap((bound) => [bound.top, bound.bottom]),
    ...segments.flatMap((segment) => [segment.startPoint.y, segment.endPoint.y]),
  ];
  if (candidateYs.length === 0) {
    return [];
  }
  return [
    Math.min(...candidateYs) - CALL_EDGE_OBSTACLE_GAP,
    Math.max(...candidateYs) + CALL_EDGE_OBSTACLE_GAP,
  ];
}

/** 在所有障碍整体之外给出绕行用的 X 锚点（左侧或右侧），用于让边先绕到外侧再下行。 */
function outerEscapeX(bounds: FlowchartNodeBounds[], segments: RouteSegment[], side: "left" | "right"): number | null {
  const xs = [
    ...bounds.flatMap((bound) => [bound.left, bound.right]),
    ...segments.flatMap((segment) => [segment.startPoint.x, segment.endPoint.x]),
  ];
  if (xs.length === 0) {
    return null;
  }
  return side === "left"
    ? Math.min(...xs) - CALL_EDGE_OBSTACLE_GAP
    : Math.max(...xs) + CALL_EDGE_OBSTACLE_GAP;
}

/** 将节点转成正交避障器需要的矩形描述；没有位置的节点不参与避障计算。 */
function nodeObstacleRect(node: LinkGraphNode): OrthogonalRect | null {
  if (!node.position) {
    return null;
  }
  const bounds = nodeBounds(node);
  return {
    id: node.id,
    x: bounds.left,
    y: bounds.top,
    width: bounds.width,
    height: bounds.height,
  };
}

/** 把一条折线段包装成超薄的矩形障碍（垂直段包成竖条、水平段包成横条），交给正交绕路算法处理。 */
function segmentObstacleRect(segment: RouteSegment, index: number): OrthogonalRect {
  const left = Math.min(segment.startPoint.x, segment.endPoint.x);
  const right = Math.max(segment.startPoint.x, segment.endPoint.x);
  const top = Math.min(segment.startPoint.y, segment.endPoint.y);
  const bottom = Math.max(segment.startPoint.y, segment.endPoint.y);
  if (isVerticalSegment(segment)) {
    return {
      id: `edge-segment:${index}`,
      x: segment.startPoint.x - 1,
      y: top,
      width: 2,
      height: Math.max(bottom - top, 2),
    };
  }
  return {
    id: `edge-segment:${index}`,
    x: left,
    y: segment.startPoint.y - 1,
    width: Math.max(right - left, 2),
    height: 2,
  };
}

/**
 * 调用网格化正交绕路算法生成调用边路径：把节点和既有折线段都当作障碍，
 * 若生成结果仍然穿过障碍或与既有折线交叉则放弃，否则返回带端口句柄的边。
 */
function routeWithGridRouter(
  edge: LinkGraphEdge,
  sourceNode: LinkGraphNode,
  targetNode: LinkGraphNode,
  obstacleNodes: LinkGraphNode[],
  obstacleSegments: RouteSegment[],
  startPoint: GraphPosition,
  endPoint: GraphPosition,
): LinkGraphEdge | null {
  const sourceRect = nodeObstacleRect(sourceNode);
  const targetRect = nodeObstacleRect(targetNode);
  if (!sourceRect || !targetRect) {
    return null;
  }
  const obstacleRects = [
    ...obstacleNodes
      .map(nodeObstacleRect)
      .filter((rect): rect is OrthogonalRect => rect !== null),
    ...obstacleSegments.map(segmentObstacleRect),
  ];
  const route = buildOrthogonalEdgeRoute({
    startPoint,
    startSide: "right",
    startRect: sourceRect,
    endPoint,
    endSide: "left",
    endRect: targetRect,
    obstacleRects,
  });
  const routePoints = route.sections.flatMap((section) => [
    section.startPoint,
    ...(section.bendPoints ?? []),
    section.endPoint,
  ]);
  const obstacleBounds = obstacleNodes
    .filter((node) => node.position)
    .map(nodeBounds);
  const intersectsBounds = routePointsIntersectBounds(routePoints, obstacleBounds);
  const crossesSegments = routePointsCrossSegments(routePoints, obstacleSegments);
  if (intersectsBounds || crossesSegments) {
    return null;
  }
  return routeWithHandles(edge, route);
}

/**
 * 为一条方法调用边规划避开节点和既有控制流折线的路径：先尝试直接折线、
 * 再尝试网格绕路，都失败时枚举绕行候选点（向上下或外侧绕）并按代价与交叉数排序选最优。
 */
function routeCallEdge(
  edge: LinkGraphEdge,
  sourceNode: LinkGraphNode,
  targetNode: LinkGraphNode,
  obstacleNodes: LinkGraphNode[],
  obstacleSegments: RouteSegment[],
): LinkGraphEdge {
  const sourceBounds = nodeBounds(sourceNode);
  const targetBounds = nodeBounds(targetNode);
  const startPoint = {
    x: sourceBounds.right,
    y: sourceBounds.top + sourceBounds.height / 2,
  };
  const endPoint = {
    x: targetBounds.left,
    y: targetBounds.top + targetBounds.height / 2,
  };
  const midX = Math.round((startPoint.x + endPoint.x) / 2);
  const obstacleBounds = obstacleNodes
    .filter((node) => node.position)
    .map(nodeBounds);
  const directPoints = [
    startPoint,
    { x: midX, y: startPoint.y },
    { x: midX, y: endPoint.y },
    endPoint,
  ];
  if (!routePointsIntersectBounds(directPoints, obstacleBounds) && !routePointsCrossSegments(directPoints, obstacleSegments)) {
    return routeFromPoints(edge, directPoints);
  }
  const gridRoute = routeWithGridRouter(
    edge,
    sourceNode,
    targetNode,
    obstacleNodes,
    obstacleSegments,
    startPoint,
    endPoint,
  );
  if (gridRoute) {
    return gridRoute;
  }

  const left = Math.min(startPoint.x, endPoint.x);
  const right = Math.max(startPoint.x, endPoint.x);
  const leftStartPoint = {
    x: sourceBounds.left,
    y: startPoint.y,
  };
  const leftEscapeX = outerEscapeX(obstacleBounds, obstacleSegments, "left");
  const outerDetourYs = outerDetourCandidates(obstacleBounds, obstacleSegments);
  const escapeXCandidates = uniqueNumbers([
    startPoint.x,
    startPoint.x + CALL_EDGE_OBSTACLE_GAP,
    startPoint.x + (endPoint.x - startPoint.x) / 3,
    midX,
    endPoint.x - CALL_EDGE_OBSTACLE_GAP,
    endPoint.x,
  ]).filter((candidate) => candidate >= left && candidate <= right);
  const routeCandidates = escapeXCandidates.flatMap((escapeX) => {
    const corridorLeft = Math.min(escapeX, endPoint.x);
    const corridorRight = Math.max(escapeX, endPoint.x);
    return uniqueNumbers([
      ...verticalDetourCandidates(obstacleBounds, corridorLeft, corridorRight),
      ...segmentDetourCandidates(obstacleSegments, corridorLeft, corridorRight),
      ...outerDetourCandidates(obstacleBounds, obstacleSegments),
      startPoint.y,
      endPoint.y,
    ]).map((detourY) => [
      startPoint,
      { x: escapeX, y: startPoint.y },
      { x: escapeX, y: detourY },
      { x: endPoint.x, y: detourY },
      endPoint,
    ]);
  });
  const sideRouteCandidates = leftEscapeX === null
    ? []
    : uniqueNumbers([
      ...outerDetourYs,
      ...verticalDetourCandidates(obstacleBounds, Math.min(leftEscapeX, endPoint.x), Math.max(leftEscapeX, endPoint.x)),
      ...segmentDetourCandidates(obstacleSegments, Math.min(leftEscapeX, endPoint.x), Math.max(leftEscapeX, endPoint.x)),
    ]).map((detourY) => ({
      points: [
        leftStartPoint,
        { x: leftEscapeX, y: leftStartPoint.y },
        { x: leftEscapeX, y: detourY },
        { x: endPoint.x, y: detourY },
        endPoint,
      ],
      sourceHandle: "source-left",
      targetHandle: "target-left",
    }));
  const typedRouteCandidates = [
    ...routeCandidates.map((points) => ({
      points,
      sourceHandle: "source-right",
      targetHandle: "target-left",
    })),
    ...sideRouteCandidates,
  ];
  const clearRoute = typedRouteCandidates
    .filter((candidate) => !routePointsIntersectBounds(candidate.points, obstacleBounds))
    .filter((candidate) => !routePointsCrossSegments(candidate.points, obstacleSegments))
    .sort((leftCandidate, rightCandidate) => routePointCost(leftCandidate.points) - routePointCost(rightCandidate.points))[0];
  const nodeClearRoute = typedRouteCandidates
    .filter((candidate) => !routePointsIntersectBounds(candidate.points, obstacleBounds))
    .sort((leftCandidate, rightCandidate) => {
      const leftCrossCount = routeSegmentCrossCount(leftCandidate.points, obstacleSegments);
      const rightCrossCount = routeSegmentCrossCount(rightCandidate.points, obstacleSegments);
      if (leftCrossCount !== rightCrossCount) {
        return leftCrossCount - rightCrossCount;
      }
      return routePointCost(leftCandidate.points) - routePointCost(rightCandidate.points);
    })[0];

  const selectedRoute = clearRoute ?? nodeClearRoute;
  if (selectedRoute) {
    return routeFromPoints(edge, selectedRoute.points, selectedRoute.sourceHandle, selectedRoute.targetHandle);
  }
  return routeFromPoints(edge, directPoints);
}

/**
 * 把方法调用展开得到的子图整体迁移到主控流右侧的泳道：按调用源堆叠分组、
 * 平移所有相关节点与内部边，并对调用边重新走避开主控流的折线路由。
 */
function applyInvocationExpansionLayout(
  nodes: LinkGraphNode[],
  edges: LinkGraphEdge[],
): { nodes: LinkGraphNode[]; edges: LinkGraphEdge[] } {
  const registry = buildFlowchartInvocationExpansionRegistry({
    nodes,
    edges,
    defaultCollapseSiblings: false,
  });
  if (registry.entries.length === 0) {
    return { nodes, edges };
  }
  const nodeIndex = new Map(nodes.map((node) => [node.id, node]));
  const expansionOwnedNodeIds = new Set(registry.entries.flatMap((entry) => entry.ownedNodeIds));
  const mainNodes = nodes.filter((node) => !expansionOwnedNodeIds.has(node.id) && node.position);
  if (mainNodes.length === 0) {
    return { nodes, edges };
  }
  const mainRight = Math.max(...mainNodes.map((node) => nodeBounds(node).right));
  const mainRouteRight = Math.max(
    mainRight,
    ...edges
      .filter((edge) => edge.type === "CONTROL_FLOW")
      .flatMap(routeSegmentsFromEdge)
      .flatMap((segment) => [segment.startPoint.x, segment.endPoint.x]),
  );
  const nodeDeltas = new Map<string, GraphPosition>();
  const sourceStackCounts = new Map<string, number>();
  const nextLaneTopByDepth = new Map<number, number>();
  const workingNodeIndex = new Map(nodes.map((node) => [node.id, node]));

  registry.entries
    .filter((entry) => entry.state === "expanded" || nodeIndex.has(invocationExpansionBlockNodeId(entry.expansionId)))
    .sort((left, right) => {
      if (left.depth !== right.depth) {
        return left.depth - right.depth;
      }
      return left.expansionId.localeCompare(right.expansionId);
    })
    .forEach((entry) => {
      const layoutNodeIds = expansionLayoutNodeIds(entry, workingNodeIndex);
      const rootNodeId = expansionRootLayoutNodeId(entry, layoutNodeIds, workingNodeIndex);
      const sourceNode = entry.sourceInvocationNodeId ? workingNodeIndex.get(entry.sourceInvocationNodeId) : null;
      const rootNode = rootNodeId ? workingNodeIndex.get(rootNodeId) : null;
      const bounds = expansionNodeBounds(layoutNodeIds, workingNodeIndex);
    if (!sourceNode?.position || !rootNode?.position || !bounds) {
      return;
    }
    const sourceBounds = nodeBounds(sourceNode);
    const rootBounds = nodeBounds(rootNode);
    const stackKey = `${entry.parentExpansionId ?? "root"}:${entry.sourceInvocationNodeId ?? entry.expansionId}`;
    const stackIndex = sourceStackCounts.get(stackKey) ?? 0;
    sourceStackCounts.set(stackKey, stackIndex + 1);
    const targetLeft = mainRouteRight + EXPANSION_LANE_GAP + Math.max(0, entry.depth - 1) * (EXPANSION_DEPTH_LANE_WIDTH + EXPANSION_LANE_GAP);
    const preferredRootTop = sourceBounds.top + stackIndex * (bounds.bottom - bounds.top + EXPANSION_SOURCE_GAP);
    const targetRootTop = Math.max(preferredRootTop, nextLaneTopByDepth.get(entry.depth) ?? preferredRootTop);
    const delta = {
      x: Math.round(targetLeft - rootBounds.left),
      y: Math.round(targetRootTop - rootBounds.top),
    };
      layoutNodeIds.forEach((nodeId) => {
        const node = workingNodeIndex.get(nodeId);
        nodeDeltas.set(nodeId, delta);
        if (node?.position) {
          workingNodeIndex.set(nodeId, {
            ...node,
            position: translatePoint(node.position, delta),
          });
        }
      });
      nextLaneTopByDepth.set(entry.depth, bounds.bottom + delta.y + EXPANSION_SOURCE_GAP);
    });

  if (nodeDeltas.size === 0) {
    return { nodes, edges };
  }

  const adjustedNodes = nodes.map((node) => {
    const delta = nodeDeltas.get(node.id);
    if (!delta || !node.position) {
      return node;
    }
    const position = translatePoint(node.position, delta);
    return {
      ...node,
      position,
      metadata: {
        ...(node.metadata ?? {}),
        "ui.x": String(position.x),
        "ui.y": String(position.y),
      },
    };
  });
  const adjustedNodeIndex = new Map(adjustedNodes.map((node) => [node.id, node]));
  const internalEdgeIds = new Set(registry.entries.flatMap((entry) => entry.internalEdgeIds));
  const callEdgeIds = new Set(registry.entries.flatMap((entry) => entry.callEdgeIds));
  const callObstacleSegments = edges
    .filter((edge) => edge.type === "CONTROL_FLOW" && !internalEdgeIds.has(edge.id))
    .flatMap(routeSegmentsFromEdge);
  const edgeDeltaById = new Map<string, GraphPosition>();
  registry.entries.forEach((entry) => {
    const firstDelta = expansionLayoutNodeIds(entry, adjustedNodeIndex).map((nodeId) => nodeDeltas.get(nodeId)).find((delta): delta is GraphPosition => Boolean(delta));
    if (!firstDelta) {
      return;
    }
    entry.internalEdgeIds.forEach((edgeId) => edgeDeltaById.set(edgeId, firstDelta));
  });

  const adjustedEdges = edges.map((edge) => {
    if (callEdgeIds.has(edge.id)) {
      const sourceNode = adjustedNodeIndex.get(edge.source);
      const targetNode = adjustedNodeIndex.get(edge.target);
      if (sourceNode?.position && targetNode?.position) {
        return routeCallEdge(
          edge,
          sourceNode,
          targetNode,
          adjustedNodes.filter((node) => node.id !== edge.source && node.id !== edge.target),
          callObstacleSegments,
        );
      }
    }
    if (internalEdgeIds.has(edge.id)) {
      const delta = edgeDeltaById.get(edge.id);
      return delta ? translateEdgeRoute(edge, delta) : edge;
    }
    return edge;
  });

  return {
    nodes: adjustedNodes,
    edges: adjustedEdges,
  };
}

/**
 * 流程图视图布局主入口：先做一次预布局以统计汇聚节点端口需求，再正式布局并锁定每条边的端口；
 * 之后重新投射决策节点的连接锚点，最后把调用展开的子图搬到右侧泳道完成最终输出。
 */
export async function layoutFlowchartView({
  nodes,
  edges,
  anchorNodeId,
  sizeSnapshot,
}: MeasuredLayoutRequest) {
  const nodeIndex = new Map(nodes.map((node) => [node.id, node]));
  const outgoingControlFlowBySource = buildOutgoingControlFlowIndex(edges);
  const incomingControlFlowByTarget = buildIncomingControlFlowIndex(edges);
  const nodeSizeIndex = new Map<string, { width: number; height: number }>();
  const initialLayoutNodes = buildLayoutNodes(
    nodes,
    anchorNodeId,
    sizeSnapshot,
    nodeSizeIndex,
    outgoingControlFlowBySource,
    new Map(),
  );
  const initialLayout = await executeElkLayout({
    mode: "FLOWCHART",
    layoutOptions: FLOWCHART_LAYOUT_OPTIONS,
    nodes: initialLayoutNodes,
    edges: edges.map((edge) => ({ edge })),
  });
  const initialNodeIndex = new Map(initialLayout.nodes.map((node) => [node.id, node]));
  const mergeTargetPortLayout = buildMergeTargetPortLayout(
    edges,
    initialNodeIndex,
    outgoingControlFlowBySource,
    incomingControlFlowByTarget,
  );
  const layoutNodes = buildLayoutNodes(
    nodes,
    anchorNodeId,
    sizeSnapshot,
    nodeSizeIndex,
    outgoingControlFlowBySource,
    mergeTargetPortLayout.countsByNodeId,
  );
  const laidOut = await executeElkLayout({
    mode: "FLOWCHART",
    layoutOptions: FLOWCHART_LAYOUT_OPTIONS,
    nodes: layoutNodes,
    edges: edges.map((edge) => {
      const sourceNode = initialNodeIndex.get(edge.source);
      const targetNode = initialNodeIndex.get(edge.target);
      const outgoingEdges = outgoingControlFlowBySource.get(edge.source);
      return {
        edge,
        sourcePort: resolveSourcePort(
          edge,
          flowchartKind(nodeIndex.get(edge.source)),
          sourceNode,
          targetNode,
          outgoingEdges,
          nodeIndex,
        ),
        targetPort: resolveTargetPort(
          edge,
          flowchartKind(nodeIndex.get(edge.target)),
          sourceNode,
          targetNode,
          outgoingEdges,
          nodeIndex,
          mergeTargetPortLayout.targetHandleByEdgeId.get(edge.id),
        ),
        layoutOptions: flowEdgeLayoutOptions(edge, nodeIndex),
      };
    }),
  });
  const laidOutNodeIndex = new Map(laidOut.nodes.map((node) => [node.id, node]));

  const projectedEdges = laidOut.edges.map((edge) => {
      const sourceNode = laidOutNodeIndex.get(edge.source);
      const targetNode = laidOutNodeIndex.get(edge.target);
      const withProjectedSource = projectDecisionAttachmentPoint(
        edge,
        sourceNode,
        targetNode,
        nodeSizeIndex.get(edge.source),
        outgoingControlFlowBySource,
        nodeIndex,
      );
      return projectDecisionAttachmentPoint(
        withProjectedSource,
        targetNode,
        sourceNode,
        nodeSizeIndex.get(edge.target),
        outgoingControlFlowBySource,
        nodeIndex,
      );
    });
  return applyInvocationExpansionLayout(laidOut.nodes, projectedEdges);
}
