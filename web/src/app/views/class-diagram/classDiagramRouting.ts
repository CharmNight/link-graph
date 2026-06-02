import type { NodeMeasuredSize } from "../../graph/nodeSizeRegistry";
import { traceLinkGraph } from "../../debug";
import type { GraphPosition, LinkGraphEdge, LinkGraphNode } from "../../types";
import {
  buildOrthogonalEdgeRoute,
  type OrthogonalRect,
} from "../../reactflow/orthogonalEdgeRouting";
import {
  ANCHOR_ROUTE_CHANNEL_GAP,
  ANCHOR_ROUTE_SOURCE_MARGIN_X,
  ANCHOR_ROUTE_TARGET_MARGIN_X,
  BOTTOM_PORT,
  CROSS_LANE_SECONDARY_RAIL_GAP,
  CROSS_LANE_SECONDARY_RAIL_STEP,
  CROSS_LANE_SECONDARY_STUB_GAP,
  DATA_ROUTE_BOTTOM_OFFSET_LIMIT,
  DATA_ROUTE_TOP_GAP,
  DATA_ROUTE_TOP_OFFSET_LIMIT,
  EDGE_CROSSING_REPAIR_CHANNEL_GAP,
  EDGE_CROSSING_REPAIR_LOCALITY_IMPROVEMENT,
  EDGE_CROSSING_REPAIR_MAX_PASSES,
  EDGE_CROSSING_REPAIR_NODE_CLEARANCE,
  LEFT_PORT,
  OUTER_ROUTE_MARGIN_X,
  OUTER_ROUTE_MARGIN_Y,
  RIGHT_PORT,
  ROUTE_GAP,
  ROUTE_LANE_GAP,
  SOURCE_LEFT_PORT,
  SOURCE_TOP_PORT,
  TARGET_BOTTOM_PORT,
  TARGET_RIGHT_PORT,
  TOP_PORT,
  clamp,
  dataColumn,
  dataOuterSide,
  dataOuterSideAwayFromTarget,
  dataRow,
  edgeSortKey,
  fanoutChannelX,
  hashText,
  isHorizontalPort,
  laneColumn,
  laneOf,
  nodeBounds,
  nodeCenterX,
  nodeCenterY,
  nodeLeft,
  nodeRight,
  nodeSortKey,
  nodeTop,
  nodeBottom,
  orthogonalRectForNode,
  portPoint,
  portSlot,
  portWithSlot,
  renderedEdgeRank,
  reversedRouteChannel,
  routeChannelRatio,
  routeFromPoints,
  routeLength,
  routePoints,
  routeSectionPoints,
  simplifyRoutePoints,
  sideFanoutSlot,
  sourceSideForPort,
  targetSideForPort,
  portBase,
  type AxisInterval,
  type ClassDiagramLane,
  type ClassNodeBounds,
  type ClassRouteContext,
  type EdgeRouteChannels,
  type RouteChannel,
} from "./classDiagramLayoutModel";
import {
  classDiagramRelationKind,
  isClassDiagramHierarchyRelation,
  isClassDiagramHierarchyRelationKind,
  isClassDiagramRoutedStructuralRelation,
  isClassDiagramRoutedStructuralRelationKind,
} from "./classDiagramRelations";
import type { ClassDiagramTopology } from "./classDiagramTopology";
import type { ClassDiagramPlacement } from "./classDiagramPlacement";
import { ClassDiagramReadabilityScorer } from "./classDiagramReadability";

export class ClassDiagramRoutingEngine {
  constructor(private readonly readability = new ClassDiagramReadabilityScorer()) {}

  routeClassDiagramEdges(
    topology: ClassDiagramTopology,
    placement: ClassDiagramPlacement,
    sizeSnapshot: ReadonlyMap<string, NodeMeasuredSize>,
  ): LinkGraphEdge[] {
    const edgeIndexByTarget = new Map<string, LinkGraphEdge[]>();
    const edgeIndexBySource = new Map<string, LinkGraphEdge[]>();
    [...topology.edges].sort((left, right) => edgeSortKey(left).localeCompare(edgeSortKey(right))).forEach((edge) => {
      edgeIndexByTarget.set(edge.target, [...(edgeIndexByTarget.get(edge.target) ?? []), edge]);
      edgeIndexBySource.set(edge.source, [...(edgeIndexBySource.get(edge.source) ?? []), edge]);
    });
    const routeContext = buildClassRouteContext(placement.nodes, sizeSnapshot);
    const sortedEdges = [...topology.edges].sort((left, right) => edgeSortKey(left).localeCompare(edgeSortKey(right)));
    const routedEdges: LinkGraphEdge[] = [];
    sortedEdges.forEach((edge, index) => {
      routedEdges.push(routeManualClassEdge(
        edge,
        index,
        placement.nodeIndex,
        sizeSnapshot,
        edgeIndexBySource,
        edgeIndexByTarget,
        routeContext,
        routedEdges,
        this.readability,
      ));
    });
    const initialLaidOutEdges = routedEdges
      .sort((left, right) => renderedEdgeRank(left) - renderedEdgeRank(right) || edgeSortKey(left).localeCompare(edgeSortKey(right)));
    const repairedLayout = this.repairAndPreserveClassDiagramRoutes(
      { nodes: placement.nodes, edges: initialLaidOutEdges },
      sizeSnapshot,
    );
    traceLinkGraph("classDiagramLayout.routing.complete", {
      nodeCount: repairedLayout.nodes.length,
      edgeCount: repairedLayout.edges.length,
      routes: summarizeClassDiagramRoutes(repairedLayout.edges, placement.nodeIndex),
    });
    return repairedLayout.edges;
  }

  repairAndPreserveClassDiagramRoutes(
    layout: { nodes: LinkGraphNode[]; edges: LinkGraphEdge[] },
    sizeSnapshot: ReadonlyMap<string, NodeMeasuredSize>,
  ): { nodes: LinkGraphNode[]; edges: LinkGraphEdge[] } {
    const nodeIndex = new Map(layout.nodes.map((node) => [node.id, node]));
    const routeContext = buildClassRouteContext(layout.nodes, sizeSnapshot);
    return {
      ...layout,
      edges: preserveClassDiagramRoutes(repairEdgeRouteCrossings(layout.edges, nodeIndex, routeContext, sizeSnapshot, this.readability)),
    };
  }
}

function edgeOffset(
  edge: LinkGraphEdge,
  edgeIndex: Map<string, LinkGraphEdge[]>,
  key: string,
) {
  const siblings = edgeIndex.get(key) ?? [edge];
  const index = siblings.findIndex((candidate) => candidate.id === edge.id);
  return (index - (siblings.length - 1) / 2) * 14;
}

function edgePairOffset(edge: LinkGraphEdge, edgeIndex: number): number {
  const pairHash = Math.abs(hashText(`${edge.source}->${edge.target}:${classDiagramRelationKind(edge)}`));
  const direction = pairHash % 2 === 0 ? -1 : 1;
  const magnitude = Math.min(Math.floor(edgeIndex / 2) + 1, 4);
  return direction * magnitude * 18;
}

function relationEndpointOrderKey(
  edge: LinkGraphEdge,
  nodeId: string,
  nodeIndex: Map<string, LinkGraphNode>,
  sizeSnapshot: ReadonlyMap<string, NodeMeasuredSize>,
): string {
  const peerId = edge.source === nodeId ? edge.target : edge.source;
  const peer = nodeIndex.get(peerId);
  const peerCenter = peer ? nodeCenterY(peer, sizeSnapshot) : 0;
  return `${Math.round(peerCenter).toString().padStart(8, "0")}|${classDiagramRelationKind(edge)}|${edgeSortKey(edge)}`;
}

function relationFanoutSlot(
  edge: LinkGraphEdge,
  nodeId: string,
  edgeIndex: Map<string, LinkGraphEdge[]>,
  nodeIndex: Map<string, LinkGraphNode>,
  sizeSnapshot: ReadonlyMap<string, NodeMeasuredSize>,
  port: string,
): number {
  const side = sourceSideForPort(port);
  const siblings = (edgeIndex.get(nodeId) ?? [edge])
    .filter((candidate) => nodeIndex.has(candidate.source) && nodeIndex.has(candidate.target))
    .filter((candidate) => !isClassDiagramHierarchyRelation(candidate))
    .filter((candidate) => relationEndpointSide(candidate, nodeId, nodeIndex, sizeSnapshot) === side)
    .sort((left, right) =>
      relationEndpointOrderKey(left, nodeId, nodeIndex, sizeSnapshot)
        .localeCompare(relationEndpointOrderKey(right, nodeId, nodeIndex, sizeSnapshot)),
    );
  const index = Math.max(0, siblings.findIndex((candidate) => candidate.id === edge.id));
  return sideFanoutSlot(index, siblings.length);
}

