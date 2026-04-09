import { useEffect, useMemo } from "react";
import { BaseEdge, type Edge, type EdgeProps, type InternalNode, useStore } from "@xyflow/react";
import { traceLinkGraph } from "../debug";
import type { GraphPosition, LinkGraphEdgeRoute, LinkGraphEdgeRouteSection } from "../types";
import {
  buildOrthogonalEdgeRoute,
  type OrthogonalRect,
  type OrthogonalSide,
} from "./orthogonalEdgeRouting";
import { reanchorRouteToEndpoints } from "./orthogonalRoute";

export interface RoutedEdgeData {
  route?: LinkGraphEdgeRoute;
}

type RoutedGraphEdge = Edge<RoutedEdgeData, "routedEdge">;

function sectionPoints(section: LinkGraphEdgeRouteSection): GraphPosition[] {
  return [section.startPoint, ...(section.bendPoints ?? []), section.endPoint];
}

function sectionFromPoints(points: GraphPosition[]): LinkGraphEdgeRouteSection {
  return {
    startPoint: points[0]!,
    bendPoints: points.length > 2 ? points.slice(1, -1) : undefined,
    endPoint: points[points.length - 1]!,
  };
}

function fallbackPath(
  sourceX: number,
  sourceY: number,
  targetX: number,
  targetY: number,
) {
  return {
    path: `M ${sourceX} ${sourceY} L ${targetX} ${targetY}`,
    labelPosition: {
      x: (sourceX + targetX) / 2,
      y: (sourceY + targetY) / 2,
    },
  };
}

function buildRoutePath(route: LinkGraphEdgeRoute | undefined): string | null {
  if (!route || route.sections.length === 0) {
    return null;
  }
  return route.sections
    .map((section) =>
      sectionPoints(section)
        .map((point, index) => `${index === 0 ? "M" : "L"} ${point.x} ${point.y}`)
        .join(" "),
    )
    .join(" ");
}

function routeStartPoint(route: LinkGraphEdgeRoute | undefined): GraphPosition | null {
  if (!route || route.sections.length === 0) {
    return null;
  }
  return route.sections[0]?.startPoint ?? null;
}

function routeEndPoint(route: LinkGraphEdgeRoute | undefined): GraphPosition | null {
  if (!route || route.sections.length === 0) {
    return null;
  }
  return route.sections[route.sections.length - 1]?.endPoint ?? null;
}

function pointDistance(left: GraphPosition, right: GraphPosition): number {
  return Math.hypot(left.x - right.x, left.y - right.y);
}

function positionToSide(position: string | null | undefined): OrthogonalSide | null {
  switch (position) {
    case "top":
      return "top";
    case "right":
      return "right";
    case "bottom":
      return "bottom";
    case "left":
      return "left";
    default:
      return null;
  }
}

function nodeRect(node: InternalNode | undefined): OrthogonalRect | null {
  const width = node?.measured?.width;
  const height = node?.measured?.height;
  const position = node?.internals.positionAbsolute;
  if (!position || !Number.isFinite(width) || !Number.isFinite(height) || !width || !height) {
    return null;
  }
  return {
    id: node.id,
    x: position.x,
    y: position.y,
    width,
    height,
  };
}

function routePoints(route: LinkGraphEdgeRoute | undefined): GraphPosition[] {
  return route?.sections.flatMap((section) => sectionPoints(section)) ?? [];
}

function routeIsOrthogonal(route: LinkGraphEdgeRoute | undefined): boolean {
  const points = routePoints(route);
  if (points.length <= 1) {
    return false;
  }
  return points.slice(1).every((point, index) => {
    const previous = points[index]!;
    return Math.abs(previous.x - point.x) <= 0.5 || Math.abs(previous.y - point.y) <= 0.5;
  });
}

function segmentMatchesSide(
  startPoint: GraphPosition,
  endPoint: GraphPosition,
  side: OrthogonalSide,
): boolean {
  switch (side) {
    case "top":
      return Math.abs(startPoint.x - endPoint.x) <= 0.5 && endPoint.y <= startPoint.y + 0.5;
    case "right":
      return Math.abs(startPoint.y - endPoint.y) <= 0.5 && endPoint.x >= startPoint.x - 0.5;
    case "bottom":
      return Math.abs(startPoint.x - endPoint.x) <= 0.5 && endPoint.y >= startPoint.y - 0.5;
    case "left":
      return Math.abs(startPoint.y - endPoint.y) <= 0.5 && endPoint.x <= startPoint.x + 0.5;
  }
}

