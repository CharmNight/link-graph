import type { NodeMeasuredSize } from "../../graph/nodeSizeRegistry";
import { traceLinkGraph } from "../../debug";
import type { GraphPosition, LinkGraphEdge, LinkGraphNode } from "../../types";
import {
  ANCHOR_ROUTE_TARGET_MARGIN_X,
  CROSS_LANE_SECONDARY_RAIL_GAP,
  EDGE_CROSSING_REPAIR_CROSSING_PENALTY,
  EDGE_CROSSING_REPAIR_NODE_CLEARANCE,
  EDGE_CROSSING_REPAIR_NODE_PENALTY,
  EDGE_CROSSING_REPAIR_OVERLAP_PENALTY,
  expandBounds,
  isHorizontalPort,
  laneColumn,
  laneOf,
  mergeBounds,
  nodeBounds,
  nodeCenterY,
  nodeLeft,
  nodeRight,
  nodeBottom,
  portBase,
  portSlot,
  routeLength,
  routePoints,
  type ClassNodeBounds,
} from "./classDiagramLayoutModel";
import {
  isClassDiagramHierarchyRelation,
  isClassDiagramRoutedStructuralRelation,
} from "./classDiagramRelations";

/** 类图布局结果：节点与边集合的简单包装，作为可读性评分的输入。 */
export interface ClassDiagramLayoutResult {
  nodes: LinkGraphNode[];
  edges: LinkGraphEdge[];
}

/** 路由修复报告：统计边/边交叉、边/节点穿越以及每条边所受压力，作为修复优先级依据。 */
export interface ClassDiagramRouteRepairReport {
  crossingCount: number;
  overlapCount: number;
  nodeCrossingCount: number;
  edgePressure: Map<string, number>;
}

/** 类图可读性度量指标集合：每一项对应一条启发式规则，决定是否可接受。 */
export interface ClassDiagramReadabilityMetrics {
  nodeCrossings: number;
  edgeCrossings: number;
  edgeOverlaps: number;
  mainRelationOuterBoxCount: number;
  anchorOutgoingRouteWithinCorridor: boolean;
  sameLaneSecondaryRouteWithinRail: boolean;
  crossLaneSecondaryRouteUsesRail: boolean;
  maxLabelDistanceToEndpoint: number;
  labelDistanceToEndpointWithinThreshold: boolean;
  portOrderMatchesTargetOrder: boolean;
}

/** 一次评分的完整报告：是否被接受、被评分的布局、各项度量以及违规列表。 */
export interface ClassDiagramReadabilityReport {
  accepted: boolean;
  acceptedLayout: ClassDiagramLayoutResult;
  metrics: ClassDiagramReadabilityMetrics;
  violations: string[];
}

// 标签到端点的最大允许距离，超过即视为可读性违规
const LABEL_ENDPOINT_DISTANCE_THRESHOLD = 220;
// 同 lane 次级轨道允许越出 lane 边界的最大距离
const SAME_LANE_RAIL_LIMIT = 112;
// 边/边交叉与重叠的可接受阈值（均为零容忍）
const EDGE_CROSSING_THRESHOLD = 0;
const EDGE_OVERLAP_THRESHOLD = 0;

/** 端口顺序检查时每条边的中间记录项：携带 slot、方向与对端节点 Y 中心。 */
interface EndpointOrderItem {
  edge: LinkGraphEdge;
  slot: number | null;
  horizontal: boolean;
  peerCenterY: number | null;
}

/**
 * 类图可读性评分器：综合边交叉、节点穿越、轨道走廊、标签距离、端口顺序等启发式，
 * 对单个候选路径或整张布局给出量化分数与违规报告，引导路由算法选出最易读的方案。
 */