type EndpointSide = ReturnType<typeof sourceSideForPort>;

function relationEndpointSide(
  edge: LinkGraphEdge,
  nodeId: string,
  nodeIndex: Map<string, LinkGraphNode>,
  sizeSnapshot: ReadonlyMap<string, NodeMeasuredSize>,
): EndpointSide | null {
  const source = nodeIndex.get(edge.source);
  const target = nodeIndex.get(edge.target);
  if (!source?.position || !target?.position) {
    return null;
  }
  if (edge.source === nodeId) {
    return preferredSourceSide(edge, source, target, nodeIndex, sizeSnapshot);
  }
  if (edge.target === nodeId) {
    return preferredTargetSide(edge, source, target, nodeIndex, sizeSnapshot);
  }
  return null;
}

function preferredSourceSide(
  edge: LinkGraphEdge,
  source: LinkGraphNode,
  target: LinkGraphNode,
  nodeIndex: Map<string, LinkGraphNode>,
  sizeSnapshot: ReadonlyMap<string, NodeMeasuredSize>,
): EndpointSide {
  if (isClassDiagramHierarchyRelation(edge)) {
    return source.position!.y > target.position!.y ? "top" : "bottom";
  }
  if (laneOf(source) === laneOf(target) && laneOf(source) !== "ANCHOR" && laneColumn(source) === laneColumn(target)) {
    return nodeCenterY(source, sizeSnapshot) <= nodeCenterY(target, sizeSnapshot) ? "bottom" : "top";
  }
  if (isClassDiagramRoutedStructuralRelation(edge)) {
    if (isCrossLaneSecondaryStructuralEdge(edge, source, target)) {
      return bottomApproachClear(source, nodeIndex, sizeSnapshot) ? "bottom" : "left";
    }
    if (laneOf(source) !== "DATA" && laneOf(target) !== "DATA") {
      if (laneOf(source) === laneOf(target) && laneColumn(source) === laneColumn(target)) {
        return source.position!.y > target.position!.y ? "top" : "bottom";
      }
      return source.position!.x > target.position!.x ? "left" : "right";
    }
    if (laneOf(source) !== "DATA" && laneOf(target) === "DATA") {
      return target.position!.x >= source.position!.x ? "right" : "left";
    }
    if (laneOf(source) === "DATA" && laneOf(target) !== "DATA") {
      return dataOuterSideAwayFromTarget(source, target);
    }
    return dataOuterSide(source);
  }
  return source.position!.x > target.position!.x ? "left" : "right";
}

function preferredTargetSide(
  edge: LinkGraphEdge,
  source: LinkGraphNode,
  target: LinkGraphNode,
  nodeIndex: Map<string, LinkGraphNode>,
  sizeSnapshot: ReadonlyMap<string, NodeMeasuredSize>,
): EndpointSide {
  if (isClassDiagramHierarchyRelation(edge)) {
    return source.position!.y > target.position!.y ? "bottom" : "top";
  }
  if (laneOf(source) === laneOf(target) && laneOf(source) !== "ANCHOR" && laneColumn(source) === laneColumn(target)) {
    return nodeCenterY(source, sizeSnapshot) <= nodeCenterY(target, sizeSnapshot) ? "top" : "bottom";
  }
  if (isClassDiagramRoutedStructuralRelation(edge)) {
    if (isCrossLaneSecondaryStructuralEdge(edge, source, target)) {
      return bottomApproachClear(target, nodeIndex, sizeSnapshot) ? "bottom" : "right";
    }
    if (laneOf(source) !== "DATA" && laneOf(target) !== "DATA") {
      if (laneOf(source) === laneOf(target) && laneColumn(source) === laneColumn(target)) {
        return source.position!.y > target.position!.y ? "bottom" : "top";
      }
      return source.position!.x > target.position!.x ? "right" : "left";
    }
    if (laneOf(target) === "DATA" && laneOf(source) !== "DATA") {
      return dataRow(target) === 0 ? "top" : dataOuterSide(target);
    }
    if (laneOf(source) === "DATA" && laneOf(target) !== "DATA") {
      return target.position!.x >= source.position!.x ? "right" : "left";
    }
    return dataOuterSide(target);
  }
  return source.position!.x > target.position!.x ? "right" : "left";
}

function relationFanoutChannel(
  edge: LinkGraphEdge,
  nodeId: string,
  edgeIndex: Map<string, LinkGraphEdge[]>,
  nodeIndex: Map<string, LinkGraphNode>,
  sizeSnapshot: ReadonlyMap<string, NodeMeasuredSize>,
): RouteChannel {
  const siblings = (edgeIndex.get(nodeId) ?? [edge])
    .filter((candidate) => nodeIndex.has(candidate.source) && nodeIndex.has(candidate.target))
    .filter((candidate) => !isClassDiagramHierarchyRelation(candidate))
    .sort((left, right) =>
      relationEndpointOrderKey(left, nodeId, nodeIndex, sizeSnapshot)
        .localeCompare(relationEndpointOrderKey(right, nodeId, nodeIndex, sizeSnapshot)),
    );
  const index = Math.max(0, siblings.findIndex((candidate) => candidate.id === edge.id));
  return {
    index,
    count: Math.max(1, siblings.length),
  };
}

function sourcePortForRoute(
  edge: LinkGraphEdge,
  source: LinkGraphNode,
  target: LinkGraphNode,
  sizeSnapshot: ReadonlyMap<string, NodeMeasuredSize>,
  edgeIndexBySource: Map<string, LinkGraphEdge[]>,
  nodeIndex: Map<string, LinkGraphNode>,
) {
  if (isClassDiagramHierarchyRelation(edge)) {
    return source.position!.y > target.position!.y ? SOURCE_TOP_PORT : BOTTOM_PORT;
  }
  if (laneOf(source) === laneOf(target) && laneOf(source) !== "ANCHOR" && laneColumn(source) === laneColumn(target)) {
    return nodeCenterY(source, sizeSnapshot) <= nodeCenterY(target, sizeSnapshot) ? BOTTOM_PORT : SOURCE_TOP_PORT;
  }
  if (isClassDiagramRoutedStructuralRelation(edge)) {
    if (laneOf(source) !== "DATA" && laneOf(target) !== "DATA") {
      if (laneOf(source) === "ANCHOR" && laneOf(target) === "OUTGOING") {
        return portWithSlot(RIGHT_PORT, relationFanoutSlot(edge, source.id, edgeIndexBySource, nodeIndex, sizeSnapshot, RIGHT_PORT));
      }
      if (laneOf(source) === laneOf(target) && laneColumn(source) === laneColumn(target)) {
        return source.position!.y > target.position!.y ? SOURCE_TOP_PORT : BOTTOM_PORT;
      }
      return source.position!.x > target.position!.x
        ? SOURCE_LEFT_PORT
        : portWithSlot(RIGHT_PORT, relationFanoutSlot(edge, source.id, edgeIndexBySource, nodeIndex, sizeSnapshot, RIGHT_PORT));
    }
    if (laneOf(source) !== "DATA" && laneOf(target) === "DATA") {
      return target.position!.x >= source.position!.x ? RIGHT_PORT : SOURCE_LEFT_PORT;
    }
    if (laneOf(source) === "DATA" && laneOf(target) !== "DATA") {
      return dataOuterSideAwayFromTarget(source, target) === "left" ? SOURCE_LEFT_PORT : RIGHT_PORT;
    }
    return dataOuterSide(source) === "left" ? SOURCE_LEFT_PORT : RIGHT_PORT;
  }
  if (laneOf(source) === "ANCHOR" && laneOf(target) === "OUTGOING") {
    return portWithSlot(RIGHT_PORT, relationFanoutSlot(edge, source.id, edgeIndexBySource, nodeIndex, sizeSnapshot, RIGHT_PORT));
  }
  if (source.position!.x > target.position!.x) {
    return SOURCE_LEFT_PORT;
  }
  return portWithSlot(RIGHT_PORT, relationFanoutSlot(edge, source.id, edgeIndexBySource, nodeIndex, sizeSnapshot, RIGHT_PORT));
}