function segmentEntersSide(
  startPoint: GraphPosition,
  endPoint: GraphPosition,
  side: OrthogonalSide,
): boolean {
  switch (side) {
    case "top":
      return Math.abs(startPoint.x - endPoint.x) <= 0.5 && startPoint.y <= endPoint.y + 0.5;
    case "right":
      return Math.abs(startPoint.y - endPoint.y) <= 0.5 && startPoint.x >= endPoint.x - 0.5;
    case "bottom":
      return Math.abs(startPoint.x - endPoint.x) <= 0.5 && startPoint.y >= endPoint.y - 0.5;
    case "left":
      return Math.abs(startPoint.y - endPoint.y) <= 0.5 && startPoint.x <= endPoint.x + 0.5;
  }
}

function routeConformsToSides(
  route: LinkGraphEdgeRoute | undefined,
  startSide: OrthogonalSide,
  endSide: OrthogonalSide,
): boolean {
  const points = routePoints(route);
  if (points.length < 2) {
    return false;
  }
  return segmentMatchesSide(points[0]!, points[1]!, startSide)
    && segmentEntersSide(points[points.length - 2]!, points[points.length - 1]!, endSide);
}

function segmentIntersectsRect(
  startPoint: GraphPosition,
  endPoint: GraphPosition,
  rect: OrthogonalRect,
): boolean {
  if (Math.abs(startPoint.x - endPoint.x) <= 0.5) {
    const x = startPoint.x;
    if (x <= rect.x + 0.5 || x >= rect.x + rect.width - 0.5) {
      return false;
    }
    const top = Math.min(startPoint.y, endPoint.y);
    const bottom = Math.max(startPoint.y, endPoint.y);
    return Math.max(top, rect.y) < Math.min(bottom, rect.y + rect.height);
  }
  if (Math.abs(startPoint.y - endPoint.y) <= 0.5) {
    const y = startPoint.y;
    if (y <= rect.y + 0.5 || y >= rect.y + rect.height - 0.5) {
      return false;
    }
    const left = Math.min(startPoint.x, endPoint.x);
    const right = Math.max(startPoint.x, endPoint.x);
    return Math.max(left, rect.x) < Math.min(right, rect.x + rect.width);
  }
  return true;
}

function routeIntersectsObstacles(
  route: LinkGraphEdgeRoute | undefined,
  obstacleRects: OrthogonalRect[],
): boolean {
  const points = routePoints(route);
  if (points.length < 2) {
    return false;
  }
  return points.slice(1).some((point, index) => {
    const previous = points[index]!;
    return obstacleRects.some((rect) => segmentIntersectsRect(previous, point, rect));
  });
}

function shouldUseLocalRoute(
  route: LinkGraphEdgeRoute | undefined,
  currentStartPoint: GraphPosition,
  currentEndPoint: GraphPosition,
  startSide: OrthogonalSide | null,
  endSide: OrthogonalSide | null,
  obstacleRects: OrthogonalRect[],
): boolean {
  if (!route || !startSide || !endSide) {
    return true;
  }
  const routeStart = routeStartPoint(route);
  const routeEnd = routeEndPoint(route);
  if (!routeStart || !routeEnd) {
    return true;
  }
  if (pointDistance(routeStart, currentStartPoint) > 0.5 || pointDistance(routeEnd, currentEndPoint) > 0.5) {
    return true;
  }
  if (!routeIsOrthogonal(route)) {
    return true;
  }
  if (!routeConformsToSides(route, startSide, endSide)) {
    return true;
  }
  return routeIntersectsObstacles(route, obstacleRects);
}

function segmentLength(startPoint: GraphPosition, endPoint: GraphPosition): number {
  return Math.hypot(endPoint.x - startPoint.x, endPoint.y - startPoint.y);
}

function routeLabelPosition(route: LinkGraphEdgeRoute | undefined): GraphPosition | null {
  if (!route || route.sections.length === 0) {
    return null;
  }
  const segments = route.sections
    .flatMap((section) => {
      const points = sectionPoints(section);
      return points.slice(1).map((point, index) => ({
        startPoint: points[index]!,
        endPoint: point,
        length: segmentLength(points[index]!, point),
      }));
    })
    .filter((segment) => segment.length > 0);
  if (segments.length === 0) {
    const firstSectionPoints = sectionPoints(route.sections[0]!);
    return firstSectionPoints[Math.floor((firstSectionPoints.length - 1) / 2)] ?? null;
  }
  const totalLength = segments.reduce((sum, segment) => sum + segment.length, 0);
  const targetOffset = totalLength / 2;
  let traversed = 0;
  for (const segment of segments) {
    if (traversed + segment.length >= targetOffset) {
      const ratio = (targetOffset - traversed) / segment.length;
      return {
        x: segment.startPoint.x + (segment.endPoint.x - segment.startPoint.x) * ratio,
        y: segment.startPoint.y + (segment.endPoint.y - segment.startPoint.y) * ratio,
      };
    }
    traversed += segment.length;
  }
  return segments[segments.length - 1]?.endPoint ?? null;
}