export class ClassDiagramReadabilityScorer {
  /**
   * 在已有图上下文中给单条候选路径打分：综合与其它边的交叉/重叠、节点穿越、跨 lane 轨道惩罚、
   * 路径长度和折点数等多维度，再加上调用方传入的局部性惩罚。
   */
  scoreRouteAgainstGraph(
    points: GraphPosition[],
    edge: LinkGraphEdge,
    edges: LinkGraphEdge[],
    nodeIndex: Map<string, LinkGraphNode>,
    sizeSnapshot: ReadonlyMap<string, NodeMeasuredSize>,
    localityPenalty: number,
  ): number {
    let crossings = 0;
    let overlaps = 0;
    edges.forEach((other) => {
      if (other.id === edge.id) {
        return;
      }
      const stats = this.routeCrossingStats(points, routePoints(other));
      crossings += stats.crossings;
      overlaps += stats.overlaps;
    });
    const nodeCrossings = this.routeNodeCrossingCount(points, edge, nodeIndex, sizeSnapshot);
    return nodeCrossings * EDGE_CROSSING_REPAIR_NODE_PENALTY
      + this.crossLaneSecondaryRailPenalty(points, edge, nodeIndex, sizeSnapshot)
      + crossings * EDGE_CROSSING_REPAIR_CROSSING_PENALTY
      + overlaps * EDGE_CROSSING_REPAIR_OVERLAP_PENALTY
      + routeLength(points) * 0.08
      + Math.max(0, points.length - 2) * 18
      + localityPenalty;
  }

  /**
   * 整张图的可读性诊断报告：成对比较所有边统计交叉/重叠，再统计每条边穿过的节点数，
   * 形成 edgePressure 用于驱动后续修复器按压力高低排序处理。
   */
  graphRouteRepairReport(
    edges: LinkGraphEdge[],
    nodeIndex: Map<string, LinkGraphNode>,
    sizeSnapshot: ReadonlyMap<string, NodeMeasuredSize>,
  ): ClassDiagramRouteRepairReport {
    let crossingCount = 0;
    let overlapCount = 0;
    let nodeCrossingCount = 0;
    const edgePressure = new Map<string, number>();
    for (let leftIndex = 0; leftIndex < edges.length; leftIndex += 1) {
      const left = edges[leftIndex]!;
      const leftPoints = routePoints(left);
      for (let rightIndex = leftIndex + 1; rightIndex < edges.length; rightIndex += 1) {
        const right = edges[rightIndex]!;
        const rightPoints = routePoints(right);
        const stats = this.routeCrossingStats(leftPoints, rightPoints);
        const pressure = stats.crossings + stats.overlaps;
        if (pressure <= 0) {
          continue;
        }
        crossingCount += stats.crossings;
        overlapCount += stats.overlaps;
        edgePressure.set(left.id, (edgePressure.get(left.id) ?? 0) + pressure);
        edgePressure.set(right.id, (edgePressure.get(right.id) ?? 0) + pressure);
      }
    }
    edges.forEach((edge) => {
      const nodeCrossings = this.routeNodeCrossingCount(routePoints(edge), edge, nodeIndex, sizeSnapshot);
      if (nodeCrossings <= 0) {
        return;
      }
      nodeCrossingCount += nodeCrossings;
      edgePressure.set(edge.id, (edgePressure.get(edge.id) ?? 0) + nodeCrossings * 4);
    });
    return { crossingCount, overlapCount, nodeCrossingCount, edgePressure };
  }