function targetPortForRoute(
  edge: LinkGraphEdge,
  source: LinkGraphNode,
  target: LinkGraphNode,
  sizeSnapshot: ReadonlyMap<string, NodeMeasuredSize>,
  edgeIndexByTarget: Map<string, LinkGraphEdge[]>,
  nodeIndex: Map<string, LinkGraphNode>,
) {
  if (isClassDiagramHierarchyRelation(edge)) {
    return source.position!.y > target.position!.y ? TARGET_BOTTOM_PORT : TOP_PORT;
  }
  if (laneOf(source) === laneOf(target) && laneOf(source) !== "ANCHOR" && laneColumn(source) === laneColumn(target)) {
    return nodeCenterY(source, sizeSnapshot) <= nodeCenterY(target, sizeSnapshot) ? TOP_PORT : TARGET_BOTTOM_PORT;
  }
  if (isClassDiagramRoutedStructuralRelation(edge)) {
    if (laneOf(source) !== "DATA" && laneOf(target) !== "DATA") {
      if (laneOf(source) === "ANCHOR" && laneOf(target) === "OUTGOING") {
        return portWithSlot(LEFT_PORT, relationFanoutSlot(edge, target.id, edgeIndexByTarget, nodeIndex, sizeSnapshot, LEFT_PORT));
      }
      if (laneOf(source) === "INCOMING" && laneOf(target) === "ANCHOR") {
        return portWithSlot(LEFT_PORT, relationFanoutSlot(edge, target.id, edgeIndexByTarget, nodeIndex, sizeSnapshot, LEFT_PORT));
      }
      if (laneOf(source) === laneOf(target) && laneColumn(source) === laneColumn(target)) {
        return source.position!.y > target.position!.y ? TARGET_BOTTOM_PORT : TOP_PORT;
      }
      return source.position!.x > target.position!.x
        ? TARGET_RIGHT_PORT
        : portWithSlot(LEFT_PORT, relationFanoutSlot(edge, target.id, edgeIndexByTarget, nodeIndex, sizeSnapshot, LEFT_PORT));
    }
    if (laneOf(target) === "DATA" && laneOf(source) !== "DATA") {
      return dataRow(target) === 0 ? TOP_PORT : dataOuterSide(target) === "left" ? LEFT_PORT : TARGET_RIGHT_PORT;
    }
    if (laneOf(source) === "DATA" && laneOf(target) !== "DATA") {
      if (source.position!.y > target.position!.y) {
        return target.position!.x >= source.position!.x ? TARGET_RIGHT_PORT : LEFT_PORT;
      }
      return target.position!.x >= source.position!.x ? TARGET_RIGHT_PORT : LEFT_PORT;
    }
    return dataOuterSide(target) === "left" ? LEFT_PORT : TARGET_RIGHT_PORT;
  }
  if (source.position!.x > target.position!.x) {
    return TARGET_RIGHT_PORT;
  }
  return portWithSlot(LEFT_PORT, relationFanoutSlot(edge, target.id, edgeIndexByTarget, nodeIndex, sizeSnapshot, LEFT_PORT));
}

function buildClassRouteContext(
  nodes: LinkGraphNode[],
  sizeSnapshot: ReadonlyMap<string, NodeMeasuredSize>,
): ClassRouteContext {
  const laneNodes = new Map<ClassDiagramLane, LinkGraphNode[]>();
  nodes.forEach((node) => {
    const lane = laneOf(node);
    laneNodes.set(lane, [...(laneNodes.get(lane) ?? []), node]);
  });
  laneNodes.forEach((bucket, lane) => {
    laneNodes.set(
      lane,
      [...bucket].sort((left, right) => nodeTop(left) - nodeTop(right) || nodeSortKey(left).localeCompare(nodeSortKey(right))),
    );
  });
  let dataBounds: ClassNodeBounds | null = null;
  const dataColumnBounds = new Map<number, ClassNodeBounds>();
  nodes
    .filter((node) => laneOf(node) === "DATA")
    .forEach((node) => {
      const bounds = nodeBounds(node, sizeSnapshot);
      const column = dataColumn(node);
      dataBounds = dataBounds ? {
        left: Math.min(dataBounds.left, bounds.left),
        right: Math.max(dataBounds.right, bounds.right),
        top: Math.min(dataBounds.top, bounds.top),
        bottom: Math.max(dataBounds.bottom, bounds.bottom),
      } : bounds;
      const existing = dataColumnBounds.get(column);
      dataColumnBounds.set(column, existing ? {
        left: Math.min(existing.left, bounds.left),
        right: Math.max(existing.right, bounds.right),
        top: Math.min(existing.top, bounds.top),
        bottom: Math.max(existing.bottom, bounds.bottom),
      } : bounds);
    });
  const obstacleRects = nodes.map((node) => orthogonalRectForNode(node, sizeSnapshot));
  return { laneNodes, dataBounds, dataColumnBounds, obstacleRects };
}

function buildAvoidingRoutePoints(
  source: LinkGraphNode,
  target: LinkGraphNode,
  sourcePoint: GraphPosition,
  targetPoint: GraphPosition,
  sourcePort: string,
  targetPort: string,
  context: ClassRouteContext,
  sizeSnapshot: ReadonlyMap<string, NodeMeasuredSize>,
): GraphPosition[] {
  const route = buildOrthogonalEdgeRoute({
    startPoint: sourcePoint,
    startSide: sourceSideForPort(sourcePort),
    startRect: orthogonalRectForNode(source, sizeSnapshot),
    endPoint: targetPoint,
    endSide: targetSideForPort(targetPort),
    endRect: orthogonalRectForNode(target, sizeSnapshot),
    obstacleRects: context.obstacleRects,
  });
  return routeSectionPoints(route);
}

function dataColumnOuterX(
  node: LinkGraphNode,
  side: "left" | "right",
  context: ClassRouteContext,
  sizeSnapshot: ReadonlyMap<string, NodeMeasuredSize>,
  offset: number,
) {
  const bounds = context.dataColumnBounds.get(dataColumn(node)) ?? nodeBounds(node, sizeSnapshot);
  return side === "left"
    ? bounds.left - OUTER_ROUTE_MARGIN_X - offset
    : bounds.right + OUTER_ROUTE_MARGIN_X + offset;
}

function dataTopRouteY(context: ClassRouteContext, fallbackY: number, offset: number) {
  const top = context.dataBounds?.top ?? fallbackY;
  return top - DATA_ROUTE_TOP_GAP - Math.min(offset, DATA_ROUTE_TOP_OFFSET_LIMIT);
}

function dataBottomRouteY(context: ClassRouteContext, fallbackY: number, offset: number) {
  const bottom = context.dataBounds?.bottom ?? fallbackY;
  return bottom + OUTER_ROUTE_MARGIN_Y + Math.min(offset, DATA_ROUTE_BOTTOM_OFFSET_LIMIT);
}

function routeLaneOffset(edgeIndex: number, relation: string) {
  const direction = edgeIndex % 2 === 0 ? -1 : 1;
  const magnitude = Math.min(Math.floor(edgeIndex / 2) + 1, 3);
  const base = isClassDiagramRoutedStructuralRelationKind(relation) ? ROUTE_LANE_GAP : ROUTE_GAP;
  return direction * magnitude * base;
}

function hasSameLaneNodeBetween(
  source: LinkGraphNode,
  target: LinkGraphNode,
  context: ClassRouteContext,
  sizeSnapshot: ReadonlyMap<string, NodeMeasuredSize>,
): boolean {
  const lane = laneOf(source);
  if (lane !== laneOf(target)) {
    return false;
  }
  const top = Math.min(nodeBottom(source, sizeSnapshot), nodeBottom(target, sizeSnapshot));
  const bottom = Math.max(nodeTop(source), nodeTop(target));
  if (bottom <= top) {
    return false;
  }
  return (context.laneNodes.get(lane) ?? []).some((node) =>
    node.id !== source.id
    && node.id !== target.id
    && nodeBottom(node, sizeSnapshot) > top
    && nodeTop(node) < bottom,
  );
}

