import type { NodeMeasuredSize } from "../../graph/nodeSizeRegistry";
import { traceLinkGraph } from "../../debug";
import type { GraphPosition, LinkGraphEdge, LinkGraphNode } from "../../types";
import {
  buildOrthogonalEdgeRoute,
  type OrthogonalRect,
} from "../../reactflow/orthogonalEdgeRouting";
import {
  ANCHOR_ROUTE_SOURCE_MARGIN_X,
  ANCHOR_ROUTE_TARGET_MARGIN_X,
  EDGE_CROSSING_REPAIR_CHANNEL_GAP,
  EDGE_CROSSING_REPAIR_LOCALITY_IMPROVEMENT,
  EDGE_CROSSING_REPAIR_MAX_PASSES,
  EDGE_CROSSING_REPAIR_NODE_CLEARANCE,
  LEFT_PORT,
  RIGHT_PORT,
  isHorizontalPort,
  laneOf,
  nodeBounds,
  orthogonalRectForNode,
  routeFromPoints,
  routePoints,
  routeSectionPoints,
  simplifyRoutePoints,
  sourceSideForPort,
  targetSideForPort,
  type AxisInterval,
  type ClassRouteContext,
} from "./classDiagramLayoutModel";
import {
  classDiagramRelationKind,
  isClassDiagramHierarchyRelation,
} from "./classDiagramRelations";
import { ClassDiagramReadabilityScorer } from "./classDiagramReadability";

/** 判断是否为"走廊绑定"的主关系（ANCHOR<->OUTGOING/INCOMING，且非层级关系），这类边不允许在修复阶段被改写。 */
function isCorridorBoundMainRelation(
  edge: LinkGraphEdge,
  source: LinkGraphNode,
  target: LinkGraphNode,
): boolean {
  if (isClassDiagramHierarchyRelation(edge)) {
    return false;
  }
  return (laneOf(source) === "ANCHOR" && laneOf(target) === "OUTGOING")
    || (laneOf(source) === "INCOMING" && laneOf(target) === "ANCHOR");
}

/** 把路径点序列归一化为字符串 key，用于在修复候选集中去重。 */
function routeCandidateKey(points: GraphPosition[]): string {
  return simplifyRoutePoints(points).map((point) => `${point.x},${point.y}`).join("|");
}

/**
 * 把一条候选路径规范化后追加到候选列表：会丢弃退化（点数不足、非轴对齐）和重复候选。
 * localityPenalty 表示该候选相对当前路径的局部性惩罚，惩罚越低越倾向于保留原路径形态。
 */
function pushRouteCandidate(
  candidates: Array<{ points: GraphPosition[]; localityPenalty: number }>,
  seen: Set<string>,
  points: GraphPosition[],
  localityPenalty: number,
) {
  const simplified = simplifyRoutePoints(points);
  if (simplified.length < 2) {
    return;
  }
  if (!simplified.slice(1).every((point, index) =>
    Math.abs(point.x - simplified[index]!.x) <= 0.5 || Math.abs(point.y - simplified[index]!.y) <= 0.5,
  )) {
    return;
  }
  const key = routeCandidateKey(simplified);
  if (seen.has(key)) {
    return;
  }
  seen.add(key);
  candidates.push({ points: simplified, localityPenalty });
}

/** 从轴候选中挑选至多 limit 个：优先按惩罚最低，再保证覆盖最小/最大值，且彼此至少相差 1 单位。 */
function selectAxisCandidates<T extends { value: number; penalty: number }>(candidates: T[], limit: number): T[] {
  const selected: T[] = [];
  const pushSelected = (candidate: T | undefined) => {
    if (!candidate || selected.some((existing) => Math.abs(existing.value - candidate.value) <= 1)) {
      return;
    }
    selected.push(candidate);
  };
  [...candidates].sort((left, right) => left.penalty - right.penalty)
    .forEach((candidate) => {
      if (selected.length < limit) {
        pushSelected(candidate);
      }
    });
  pushSelected([...candidates].sort((left, right) => left.value - right.value)[0]);
  pushSelected([...candidates].sort((left, right) => right.value - left.value)[0]);
  return selected;
}

