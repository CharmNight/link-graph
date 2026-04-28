import type { GraphPosition, LinkGraphEdgeRoute, LinkGraphEdgeRouteSection } from "../types";

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

function isVerticalSegment(startPoint: GraphPosition, endPoint: GraphPosition): boolean {
  return Math.abs(startPoint.x - endPoint.x) <= 0.5;
}

function isHorizontalSegment(startPoint: GraphPosition, endPoint: GraphPosition): boolean {
  return Math.abs(startPoint.y - endPoint.y) <= 0.5;
}

function shiftPointX(point: GraphPosition, deltaX: number): GraphPosition {
  return { ...point, x: point.x + deltaX };
}

function shiftPointY(point: GraphPosition, deltaY: number): GraphPosition {
  return { ...point, y: point.y + deltaY };
}

function isHorizontalSide(position: string | null | undefined): boolean {
  return position === "left" || position === "right";
}

function isVerticalSide(position: string | null | undefined): boolean {
  return position === "top" || position === "bottom";
}

function reanchorSectionStart(
  section: LinkGraphEdgeRouteSection,
  nextStartPoint: GraphPosition,
  position: string | null | undefined,
): LinkGraphEdgeRouteSection {
  const originalPoints = sectionPoints(section);
  if (originalPoints.length === 0) {
    return section;
  }
  const points = originalPoints.map((point) => ({ ...point }));
  const currentStartPoint = originalPoints[0]!;
  const deltaX = nextStartPoint.x - currentStartPoint.x;
  const deltaY = nextStartPoint.y - currentStartPoint.y;

  points[0] = nextStartPoint;
  if (points.length === 1) {
    return sectionFromPoints(points);
  }
  if (points.length === 2) {
    return sectionFromPoints(points);
  }

  if (isHorizontalSide(position)) {
      points[1] = shiftPointY(points[1]!, deltaY);
      if (Math.abs(deltaX) > 0.5) {
        points[1] = shiftPointX(points[1]!, deltaX);
        for (let index = 2; index < points.length; index += 1) {
          if (!isVerticalSegment(originalPoints[index - 1]!, originalPoints[index]!)) {
            break;
          }
          points[index] = shiftPointX(points[index]!, deltaX);
        }
      }
  } else if (isVerticalSide(position)) {
      points[1] = shiftPointX(points[1]!, deltaX);
  } else {
      points[1] = {
        ...points[1]!,
        x: points[1]!.x + deltaX,
        y: points[1]!.y + deltaY,
      };
  }

  return sectionFromPoints(points);
}

function reanchorSectionEnd(
  section: LinkGraphEdgeRouteSection,
  nextEndPoint: GraphPosition,
  position: string | null | undefined,
): LinkGraphEdgeRouteSection {
  const originalPoints = sectionPoints(section);
  if (originalPoints.length === 0) {
    return section;
  }
  const points = originalPoints.map((point) => ({ ...point }));
  const lastIndex = points.length - 1;
  const currentEndPoint = originalPoints[lastIndex]!;
  const deltaX = nextEndPoint.x - currentEndPoint.x;
  const deltaY = nextEndPoint.y - currentEndPoint.y;

  points[lastIndex] = nextEndPoint;
  if (points.length === 1) {
    return sectionFromPoints(points);
  }
  if (points.length === 2) {
    return sectionFromPoints(points);
  }

  if (isHorizontalSide(position)) {
      const previousPointIndex = lastIndex - 1;
      points[previousPointIndex] = shiftPointY(points[previousPointIndex]!, deltaY);
      if (Math.abs(deltaX) > 0.5) {
        points[previousPointIndex] = shiftPointX(points[previousPointIndex]!, deltaX);
        for (let index = previousPointIndex - 1; index >= 0; index -= 1) {
          if (!isVerticalSegment(originalPoints[index]!, originalPoints[index + 1]!)) {
            break;
          }
          points[index] = shiftPointX(points[index]!, deltaX);
        }
      }
  } else if (isVerticalSide(position)) {
      const previousPointIndex = lastIndex - 1;
      points[previousPointIndex] = shiftPointX(points[previousPointIndex]!, deltaX);
  } else {
      const previousPointIndex = lastIndex - 1;
      points[previousPointIndex] = {
        ...points[previousPointIndex]!,
        x: points[previousPointIndex]!.x + deltaX,
        y: points[previousPointIndex]!.y + deltaY,
      };
  }

  return sectionFromPoints(points);
}

export function reanchorRouteToEndpoints(
  route: LinkGraphEdgeRoute | undefined,
  startPoint: GraphPosition,
  startPosition: string | null | undefined,
  endPoint: GraphPosition,
  endPosition: string | null | undefined,
): LinkGraphEdgeRoute | undefined {
  if (!route || route.sections.length === 0) {
    return route;
  }
  const sections: LinkGraphEdgeRouteSection[] = route.sections.map((section) => ({
    ...section,
    startPoint: { ...section.startPoint },
    endPoint: { ...section.endPoint },
    bendPoints: section.bendPoints?.map((point) => ({ ...point })),
  }));
  sections[0] = reanchorSectionStart(sections[0]!, startPoint, startPosition);
  sections[sections.length - 1] = reanchorSectionEnd(sections[sections.length - 1]!, endPoint, endPosition);
  return {
    sections,
  };
}

export function reanchorRouteStart(
  route: LinkGraphEdgeRoute | undefined,
  startPoint: GraphPosition,
  startPosition: string | null | undefined,
): LinkGraphEdgeRoute | undefined {
  if (!route || route.sections.length === 0) {
    return route;
  }
  const sections: LinkGraphEdgeRouteSection[] = route.sections.map((section) => ({
    ...section,
    startPoint: { ...section.startPoint },
    endPoint: { ...section.endPoint },
    bendPoints: section.bendPoints?.map((point) => ({ ...point })),
  }));
  sections[0] = reanchorSectionStart(sections[0]!, startPoint, startPosition);
  return { sections };
}

export function reanchorRouteEnd(
  route: LinkGraphEdgeRoute | undefined,
  endPoint: GraphPosition,
  endPosition: string | null | undefined,
): LinkGraphEdgeRoute | undefined {
  if (!route || route.sections.length === 0) {
    return route;
  }
  const sections: LinkGraphEdgeRouteSection[] = route.sections.map((section) => ({
    ...section,
    startPoint: { ...section.startPoint },
    endPoint: { ...section.endPoint },
    bendPoints: section.bendPoints?.map((point) => ({ ...point })),
  }));
  sections[sections.length - 1] = reanchorSectionEnd(sections[sections.length - 1]!, endPoint, endPosition);
  return { sections };
}