function sameLaneVerticalRoutePoints(
  edge: LinkGraphEdge,
  source: LinkGraphNode,
  target: LinkGraphNode,
  sourcePoint: GraphPosition,
  targetPoint: GraphPosition,
  context: ClassRouteContext,
  sizeSnapshot: ReadonlyMap<string, NodeMeasuredSize>,
  laneOffset: number,
  pairOffset: number,
): GraphPosition[] {
  const sourceAboveTarget = nodeCenterY(source, sizeSnapshot) <= nodeCenterY(target, sizeSnapshot);
  const sourceBoundaryY = sourceAboveTarget ? nodeBottom(source, sizeSnapshot) : nodeTop(source);
  const targetBoundaryY = sourceAboveTarget ? nodeTop(target) : nodeBottom(target, sizeSnapshot);
  const verticalGap = Math.abs(targetBoundaryY - sourceBoundaryY);
  const relation = classDiagramRelationKind(edge);
  const hasNodeBetween = hasSameLaneNodeBetween(source, target, context, sizeSnapshot);
  if (!hasNodeBetween) {
    if (Math.abs(sourcePoint.x - targetPoint.x) <= 0.5) {
      return [sourcePoint, targetPoint];
    }
    const gapMiddleY = Math.round((sourceBoundaryY + targetBoundaryY) / 2);
    return [
      sourcePoint,
      { x: sourcePoint.x, y: gapMiddleY },
      { x: targetPoint.x, y: gapMiddleY },
      targetPoint,
    ];
  }
  const edgeSkew = isClassDiagramRoutedStructuralRelationKind(relation) ? pairOffset : pairOffset / 2;
  const sameLane = laneOf(source);
  const laneNodes = context.laneNodes.get(sameLane) ?? [source, target];
  const laneLeft = Math.min(...laneNodes.map(nodeLeft));
  const laneRight = Math.max(...laneNodes.map((node) => nodeRight(node, sizeSnapshot)));
  const laneSlotOffset = Math.min(Math.round(laneOffset / ROUTE_GAP), 3) * 8;
  const boundedSkew = clamp(edgeSkew / 4, -8, 8);
  const laneX = sameLane === "OUTGOING"
    ? laneRight + 30 + laneSlotOffset + Math.max(0, boundedSkew)
    : laneLeft - 30 - laneSlotOffset + Math.min(0, boundedSkew);
  const exitY = sourceAboveTarget
    ? sourceBoundaryY + Math.min(Math.max(60, verticalGap * 0.2), 92)
    : sourceBoundaryY - Math.min(Math.max(60, verticalGap * 0.2), 92);
  return [
    sourcePoint,
    { x: sourcePoint.x, y: exitY },
    { x: laneX, y: exitY },
    { x: laneX, y: targetPoint.y },
    targetPoint,
  ];
}

function routeAnchorToOutgoingPoints(
  sourcePoint: GraphPosition,
  targetPoint: GraphPosition,
  target: LinkGraphNode,
  sizeSnapshot: ReadonlyMap<string, NodeMeasuredSize>,
  sourceChannel: RouteChannel,
  pairOffset = 0,
  context?: ClassRouteContext,
): GraphPosition[] {
  const gap = targetPoint.x - sourcePoint.x;
  if (gap <= 96) {
    return [sourcePoint, targetPoint];
  }
  const targetLeft = nodeLeft(target);
  const columnGap = anchorOutgoingColumnGap(target, context, sizeSnapshot);
  const crossingAwareChannel = reversedRouteChannel(sourceChannel);
  if (columnGap) {
    const midX = fanoutChannelX(columnGap.minX, columnGap.maxX, crossingAwareChannel, pairOffset);
    return [
      sourcePoint,
      { x: midX, y: sourcePoint.y },
      { x: midX, y: targetPoint.y },
      targetPoint,
    ];
  }
  const channelMinX = sourcePoint.x + ANCHOR_ROUTE_SOURCE_MARGIN_X;
  const channelMaxX = targetLeft - ANCHOR_ROUTE_TARGET_MARGIN_X;
  if (channelMinX <= channelMaxX) {
    const midX = fanoutChannelX(channelMinX, channelMaxX, crossingAwareChannel, pairOffset);
    return [
      sourcePoint,
      { x: midX, y: sourcePoint.y },
      { x: midX, y: targetPoint.y },
      targetPoint,
    ];
  }
  const channelOffset = Math.round((routeChannelRatio(sourceChannel) - 0.5) * ANCHOR_ROUTE_CHANNEL_GAP);
  const approachX = Math.round(targetLeft - clamp(70 + Math.abs(channelOffset), 58, Math.max(58, gap - 72)));
  const fallbackX = sourcePoint.x + clamp(Math.round(gap * 0.68) + pairOffset, 96, gap - 72);
  const midX = clamp(approachX + channelOffset + Math.round(pairOffset / 2), sourcePoint.x + 92, targetLeft - 48);
  const resolvedMidX = Number.isFinite(midX) ? midX : fallbackX;
  return [
    sourcePoint,
    { x: resolvedMidX, y: sourcePoint.y },
    { x: resolvedMidX, y: targetPoint.y },
    targetPoint,
  ];
}

function routeIncomingToAnchorPoints(
  sourcePoint: GraphPosition,
  targetPoint: GraphPosition,
  targetChannel: RouteChannel,
  pairOffset: number,
): GraphPosition[] {
  const gap = targetPoint.x - sourcePoint.x;
  if (gap <= 96) {
    return [sourcePoint, targetPoint];
  }
  const channelMinX = sourcePoint.x + ANCHOR_ROUTE_TARGET_MARGIN_X;
  const channelMaxX = targetPoint.x - ANCHOR_ROUTE_SOURCE_MARGIN_X;
  const laneX = channelMinX <= channelMaxX
    ? fanoutChannelX(channelMinX, channelMaxX, targetChannel, pairOffset)
    : sourcePoint.x + Math.max(150, Math.round(gap * 0.5)) + pairOffset;
  return [
    sourcePoint,
    { x: laneX, y: sourcePoint.y },
    { x: laneX, y: targetPoint.y },
    targetPoint,
  ];
}

function sameLaneOuterRoutePorts(lane: ClassDiagramLane): { sourcePort: string; targetPort: string } | null {
  if (lane === "OUTGOING" || lane === "RELATED") {
    return { sourcePort: RIGHT_PORT, targetPort: TARGET_RIGHT_PORT };
  }
  if (lane === "INCOMING") {
    return { sourcePort: SOURCE_LEFT_PORT, targetPort: LEFT_PORT };
  }
  return null;
}

function sameLaneOuterRoutePortsForEdge(
  edge: LinkGraphEdge,
  source: LinkGraphNode,
  target: LinkGraphNode,
  sizeSnapshot: ReadonlyMap<string, NodeMeasuredSize>,
  edgeIndexBySource: Map<string, LinkGraphEdge[]>,
  edgeIndexByTarget: Map<string, LinkGraphEdge[]>,
  nodeIndex: Map<string, LinkGraphNode>,
): { sourcePort: string; targetPort: string } | null {
  const lane = laneOf(source);
  if (lane !== laneOf(target) || lane === "ANCHOR" || laneColumn(source) !== laneColumn(target)) {
    return null;
  }
  const ports = sameLaneOuterRoutePorts(lane);
  if (!ports) {
    return null;
  }
  return {
    sourcePort: portWithSlot(ports.sourcePort, relationFanoutSlot(edge, source.id, edgeIndexBySource, nodeIndex, sizeSnapshot, ports.sourcePort)),
    targetPort: portWithSlot(ports.targetPort, relationFanoutSlot(edge, target.id, edgeIndexByTarget, nodeIndex, sizeSnapshot, ports.targetPort)),
  };
}

function sameLaneOuterRoutePoints(
  source: LinkGraphNode,
  target: LinkGraphNode,
  sourcePoint: GraphPosition,
  targetPoint: GraphPosition,
  context: ClassRouteContext,
  sizeSnapshot: ReadonlyMap<string, NodeMeasuredSize>,
  laneOffset: number,
  pairOffset: number,
): GraphPosition[] {
  const lane = laneOf(source);
  const laneNodes = context.laneNodes.get(lane) ?? [source, target];
  const laneLeft = Math.min(...laneNodes.map(nodeLeft));
  const laneRight = Math.max(...laneNodes.map((node) => nodeRight(node, sizeSnapshot)));
  const laneSlotOffset = Math.min(Math.round(laneOffset / ROUTE_GAP), 3) * 8;
  const boundedSkew = clamp(pairOffset / 4, -8, 8);
  const routeOnLeft = lane === "INCOMING";
  const laneX = routeOnLeft
    ? laneLeft - 30 - laneSlotOffset + Math.min(0, boundedSkew)
    : laneRight + 30 + laneSlotOffset + Math.max(0, boundedSkew);
  return [
    sourcePoint,
    { x: laneX, y: sourcePoint.y },
    { x: laneX, y: targetPoint.y },
    targetPoint,
  ];
}

function anchorOutgoingColumnGap(
  target: LinkGraphNode,
  context: ClassRouteContext | undefined,
  sizeSnapshot: ReadonlyMap<string, NodeMeasuredSize>,
): { minX: number; maxX: number } | null {
  if (!context || laneColumn(target) <= 0) {
    return null;
  }
  const previousColumnNodes = (context.laneNodes.get(laneOf(target)) ?? [])
    .filter((node) => laneColumn(node) < laneColumn(target));
  if (previousColumnNodes.length === 0) {
    return null;
  }
  const previousRight = Math.max(...previousColumnNodes.map((node) => nodeRight(node, sizeSnapshot)));
  const targetLeft = nodeLeft(target);
  const minX = previousRight + 40;
  const maxX = targetLeft - 40;
  if (minX <= maxX) {
    return { minX, maxX };
  }
  const middleX = Math.round((previousRight + targetLeft) / 2);
  return { minX: middleX, maxX: middleX };
}