export function RoutedEdge({
  id,
  data,
  label,
  markerStart,
  markerEnd,
  sourceX,
  sourceY,
  targetX,
  targetY,
  interactionWidth,
  style,
  sourcePosition,
  targetPosition,
  source,
  target,
}: EdgeProps<RoutedGraphEdge>) {
  const nodeLookup = useStore((state) => state.nodeLookup);
  const currentStartPoint = { x: sourceX, y: sourceY };
  const currentEndPoint = { x: targetX, y: targetY };
  const fallback = fallbackPath(sourceX, sourceY, targetX, targetY);
  const adjustedRoute = reanchorRouteToEndpoints(
    data?.route,
    currentStartPoint,
    sourcePosition,
    currentEndPoint,
    targetPosition,
  );
  const localRoute = useMemo(() => {
    const startSide = positionToSide(sourcePosition);
    const endSide = positionToSide(targetPosition);
    const sourceRect = nodeRect(nodeLookup.get(source));
    const targetRect = nodeRect(nodeLookup.get(target));
    if (!startSide || !endSide || !sourceRect || !targetRect) {
      return null;
    }
    const obstacleRects = Array.from(nodeLookup.values())
      .map((node) => nodeRect(node))
      .filter((rect): rect is OrthogonalRect => rect !== null && rect.id !== source && rect.id !== target);
    if (!shouldUseLocalRoute(adjustedRoute, currentStartPoint, currentEndPoint, startSide, endSide, obstacleRects)) {
      return null;
    }
    return buildOrthogonalEdgeRoute({
      startPoint: currentStartPoint,
      startSide,
      startRect: sourceRect,
      endPoint: currentEndPoint,
      endSide,
      endRect: targetRect,
      obstacleRects,
    });
  }, [
    adjustedRoute,
    currentEndPoint.x,
    currentEndPoint.y,
    currentStartPoint.x,
    currentStartPoint.y,
    nodeLookup,
    source,
    sourcePosition,
    target,
    targetPosition,
  ]);
  const renderedRoute = localRoute ?? adjustedRoute;
  const path = buildRoutePath(renderedRoute) ?? fallback.path;
  const labelPosition = routeLabelPosition(renderedRoute) ?? fallback.labelPosition;
  const originalRouteStart = routeStartPoint(data?.route);
  const originalRouteEnd = routeEndPoint(data?.route);
  const adjustedRouteStart = routeStartPoint(renderedRoute);
  const adjustedRouteEnd = routeEndPoint(renderedRoute);

  useEffect(() => {
    traceLinkGraph("routedEdge.render", {
      id,
      mode: localRoute ? "local-orthogonal" : adjustedRoute ? "stored-route" : "fallback",
      sourcePosition: sourcePosition ?? null,
      targetPosition: targetPosition ?? null,
      liveEndpoints: {
        startPoint: currentStartPoint,
        endPoint: currentEndPoint,
      },
      originalRoute: {
        sectionCount: data?.route?.sections.length ?? 0,
        startPoint: originalRouteStart,
        endPoint: originalRouteEnd,
      },
      adjustedRoute: {
        sectionCount: renderedRoute?.sections.length ?? 0,
        startPoint: adjustedRouteStart,
        endPoint: adjustedRouteEnd,
      },
      path,
    });
  }, [
    adjustedRoute?.sections.length,
    adjustedRouteEnd?.x,
    adjustedRouteEnd?.y,
    adjustedRouteStart?.x,
    adjustedRouteStart?.y,
    currentEndPoint.x,
    currentEndPoint.y,
    currentStartPoint.x,
    currentStartPoint.y,
    data?.route?.sections.length,
    id,
    localRoute,
    originalRouteEnd?.x,
    originalRouteEnd?.y,
    originalRouteStart?.x,
    originalRouteStart?.y,
    path,
    renderedRoute?.sections.length,
    sourcePosition,
    targetPosition,
  ]);

  return (
    <BaseEdge
      id={id}
      path={path}
      label={label}
      labelX={labelPosition.x}
      labelY={labelPosition.y}
      markerStart={markerStart}
      markerEnd={markerEnd}
      interactionWidth={interactionWidth}
      style={style}
    />
  );
}

export const ROUTED_EDGE_TYPES = {
  routedEdge: RoutedEdge,
};
