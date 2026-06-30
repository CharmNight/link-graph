// 正交边路由的"重新锚定"工具：在节点位置变化时，把现有的边路由按端点变化重新对齐，
// 而不是完全重算路由。这样可以在拖动节点时保持路由的视觉稳定性（避免每次拖动都重路由）。
//
// 主要场景：节点拖动后，边的起止点跟着移动；我们想保留原来的折线形态，
// 只在端点附近做最小调整，让用户感觉边是"跟着节点走的"。
import type { GraphPosition, LinkGraphEdgeRoute, LinkGraphEdgeRouteSection } from "../types";

/** 把段（section）展开为点列表：起点 + 中间转折点 + 终点。 */
function sectionPoints(section: LinkGraphEdgeRouteSection): GraphPosition[] {
  return [section.startPoint, ...(section.bendPoints ?? []), section.endPoint];
}

/** 把点列表重新组装为段；仅 2 点时不带 bendPoints。 */
function sectionFromPoints(points: GraphPosition[]): LinkGraphEdgeRouteSection {
  return {
    startPoint: points[0]!,
    bendPoints: points.length > 2 ? points.slice(1, -1) : undefined,
    endPoint: points[points.length - 1]!,
  };
}

/** 判断段是否为垂直（x 坐标相同，0.5 像素容差避免浮点误差）。 */
function isVerticalSegment(startPoint: GraphPosition, endPoint: GraphPosition): boolean {
  return Math.abs(startPoint.x - endPoint.x) <= 0.5;
}

/** 把点的 x 坐标平移 deltaX。 */
function shiftPointX(point: GraphPosition, deltaX: number): GraphPosition {
  return { ...point, x: point.x + deltaX };
}

/** 把点的 y 坐标平移 deltaY。 */
function shiftPointY(point: GraphPosition, deltaY: number): GraphPosition {
  return { ...point, y: point.y + deltaY };
}

/** 判断节点连接柄位置是否在水平方向（left/right）。 */
function isHorizontalSide(position: string | null | undefined): boolean {
  return position === "left" || position === "right";
}

/** 判断节点连接柄位置是否在垂直方向（top/bottom）。 */
function isVerticalSide(position: string | null | undefined): boolean {
  return position === "top" || position === "bottom";
}

/**
 * 重新锚定段的"起点"。
 *
 * 当段起点从旧位置移到新位置时，调整段前若干个点使折线仍然合理。
 * - 水平连接柄：起点附近的水平段做 y 平移，垂直段做 x 平移；
 * - 垂直连接柄：第二个点做 x 平移；
 * - 其他：第二个点做完整 (x, y) 平移。
 *
 * 这样保证拖动节点时，边从节点边缘出发后立即按原方向延伸，整体形态稳定。
 */
function reanchorSectionStart(
  section: LinkGraphEdgeRouteSection,
  nextStartPoint: GraphPosition,
  position: string | null | undefined,
): LinkGraphEdgeRouteSection {
  const originalPoints = sectionPoints(section);
  if (originalPoints.length === 0) {
    return section;
  }
  // 拷贝一份避免修改原数据
  const points = originalPoints.map((point) => ({ ...point }));
  const currentStartPoint = originalPoints[0]!;
  const deltaX = nextStartPoint.x - currentStartPoint.x;
  const deltaY = nextStartPoint.y - currentStartPoint.y;

  // 替换起点为新位置
  points[0] = nextStartPoint;
  // 仅 1 个或 2 个点的段：直接返回（没有可调整的中间点）
  if (points.length === 1) {
    return sectionFromPoints(points);
  }
  if (points.length === 2) {
    return sectionFromPoints(points);
  }

  if (isHorizontalSide(position)) {
      // 起点在水平方向：第二个点做 y 平移（保持从节点边缘水平延伸）
      points[1] = shiftPointY(points[1]!, deltaY);
      if (Math.abs(deltaX) > 0.5) {
        // x 也变了：第二个点同时做 x 平移，并继续传播到后续的垂直段
        points[1] = shiftPointX(points[1]!, deltaX);
        for (let index = 2; index < points.length; index += 1) {
          // 遇到非垂直段停止传播：水平段不应被 x 平移影响
          if (!isVerticalSegment(originalPoints[index - 1]!, originalPoints[index]!)) {
            break;
          }
          points[index] = shiftPointX(points[index]!, deltaX);
        }
      }
  } else if (isVerticalSide(position)) {
      // 起点在垂直方向：第二个点做 x 平移（保持从节点边缘垂直延伸）
      points[1] = shiftPointX(points[1]!, deltaX);
  } else {
      // 未知连接柄位置：第二个点做完整平移
      points[1] = {
        ...points[1]!,
        x: points[1]!.x + deltaX,
        y: points[1]!.y + deltaY,
      };
  }

  return sectionFromPoints(points);
}