interface RouteStrategyArgs {
  edge: LinkGraphEdge;
  edgeIndex: number;
  source: LinkGraphNode;
  target: LinkGraphNode;
  sourcePoint: GraphPosition;
  targetPoint: GraphPosition;
  sourcePort: string;
  targetPort: string;
  routeChannels: EdgeRouteChannels;
  context: ClassRouteContext;
  sizeSnapshot: ReadonlyMap<string, NodeMeasuredSize>;
  relation: string;
  sourceLane: ClassDiagramLane;
  targetLane: ClassDiagramLane;
  pairOffset: number;
  laneOffset: number;
}

interface ClassDiagramRouteStrategy {
  canRoute(args: RouteStrategyArgs): boolean;
  route(args: RouteStrategyArgs): GraphPosition[];
}

class SameLaneOuterRouteStrategy implements ClassDiagramRouteStrategy {
  canRoute(args: RouteStrategyArgs): boolean {
    return args.sourceLane === args.targetLane
      && args.sourceLane !== "ANCHOR"
      && laneColumn(args.source) === laneColumn(args.target)
      && Boolean(sameLaneOuterRoutePorts(args.sourceLane))
      && isHorizontalPort(args.sourcePort)
      && isHorizontalPort(args.targetPort);
  }

  route(args: RouteStrategyArgs): GraphPosition[] {
    return sameLaneOuterRoutePoints(
      args.source,
      args.target,
      args.sourcePoint,
      args.targetPoint,
      args.context,
      args.sizeSnapshot,
      args.laneOffset,
      args.pairOffset,
    );
  }
}

class SameLaneObstacleAvoidanceRouteStrategy implements ClassDiagramRouteStrategy {
  canRoute(args: RouteStrategyArgs): boolean {
    if (
      args.sourceLane === "OUTGOING"
      && args.targetLane === "OUTGOING"
      && laneColumn(args.source) === laneColumn(args.target)
      && hasSameLaneNodeBetween(args.source, args.target, args.context, args.sizeSnapshot)
    ) {
      return true;
    }
    return args.sourceLane === args.targetLane
      && args.sourceLane !== "ANCHOR"
      && laneColumn(args.source) !== laneColumn(args.target);
  }

  route(args: RouteStrategyArgs): GraphPosition[] {
    return buildAvoidingRoutePoints(
      args.source,
      args.target,
      args.sourcePoint,
      args.targetPoint,
      args.sourcePort,
      args.targetPort,
      args.context,
      args.sizeSnapshot,
    );
  }
}

class HierarchyRouteStrategy implements ClassDiagramRouteStrategy {
  canRoute(args: RouteStrategyArgs): boolean {
    return isClassDiagramHierarchyRelationKind(args.relation)
      && !isHorizontalPort(args.sourcePort)
      && !isHorizontalPort(args.targetPort);
  }

  route(args: RouteStrategyArgs): GraphPosition[] {
    const laneY = Math.min(nodeTop(args.source), nodeTop(args.target)) - OUTER_ROUTE_MARGIN_Y - args.laneOffset;
    return [
      args.sourcePoint,
      { x: args.sourcePoint.x, y: laneY },
      { x: args.targetPoint.x + args.pairOffset, y: laneY },
      { x: args.targetPoint.x + args.pairOffset, y: args.targetPoint.y },
      args.targetPoint,
    ];
  }
}

class AnchorOutgoingRouteStrategy implements ClassDiagramRouteStrategy {
  canRoute(args: RouteStrategyArgs): boolean {
    return args.sourceLane === "ANCHOR" && args.targetLane === "OUTGOING";
  }

  route(args: RouteStrategyArgs): GraphPosition[] {
    return routeAnchorToOutgoingPoints(
      args.sourcePoint,
      args.targetPoint,
      args.target,
      args.sizeSnapshot,
      args.routeChannels.source,
      args.pairOffset,
      args.context,
    );
  }
}

class IncomingAnchorRouteStrategy implements ClassDiagramRouteStrategy {
  canRoute(args: RouteStrategyArgs): boolean {
    return args.sourceLane === "INCOMING" && args.targetLane === "ANCHOR";
  }

  route(args: RouteStrategyArgs): GraphPosition[] {
    return routeIncomingToAnchorPoints(args.sourcePoint, args.targetPoint, args.routeChannels.target, args.pairOffset);
  }
}

class CrossLaneSecondaryRailRouteStrategy implements ClassDiagramRouteStrategy {
  canRoute(args: RouteStrategyArgs): boolean {
    return isClassDiagramRoutedStructuralRelationKind(args.relation)
      && args.sourceLane === "INCOMING"
      && args.targetLane === "OUTGOING";
  }

  route(args: RouteStrategyArgs): GraphPosition[] {
    const channel = crossLaneSecondaryChannel(args.routeChannels);
    const railY = crossLaneSecondaryRailY(args, args.sourcePoint.x, args.targetPoint.x, channel);
    const sourceRailX = crossLaneRailX(args.sourcePoint, args.sourcePort);
    const targetRailX = crossLaneRailX(args.targetPoint, args.targetPort);
    return [
      args.sourcePoint,
      ...(isHorizontalPort(args.sourcePort) ? [{ x: sourceRailX, y: args.sourcePoint.y }] : []),
      { x: sourceRailX, y: railY },
      { x: targetRailX, y: railY },
      ...(isHorizontalPort(args.targetPort) ? [{ x: targetRailX, y: args.targetPoint.y }] : []),
      args.targetPoint,
    ];
  }
}

class StructuralFallbackRouteStrategy implements ClassDiagramRouteStrategy {
  canRoute(): boolean {
    return true;
  }

  route(args: RouteStrategyArgs): GraphPosition[] {
    return structuralAndFallbackRoutePoints(
      args.edge,
      args.edgeIndex,
      args.source,
      args.target,
      args.sourcePoint,
      args.targetPoint,
      args.sourcePort,
      args.targetPort,
      args.routeChannels,
      args.context,
      args.sizeSnapshot,
      args.relation,
      args.sourceLane,
      args.targetLane,
      args.pairOffset,
      args.laneOffset,
    );
  }
}

const ROUTE_STRATEGIES: ClassDiagramRouteStrategy[] = [
  new SameLaneOuterRouteStrategy(),
  new SameLaneObstacleAvoidanceRouteStrategy(),
  new HierarchyRouteStrategy(),
  new AnchorOutgoingRouteStrategy(),
  new IncomingAnchorRouteStrategy(),
  new CrossLaneSecondaryRailRouteStrategy(),
  new StructuralFallbackRouteStrategy(),
];

function routePointsForPorts(
  edge: LinkGraphEdge,
  edgeIndex: number,
  source: LinkGraphNode,
  target: LinkGraphNode,
  sourcePoint: GraphPosition,
  targetPoint: GraphPosition,
  sourcePort: string,
  targetPort: string,
  routeChannels: EdgeRouteChannels,
  context: ClassRouteContext,
  sizeSnapshot: ReadonlyMap<string, NodeMeasuredSize>,
): GraphPosition[] {
  const relation = classDiagramRelationKind(edge);
  const sourceLane = laneOf(source);
  const targetLane = laneOf(target);
  const pairOffset = edgePairOffset(edge, edgeIndex);
  const laneOffset = Math.abs(routeLaneOffset(edgeIndex, relation));
  const args: RouteStrategyArgs = {
    edge,
    edgeIndex,
    source,
    target,
    sourcePoint,
    targetPoint,
    sourcePort,
    targetPort,
    routeChannels,
    context,
    sizeSnapshot,
    relation,
    sourceLane,
    targetLane,
    pairOffset,
    laneOffset,
  };
  return ROUTE_STRATEGIES.find((strategy) => strategy.canRoute(args))!.route(args);
}

function isCrossLaneSecondaryStructuralEdge(
  edge: LinkGraphEdge,
  source: LinkGraphNode,
  target: LinkGraphNode,
): boolean {
  return isClassDiagramRoutedStructuralRelation(edge)
    && laneOf(source) === "INCOMING"
    && laneOf(target) === "OUTGOING";
}

function horizontalStubX(point: GraphPosition, port: string, gap: number): number {
  const basePort = portBase(port);
  if (basePort === SOURCE_LEFT_PORT || basePort === LEFT_PORT) {
    return Math.round(point.x - gap);
  }
  return Math.round(point.x + gap);
}

function crossLaneRailX(point: GraphPosition, port: string): number {
  return isHorizontalPort(port)
    ? horizontalStubX(point, port, CROSS_LANE_SECONDARY_STUB_GAP)
    : Math.round(point.x);
}

