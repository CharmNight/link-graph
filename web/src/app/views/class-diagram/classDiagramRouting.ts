import type { NodeMeasuredSize } from "../../graph/nodeSizeRegistry";
import { traceLinkGraph } from "../../debug";
import type { GraphPosition, LinkGraphEdge, LinkGraphNode } from "../../types";
import { buildOrthogonalEdgeRoute } from "../../reactflow/orthogonalEdgeRouting";
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
  nodeCenterY,
  nodeLeft,
  nodeRight,
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
  routeSectionPoints,
  simplifyRoutePoints,
  sourceSideForPort,
  targetSideForPort,
  portBase,
  type ClassDiagramLane,
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
import {
  preserveClassDiagramRoutes,
  repairEdgeRouteCrossings,
  summarizeClassDiagramRoutes,
} from "./classDiagramRouteRepair";
import {
  buildClassRouteContext,
  routeContextNodes,
} from "./classDiagramRouteContext";
import {
  bottomApproachClear,
  horizontalPortPairForRoute,
  isCrossLaneSecondaryStructuralEdge,
  relationFanoutChannel,
  relationFanoutSlot,
  shouldUseVerticalStackPorts,
  sourcePortForRoute,
  targetPortForRoute,
  verticalPortPairForRoute,
} from "./classDiagramPortRouting";

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

function compactHorizontalRoutePoints(sourcePoint: GraphPosition, targetPoint: GraphPosition): GraphPosition[] {
  if (Math.abs(sourcePoint.y - targetPoint.y) <= 0.5) return [sourcePoint, targetPoint];
  const midX = Math.round((sourcePoint.x + targetPoint.x) / 2);
  return [sourcePoint, { x: midX, y: sourcePoint.y }, { x: midX, y: targetPoint.y }, targetPoint];
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
    return compactHorizontalRoutePoints(sourcePoint, targetPoint);
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
    return compactHorizontalRoutePoints(sourcePoint, targetPoint);
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
    const verticalPorts = verticalPortPairForRoute(source, target, sizeSnapshot);
    const horizontalPorts = horizontalPortPairForRoute(edge, source, target, sizeSnapshot, edgeIndexBySource, edgeIndexByTarget, nodeIndex);
    const verticalPreferred = shouldUseVerticalStackPorts(source, target, sizeSnapshot);
    const verticalGap = Math.abs(nodeCenterY(source, sizeSnapshot) - nodeCenterY(target, sizeSnapshot));
    pushManualPortPair(
      candidates,
      seen,
      verticalPreferred ? verticalPorts.sourcePort : horizontalPorts.sourcePort,
      verticalPreferred ? verticalPorts.targetPort : horizontalPorts.targetPort,
      0,
    );
    if (verticalPreferred) {
      pushManualPortPair(candidates, seen, horizontalPorts.sourcePort, horizontalPorts.targetPort, 54);
    } else if (verticalGap >= 42) {
      pushManualPortPair(candidates, seen, verticalPorts.sourcePort, verticalPorts.targetPort, 144);
    }
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