/**
 * 重新锚定段的"终点"。
 * 与 [reanchorSectionStart] 对称，但作用在段的末尾。
 */
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

  // 替换终点为新位置
  points[lastIndex] = nextEndPoint;
  if (points.length === 1) {
    return sectionFromPoints(points);
  }
  if (points.length === 2) {
    return sectionFromPoints(points);
  }

  if (isHorizontalSide(position)) {
      // 终点在水平方向：倒数第二个点做 y 平移
      const previousPointIndex = lastIndex - 1;
      points[previousPointIndex] = shiftPointY(points[previousPointIndex]!, deltaY);
      if (Math.abs(deltaX) > 0.5) {
        // x 也变了：同时做 x 平移，并向前传播到垂直段
        points[previousPointIndex] = shiftPointX(points[previousPointIndex]!, deltaX);
        for (let index = previousPointIndex - 1; index >= 0; index -= 1) {
          if (!isVerticalSegment(originalPoints[index]!, originalPoints[index + 1]!)) {
            break;
          }
          points[index] = shiftPointX(points[index]!, deltaX);
        }
      }
  } else if (isVerticalSide(position)) {
      // 终点在垂直方向：倒数第二个点做 x 平移
      const previousPointIndex = lastIndex - 1;
      points[previousPointIndex] = shiftPointX(points[previousPointIndex]!, deltaX);
  } else {
      // 未知连接柄位置：完整平移
      const previousPointIndex = lastIndex - 1;
      points[previousPointIndex] = {
        ...points[previousPointIndex]!,
        x: points[previousPointIndex]!.x + deltaX,
        y: points[previousPointIndex]!.y + deltaY,
      };
  }

  return sectionFromPoints(points);
}

/**
 * 重新锚定整条路由：同时调整首段起点和末段终点。
 *
 * 用于节点位置变化后让边跟着节点走，同时保留中间段的折线形态。
 *
 * @param route 原始路由
 * @param startPoint 新起点
 * @param startPosition 起点连接柄位置（left/right/top/bottom）
 * @param endPoint 新终点
 * @param endPosition 终点连接柄位置
 * @return 重新锚定后的路由；输入为空时原样返回
 */
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
  // 深拷贝所有段，避免修改原始数据
  const sections: LinkGraphEdgeRouteSection[] = route.sections.map((section) => ({
    ...section,
    startPoint: { ...section.startPoint },
    endPoint: { ...section.endPoint },
    bendPoints: section.bendPoints?.map((point) => ({ ...point })),
  }));
  // 首段：调整起点；末段：调整终点
  sections[0] = reanchorSectionStart(sections[0]!, startPoint, startPosition);
  sections[sections.length - 1] = reanchorSectionEnd(sections[sections.length - 1]!, endPoint, endPosition);
  return {
    sections,
  };
}

/** 仅重新锚定路由起点；用于只有起点变化的场景。 */
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

/** 仅重新锚定路由终点；用于只有终点变化的场景。 */
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