function crossLaneSecondaryChannel(routeChannels: EdgeRouteChannels): RouteChannel {
  const count = Math.max(routeChannels.source.count, routeChannels.target.count, 1);
  const index = Math.max(routeChannels.source.index, routeChannels.target.index, 0);
  return {
    index: clamp(index, 0, count - 1),
    count,
  };
}

function bottomApproachClear(
  node: LinkGraphNode,
  nodeIndex: Map<string, LinkGraphNode>,
  sizeSnapshot: ReadonlyMap<string, NodeMeasuredSize>,
): boolean {
  const bounds = nodeBounds(node, sizeSnapshot);
  const centerX = nodeCenterX(node, sizeSnapshot);
  return !Array.from(nodeIndex.values()).some((candidate) => {
    if (candidate.id === node.id) {
      return false;
    }
    const candidateBounds = nodeBounds(candidate, sizeSnapshot);
    return candidateBounds.top >= bounds.bottom - 0.5
      && centerX > candidateBounds.left + EDGE_CROSSING_REPAIR_NODE_CLEARANCE
      && centerX < candidateBounds.right - EDGE_CROSSING_REPAIR_NODE_CLEARANCE;
  });
}

function crossLaneSecondaryRailY(
  args: RouteStrategyArgs,
  sourceRailX: number,
  targetRailX: number,
  channel: RouteChannel,
): number {
  const routeMinX = Math.min(sourceRailX, targetRailX);
  const routeMaxX = Math.max(sourceRailX, targetRailX);
  const targetBounds = nodeBounds(args.target, args.sizeSnapshot);
  const endpointTop = Math.min(nodeCenterY(args.source, args.sizeSnapshot), nodeCenterY(args.target, args.sizeSnapshot));
  const endpointBottom = Math.max(nodeCenterY(args.source, args.sizeSnapshot), nodeCenterY(args.target, args.sizeSnapshot));
  const blockingBottom = routeContextNodes(args.context)
    .filter((node) => node.id !== args.source.id && node.id !== args.target.id)
    .map((node) => nodeBounds(node, args.sizeSnapshot))
    .filter((bounds) =>
      bounds.right > routeMinX
      && bounds.left < routeMaxX
      && bounds.bottom >= endpointTop - EDGE_CROSSING_REPAIR_NODE_CLEARANCE
      && bounds.top <= endpointBottom + EDGE_CROSSING_REPAIR_NODE_CLEARANCE,
    )
    .reduce(
      (bottom, bounds) => Math.max(bottom, bounds.bottom),
      portBase(args.targetPort) === TARGET_BOTTOM_PORT
        ? Math.max(nodeBottom(args.source, args.sizeSnapshot), targetBounds.bottom)
        : nodeBottom(args.source, args.sizeSnapshot),
    );
  return Math.round(
    blockingBottom
    + CROSS_LANE_SECONDARY_RAIL_GAP
    + channel.index * CROSS_LANE_SECONDARY_RAIL_STEP
    + (Math.abs(hashText(args.edge.id)) % 3) * CROSS_LANE_SECONDARY_RAIL_STEP,
  );
}

function structuralAndFallbackRoutePoints(
  edge: LinkGraphEdge,
  edgeIndex: number,
  source: LinkGraphNode,
  target: LinkGraphNode,
  sourcePoint: GraphPosition,
  targetPoint: GraphPosition,
  sourcePort: string,
  targetPort: string,
  routeChannels: EdgeRouteChannels,
  context: ClassRouteContext,
  sizeSnapshot: ReadonlyMap<string, NodeMeasuredSize>,
  relation: string,
  sourceLane: ClassDiagramLane,
  targetLane: ClassDiagramLane,
  pairOffset: number,
  laneOffset: number,
): GraphPosition[] {
  const structuralRelation = isClassDiagramRoutedStructuralRelationKind(relation);
  if (structuralRelation && sourceLane !== "DATA" && targetLane !== "DATA") {
    if (sourceLane === targetLane && laneColumn(source) === laneColumn(target)) {
      return sameLaneVerticalRoutePoints(edge, source, target, sourcePoint, targetPoint, context, sizeSnapshot, laneOffset, pairOffset);
    }
    const laneX = sourcePoint.x <= targetPoint.x
      ? sourcePoint.x + Math.max(160, Math.round((targetPoint.x - sourcePoint.x) * 0.46)) + pairOffset
      : Math.min(nodeLeft(source), nodeLeft(target)) - OUTER_ROUTE_MARGIN_X - laneOffset;
    return [
      sourcePoint,
      { x: laneX, y: sourcePoint.y },
      { x: laneX, y: targetPoint.y },
      targetPoint,
    ];
  }
  if (structuralRelation && sourceLane !== "DATA" && targetLane === "DATA") {
    if (isHorizontalPort(sourcePort)) {
      const sourceOuterX = sourcePort === SOURCE_LEFT_PORT
        ? nodeLeft(source) - OUTER_ROUTE_MARGIN_X - laneOffset
        : nodeRight(source, sizeSnapshot) + OUTER_ROUTE_MARGIN_X + laneOffset;
      const targetSide = dataOuterSide(target);
      const targetOuterX = dataColumnOuterX(target, targetSide, context, sizeSnapshot, laneOffset);
      const laneY = dataTopRouteY(context, targetPoint.y, laneOffset);
      return [
        sourcePoint,
        { x: sourceOuterX, y: sourcePoint.y },
        { x: sourceOuterX, y: laneY },
        { x: targetOuterX, y: laneY },
        { x: targetOuterX, y: targetPoint.y },
        targetPoint,
      ];
    }
    const laneY = dataTopRouteY(context, targetPoint.y, laneOffset);
    if (dataRow(target) > 0) {
      const targetSide = dataOuterSide(target);
      const outerX = dataColumnOuterX(target, targetSide, context, sizeSnapshot, laneOffset);
      return [
        sourcePoint,
        { x: sourcePoint.x, y: laneY },
        { x: outerX, y: laneY },
        { x: outerX, y: targetPoint.y },
        targetPoint,
      ];
    }
    return [
      sourcePoint,
      { x: sourcePoint.x, y: laneY },
      { x: targetPoint.x, y: laneY },
      targetPoint,
    ];
  }
  if (structuralRelation && sourceLane === "DATA" && targetLane !== "DATA") {
    const sourceSide = dataOuterSideAwayFromTarget(source, target);
    const sourceOuterX = dataColumnOuterX(source, sourceSide, context, sizeSnapshot, laneOffset);
    const laneY = dataTopRouteY(context, sourcePoint.y, laneOffset);
    if (isHorizontalPort(targetPort)) {
      const targetOuterX = targetPort === LEFT_PORT
        ? nodeLeft(target) - OUTER_ROUTE_MARGIN_X - laneOffset
        : nodeRight(target, sizeSnapshot) + OUTER_ROUTE_MARGIN_X + laneOffset;
      return [
        sourcePoint,
        { x: sourceOuterX, y: sourcePoint.y },
        { x: sourceOuterX, y: laneY },
        { x: targetOuterX, y: laneY },
        { x: targetOuterX, y: targetPoint.y },
        targetPoint,
      ];
    }
    return [
      sourcePoint,
      { x: sourceOuterX, y: sourcePoint.y },
      { x: sourceOuterX, y: laneY },
      { x: targetPoint.x, y: laneY },
      targetPoint,
    ];
  }
  if (structuralRelation && sourceLane === "DATA" && targetLane === "DATA") {
    const sourceSide = dataOuterSide(source);
    const targetSide = dataOuterSide(target);
    const sourceOuterX = dataColumnOuterX(source, sourceSide, context, sizeSnapshot, laneOffset);
    const targetOuterX = dataColumnOuterX(target, targetSide, context, sizeSnapshot, laneOffset);
    if (dataColumn(source) !== dataColumn(target)) {
      const laneY = dataBottomRouteY(context, Math.max(sourcePoint.y, targetPoint.y), laneOffset);
      return [
        sourcePoint,
        { x: sourceOuterX, y: sourcePoint.y },
        { x: sourceOuterX, y: laneY },
        { x: targetOuterX, y: laneY },
        { x: targetOuterX, y: targetPoint.y },
        targetPoint,
      ];
    }
    return [
      sourcePoint,
      { x: sourceOuterX, y: sourcePoint.y },
      { x: sourceOuterX, y: targetPoint.y },
      targetPoint,
    ];
  }
  if (sourceLane === "ANCHOR" && targetLane === "OUTGOING") {
    return routeAnchorToOutgoingPoints(sourcePoint, targetPoint, target, sizeSnapshot, routeChannels.source, pairOffset, context);
  }
  if (isHorizontalPort(sourcePort) && isHorizontalPort(targetPort)) {
    const midX = sourcePoint.x <= targetPoint.x
      ? Math.round((sourcePoint.x + targetPoint.x) / 2) + routeLaneOffset(edgeIndex, relation)
      : Math.min(nodeLeft(source), nodeLeft(target)) - OUTER_ROUTE_MARGIN_X - Math.abs(routeLaneOffset(edgeIndex, relation));
    return [
      sourcePoint,
      { x: midX, y: sourcePoint.y },
      { x: midX, y: targetPoint.y },
      targetPoint,
    ];
  }
  if (!isHorizontalPort(sourcePort) && !isHorizontalPort(targetPort)) {
    const sourceAboveTarget = sourcePoint.y <= targetPoint.y;
    const siblingOffset = laneOffset;
    const laneY = sourceAboveTarget
      ? sourcePoint.y + Math.max(48, Math.round((targetPoint.y - sourcePoint.y) * 0.38)) + siblingOffset
      : Math.min(nodeTop(source), nodeTop(target)) - OUTER_ROUTE_MARGIN_Y - siblingOffset;
    return [
      sourcePoint,
      { x: sourcePoint.x, y: laneY },
      { x: targetPoint.x, y: laneY },
      targetPoint,
    ];
  }
  if (isHorizontalPort(sourcePort)) {
    const laneX = sourcePort === SOURCE_LEFT_PORT
      ? Math.min(nodeLeft(source), nodeLeft(target)) - OUTER_ROUTE_MARGIN_X - laneOffset
      : Math.max(nodeRight(source, sizeSnapshot), nodeRight(target, sizeSnapshot)) + OUTER_ROUTE_MARGIN_X + laneOffset;
    return [
      sourcePoint,
      { x: laneX, y: sourcePoint.y },
      { x: laneX, y: targetPoint.y },
      targetPoint,
    ];
  }
  const laneY = sourcePort === SOURCE_TOP_PORT
    ? Math.min(nodeTop(source), nodeTop(target)) - OUTER_ROUTE_MARGIN_Y - laneOffset
    : Math.max(nodeBottom(source, sizeSnapshot), nodeBottom(target, sizeSnapshot)) + OUTER_ROUTE_MARGIN_Y + laneOffset;
  return [
    sourcePoint,
    { x: sourcePoint.x, y: laneY },
    { x: targetPoint.x, y: laneY },
    targetPoint,
  ];
}