  /**
   * 给整张布局做综合评分并产出报告：先收集底层度量（交叉、轨道、端口顺序、标签距离等），
   * 再通过 violations 列表决定是否接受该布局，并把结果通过 trace 暴露给调试工具。
   */
  scoreClassDiagramReadability(
    layout: ClassDiagramLayoutResult,
    sizeSnapshot: ReadonlyMap<string, NodeMeasuredSize>,
  ): ClassDiagramReadabilityReport {
    const nodeIndex = new Map(layout.nodes.map((node) => [node.id, node]));
    const routeReport = this.graphRouteRepairReport(layout.edges, nodeIndex, sizeSnapshot);
    const mainRelationOuterBoxCount = this.mainRelationOuterBoxCount(layout.edges, nodeIndex, sizeSnapshot);
    const anchorOutgoingRouteWithinCorridor = this.anchorOutgoingRouteWithinCorridor(layout.edges, nodeIndex, sizeSnapshot);
    const sameLaneSecondaryRouteWithinRail = this.sameLaneSecondaryRouteWithinRail(layout.edges, layout.nodes, nodeIndex, sizeSnapshot);
    const crossLaneSecondaryRouteUsesRail = this.crossLaneSecondaryRouteUsesRail(layout.edges, layout.nodes, nodeIndex, sizeSnapshot);
    const maxLabelDistanceToEndpoint = this.maxLabelDistanceToEndpoint(layout.edges);
    const portOrderMatchesTargetOrder = this.portOrderMatchesTargetOrder(layout.edges, nodeIndex, sizeSnapshot);
    const metrics: ClassDiagramReadabilityMetrics = {
      nodeCrossings: routeReport.nodeCrossingCount,
      edgeCrossings: routeReport.crossingCount,
      edgeOverlaps: routeReport.overlapCount,
      mainRelationOuterBoxCount,
      anchorOutgoingRouteWithinCorridor,
      sameLaneSecondaryRouteWithinRail,
      crossLaneSecondaryRouteUsesRail,
      maxLabelDistanceToEndpoint,
      labelDistanceToEndpointWithinThreshold: maxLabelDistanceToEndpoint <= LABEL_ENDPOINT_DISTANCE_THRESHOLD,
      portOrderMatchesTargetOrder,
    };
    const violations = this.violations(metrics);
    const report = {
      accepted: violations.length === 0,
      acceptedLayout: layout,
      metrics,
      violations,
    };
    traceLinkGraph("classDiagramLayout.readability.score", {
      accepted: report.accepted,
      ...metrics,
      violations,
    });
    return report;
  }

  /** 把各项度量转成可读的违规字符串列表：每一项不达标都会追加对应违规标签。 */
  private violations(metrics: ClassDiagramReadabilityMetrics): string[] {
    const violations: string[] = [];
    if (metrics.nodeCrossings !== 0) {
      violations.push("nodeCrossings");
    }
    if (metrics.edgeCrossings > EDGE_CROSSING_THRESHOLD) {
      violations.push("edgeCrossings");
    }
    if (metrics.edgeOverlaps > EDGE_OVERLAP_THRESHOLD) {
      violations.push("edgeOverlaps");
    }
    if (metrics.mainRelationOuterBoxCount !== 0) {
      violations.push("mainRelationOuterBoxCount");
    }
    if (!metrics.anchorOutgoingRouteWithinCorridor) {
      violations.push("anchorOutgoingRouteWithinCorridor");
    }
    if (!metrics.sameLaneSecondaryRouteWithinRail) {
      violations.push("sameLaneSecondaryRouteWithinRail");
    }
    if (!metrics.crossLaneSecondaryRouteUsesRail) {
      violations.push("crossLaneSecondaryRouteUsesRail");
    }
    if (!metrics.labelDistanceToEndpointWithinThreshold) {
      violations.push("labelDistanceToEndpoint");
    }
    if (!metrics.portOrderMatchesTargetOrder) {
      violations.push("portOrderMatchesTargetOrder");
    }
    return violations;
  }

  /** 计算两条折线路径之间的交叉数和共线重叠数，端点重叠不计入交叉。 */
  private routeCrossingStats(
    leftPoints: GraphPosition[],
    rightPoints: GraphPosition[],
  ): { crossings: number; overlaps: number } {
    let crossings = 0;
    let overlaps = 0;
    for (let leftIndex = 1; leftIndex < leftPoints.length; leftIndex += 1) {
      const leftStart = leftPoints[leftIndex - 1]!;
      const leftEnd = leftPoints[leftIndex]!;
      for (let rightIndex = 1; rightIndex < rightPoints.length; rightIndex += 1) {
        const rightStart = rightPoints[rightIndex - 1]!;
        const rightEnd = rightPoints[rightIndex]!;
        const intersection = this.orthogonalSegmentIntersection(leftStart, leftEnd, rightStart, rightEnd);
        if (
          intersection
          && !this.routeEndpoint(intersection, leftPoints)
          && !this.routeEndpoint(intersection, rightPoints)
        ) {
          crossings += 1;
        }
        overlaps += this.collinearOverlapScore(leftStart, leftEnd, rightStart, rightEnd);
      }
    }
    return { crossings, overlaps };
  }