/** 把路由上下文中按 lane 分桶的节点打平为单一数组。 */
function routeContextNodes(context: ClassRouteContext): LinkGraphNode[] {
  return Array.from(context.laneNodes.values()).flat();
}

/** 合并一维区间数组：去除空区间、按起点排序后合并相邻或重叠区间。 */
function mergeAxisIntervals(intervals: AxisInterval[]): AxisInterval[] {
  const ordered = intervals
    .filter((interval) => interval.end > interval.start)
    .sort((left, right) => left.start - right.start || left.end - right.end);
  const merged: AxisInterval[] = [];
  ordered.forEach((interval) => {
    const previous = merged[merged.length - 1];
    if (!previous || interval.start > previous.end) {
      merged.push({ ...interval });
      return;
    }
    previous.end = Math.max(previous.end, interval.end);
  });
  return merged;
}

/** 在给定轴上扫描节点之间的"空隙"，返回可作为安全布线通道的坐标列表（含 gap 起止内偏移和中点）。 */
function clearAxisChannels(
  nodes: LinkGraphNode[],
  axis: "x" | "y",
  sizeSnapshot: ReadonlyMap<string, NodeMeasuredSize>,
): number[] {
  const intervals = mergeAxisIntervals(nodes.map((node) => {
    const bounds = {
      left: nodeBounds(node, sizeSnapshot).left - EDGE_CROSSING_REPAIR_NODE_CLEARANCE,
      right: nodeBounds(node, sizeSnapshot).right + EDGE_CROSSING_REPAIR_NODE_CLEARANCE,
      top: nodeBounds(node, sizeSnapshot).top - EDGE_CROSSING_REPAIR_NODE_CLEARANCE,
      bottom: nodeBounds(node, sizeSnapshot).bottom + EDGE_CROSSING_REPAIR_NODE_CLEARANCE,
    };
    return axis === "x"
      ? { start: bounds.left, end: bounds.right }
      : { start: bounds.top, end: bounds.bottom };
  }));
  const requiredGap = EDGE_CROSSING_REPAIR_CHANNEL_GAP + EDGE_CROSSING_REPAIR_NODE_CLEARANCE * 2;
  const channels: number[] = [];
  const addChannel = (value: number) => {
    const rounded = Math.round(value);
    if (!channels.some((channel) => Math.abs(channel - rounded) <= 1)) {
      channels.push(rounded);
    }
  };
  for (let index = 1; index < intervals.length; index += 1) {
    const previous = intervals[index - 1]!;
    const current = intervals[index]!;
    const gap = current.start - previous.end;
    if (gap >= requiredGap) {
      const innerOffset = Math.max(EDGE_CROSSING_REPAIR_CHANNEL_GAP, EDGE_CROSSING_REPAIR_NODE_CLEARANCE * 3);
      addChannel(previous.end + innerOffset);
      addChannel((previous.end + current.start) / 2);
      addChannel(current.start - innerOffset);
    }
  }
  return channels;
}

/** 给候选通道计算惩罚：基础惩罚 + 与当前通道距离的微调，让修复倾向于贴近原通道以减少抖动。 */
function axisChannelPenalty(channel: number, currentChannel: number | undefined, basePenalty: number): number {
  if (currentChannel == null) {
    return basePenalty;
  }
  return basePenalty + Math.min(46, Math.abs(channel - currentChannel) * 0.02);
}

