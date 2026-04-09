import type { GraphPosition, LinkGraphEdgeRoute } from "../types";

export type OrthogonalSide = "top" | "right" | "bottom" | "left";

export interface OrthogonalRect {
  id?: string;
  x: number;
  y: number;
  width: number;
  height: number;
}

interface ExpandedRect {
  id?: string;
  left: number;
  right: number;
  top: number;
  bottom: number;
}

export interface BuildOrthogonalEdgeRouteOptions {
  startPoint: GraphPosition;
  startSide: OrthogonalSide;
  startRect: OrthogonalRect;
  endPoint: GraphPosition;
  endSide: OrthogonalSide;
  endRect: OrthogonalRect;
  obstacleRects: OrthogonalRect[];
}

const OBSTACLE_PADDING = 24;
const PORT_STUB = 32;
const TURN_PENALTY = 28;
const EPSILON = 0.5;

function round(value: number): number {
  return Math.round(value * 1000) / 1000;
}

function pointKey(point: GraphPosition): string {
  return `${round(point.x)}:${round(point.y)}`;
}

function expandedRect(rect: OrthogonalRect, padding = OBSTACLE_PADDING): ExpandedRect {
  return {
    id: rect.id,
    left: rect.x - padding,
    right: rect.x + rect.width + padding,
    top: rect.y - padding,
    bottom: rect.y + rect.height + padding,
  };
}

function pointInsideExpandedRect(point: GraphPosition, rect: ExpandedRect): boolean {
  return point.x > rect.left + EPSILON
    && point.x < rect.right - EPSILON
    && point.y > rect.top + EPSILON
    && point.y < rect.bottom - EPSILON;
}

function isHorizontalSegment(startPoint: GraphPosition, endPoint: GraphPosition): boolean {
  return Math.abs(startPoint.y - endPoint.y) <= EPSILON;
}

function isVerticalSegment(startPoint: GraphPosition, endPoint: GraphPosition): boolean {
  return Math.abs(startPoint.x - endPoint.x) <= EPSILON;
}

function segmentIntersectsRect(
  startPoint: GraphPosition,
  endPoint: GraphPosition,
  rect: ExpandedRect,
): boolean {
  if (isVerticalSegment(startPoint, endPoint)) {
    const x = startPoint.x;
    if (x <= rect.left + EPSILON || x >= rect.right - EPSILON) {
      return false;
    }
    const top = Math.min(startPoint.y, endPoint.y);
    const bottom = Math.max(startPoint.y, endPoint.y);
    return Math.max(top, rect.top) < Math.min(bottom, rect.bottom);
  }
  if (isHorizontalSegment(startPoint, endPoint)) {
    const y = startPoint.y;
    if (y <= rect.top + EPSILON || y >= rect.bottom - EPSILON) {
      return false;
    }
    const left = Math.min(startPoint.x, endPoint.x);
    const right = Math.max(startPoint.x, endPoint.x);
    return Math.max(left, rect.left) < Math.min(right, rect.right);
  }
  return true;
}

function segmentClear(
  startPoint: GraphPosition,
  endPoint: GraphPosition,
  obstacles: ExpandedRect[],
): boolean {
  return obstacles.every((rect) => !segmentIntersectsRect(startPoint, endPoint, rect));
}

function offsetFromSide(point: GraphPosition, side: OrthogonalSide, distance: number): GraphPosition {
  switch (side) {
    case "top":
      return { x: point.x, y: point.y - distance };
    case "right":
      return { x: point.x + distance, y: point.y };
    case "bottom":
      return { x: point.x, y: point.y + distance };
    case "left":
      return { x: point.x - distance, y: point.y };
  }
}

function addCoordinate(set: Set<number>, value: number) {
  set.add(round(value));
}