  /** 统计路径在端点节点之外穿过了多少个节点（按带 clearance 的边界检测）。 */
  private routeNodeCrossingCount(
    points: GraphPosition[],
    edge: LinkGraphEdge,
    nodeIndex: Map<string, LinkGraphNode>,
    sizeSnapshot: ReadonlyMap<string, NodeMeasuredSize>,
  ): number {
    let crossings = 0;
    nodeIndex.forEach((node) => {
      if (node.id === edge.source || node.id === edge.target) {
        return;
      }
      const bounds = expandBounds(nodeBounds(node, sizeSnapshot), EDGE_CROSSING_REPAIR_NODE_CLEARANCE);
      if (points.slice(1).some((point, index) => this.segmentCrossesNodeBounds(points[index]!, point, bounds))) {
        crossings += 1;
      }
    });
    return crossings;
  }

  /** 判断点是否落在由 start/end 构成的轴对齐线段上（含 0.5 像素容差）。 */
  private pointOnSegment(point: GraphPosition, start: GraphPosition, end: GraphPosition): boolean {
    return point.x >= Math.min(start.x, end.x) - 0.5
      && point.x <= Math.max(start.x, end.x) + 0.5
      && point.y >= Math.min(start.y, end.y) - 0.5
      && point.y <= Math.max(start.y, end.y) + 0.5;
  }

  /** 判断点是否等于路径起点或终点（含 0.5 像素容差），用于在交叉计数中过滤共享端点。 */
  private routeEndpoint(point: GraphPosition, points: GraphPosition[]): boolean {
    const start = points[0];
    const end = points[points.length - 1];
    return (!!start && Math.abs(point.x - start.x) <= 0.5 && Math.abs(point.y - start.y) <= 0.5)
      || (!!end && Math.abs(point.x - end.x) <= 0.5 && Math.abs(point.y - end.y) <= 0.5);
  }

  /** 求两条轴对齐线段的交点：仅在一横一竖的情况下计算并校验交点同时落在两段内。 */
  private orthogonalSegmentIntersection(
    leftStart: GraphPosition,
    leftEnd: GraphPosition,
    rightStart: GraphPosition,
    rightEnd: GraphPosition,
  ): GraphPosition | null {
    const leftVertical = Math.abs(leftStart.x - leftEnd.x) <= 0.5;
    const leftHorizontal = Math.abs(leftStart.y - leftEnd.y) <= 0.5;
    const rightVertical = Math.abs(rightStart.x - rightEnd.x) <= 0.5;
    const rightHorizontal = Math.abs(rightStart.y - rightEnd.y) <= 0.5;
    if (leftVertical && rightHorizontal) {
      const point = { x: leftStart.x, y: rightStart.y };
      return this.pointOnSegment(point, leftStart, leftEnd) && this.pointOnSegment(point, rightStart, rightEnd) ? point : null;
    }
    if (leftHorizontal && rightVertical) {
      const point = { x: rightStart.x, y: leftStart.y };
      return this.pointOnSegment(point, leftStart, leftEnd) && this.pointOnSegment(point, rightStart, rightEnd) ? point : null;
    }
    return null;
  }

  /** 共线重叠计分：两条同方向且共线的线段重叠长度超过阈值时返回 1，否则返回 0。 */
  private collinearOverlapScore(
    leftStart: GraphPosition,
    leftEnd: GraphPosition,
    rightStart: GraphPosition,
    rightEnd: GraphPosition,
  ): number {
    const leftVertical = Math.abs(leftStart.x - leftEnd.x) <= 0.5;
    const rightVertical = Math.abs(rightStart.x - rightEnd.x) <= 0.5;
    if (leftVertical && rightVertical && Math.abs(leftStart.x - rightStart.x) <= 0.5) {
      const overlap = Math.min(Math.max(leftStart.y, leftEnd.y), Math.max(rightStart.y, rightEnd.y))
        - Math.max(Math.min(leftStart.y, leftEnd.y), Math.min(rightStart.y, rightEnd.y));
      return overlap > 8 ? 1 : 0;
    }
    const leftHorizontal = Math.abs(leftStart.y - leftEnd.y) <= 0.5;
    const rightHorizontal = Math.abs(rightStart.y - rightEnd.y) <= 0.5;
    if (leftHorizontal && rightHorizontal && Math.abs(leftStart.y - rightStart.y) <= 0.5) {
      const overlap = Math.min(Math.max(leftStart.x, leftEnd.x), Math.max(rightStart.x, rightEnd.x))
        - Math.max(Math.min(leftStart.x, leftEnd.x), Math.min(rightStart.x, rightEnd.x));
      return overlap > 8 ? 1 : 0;
    }
    return 0;
  }