/** 枚举单条边的水平折点 X 候选：当前通道、走廊内通道、lane 内/外、各种外侧回绕位置，各自附带惩罚分。 */
function candidateXsForRoute(
  source: LinkGraphNode,
  target: LinkGraphNode,
  currentPoints: GraphPosition[],
  context: ClassRouteContext,
  sizeSnapshot: ReadonlyMap<string, NodeMeasuredSize>,
  readability: ClassDiagramReadabilityScorer,
): Array<{ value: number; penalty: number }> {
  const sourceBounds = nodeBounds(source, sizeSnapshot);
  const targetBounds = nodeBounds(target, sizeSnapshot);
  const sourceLane = laneOf(source);
  const targetLane = laneOf(target);
  const laneNodes = [
    ...(context.laneNodes.get(laneOf(source)) ?? []),
    ...(context.laneNodes.get(laneOf(target)) ?? []),
  ];
  const localBounds = readability.classDiagramBounds(laneNodes, sizeSnapshot) ?? {
    left: Math.min(sourceBounds.left, targetBounds.left),
    right: Math.max(sourceBounds.right, targetBounds.right),
    top: Math.min(sourceBounds.top, targetBounds.top),
    bottom: Math.max(sourceBounds.bottom, targetBounds.bottom),
  };
  const allBounds = readability.classDiagramBounds(Array.from(context.laneNodes.values()).flat(), sizeSnapshot) ?? localBounds;
  const currentVerticalX = currentPoints.slice(1).find((point, index) =>
    Math.abs(point.x - currentPoints[index]!.x) <= 0.5 && Math.abs(point.y - currentPoints[index]!.y) > 0.5,
  )?.x;
  const betweenMin = Math.min(sourceBounds.right, targetBounds.right) + 44;
  const betweenMax = Math.max(sourceBounds.left, targetBounds.left) - 44;
  const values: Array<{ value: number; penalty: number }> = [];
  if (currentVerticalX != null) {
    values.push({ value: currentVerticalX, penalty: 0 });
  }
  if (
    (sourceLane === "ANCHOR" && targetLane === "OUTGOING")
    || (sourceLane === "INCOMING" && targetLane === "ANCHOR")
  ) {
    const corridorMin = sourceBounds.right + ANCHOR_ROUTE_SOURCE_MARGIN_X;
    const corridorMax = targetBounds.left - ANCHOR_ROUTE_TARGET_MARGIN_X;
    if (corridorMin <= corridorMax) {
      clearAxisChannels(routeContextNodes(context), "x", sizeSnapshot)
        .filter((value) => value >= corridorMin && value <= corridorMax)
        .forEach((value) => {
          values.push({ value, penalty: axisChannelPenalty(value, currentVerticalX, 3) });
        });
      values.push(
        { value: Math.round((corridorMin + corridorMax) / 2), penalty: 2 },
        { value: Math.round(corridorMin + (corridorMax - corridorMin) * 0.33), penalty: 8 },
        { value: Math.round(corridorMin + (corridorMax - corridorMin) * 0.67), penalty: 8 },
      );
    }
    return values;
  }
  clearAxisChannels(routeContextNodes(context), "x", sizeSnapshot).forEach((value) => {
    values.push({ value, penalty: axisChannelPenalty(value, currentVerticalX, 34) });
  });
  values.push(
    { value: Math.round((sourceBounds.right + targetBounds.left) / 2), penalty: 10 },
    { value: Math.round((sourceBounds.left + targetBounds.right) / 2), penalty: 10 },
    { value: localBounds.right + EDGE_CROSSING_REPAIR_NODE_CLEARANCE + 10, penalty: 70 },
    { value: localBounds.left - EDGE_CROSSING_REPAIR_NODE_CLEARANCE - 10, penalty: 70 },
    { value: localBounds.right + 48, penalty: 80 },
    { value: localBounds.left - 48, penalty: 80 },
    { value: localBounds.right + 94, penalty: 110 },
    { value: localBounds.left - 94, penalty: 110 },
    { value: allBounds.right + 72, penalty: 180 },
    { value: allBounds.left - 72, penalty: 180 },
  );
  if (betweenMin <= betweenMax) {
    values.push({ value: Math.round((betweenMin + betweenMax) / 2), penalty: 4 });
  }
  return values;
}

