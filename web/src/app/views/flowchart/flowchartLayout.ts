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
import { buildFlowchartLayoutModel, type FlowchartExpansionGroup } from "./flowchartLayoutModel";

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

const DEFAULT_FLOWCHART_NODE_HEIGHT = 156;
const EXPANSION_LANE_GAP = 160;
const EXPANSION_SOURCE_GAP = 96;
const CALL_EDGE_OBSTACLE_GAP = 48;
const ROUTE_INTERSECTION_EPSILON = 1;

function nodeHeight(node: LinkGraphNode): number {
  return flowchartKind(node) === "DECISION" ? 228 : DEFAULT_FLOWCHART_NODE_HEIGHT;
}

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

function flowchartKind(node?: MeasuredLayoutRequest["nodes"][number]): string {
  return resolveFlowchartKind(node);
}

function normalizedFlowLabel(edge?: MeasuredLayoutRequest["edges"][number]): string {
  return edge?.label?.trim().toUpperCase() ?? "";
}

function flowEdgeRole(edge?: LinkGraphEdge): string {
  return edge?.metadata?.["flow.edgeRole"]?.toUpperCase() ?? "";
}

function flowScopeCategory(node?: LinkGraphNode): string {
  return node?.metadata?.["flow.scopeCategory"] ?? "";
}

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

function translatePoint(point: GraphPosition, delta: GraphPosition): GraphPosition {
  return {
    x: point.x + delta.x,
    y: point.y + delta.y,
  };
}

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

function expansionGroupBounds(
  group: FlowchartExpansionGroup,
  nodeIndex: Map<string, LinkGraphNode>,
) {
  const groupNodes = group.nodeIds.map((nodeId) => nodeIndex.get(nodeId)).filter((node): node is LinkGraphNode => Boolean(node?.position));
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

type FlowchartNodeBounds = ReturnType<typeof nodeBounds>;
type RouteSegment = { startPoint: GraphPosition; endPoint: GraphPosition };

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

function routeSegmentsFromPoints(points: GraphPosition[]): RouteSegment[] {
  const compacted = compactRoutePoints(points);
  return compacted.slice(1).map((point, index) => ({
    startPoint: compacted[index]!,
    endPoint: point,
  }));
}

function isVerticalSegment(segment: RouteSegment): boolean {
  return Math.abs(segment.startPoint.x - segment.endPoint.x) <= 0.5;
}

function isHorizontalSegment(segment: RouteSegment): boolean {
  return Math.abs(segment.startPoint.y - segment.endPoint.y) <= 0.5;
}

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

function routePointsCrossSegments(points: GraphPosition[], segments: RouteSegment[]): boolean {
  return routeSegmentsFromPoints(points).some((candidateSegment) =>
    segments.some((segment) => segmentsCross(candidateSegment, segment)),
  );
}

function routeSegmentCrossCount(points: GraphPosition[], segments: RouteSegment[]): number {
  return routeSegmentsFromPoints(points).reduce(
    (count, candidateSegment) => count + segments.filter((segment) => segmentsCross(candidateSegment, segment)).length,
    0,
  );
}

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

function routePointCost(points: GraphPosition[]): number {
  const compacted = compactRoutePoints(points);
  return compacted.slice(1).reduce((total, point, index) => {
    const previous = compacted[index]!;
    return total + Math.abs(previous.x - point.x) + Math.abs(previous.y - point.y);
  }, 0);
}

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

function uniqueNumbers(values: number[]): number[] {
  return Array.from(new Set(values.map((value) => Math.round(value))));
}

function boundsOverlapHorizontalRange(bounds: FlowchartNodeBounds, left: number, right: number): boolean {
  return Math.max(left, bounds.left) < Math.min(right, bounds.right);
}

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

function segmentOverlapsHorizontalRange(segment: RouteSegment, left: number, right: number): boolean {
  if (isVerticalSegment(segment)) {
    const x = segment.startPoint.x;
    return x > left + ROUTE_INTERSECTION_EPSILON && x < right - ROUTE_INTERSECTION_EPSILON;
  }
  const segmentLeft = Math.min(segment.startPoint.x, segment.endPoint.x);
  const segmentRight = Math.max(segment.startPoint.x, segment.endPoint.x);
  return Math.max(left, segmentLeft) < Math.min(right, segmentRight);
}

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

function applyInvocationExpansionLayout(
  nodes: LinkGraphNode[],
  edges: LinkGraphEdge[],
): { nodes: LinkGraphNode[]; edges: LinkGraphEdge[] } {
  const model = buildFlowchartLayoutModel(nodes, edges);
  if (model.expansionGroups.length === 0) {
    return { nodes, edges };
  }
  const nodeIndex = new Map(nodes.map((node) => [node.id, node]));
  const mainNodes = model.mainNodeIds.map((nodeId) => nodeIndex.get(nodeId)).filter((node): node is LinkGraphNode => Boolean(node?.position));
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
  const expansionLaneLeft = mainRouteRight + EXPANSION_LANE_GAP;
  const nodeDeltas = new Map<string, GraphPosition>();
  const sourceStackCounts = new Map<string, number>();
  let nextExpansionLaneTop: number | null = null;

  model.expansionGroups.forEach((group) => {
    const sourceNode = group.sourceInvocationNodeId ? nodeIndex.get(group.sourceInvocationNodeId) : null;
    const rootNode = group.rootNodeId ? nodeIndex.get(group.rootNodeId) : null;
    const bounds = expansionGroupBounds(group, nodeIndex);
    if (!sourceNode?.position || !rootNode?.position || !bounds) {
      return;
    }
    const sourceBounds = nodeBounds(sourceNode);
    const rootBounds = nodeBounds(rootNode);
    const stackKey = group.sourceInvocationNodeId ?? group.expansionId;
    const stackIndex = sourceStackCounts.get(stackKey) ?? 0;
    sourceStackCounts.set(stackKey, stackIndex + 1);
    const targetLeft = expansionLaneLeft;
    const preferredRootTop = sourceBounds.top + stackIndex * (bounds.bottom - bounds.top + EXPANSION_SOURCE_GAP);
    const targetRootTop = Math.max(preferredRootTop, nextExpansionLaneTop ?? preferredRootTop);
    const delta = {
      x: Math.round(targetLeft - rootBounds.left),
      y: Math.round(targetRootTop - rootBounds.top),
    };
    group.nodeIds.forEach((nodeId) => nodeDeltas.set(nodeId, delta));
    nextExpansionLaneTop = bounds.bottom + delta.y + EXPANSION_SOURCE_GAP;
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
  const internalEdgeIds = new Set(model.expansionGroups.flatMap((group) => group.internalEdgeIds));
  const callEdgeIds = new Set(model.expansionGroups.flatMap((group) => group.callEdgeIds));
  const callObstacleSegments = edges
    .filter((edge) => edge.type === "CONTROL_FLOW" && !internalEdgeIds.has(edge.id))
    .flatMap(routeSegmentsFromEdge);
  const edgeDeltaById = new Map<string, GraphPosition>();
  model.expansionGroups.forEach((group) => {
    const firstDelta = group.nodeIds.map((nodeId) => nodeDeltas.get(nodeId)).find((delta): delta is GraphPosition => Boolean(delta));
    if (!firstDelta) {
      return;
    }
    group.internalEdgeIds.forEach((edgeId) => edgeDeltaById.set(edgeId, firstDelta));
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