  /** 判断一条轴对齐线段是否真正穿过给定节点边界（仅擦边不算穿过）。 */
  private segmentCrossesNodeBounds(
    startPoint: GraphPosition,
    endPoint: GraphPosition,
    bounds: ClassNodeBounds,
  ): boolean {
    if (Math.abs(startPoint.x - endPoint.x) <= 0.5) {
      const x = startPoint.x;
      if (x <= bounds.left + 0.5 || x >= bounds.right - 0.5) {
        return false;
      }
      const top = Math.min(startPoint.y, endPoint.y);
      const bottom = Math.max(startPoint.y, endPoint.y);
      return Math.max(top, bounds.top) < Math.min(bottom, bounds.bottom);
    }
    if (Math.abs(startPoint.y - endPoint.y) <= 0.5) {
      const y = startPoint.y;
      if (y <= bounds.top + 0.5 || y >= bounds.bottom - 0.5) {
        return false;
      }
      const left = Math.min(startPoint.x, endPoint.x);
      const right = Math.max(startPoint.x, endPoint.x);
      return Math.max(left, bounds.left) < Math.min(right, bounds.right);
    }
    return true;
  }

  /** 统计主关系（ANCHOR<->OUTGOING/INCOMING）未走水平走廊的边数，作为可读性违规度量。 */
  private mainRelationOuterBoxCount(
    edges: LinkGraphEdge[],
    nodeIndex: Map<string, LinkGraphNode>,
    sizeSnapshot: ReadonlyMap<string, NodeMeasuredSize>,
  ): number {
    return edges.filter((edge) => {
      const source = nodeIndex.get(edge.source);
      const target = nodeIndex.get(edge.target);
      if (!source || !target || isClassDiagramHierarchyRelation(edge)) {
        return false;
      }
      const sourceLane = laneOf(source);
      const targetLane = laneOf(target);
      if (!((sourceLane === "ANCHOR" && targetLane === "OUTGOING") || (sourceLane === "INCOMING" && targetLane === "ANCHOR"))) {
        return false;
      }
      return !this.routeWithinHorizontalCorridor(edge, source, target, sizeSnapshot);
    }).length;
  }

  /** 判断所有 ANCHOR->OUTGOING 边是否都落在指定的水平走廊内。 */
  private anchorOutgoingRouteWithinCorridor(
    edges: LinkGraphEdge[],
    nodeIndex: Map<string, LinkGraphNode>,
    sizeSnapshot: ReadonlyMap<string, NodeMeasuredSize>,
  ): boolean {
    return edges.every((edge) => {
      const source = nodeIndex.get(edge.source);
      const target = nodeIndex.get(edge.target);
      if (!source || !target || isClassDiagramHierarchyRelation(edge)) {
        return true;
      }
      if (laneOf(source) !== "ANCHOR" || laneOf(target) !== "OUTGOING") {
        return true;
      }
      return this.routeWithinHorizontalCorridor(edge, source, target, sizeSnapshot);
    });
  }

  /** 判断单条边的所有路径点是否都落在 source/target 右边界与对端左边界+边距构成的走廊中。 */
  private routeWithinHorizontalCorridor(
    edge: LinkGraphEdge,
    source: LinkGraphNode,
    target: LinkGraphNode,
    sizeSnapshot: ReadonlyMap<string, NodeMeasuredSize>,
  ): boolean {
    const points = routePoints(edge);
    if (points.length < 2) {
      return true;
    }
    const minX = Math.min(nodeRight(source, sizeSnapshot), nodeRight(target, sizeSnapshot)) - 0.5;
    const maxX = Math.max(nodeLeft(source), nodeLeft(target)) + ANCHOR_ROUTE_TARGET_MARGIN_X + 0.5;
    return points.every((point) => point.x >= minX && point.x <= maxX);
  }