/** 枚举单条边的水平段 Y 候选：当前 Y、节点间空隙通道、lane 上下回绕位置，各自附带惩罚分。 */
function candidateYsForRoute(
  source: LinkGraphNode,
  target: LinkGraphNode,
  currentPoints: GraphPosition[],
  context: ClassRouteContext,
  sizeSnapshot: ReadonlyMap<string, NodeMeasuredSize>,
  readability: ClassDiagramReadabilityScorer,
): Array<{ value: number; penalty: number }> {
  const sourceBounds = nodeBounds(source, sizeSnapshot);
  const targetBounds = nodeBounds(target, sizeSnapshot);
  const laneNodes = [
    ...(context.laneNodes.get(laneOf(source)) ?? []),
    ...(context.laneNodes.get(laneOf(target)) ?? []),
  ];
  const localBounds = readability.classDiagramBounds(laneNodes, sizeSnapshot) ?? {
    left: Math.min(sourceBounds.left, targetBounds.left),
    right: Math.max(sourceBounds.right, targetBounds.right),
    top: Math.min(sourceBounds.top, targetBounds.top),
    bottom: Math.max(sourceBounds.bottom, targetBounds.bottom),
  };
  const allBounds = readability.classDiagramBounds(Array.from(context.laneNodes.values()).flat(), sizeSnapshot) ?? localBounds;
  const currentHorizontalY = currentPoints.slice(1).find((point, index) =>
    Math.abs(point.y - currentPoints[index]!.y) <= 0.5 && Math.abs(point.x - currentPoints[index]!.x) > 0.5,
  )?.y;
  const betweenMin = Math.min(sourceBounds.bottom, targetBounds.bottom) + 42;
  const betweenMax = Math.max(sourceBounds.top, targetBounds.top) - 42;
  const values: Array<{ value: number; penalty: number }> = [];
  if (currentHorizontalY != null) {
    values.push({ value: currentHorizontalY, penalty: 0 });
  }
  clearAxisChannels(routeContextNodes(context), "y", sizeSnapshot).forEach((value) => {
    values.push({ value, penalty: axisChannelPenalty(value, currentHorizontalY, 34) });
  });
  values.push(
    { value: Math.round((sourceBounds.bottom + targetBounds.top) / 2), penalty: 10 },
    { value: Math.round((sourceBounds.top + targetBounds.bottom) / 2), penalty: 10 },
    { value: localBounds.top - 46, penalty: 80 },
    { value: localBounds.bottom + 46, penalty: 80 },
    { value: localBounds.top - 92, penalty: 120 },
    { value: localBounds.bottom + 92, penalty: 120 },
    { value: allBounds.top - 72, penalty: 190 },
    { value: allBounds.bottom + 72, penalty: 190 },
  );
  if (betweenMin <= betweenMax) {
    values.push({ value: Math.round((betweenMin + betweenMax) / 2), penalty: 4 });
  }
  return values;
}

/**
 * 为单条边生成所有交叉修复候选：按端口方向（双横/双竖/横竖混合）组合 X/Y 候选，
 * 构造从简单两段折线到复杂"双 X 双 Y"绕行的多种形态供评分择优。
 */