interface ManualRouteCandidate {
  sourcePort: string;
  targetPort: string;
  points: GraphPosition[];
  semanticPenalty: number;
}

interface ManualPortPairCandidate {
  sourcePort: string;
  targetPort: string;
  semanticPenalty: number;
}

function pushManualPortPair(
  candidates: ManualPortPairCandidate[],
  seen: Set<string>,
  sourcePort: string,
  targetPort: string,
  semanticPenalty: number,
) {
  const key = `${sourcePort}->${targetPort}`;
  if (seen.has(key)) {
    return;
  }
  seen.add(key);
  candidates.push({ sourcePort, targetPort, semanticPenalty });
}

function horizontalPortPairForRoute(
  edge: LinkGraphEdge,
  source: LinkGraphNode,
  target: LinkGraphNode,
  sizeSnapshot: ReadonlyMap<string, NodeMeasuredSize>,
  edgeIndexBySource: Map<string, LinkGraphEdge[]>,
  edgeIndexByTarget: Map<string, LinkGraphEdge[]>,
  nodeIndex: Map<string, LinkGraphNode>,
): { sourcePort: string; targetPort: string } {
  if (nodeCenterX(source, sizeSnapshot) <= nodeCenterX(target, sizeSnapshot)) {
    return {
      sourcePort: portWithSlot(RIGHT_PORT, relationFanoutSlot(edge, source.id, edgeIndexBySource, nodeIndex, sizeSnapshot, RIGHT_PORT)),
      targetPort: portWithSlot(LEFT_PORT, relationFanoutSlot(edge, target.id, edgeIndexByTarget, nodeIndex, sizeSnapshot, LEFT_PORT)),
    };
  }
  return {
    sourcePort: portWithSlot(SOURCE_LEFT_PORT, relationFanoutSlot(edge, source.id, edgeIndexBySource, nodeIndex, sizeSnapshot, SOURCE_LEFT_PORT)),
    targetPort: portWithSlot(TARGET_RIGHT_PORT, relationFanoutSlot(edge, target.id, edgeIndexByTarget, nodeIndex, sizeSnapshot, TARGET_RIGHT_PORT)),
  };
}

function verticalPortPairForRoute(
  source: LinkGraphNode,
  target: LinkGraphNode,
  sizeSnapshot: ReadonlyMap<string, NodeMeasuredSize>,
): { sourcePort: string; targetPort: string } {
  return nodeCenterY(source, sizeSnapshot) <= nodeCenterY(target, sizeSnapshot)
    ? { sourcePort: BOTTOM_PORT, targetPort: TOP_PORT }
    : { sourcePort: SOURCE_TOP_PORT, targetPort: TARGET_BOTTOM_PORT };
}

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

function routePortPairCandidates(
  edge: LinkGraphEdge,
  source: LinkGraphNode,
  target: LinkGraphNode,
  sizeSnapshot: ReadonlyMap<string, NodeMeasuredSize>,
  edgeIndexBySource: Map<string, LinkGraphEdge[]>,
  edgeIndexByTarget: Map<string, LinkGraphEdge[]>,
  nodeIndex: Map<string, LinkGraphNode>,
): ManualPortPairCandidate[] {
  const candidates: ManualPortPairCandidate[] = [];
  const seen = new Set<string>();
  const sourceLane = laneOf(source);
  const targetLane = laneOf(target);
  const structuralSameLaneOuterPorts = isClassDiagramRoutedStructuralRelation(edge)
    ? sameLaneOuterRoutePortsForEdge(edge, source, target, sizeSnapshot, edgeIndexBySource, edgeIndexByTarget, nodeIndex)
    : null;
  const preferredSourcePort = structuralSameLaneOuterPorts?.sourcePort
    ?? sourcePortForRoute(edge, source, target, sizeSnapshot, edgeIndexBySource, nodeIndex);
  const preferredTargetPort = structuralSameLaneOuterPorts?.targetPort
    ?? targetPortForRoute(edge, source, target, sizeSnapshot, edgeIndexByTarget, nodeIndex);
  if (isClassDiagramHierarchyRelation(edge)) {
    pushManualPortPair(candidates, seen, preferredSourcePort, preferredTargetPort, 0);
    return candidates;
  }
  if (isCrossLaneSecondaryStructuralEdge(edge, source, target)) {
    const sourceBottomClear = bottomApproachClear(source, nodeIndex, sizeSnapshot);
    const targetBottomClear = bottomApproachClear(target, nodeIndex, sizeSnapshot);
    const sourcePort = sourceBottomClear
      ? BOTTOM_PORT
      : portWithSlot(SOURCE_LEFT_PORT, relationFanoutSlot(edge, source.id, edgeIndexBySource, nodeIndex, sizeSnapshot, SOURCE_LEFT_PORT));
    const targetPort = targetBottomClear
      ? TARGET_BOTTOM_PORT
      : portWithSlot(TARGET_RIGHT_PORT, relationFanoutSlot(edge, target.id, edgeIndexByTarget, nodeIndex, sizeSnapshot, TARGET_RIGHT_PORT));
    pushManualPortPair(candidates, seen, sourcePort, targetPort, 0);
    return candidates;
  }
  if (isCorridorBoundMainRelation(edge, source, target)) {
    const horizontalPorts = horizontalPortPairForRoute(edge, source, target, sizeSnapshot, edgeIndexBySource, edgeIndexByTarget, nodeIndex);
    pushManualPortPair(candidates, seen, horizontalPorts.sourcePort, horizontalPorts.targetPort, 0);
    return candidates;
  }
  pushManualPortPair(candidates, seen, preferredSourcePort, preferredTargetPort, 0);

  const horizontalPorts = horizontalPortPairForRoute(edge, source, target, sizeSnapshot, edgeIndexBySource, edgeIndexByTarget, nodeIndex);
  pushManualPortPair(
    candidates,
    seen,
    horizontalPorts.sourcePort,
    horizontalPorts.targetPort,
    sourceLane === "ANCHOR" || targetLane === "ANCHOR" ? 18 : 34,
  );

  const sameLaneOuterPorts = sameLaneOuterRoutePortsForEdge(edge, source, target, sizeSnapshot, edgeIndexBySource, edgeIndexByTarget, nodeIndex);
  if (sameLaneOuterPorts) {
    pushManualPortPair(
      candidates,
      seen,
      sameLaneOuterPorts.sourcePort,
      sameLaneOuterPorts.targetPort,
      isClassDiagramRoutedStructuralRelation(edge) ? 8 : 58,
    );
  }

  const structuralSameColumn = Boolean(structuralSameLaneOuterPorts)
    && sourceLane === targetLane
    && laneColumn(source) === laneColumn(target);
  const verticalGap = Math.abs(nodeCenterY(source, sizeSnapshot) - nodeCenterY(target, sizeSnapshot));
  if (!structuralSameColumn && verticalGap >= 42) {
    const verticalPorts = verticalPortPairForRoute(source, target, sizeSnapshot);
    const anchorRelation = sourceLane === "ANCHOR" || targetLane === "ANCHOR";
    const sameLaneRelation = sourceLane === targetLane;
    pushManualPortPair(
      candidates,
      seen,
      verticalPorts.sourcePort,
      verticalPorts.targetPort,
      anchorRelation ? 118 : sameLaneRelation ? 46 : 72,
    );
  }

  return candidates;
}