  /** 判断同 lane 同列的次级关系边是否都紧贴 lane 外侧轨道（不超过限定距离）。 */
  private sameLaneSecondaryRouteWithinRail(
    edges: LinkGraphEdge[],
    nodes: LinkGraphNode[],
    nodeIndex: Map<string, LinkGraphNode>,
    sizeSnapshot: ReadonlyMap<string, NodeMeasuredSize>,
  ): boolean {
    return edges.every((edge) => {
      const source = nodeIndex.get(edge.source);
      const target = nodeIndex.get(edge.target);
      if (!source || !target || laneOf(source) !== laneOf(target) || laneOf(source) === "ANCHOR" || laneColumn(source) !== laneColumn(target)) {
        return true;
      }
      const lane = laneOf(source);
      if (lane !== "OUTGOING" && lane !== "RELATED" && lane !== "INCOMING") {
        return true;
      }
      const laneNodes = nodes.filter((node) => laneOf(node) === lane && laneColumn(node) === laneColumn(source));
      const points = routePoints(edge);
      if (points.length < 2 || laneNodes.length === 0) {
        return true;
      }
      const laneLeft = Math.min(...laneNodes.map(nodeLeft));
      const laneRight = Math.max(...laneNodes.map((node) => nodeRight(node, sizeSnapshot)));
      if (lane === "INCOMING") {
        return Math.min(...points.map((point) => point.x)) >= laneLeft - SAME_LANE_RAIL_LIMIT;
      }
      return Math.max(...points.map((point) => point.x)) <= laneRight + SAME_LANE_RAIL_LIMIT;
    });
  }

  /** 判断跨 lane 次级关系边是否都使用了底部次级轨道（绕过阻挡节点的水平段）。 */
  private crossLaneSecondaryRouteUsesRail(
    edges: LinkGraphEdge[],
    nodes: LinkGraphNode[],
    nodeIndex: Map<string, LinkGraphNode>,
    sizeSnapshot: ReadonlyMap<string, NodeMeasuredSize>,
  ): boolean {
    return edges.every((edge) => {
      const source = nodeIndex.get(edge.source);
      const target = nodeIndex.get(edge.target);
      if (
        !source
        || !target
        || !isClassDiagramRoutedStructuralRelation(edge)
        || laneOf(source) !== "INCOMING"
        || laneOf(target) !== "OUTGOING"
      ) {
        return true;
      }
      const points = routePoints(edge);
      if (points.length < 2) {
        return false;
      }
      const routeMinX = Math.min(...points.map((point) => point.x));
      const routeMaxX = Math.max(...points.map((point) => point.x));
      const blockingBottom = this.crossLaneSecondaryBlockingBottom(
        source,
        target,
        edge.targetHandle ?? edge.metadata?.["layout.targetPort"] ?? null,
        nodes,
        routeMinX,
        routeMaxX,
        sizeSnapshot,
      );
      const minimumRailY = blockingBottom + CROSS_LANE_SECONDARY_RAIL_GAP - 0.5;
      return points.some((point, index) => {
        if (index === 0) {
          return false;
        }
        const previous = points[index - 1]!;
        return Math.abs(previous.y - point.y) <= 0.5
          && Math.abs(previous.x - point.x) > 0.5
          && point.y >= minimumRailY;
      });
    });
  }