function crossingRepairCandidates(
  edge: LinkGraphEdge,
  nodeIndex: Map<string, LinkGraphNode>,
  context: ClassRouteContext,
  sizeSnapshot: ReadonlyMap<string, NodeMeasuredSize>,
  readability: ClassDiagramReadabilityScorer,
): Array<{ points: GraphPosition[]; localityPenalty: number }> {
  const source = nodeIndex.get(edge.source);
  const target = nodeIndex.get(edge.target);
  const currentPoints = routePoints(edge);
  if (!source || !target || currentPoints.length < 2) {
    return [];
  }
  const sourcePoint = currentPoints[0]!;
  const targetPoint = currentPoints[currentPoints.length - 1]!;
  const sourcePort = edge.sourceHandle ?? edge.metadata?.["layout.sourcePort"] ?? RIGHT_PORT;
  const targetPort = edge.targetHandle ?? edge.metadata?.["layout.targetPort"] ?? LEFT_PORT;
  const sourceHorizontal = isHorizontalPort(sourcePort);
  const targetHorizontal = isHorizontalPort(targetPort);
  const corridorBoundRelation = (laneOf(source) === "ANCHOR" && laneOf(target) === "OUTGOING")
    || (laneOf(source) === "INCOMING" && laneOf(target) === "ANCHOR");
  const candidates: Array<{ points: GraphPosition[]; localityPenalty: number }> = [];
  const seen = new Set<string>();
  pushRouteCandidate(candidates, seen, currentPoints, 0);

  if (sourceHorizontal && targetHorizontal) {
    const xCandidates = candidateXsForRoute(source, target, currentPoints, context, sizeSnapshot, readability);
    xCandidates.forEach(({ value, penalty }) => {
      pushRouteCandidate(candidates, seen, [sourcePoint, { x: value, y: sourcePoint.y }, { x: value, y: targetPoint.y }, targetPoint], penalty);
    });
    if (corridorBoundRelation) {
      return candidates;
    }
    const yCandidates = candidateYsForRoute(source, target, currentPoints, context, sizeSnapshot, readability)
      .filter((candidate) => candidate.penalty >= 70)
      .slice(0, 6);
    xCandidates.forEach(({ value, penalty }) => {
      yCandidates.forEach(({ value: y, penalty: yPenalty }) => {
        pushRouteCandidate(
          candidates,
          seen,
          [sourcePoint, { x: value, y: sourcePoint.y }, { x: value, y }, { x: targetPoint.x, y }, targetPoint],
          penalty + yPenalty + 28,
        );
      });
    });
    const escapeXs = selectAxisCandidates(xCandidates, 8);
    const escapeYs = selectAxisCandidates(yCandidates, 6);
    escapeXs.forEach(({ value: sourceX, penalty: sourceXPenalty }) => {
      escapeXs.forEach(({ value: targetX, penalty: targetXPenalty }) => {
        if (Math.abs(sourceX - targetX) <= 1) {
          return;
        }
        escapeYs.forEach(({ value: sourceY, penalty: sourceYPenalty }) => {
          escapeYs.forEach(({ value: targetY, penalty: targetYPenalty }) => {
            if (Math.abs(sourceY - targetY) <= 1) {
              return;
            }
            pushRouteCandidate(
              candidates,
              seen,
              [
                sourcePoint,
                { x: sourceX, y: sourcePoint.y },
                { x: sourceX, y: sourceY },
                { x: targetX, y: sourceY },
                { x: targetX, y: targetY },
                { x: targetPoint.x, y: targetY },
                targetPoint,
              ],
              sourceXPenalty + targetXPenalty + sourceYPenalty + targetYPenalty + 72,
            );
          });
        });
      });
    });
    return candidates;
  }

  if (!sourceHorizontal && !targetHorizontal) {
    candidateYsForRoute(source, target, currentPoints, context, sizeSnapshot, readability).forEach(({ value, penalty }) => {
      pushRouteCandidate(candidates, seen, [sourcePoint, { x: sourcePoint.x, y: value }, { x: targetPoint.x, y: value }, targetPoint], penalty);
    });
    return candidates;
  }

  const xCandidates = candidateXsForRoute(source, target, currentPoints, context, sizeSnapshot, readability).slice(0, 6);
  const yCandidates = candidateYsForRoute(source, target, currentPoints, context, sizeSnapshot, readability).slice(0, 6);
  xCandidates.forEach(({ value: x, penalty: xPenalty }) => {
    yCandidates.forEach(({ value: y, penalty: yPenalty }) => {
      if (sourceHorizontal) {
        pushRouteCandidate(candidates, seen, [sourcePoint, { x, y: sourcePoint.y }, { x, y }, { x: targetPoint.x, y }, targetPoint], xPenalty + yPenalty + 24);
      } else {
        pushRouteCandidate(candidates, seen, [sourcePoint, { x: sourcePoint.x, y }, { x, y }, { x, y: targetPoint.y }, targetPoint], xPenalty + yPenalty + 24);
      }
    });
  });
  return candidates;
}

/** 把一条边的一段折线转成极薄的 OrthogonalRect（垂直段宽 2 / 水平段高 2），作为障碍矩形参与避障布线。 */
function routeSegmentObstacleRect(edge: LinkGraphEdge, segmentIndex: number, start: GraphPosition, end: GraphPosition): OrthogonalRect {
  const left = Math.min(start.x, end.x);
  const right = Math.max(start.x, end.x);
  const top = Math.min(start.y, end.y);
  const bottom = Math.max(start.y, end.y);
  if (Math.abs(start.x - end.x) <= 0.5) {
    return {
      id: `edge-segment:${edge.id}:${segmentIndex}`,
      x: start.x - 1,
      y: top,
      width: 2,
      height: Math.max(bottom - top, 2),
    };
  }
  return {
    id: `edge-segment:${edge.id}:${segmentIndex}`,
    x: left,
    y: start.y - 1,
    width: Math.max(right - left, 2),
    height: 2,
  };
}

