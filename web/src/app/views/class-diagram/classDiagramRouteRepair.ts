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

export function repairEdgeRouteCrossings(
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

function roundTracePoint(point: GraphPosition): GraphPosition {
  return {
    x: Math.round(point.x * 10) / 10,
    y: Math.round(point.y * 10) / 10,
  };
}

