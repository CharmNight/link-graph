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

export interface ClassDiagramLayoutResult {
  nodes: LinkGraphNode[];
  edges: LinkGraphEdge[];
}

export interface ClassDiagramRouteRepairReport {
  crossingCount: number;
  overlapCount: number;
  nodeCrossingCount: number;
  edgePressure: Map<string, number>;
}

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

export interface ClassDiagramReadabilityReport {
  accepted: boolean;
  acceptedLayout: ClassDiagramLayoutResult;
  metrics: ClassDiagramReadabilityMetrics;
  violations: string[];
}

const LABEL_ENDPOINT_DISTANCE_THRESHOLD = 220;
const SAME_LANE_RAIL_LIMIT = 112;
const EDGE_CROSSING_THRESHOLD = 0;
const EDGE_OVERLAP_THRESHOLD = 0;

interface EndpointOrderItem {
  edge: LinkGraphEdge;
  slot: number | null;
  horizontal: boolean;
  peerCenterY: number | null;
}

export class ClassDiagramReadabilityScorer {
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

  private pointOnSegment(point: GraphPosition, start: GraphPosition, end: GraphPosition): boolean {
    return point.x >= Math.min(start.x, end.x) - 0.5
      && point.x <= Math.max(start.x, end.x) + 0.5
      && point.y >= Math.min(start.y, end.y) - 0.5
      && point.y <= Math.max(start.y, end.y) + 0.5;
  }

  private routeEndpoint(point: GraphPosition, points: GraphPosition[]): boolean {
    const start = points[0];
    const end = points[points.length - 1];
    return (!!start && Math.abs(point.x - start.x) <= 0.5 && Math.abs(point.y - start.y) <= 0.5)
      || (!!end && Math.abs(point.x - end.x) <= 0.5 && Math.abs(point.y - end.y) <= 0.5);
  }

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

  private portOrderMatchesTargetOrder(
    edges: LinkGraphEdge[],
    nodeIndex: Map<string, LinkGraphNode>,
    sizeSnapshot: ReadonlyMap<string, NodeMeasuredSize>,
  ): boolean {
    return this.endpointPortOrderMatches(edges, nodeIndex, sizeSnapshot, "source")
      && this.endpointPortOrderMatches(edges, nodeIndex, sizeSnapshot, "target");
  }

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