/** 汇总除当前边外所有边的所有线段为障碍矩形集合，供避障算法把已有边当作屏障。 */
function edgeSegmentObstacleRects(edges: LinkGraphEdge[], routedEdgeId: string): OrthogonalRect[] {
  return edges
    .filter((edge) => edge.id !== routedEdgeId)
    .flatMap((edge) => {
      const points = routePoints(edge);
      return points.slice(1).map((point, index) =>
        routeSegmentObstacleRect(edge, index, points[index]!, point),
      );
    });
}

/**
 * 借助通用正交避障算法为单条边产出"绕开所有节点+其它边"的候选路径，
 * 作为组合式候选之外的全局兜底方案，惩罚较高以优先选择更紧凑的候选。
 */
function graphAvoidingRepairCandidates(
  edge: LinkGraphEdge,
  edges: LinkGraphEdge[],
  nodeIndex: Map<string, LinkGraphNode>,
  context: ClassRouteContext,
  sizeSnapshot: ReadonlyMap<string, NodeMeasuredSize>,
): Array<{ points: GraphPosition[]; localityPenalty: number }> {
  const source = nodeIndex.get(edge.source);
  const target = nodeIndex.get(edge.target);
  const currentPoints = routePoints(edge);
  if (!source || !target || currentPoints.length < 2) {
    return [];
  }
  const sourcePoint = currentPoints[0]!;
  const targetPoint = currentPoints[currentPoints.length - 1]!;
  const sourcePort = edge.sourceHandle ?? edge.metadata?.["layout.sourcePort"] ?? RIGHT_PORT;
  const targetPort = edge.targetHandle ?? edge.metadata?.["layout.targetPort"] ?? LEFT_PORT;
  const route = buildOrthogonalEdgeRoute({
    startPoint: sourcePoint,
    startSide: sourceSideForPort(sourcePort),
    startRect: orthogonalRectForNode(source, sizeSnapshot),
    endPoint: targetPoint,
    endSide: targetSideForPort(targetPort),
    endRect: orthogonalRectForNode(target, sizeSnapshot),
    obstacleRects: [
      ...context.obstacleRects,
      ...edgeSegmentObstacleRects(edges, edge.id),
    ],
  });
  const candidates: Array<{ points: GraphPosition[]; localityPenalty: number }> = [];
  const seen = new Set<string>();
  pushRouteCandidate(candidates, seen, routeSectionPoints(route), 120);
  return candidates;
}

/** 把交叉修复候选和全局避障候选合并去重，作为某条边在当前图上下文中可用的全部修复方案。 */
function routeRepairCandidates(
  edge: LinkGraphEdge,
  edges: LinkGraphEdge[],
  nodeIndex: Map<string, LinkGraphNode>,
  context: ClassRouteContext,
  sizeSnapshot: ReadonlyMap<string, NodeMeasuredSize>,
  readability: ClassDiagramReadabilityScorer,
): Array<{ points: GraphPosition[]; localityPenalty: number }> {
  const candidates: Array<{ points: GraphPosition[]; localityPenalty: number }> = [];
  const seen = new Set<string>();
  [
    ...crossingRepairCandidates(edge, nodeIndex, context, sizeSnapshot, readability),
    ...graphAvoidingRepairCandidates(edge, edges, nodeIndex, context, sizeSnapshot),
  ].forEach((candidate) => pushRouteCandidate(candidates, seen, candidate.points, candidate.localityPenalty));
  return candidates;
}

/**
 * 在已经完成交叉修复的边集合上做第二轮局部优化：仅针对被修复过的边，尝试找到比当前评分
 * 明显更好（达到 LOCALITY_IMPROVEMENT 阈值）的候选，提升整体可读性而不引入新的交叉。
 */