function buildManualRouteCandidates(
  edge: LinkGraphEdge,
  edgeIndex: number,
  source: LinkGraphNode,
  target: LinkGraphNode,
  sizeSnapshot: ReadonlyMap<string, NodeMeasuredSize>,
  edgeIndexBySource: Map<string, LinkGraphEdge[]>,
  edgeIndexByTarget: Map<string, LinkGraphEdge[]>,
  routeContext: ClassRouteContext,
  nodeIndex: Map<string, LinkGraphNode>,
): ManualRouteCandidate[] {
  const routeCandidates: ManualRouteCandidate[] = [];
  const seen = new Set<string>();
  routePortPairCandidates(edge, source, target, sizeSnapshot, edgeIndexBySource, edgeIndexByTarget, nodeIndex)
    .forEach(({ sourcePort, targetPort, semanticPenalty }) => {
      const sourceLane = laneOf(source);
      const targetLane = laneOf(target);
      const sameLaneVerticalRelation = sourceLane === targetLane && !isHorizontalPort(sourcePort) && !isHorizontalPort(targetPort);
      const sourceOffset = portSlot(sourcePort) == null && !sameLaneVerticalRelation ? edgeOffset(edge, edgeIndexBySource, edge.source) : 0;
      const targetOffset = portSlot(targetPort) == null && !sameLaneVerticalRelation ? edgeOffset(edge, edgeIndexByTarget, edge.target) : 0;
      const sourcePoint = portPoint(source, sourcePort, sizeSnapshot, sourceOffset);
      const targetPoint = portPoint(target, targetPort, sizeSnapshot, targetOffset);
      const routeChannels: EdgeRouteChannels = {
        source: relationFanoutChannel(edge, source.id, edgeIndexBySource, nodeIndex, sizeSnapshot),
        target: relationFanoutChannel(edge, target.id, edgeIndexByTarget, nodeIndex, sizeSnapshot),
      };
      const routeOptions = [
        {
          points: routePointsForPorts(
            edge,
            edgeIndex,
            source,
            target,
            sourcePoint,
            targetPoint,
            sourcePort,
            targetPort,
            routeChannels,
            routeContext,
            sizeSnapshot,
          ),
          penalty: semanticPenalty,
        },
      ];
      if (!isCrossLaneSecondaryStructuralEdge(edge, source, target) && !isCorridorBoundMainRelation(edge, source, target)) {
        routeOptions.push({
          points: buildAvoidingRoutePoints(source, target, sourcePoint, targetPoint, sourcePort, targetPort, routeContext, sizeSnapshot),
          penalty: semanticPenalty + 92,
        });
      }
      routeOptions.forEach(({ points, penalty }) => {
        const simplified = simplifyRoutePoints(points);
        const key = `${sourcePort}->${targetPort}|${routeCandidateKey(simplified)}`;
        if (seen.has(key)) {
          return;
        }
        seen.add(key);
        routeCandidates.push({
          sourcePort,
          targetPort,
          points: simplified,
          semanticPenalty: penalty,
        });
      });
    });
  return routeCandidates;
}

function selectManualRouteCandidate(
  edge: LinkGraphEdge,
  candidates: ManualRouteCandidate[],
  routedEdges: LinkGraphEdge[],
  nodeIndex: Map<string, LinkGraphNode>,
  sizeSnapshot: ReadonlyMap<string, NodeMeasuredSize>,
  readability: ClassDiagramReadabilityScorer,
): ManualRouteCandidate | null {
  return candidates
    .map((candidate) => {
      const candidateEdge = { ...edge, route: routeFromPoints(candidate.points) };
      return {
        ...candidate,
        score: readability.scoreRouteAgainstGraph(candidate.points, candidateEdge, routedEdges, nodeIndex, sizeSnapshot, candidate.semanticPenalty),
      };
    })
    .sort((left, right) =>
      left.score - right.score
      || left.semanticPenalty - right.semanticPenalty
      || routeLength(left.points) - routeLength(right.points),
    )[0] ?? null;
}

function routeManualClassEdge(
  edge: LinkGraphEdge,
  edgeIndex: number,
  nodeIndex: Map<string, LinkGraphNode>,
  sizeSnapshot: ReadonlyMap<string, NodeMeasuredSize>,
  edgeIndexBySource: Map<string, LinkGraphEdge[]>,
  edgeIndexByTarget: Map<string, LinkGraphEdge[]>,
  routeContext: ClassRouteContext,
  routedEdges: LinkGraphEdge[],
  readability: ClassDiagramReadabilityScorer,
): LinkGraphEdge {
  const source = nodeIndex.get(edge.source);
  const target = nodeIndex.get(edge.target);
  if (!source?.position || !target?.position) {
    return edge;
  }
  const sourceLane = laneOf(source);
  const targetLane = laneOf(target);
  const selectedRoute = selectManualRouteCandidate(
    edge,
    buildManualRouteCandidates(
      edge,
      edgeIndex,
      source,
      target,
      sizeSnapshot,
      edgeIndexBySource,
      edgeIndexByTarget,
      routeContext,
      nodeIndex,
    ),
    routedEdges,
    nodeIndex,
    sizeSnapshot,
    readability,
  );
  if (!selectedRoute) {
    return edge;
  }
  const anchorRelation = sourceLane === "ANCHOR" || targetLane === "ANCHOR";
  const sameLaneRelation = sourceLane === targetLane && sourceLane !== "ANCHOR";
  const route = routeFromPoints(selectedRoute.points);
  return {
    ...edge,
    sourceHandle: selectedRoute.sourcePort,
    targetHandle: selectedRoute.targetPort,
    route,
    metadata: {
      ...(edge.metadata ?? {}),
      "layout.sourcePort": selectedRoute.sourcePort,
      "layout.targetPort": selectedRoute.targetPort,
      "layout.route": "class-diagram-lane",
      "layout.anchorRelation": String(anchorRelation),
      "layout.sameLaneRelation": String(sameLaneRelation),
      "layout.labelPlacement": targetLane === "ANCHOR" ? "source-stub" : "target-stub",
    },
  };
}

function routeCandidateKey(points: GraphPosition[]): string {
  return simplifyRoutePoints(points).map((point) => `${point.x},${point.y}`).join("|");
}

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

function routeContextNodes(context: ClassRouteContext): LinkGraphNode[] {
  return Array.from(context.laneNodes.values()).flat();
}

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

function axisChannelPenalty(channel: number, currentChannel: number | undefined, basePenalty: number): number {
  if (currentChannel == null) {
    return basePenalty;
  }
  return basePenalty + Math.min(46, Math.abs(channel - currentChannel) * 0.02);
}

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

function repairEdgeRouteCrossings(
  edges: LinkGraphEdge[],
  nodeIndex: Map<string, LinkGraphNode>,
  context: ClassRouteContext,
  sizeSnapshot: ReadonlyMap<string, NodeMeasuredSize>,
  readability: ClassDiagramReadabilityScorer,
): LinkGraphEdge[] {
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
      repaired = repaired.map((candidate) => candidate.id === edge.id
        ? {
            ...candidate,
            route: routeFromPoints(best.points),
            metadata: {
              ...(candidate.metadata ?? {}),
              "layout.edgeCrossingRepair": "true",
            },
          }
        : candidate);
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

function preserveClassDiagramRoutes(edges: LinkGraphEdge[]): LinkGraphEdge[] {
  return edges.map((edge) => ({
    ...edge,
    metadata: {
      ...(edge.metadata ?? {}),
      "layout.routeMode": "stored",
      "layout.labelPlacement": edge.metadata?.["layout.labelPlacement"] ?? "target-stub",
    },
  }));
}

function summarizeClassDiagramRoutes(edges: LinkGraphEdge[], nodeIndex: Map<string, LinkGraphNode>) {
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

function roundTracePoint(point: GraphPosition): GraphPosition {
  return {
    x: Math.round(point.x * 10) / 10,
    y: Math.round(point.y * 10) / 10,
  };
}