  /** 跨 lane 次级关系评分惩罚：若候选路径未使用应有的底部轨道则施加巨幅惩罚，迫使评分器避开。 */
  private crossLaneSecondaryRailPenalty(
    points: GraphPosition[],
    edge: LinkGraphEdge,
    nodeIndex: Map<string, LinkGraphNode>,
    sizeSnapshot: ReadonlyMap<string, NodeMeasuredSize>,
  ): number {
    const source = nodeIndex.get(edge.source);
    const target = nodeIndex.get(edge.target);
    if (
      !source
      || !target
      || !isClassDiagramRoutedStructuralRelation(edge)
      || laneOf(source) !== "INCOMING"
      || laneOf(target) !== "OUTGOING"
      || points.length < 2
    ) {
      return 0;
    }
    const routeMinX = Math.min(...points.map((point) => point.x));
    const routeMaxX = Math.max(...points.map((point) => point.x));
    const blockingBottom = this.crossLaneSecondaryBlockingBottom(
      source,
      target,
      edge.targetHandle ?? edge.metadata?.["layout.targetPort"] ?? null,
      Array.from(nodeIndex.values()),
      routeMinX,
      routeMaxX,
      sizeSnapshot,
    );
    const minimumRailY = blockingBottom + CROSS_LANE_SECONDARY_RAIL_GAP - 0.5;
    const usesLowerRail = points.some((point, index) => {
      if (index === 0) {
        return false;
      }
      const previous = points[index - 1]!;
      return Math.abs(previous.y - point.y) <= 0.5
        && Math.abs(previous.x - point.x) > 0.5
        && point.y >= minimumRailY;
    });
    return usesLowerRail ? 0 : 250_000;
  }

  /** 计算跨 lane 次级轨道必须避让的"最底端 Y"：考虑路径水平区间内阻挡节点的底边和源/目标节点本身。 */
  private crossLaneSecondaryBlockingBottom(
    source: LinkGraphNode,
    target: LinkGraphNode,
    targetPort: string | null,
    nodes: LinkGraphNode[],
    routeMinX: number,
    routeMaxX: number,
    sizeSnapshot: ReadonlyMap<string, NodeMeasuredSize>,
  ): number {
    const endpointTop = Math.min(nodeCenterY(source, sizeSnapshot), nodeCenterY(target, sizeSnapshot));
    const endpointBottom = Math.max(nodeCenterY(source, sizeSnapshot), nodeCenterY(target, sizeSnapshot));
    return nodes
      .filter((node) => node.id !== source.id && node.id !== target.id)
      .map((node) => nodeBounds(node, sizeSnapshot))
      .filter((bounds) =>
        bounds.right > routeMinX
        && bounds.left < routeMaxX
        && bounds.bottom >= endpointTop - EDGE_CROSSING_REPAIR_NODE_CLEARANCE
        && bounds.top <= endpointBottom + EDGE_CROSSING_REPAIR_NODE_CLEARANCE,
      )
      .reduce(
        (bottom, bounds) => Math.max(bottom, bounds.bottom),
        targetPort && portBase(targetPort) === "target-bottom"
          ? Math.max(nodeBottom(source, sizeSnapshot), nodeBottom(target, sizeSnapshot))
          : nodeBottom(source, sizeSnapshot),
      );
  }

  /** 计算所有边的标签摆放点与对应端点的最大距离，用于衡量标签是否离端点过远。 */
  private maxLabelDistanceToEndpoint(edges: LinkGraphEdge[]): number {
    return edges.reduce((maxDistance, edge) => {
      const points = routePoints(edge);
      const labelPoint = this.routeLabelPoint(points, edge.metadata?.["layout.labelPlacement"] ?? "target-stub");
      const endpoint = edge.metadata?.["layout.labelPlacement"] === "source-stub" ? points[0] : points[points.length - 1];
      if (!labelPoint || !endpoint) {
        return maxDistance;
      }
      return Math.max(maxDistance, Math.round(Math.hypot(labelPoint.x - endpoint.x, labelPoint.y - endpoint.y)));
    }, 0);
  }

  /** 根据 placement 策略（source-stub/target-stub/中点）算出标签应该摆放的路径点。 */
  private routeLabelPoint(points: GraphPosition[], placement: string): GraphPosition | null {
    if (points.length === 0) {
      return null;
    }
    if (placement === "source-stub") {
      return this.pointAlongRoute(points, 88, "from-start");
    }
    if (placement === "target-stub") {
      return this.pointAlongRoute(points, 88, "from-end");
    }
    return this.pointAlongRoute(points, routeLength(points) / 2, "from-start");
  }