function improveRepairedRouteLocality(
  edges: LinkGraphEdge[],
  nodeIndex: Map<string, LinkGraphNode>,
  context: ClassRouteContext,
  sizeSnapshot: ReadonlyMap<string, NodeMeasuredSize>,
  readability: ClassDiagramReadabilityScorer,
): LinkGraphEdge[] {
  let improved = edges;
  for (const edge of edges) {
    if (edge.metadata?.["layout.edgeCrossingRepair"] !== "true") {
      continue;
    }
    const current = improved.find((candidate) => candidate.id === edge.id);
    if (!current) {
      continue;
    }
    const currentPoints = routePoints(current);
    const currentScore = readability.scoreRouteAgainstGraph(currentPoints, current, improved, nodeIndex, sizeSnapshot, 0);
    const best = routeRepairCandidates(current, improved, nodeIndex, context, sizeSnapshot, readability)
      .map((candidate) => ({
        ...candidate,
        score: readability.scoreRouteAgainstGraph(candidate.points, current, improved, nodeIndex, sizeSnapshot, candidate.localityPenalty),
      }))
      .sort((left, right) => left.score - right.score)[0];
    if (!best || best.score >= currentScore - EDGE_CROSSING_REPAIR_LOCALITY_IMPROVEMENT) {
      continue;
    }
    improved = improved.map((candidate) => candidate.id === current.id
      ? {
          ...candidate,
          route: routeFromPoints(best.points),
          metadata: {
            ...(candidate.metadata ?? {}),
            "layout.routeLocalityRepair": "true",
          },
        }
      : candidate);
  }
  return improved;
}

/**
 * 边交叉修复主入口：若初始就有交叉/重叠/节点穿越，则按"压力从高到低"逐条尝试用候选路径替换，
 * 多趟迭代直到无交叉或达到最大次数；若最终清零则再触发局部性优化，并通过 trace 输出统计。
 */