function simplifyPoints(points: GraphPosition[]): GraphPosition[] {
  const deduped: GraphPosition[] = [];
  points.forEach((point) => {
    const previous = deduped[deduped.length - 1];
    if (!previous || Math.abs(previous.x - point.x) > EPSILON || Math.abs(previous.y - point.y) > EPSILON) {
      deduped.push({ x: round(point.x), y: round(point.y) });
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
    const sameVertical = isVerticalSegment(previous, current) && isVerticalSegment(current, next);
    const sameHorizontal = isHorizontalSegment(previous, current) && isHorizontalSegment(current, next);
    if (sameVertical || sameHorizontal) {
      continue;
    }
    simplified.push(current);
  }
  simplified.push(deduped[deduped.length - 1]!);
  return simplified;
}

interface CandidateNode {
  point: GraphPosition;
  key: string;
}

interface RouteState {
  cost: number;
  estimate: number;
  key: string;
  pointKey: string;
  direction: "horizontal" | "vertical" | "start";
}

function heuristic(left: GraphPosition, right: GraphPosition): number {
  return Math.abs(left.x - right.x) + Math.abs(left.y - right.y);
}

function buildAdjacency(points: GraphPosition[], obstacles: ExpandedRect[]): Map<string, Array<{
  point: GraphPosition;
  cost: number;
  direction: "horizontal" | "vertical";
}>> {
  const adjacency = new Map<string, Array<{ point: GraphPosition; cost: number; direction: "horizontal" | "vertical" }>>();
  const byX = new Map<number, CandidateNode[]>();
  const byY = new Map<number, CandidateNode[]>();

  points.forEach((point) => {
    const key = pointKey(point);
    const xKey = round(point.x);
    const yKey = round(point.y);
    const candidate = { point, key };
    const sameX = byX.get(xKey) ?? [];
    sameX.push(candidate);
    byX.set(xKey, sameX);
    const sameY = byY.get(yKey) ?? [];
    sameY.push(candidate);
    byY.set(yKey, sameY);
    adjacency.set(key, []);
  });

  byY.forEach((nodesOnRow) => {
    nodesOnRow.sort((left, right) => left.point.x - right.point.x);
    for (let index = 1; index < nodesOnRow.length; index += 1) {
      const leftNode = nodesOnRow[index - 1]!;
      const rightNode = nodesOnRow[index]!;
      if (!segmentClear(leftNode.point, rightNode.point, obstacles)) {
        continue;
      }
      const cost = Math.abs(leftNode.point.x - rightNode.point.x);
      adjacency.get(leftNode.key)?.push({ point: rightNode.point, cost, direction: "horizontal" });
      adjacency.get(rightNode.key)?.push({ point: leftNode.point, cost, direction: "horizontal" });
    }
  });

  byX.forEach((nodesOnColumn) => {
    nodesOnColumn.sort((top, bottom) => top.point.y - bottom.point.y);
    for (let index = 1; index < nodesOnColumn.length; index += 1) {
      const topNode = nodesOnColumn[index - 1]!;
      const bottomNode = nodesOnColumn[index]!;
      if (!segmentClear(topNode.point, bottomNode.point, obstacles)) {
        continue;
      }
      const cost = Math.abs(topNode.point.y - bottomNode.point.y);
      adjacency.get(topNode.key)?.push({ point: bottomNode.point, cost, direction: "vertical" });
      adjacency.get(bottomNode.key)?.push({ point: topNode.point, cost, direction: "vertical" });
    }
  });

  return adjacency;
}

function restoreRoute(
  cameFrom: Map<string, string>,
  states: Map<string, RouteState>,
  endKey: string,
  pointsByKey: Map<string, GraphPosition>,
): GraphPosition[] {
  const route: GraphPosition[] = [];
  let currentKey: string | undefined = endKey;
  while (currentKey) {
    const state = states.get(currentKey);
    if (!state) {
      break;
    }
    const point = pointsByKey.get(state.pointKey);
    if (point) {
      route.push(point);
    }
    currentKey = cameFrom.get(currentKey);
  }
  return route.reverse();
}

function findGridPath(
  startPoint: GraphPosition,
  endPoint: GraphPosition,
  obstacles: ExpandedRect[],
  candidatePoints: GraphPosition[],
): GraphPosition[] | null {
  const pointsByKey = new Map<string, GraphPosition>();
  candidatePoints.forEach((point) => pointsByKey.set(pointKey(point), point));
  const adjacency = buildAdjacency(candidatePoints, obstacles);
  const startPointKey = pointKey(startPoint);
  const endPointKey = pointKey(endPoint);
  const startStateKey = `${startPointKey}:start`;
  const openStates: RouteState[] = [{
    key: startStateKey,
    pointKey: startPointKey,
    direction: "start",
    cost: 0,
    estimate: heuristic(startPoint, endPoint),
  }];
  const bestCost = new Map<string, number>([[startStateKey, 0]]);
  const cameFrom = new Map<string, string>();
  const states = new Map<string, RouteState>([[startStateKey, openStates[0]!]]);

  while (openStates.length > 0) {
    openStates.sort((left, right) => (left.cost + left.estimate) - (right.cost + right.estimate));
    const current = openStates.shift()!;
    const currentPoint = pointsByKey.get(current.pointKey);
    if (!currentPoint) {
      continue;
    }
    if (current.pointKey === endPointKey) {
      return restoreRoute(cameFrom, states, current.key, pointsByKey);
    }
    const neighbors = adjacency.get(current.pointKey) ?? [];
    neighbors.forEach((neighbor) => {
      const nextKey = `${pointKey(neighbor.point)}:${neighbor.direction}`;
      const turnPenalty = current.direction === "start" || current.direction === neighbor.direction ? 0 : TURN_PENALTY;
      const nextCost = current.cost + neighbor.cost + turnPenalty;
      if (nextCost >= (bestCost.get(nextKey) ?? Number.POSITIVE_INFINITY)) {
        return;
      }
      const nextState: RouteState = {
        key: nextKey,
        pointKey: pointKey(neighbor.point),
        direction: neighbor.direction,
        cost: nextCost,
        estimate: heuristic(neighbor.point, endPoint),
      };
      bestCost.set(nextKey, nextCost);
      cameFrom.set(nextKey, current.key);
      states.set(nextKey, nextState);
      openStates.push(nextState);
    });
  }

  return null;
}

function routeFromPoints(points: GraphPosition[]): LinkGraphEdgeRoute {
  const simplified = simplifyPoints(points);
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

export function buildOrthogonalEdgeRoute({
  startPoint,
  startSide,
  startRect,
  endPoint,
  endSide,
  endRect,
  obstacleRects,
}: BuildOrthogonalEdgeRouteOptions): LinkGraphEdgeRoute {
  const startStub = offsetFromSide(startPoint, startSide, PORT_STUB);
  const endStub = offsetFromSide(endPoint, endSide, PORT_STUB);
  const searchObstacles = [
    expandedRect(startRect),
    expandedRect(endRect),
    ...obstacleRects
      .filter((rect) => rect.id !== startRect.id && rect.id !== endRect.id)
      .map((rect) => expandedRect(rect)),
  ];

  const xCoordinates = new Set<number>();
  const yCoordinates = new Set<number>();
  [
    startPoint.x,
    startStub.x,
    endPoint.x,
    endStub.x,
  ].forEach((value) => addCoordinate(xCoordinates, value));
  [
    startPoint.y,
    startStub.y,
    endPoint.y,
    endStub.y,
  ].forEach((value) => addCoordinate(yCoordinates, value));
  searchObstacles.forEach((rect) => {
    addCoordinate(xCoordinates, rect.left);
    addCoordinate(xCoordinates, rect.right);
    addCoordinate(yCoordinates, rect.top);
    addCoordinate(yCoordinates, rect.bottom);
  });

  const candidatePoints = Array.from(xCoordinates)
    .flatMap((x) => Array.from(yCoordinates).map((y) => ({ x, y })))
    .filter((point) => !searchObstacles.some((rect) => pointInsideExpandedRect(point, rect)));

  [startStub, endStub].forEach((point) => {
    const key = pointKey(point);
    if (!candidatePoints.some((candidate) => pointKey(candidate) === key)) {
      candidatePoints.push(point);
    }
  });

  const gridPath = findGridPath(startStub, endStub, searchObstacles, candidatePoints);
  if (!gridPath) {
    return routeFromPoints([startPoint, startStub, endStub, endPoint]);
  }
  return routeFromPoints([startPoint, ...gridPath, endPoint]);
}