  /** 沿路径行进指定距离得到一个插值点；direction 决定从起点或终点开始累积。 */
  private pointAlongRoute(points: GraphPosition[], distance: number, direction: "from-start" | "from-end"): GraphPosition | null {
    const routeLine = direction === "from-start" ? points : [...points].reverse();
    if (routeLine.length === 0) {
      return null;
    }
    if (routeLine.length === 1) {
      return routeLine[0]!;
    }
    let traversed = 0;
    for (let index = 1; index < routeLine.length; index += 1) {
      const start = routeLine[index - 1]!;
      const end = routeLine[index]!;
      const segmentLength = Math.abs(end.x - start.x) + Math.abs(end.y - start.y);
      if (segmentLength <= 0) {
        continue;
      }
      if (traversed + segmentLength >= distance) {
        const ratio = (distance - traversed) / segmentLength;
        return {
          x: start.x + (end.x - start.x) * ratio,
          y: start.y + (end.y - start.y) * ratio,
        };
      }
      traversed += segmentLength;
    }
    return routeLine[routeLine.length - 1]!;
  }

  /** 检查所有端点的端口 slot 顺序是否与对端节点的 Y 顺序一致，避免出现"交叉扇出"。 */
  private portOrderMatchesTargetOrder(
    edges: LinkGraphEdge[],
    nodeIndex: Map<string, LinkGraphNode>,
    sizeSnapshot: ReadonlyMap<string, NodeMeasuredSize>,
  ): boolean {
    return this.endpointPortOrderMatches(edges, nodeIndex, sizeSnapshot, "source")
      && this.endpointPortOrderMatches(edges, nodeIndex, sizeSnapshot, "target");
  }

  /** 对单侧（source/target）端口顺序做检查：同一节点同一侧端口的 slot 序与对端节点 Y 序应保持一致。 */
  private endpointPortOrderMatches(
    edges: LinkGraphEdge[],
    nodeIndex: Map<string, LinkGraphNode>,
    sizeSnapshot: ReadonlyMap<string, NodeMeasuredSize>,
    endpoint: "source" | "target",
  ): boolean {
    const groups = new Map<string, LinkGraphEdge[]>();
    edges.forEach((edge) => {
      const nodeId = endpoint === "source" ? edge.source : edge.target;
      const handle = endpoint === "source" ? edge.sourceHandle : edge.targetHandle;
      const handleSide = handle ? portBase(handle) : "unknown";
      const groupKey = `${nodeId}:${handleSide}`;
      groups.set(groupKey, [...(groups.get(groupKey) ?? []), edge]);
    });
    for (const [nodeId, group] of groups) {
      void nodeId;
      const ordered = group
        .map((edge) => {
          const handle = endpoint === "source" ? edge.sourceHandle : edge.targetHandle;
          const peerId = endpoint === "source" ? edge.target : edge.source;
          const peer = nodeIndex.get(peerId);
          return {
            edge,
            slot: handle ? portSlot(handle) : null,
            horizontal: handle ? isHorizontalPort(handle) : false,
            peerCenterY: peer ? nodeCenterY(peer, sizeSnapshot) : null,
          };
        })
        .filter((item): item is EndpointOrderItem & { slot: number; peerCenterY: number } =>
          item.slot != null && item.peerCenterY != null && item.horizontal,
        )
        .sort((left, right) => left.slot - right.slot || left.edge.id.localeCompare(right.edge.id));
      for (let index = 1; index < ordered.length; index += 1) {
        const current = ordered[index]!;
        const previous = ordered[index - 1]!;
        if (current.slot !== previous.slot && current.peerCenterY + 0.5 < previous.peerCenterY) {
          return false;
        }
      }
    }
    return true;
  }

  /** 计算给定节点集合的整体包围盒，全部节点都为空时返回 null。 */
  classDiagramBounds(
    nodes: Iterable<LinkGraphNode>,
    sizeSnapshot: ReadonlyMap<string, NodeMeasuredSize>,
  ): ClassNodeBounds | null {
    let bounds: ClassNodeBounds | null = null;
    for (const node of nodes) {
      bounds = mergeBounds(bounds, nodeBounds(node, sizeSnapshot));
    }
    return bounds;
  }
}