export function repairEdgeRouteCrossings(
  edges: LinkGraphEdge[],
  nodeIndex: Map<string, LinkGraphNode>,
  context: ClassRouteContext,
  sizeSnapshot: ReadonlyMap<string, NodeMeasuredSize>,
  readability: ClassDiagramReadabilityScorer,
): LinkGraphEdge[] {
  // 用 Map<edgeId, edge> 作为可变状态：每次更新单条 edge 时 O(1) 写入，避免 O(E) 全表扫描。
  // 与 readability scorer 交互时再 `Array.from(repairedById.values())` 取回数组形式。
  const repairedById = new Map<string, LinkGraphEdge>(edges.map((edge) => [edge.id, edge]));
  let repaired = edges;
  const initialReport = readability.graphRouteRepairReport(repaired, nodeIndex, sizeSnapshot);
  if (initialReport.crossingCount + initialReport.overlapCount + initialReport.nodeCrossingCount === 0) {
    return repaired;
  }
  for (let pass = 0; pass < EDGE_CROSSING_REPAIR_MAX_PASSES; pass += 1) {
    const report = readability.graphRouteRepairReport(repaired, nodeIndex, sizeSnapshot);
    if (report.crossingCount + report.overlapCount + report.nodeCrossingCount === 0) {
      break;
    }
    let changed = false;
    const pressureOrder = [...report.edgePressure.entries()]
      .sort((left, right) => right[1] - left[1] || left[0].localeCompare(right[0]))
      .map(([edgeId]) => edgeId);
    for (const edgeId of pressureOrder) {
      const edge = repaired.find((candidate) => candidate.id === edgeId);
      if (!edge) {
        continue;
      }
      const source = nodeIndex.get(edge.source);
      const target = nodeIndex.get(edge.target);
      if (source && target && isCorridorBoundMainRelation(edge, source, target)) {
        continue;
      }
      const currentPoints = routePoints(edge);
      const currentScore = readability.scoreRouteAgainstGraph(currentPoints, edge, repaired, nodeIndex, sizeSnapshot, 0);
      const candidates = routeRepairCandidates(edge, repaired, nodeIndex, context, sizeSnapshot, readability);
      const best = candidates
        .map((candidate) => ({
          ...candidate,
          score: readability.scoreRouteAgainstGraph(candidate.points, edge, repaired, nodeIndex, sizeSnapshot, candidate.localityPenalty),
        }))
        .sort((left, right) => left.score - right.score)[0];
      if (!best || best.score >= currentScore - 1) {
        continue;
      }
      // 旧实现用 `.map(...)` 在每条 edge 更新时遍历整个 repaired 数组（O(E) per update, O(E²) 全程）。
      // 改为 Map<edgeId, edge> 累积：每次更新只重写一个 entry，最后再转回数组。
      repairedById.set(edge.id, {
        ...(repairedById.get(edge.id) ?? edge),
        route: routeFromPoints(best.points),
        metadata: {
          ...((repairedById.get(edge.id) ?? edge).metadata ?? {}),
          "layout.edgeCrossingRepair": "true",
        },
      });
      repaired = Array.from(repairedById.values());
      changed = true;
    }
    if (!changed) {
      break;
    }
  }
  const repairedReport = readability.graphRouteRepairReport(repaired, nodeIndex, sizeSnapshot);
  const optimized = repairedReport.crossingCount + repairedReport.overlapCount + repairedReport.nodeCrossingCount === 0
    ? improveRepairedRouteLocality(repaired, nodeIndex, context, sizeSnapshot, readability)
    : repaired;
  const finalReport = readability.graphRouteRepairReport(optimized, nodeIndex, sizeSnapshot);
  traceLinkGraph("classDiagramLayout.edgeCrossingRepair.complete", {
    initialCrossings: initialReport.crossingCount,
    initialOverlaps: initialReport.overlapCount,
    initialNodeCrossings: initialReport.nodeCrossingCount,
    finalCrossings: finalReport.crossingCount,
    finalOverlaps: finalReport.overlapCount,
    finalNodeCrossings: finalReport.nodeCrossingCount,
    repairedEdges: optimized.filter((edge) => edge.metadata?.["layout.edgeCrossingRepair"] === "true").length,
    localityRepairedEdges: optimized.filter((edge) => edge.metadata?.["layout.routeLocalityRepair"] === "true").length,
  });
  return optimized;
}

/** 给所有边写入 routeMode=stored 元数据，标记当前路径为"已存储"，渲染时跳过重路由以保持稳定。 */
export function preserveClassDiagramRoutes(edges: LinkGraphEdge[]): LinkGraphEdge[] {
  return edges.map((edge) => ({
    ...edge,
    metadata: {
      ...(edge.metadata ?? {}),
      "layout.routeMode": "stored",
      "layout.labelPlacement": edge.metadata?.["layout.labelPlacement"] ?? "target-stub",
    },
  }));
}

/** 把每条边的路径信息汇总为可读的轨迹摘要（关系、端点、端口、点数、坐标列表），供 trace 工具展示。 */
export function summarizeClassDiagramRoutes(edges: LinkGraphEdge[], nodeIndex: Map<string, LinkGraphNode>) {
  return edges.map((edge) => {
    const source = nodeIndex.get(edge.source);
    const target = nodeIndex.get(edge.target);
    return {
      id: edge.id,
      relation: classDiagramRelationKind(edge),
      source: source?.title ?? edge.source,
      target: target?.title ?? edge.target,
      sourceLane: source ? laneOf(source) : null,
      targetLane: target ? laneOf(target) : null,
      sourceHandle: edge.sourceHandle ?? edge.metadata?.["layout.sourcePort"] ?? null,
      targetHandle: edge.targetHandle ?? edge.metadata?.["layout.targetPort"] ?? null,
      pointCount: routePoints(edge).length,
      points: routePoints(edge).map(roundTracePoint),
    };
  });
}

/** 把坐标四舍五入到 0.1 像素精度，让 trace 输出更紧凑可读。 */
function roundTracePoint(point: GraphPosition): GraphPosition {
  return {
    x: Math.round(point.x * 10) / 10,
    y: Math.round(point.y * 10) / 10,
  };
}

