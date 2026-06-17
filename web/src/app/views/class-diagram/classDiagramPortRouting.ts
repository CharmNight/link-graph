import type { NodeMeasuredSize } from "../../graph/nodeSizeRegistry";
import type { LinkGraphEdge, LinkGraphNode } from "../../types";
import {
  BOTTOM_PORT,
  EDGE_CROSSING_REPAIR_NODE_CLEARANCE,
  LEFT_PORT,
  RIGHT_PORT,
  SOURCE_LEFT_PORT,
  SOURCE_TOP_PORT,
  TARGET_BOTTOM_PORT,
  TARGET_RIGHT_PORT,
  TOP_PORT,
  dataOuterSide,
  dataOuterSideAwayFromTarget,
  dataRow,
  edgeSortKey,
  laneColumn,
  laneOf,
  nodeBounds,
  nodeBottom,
  nodeCenterX,
  nodeCenterY,
  nodeLeft,
  nodeRight,
  nodeTop,
  portWithSlot,
  sideFanoutSlot,
  sourceSideForPort,
  type RouteChannel,
} from "./classDiagramLayoutModel";
import {
  classDiagramRelationKind,
  isClassDiagramHierarchyRelation,
  isClassDiagramRoutedStructuralRelation,
} from "./classDiagramRelations";

type EndpointSide = ReturnType<typeof sourceSideForPort>;

export function relationEndpointOrderKey(
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

export function relationFanoutSlot(
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
    .filter((candidate) => relationEndpointSide(candidate, nodeId, nodeIndex, sizeSnapshot) === side)
    .sort((left, right) =>
      relationEndpointOrderKey(left, nodeId, nodeIndex, sizeSnapshot)
        .localeCompare(relationEndpointOrderKey(right, nodeId, nodeIndex, sizeSnapshot)),
    );
  const index = Math.max(0, siblings.findIndex((candidate) => candidate.id === edge.id));
  return sideFanoutSlot(index, siblings.length);
}

export function relationFanoutChannel(
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

function verticalSourceSideForRoute(
  source: LinkGraphNode,
  target: LinkGraphNode,
  sizeSnapshot: ReadonlyMap<string, NodeMeasuredSize>,
): EndpointSide {
  return nodeCenterY(source, sizeSnapshot) > nodeCenterY(target, sizeSnapshot) ? "top" : "bottom";
}

function verticalTargetSideForRoute(
  source: LinkGraphNode,
  target: LinkGraphNode,
  sizeSnapshot: ReadonlyMap<string, NodeMeasuredSize>,
): EndpointSide {
  return nodeCenterY(source, sizeSnapshot) > nodeCenterY(target, sizeSnapshot) ? "bottom" : "top";
}

function horizontalSourceSideForRoute(
  source: LinkGraphNode,
  target: LinkGraphNode,
  sizeSnapshot: ReadonlyMap<string, NodeMeasuredSize>,
): EndpointSide {
  return nodeCenterX(source, sizeSnapshot) > nodeCenterX(target, sizeSnapshot) ? "left" : "right";
}

function horizontalTargetSideForRoute(
  source: LinkGraphNode,
  target: LinkGraphNode,
  sizeSnapshot: ReadonlyMap<string, NodeMeasuredSize>,
): EndpointSide {
  return nodeCenterX(source, sizeSnapshot) > nodeCenterX(target, sizeSnapshot) ? "right" : "left";
}

function nodeHorizontalOverlap(
  source: LinkGraphNode,
  target: LinkGraphNode,
  sizeSnapshot: ReadonlyMap<string, NodeMeasuredSize>,
): number {
  return Math.max(0, Math.min(nodeRight(source, sizeSnapshot), nodeRight(target, sizeSnapshot)) - Math.max(nodeLeft(source), nodeLeft(target)));
}

function nodeVerticalGap(
  source: LinkGraphNode,
  target: LinkGraphNode,
  sizeSnapshot: ReadonlyMap<string, NodeMeasuredSize>,
): number {
  const upperBottom = Math.min(nodeBottom(source, sizeSnapshot), nodeBottom(target, sizeSnapshot));
  const lowerTop = Math.max(nodeTop(source), nodeTop(target));
  return Math.max(0, lowerTop - upperBottom);
}

function visuallySameColumn(
  source: LinkGraphNode,
  target: LinkGraphNode,
  sizeSnapshot: ReadonlyMap<string, NodeMeasuredSize>,
): boolean {
  if (laneColumn(source) !== laneColumn(target)) {
    return false;
  }
  const sourceWidth = nodeRight(source, sizeSnapshot) - nodeLeft(source);
  const targetWidth = nodeRight(target, sizeSnapshot) - nodeLeft(target);
  const narrowWidth = Math.max(1, Math.min(sourceWidth, targetWidth));
  const centerDelta = Math.abs(nodeCenterX(source, sizeSnapshot) - nodeCenterX(target, sizeSnapshot));
  const overlap = nodeHorizontalOverlap(source, target, sizeSnapshot);
  return centerDelta <= Math.max(48, narrowWidth * 0.35) || overlap >= narrowWidth * 0.45;
}

export function shouldUseVerticalStackPorts(
  source: LinkGraphNode,
  target: LinkGraphNode,
  sizeSnapshot: ReadonlyMap<string, NodeMeasuredSize>,
): boolean {
  if (!visuallySameColumn(source, target, sizeSnapshot)) {
    return false;
  }
  return nodeVerticalGap(source, target, sizeSnapshot) >= 24
    || Math.abs(nodeCenterY(source, sizeSnapshot) - nodeCenterY(target, sizeSnapshot)) >= 80;
}

function verticalSourcePortForRoute(
  source: LinkGraphNode,
  target: LinkGraphNode,
  sizeSnapshot: ReadonlyMap<string, NodeMeasuredSize>,
): string {
  return verticalSourceSideForRoute(source, target, sizeSnapshot) === "top" ? SOURCE_TOP_PORT : BOTTOM_PORT;
}

function verticalTargetPortForRoute(
  source: LinkGraphNode,
  target: LinkGraphNode,
  sizeSnapshot: ReadonlyMap<string, NodeMeasuredSize>,
): string {
  return verticalTargetSideForRoute(source, target, sizeSnapshot) === "bottom" ? TARGET_BOTTOM_PORT : TOP_PORT;
}

function horizontalSourcePortForRoute(
  edge: LinkGraphEdge,
  source: LinkGraphNode,
  target: LinkGraphNode,
  sizeSnapshot: ReadonlyMap<string, NodeMeasuredSize>,
  edgeIndexBySource: Map<string, LinkGraphEdge[]>,
  nodeIndex: Map<string, LinkGraphNode>,
): string {
  const port = horizontalSourceSideForRoute(source, target, sizeSnapshot) === "left" ? SOURCE_LEFT_PORT : RIGHT_PORT;
  return portWithSlot(port, relationFanoutSlot(edge, source.id, edgeIndexBySource, nodeIndex, sizeSnapshot, port));
}

function horizontalTargetPortForRoute(
  edge: LinkGraphEdge,
  source: LinkGraphNode,
  target: LinkGraphNode,
  sizeSnapshot: ReadonlyMap<string, NodeMeasuredSize>,
  edgeIndexByTarget: Map<string, LinkGraphEdge[]>,
  nodeIndex: Map<string, LinkGraphNode>,
): string {
  const port = horizontalTargetSideForRoute(source, target, sizeSnapshot) === "right" ? TARGET_RIGHT_PORT : LEFT_PORT;
  return portWithSlot(port, relationFanoutSlot(edge, target.id, edgeIndexByTarget, nodeIndex, sizeSnapshot, port));
}

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
    return shouldUseVerticalStackPorts(source, target, sizeSnapshot)
      ? verticalSourceSideForRoute(source, target, sizeSnapshot)
      : horizontalSourceSideForRoute(source, target, sizeSnapshot);
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
    return shouldUseVerticalStackPorts(source, target, sizeSnapshot)
      ? verticalTargetSideForRoute(source, target, sizeSnapshot)
      : horizontalTargetSideForRoute(source, target, sizeSnapshot);
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

export function sourcePortForRoute(
  edge: LinkGraphEdge,
  source: LinkGraphNode,
  target: LinkGraphNode,
  sizeSnapshot: ReadonlyMap<string, NodeMeasuredSize>,
  edgeIndexBySource: Map<string, LinkGraphEdge[]>,
  nodeIndex: Map<string, LinkGraphNode>,
) {
  if (isClassDiagramHierarchyRelation(edge)) {
    if (shouldUseVerticalStackPorts(source, target, sizeSnapshot)) {
      return verticalSourcePortForRoute(source, target, sizeSnapshot);
    }
    return horizontalSourcePortForRoute(edge, source, target, sizeSnapshot, edgeIndexBySource, nodeIndex);
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

export function targetPortForRoute(
  edge: LinkGraphEdge,
  source: LinkGraphNode,
  target: LinkGraphNode,
  sizeSnapshot: ReadonlyMap<string, NodeMeasuredSize>,
  edgeIndexByTarget: Map<string, LinkGraphEdge[]>,
  nodeIndex: Map<string, LinkGraphNode>,
) {
  if (isClassDiagramHierarchyRelation(edge)) {
    if (shouldUseVerticalStackPorts(source, target, sizeSnapshot)) {
      return verticalTargetPortForRoute(source, target, sizeSnapshot);
    }
    return horizontalTargetPortForRoute(edge, source, target, sizeSnapshot, edgeIndexByTarget, nodeIndex);
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

export function horizontalPortPairForRoute(
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

export function verticalPortPairForRoute(
  source: LinkGraphNode,
  target: LinkGraphNode,
  sizeSnapshot: ReadonlyMap<string, NodeMeasuredSize>,
): { sourcePort: string; targetPort: string } {
  return nodeCenterY(source, sizeSnapshot) <= nodeCenterY(target, sizeSnapshot)
    ? { sourcePort: BOTTOM_PORT, targetPort: TOP_PORT }
    : { sourcePort: SOURCE_TOP_PORT, targetPort: TARGET_BOTTOM_PORT };
}

export function isCrossLaneSecondaryStructuralEdge(
  edge: LinkGraphEdge,
  source: LinkGraphNode,
  target: LinkGraphNode,
): boolean {
  return isClassDiagramRoutedStructuralRelation(edge)
    && laneOf(source) === "INCOMING"
    && laneOf(target) === "OUTGOING";
}

export function bottomApproachClear(
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
