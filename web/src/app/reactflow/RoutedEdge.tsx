// React Flow 中的"已路由边"组件。
// 主要职责：
// - 渲染一条带路由的边（折线 SVG path）；
// - 在节点拖动时做局部重新路由（不重算整张图，避免性能问题）；
// - 处理标签位置（中心/源端/目标端）、装饰（点/线/菱形）等可视化需求；
// - 当节点数过多时做障碍物裁剪（只考虑路由附近的若干节点）。
import { useEffect, useMemo } from "react";
import {
  BaseEdge,
  type Edge,
  type EdgeProps,
  type EdgeTypes,
  type InternalNode,
  useStore,
} from "@xyflow/react";
import { traceLinkGraph } from "../debug";
import type { GraphPosition, LinkGraphEdgeRoute, LinkGraphEdgeRouteSection } from "../types";
import {
  buildOrthogonalEdgeRoute,
  type OrthogonalRect,
  type OrthogonalSide,
} from "./orthogonalEdgeRouting";
import { reanchorRouteToEndpoints } from "./orthogonalRoute";

/**
 * React Flow 边上携带的额外数据。
 * 通过 data 字段挂载在 Edge 对象上，本组件读取后做相应渲染。
 */
export interface RoutedEdgeData extends Record<string, unknown> {
  /** 存储的路由（来自后端 / 上次布局）；缺失时本组件会即时计算。 */
  route?: LinkGraphEdgeRoute;
  /** 标签可见性策略。 */
  labelVisibility?: "always" | "selected" | "focus" | "hidden";
  /** 标签的 title 文本（鼠标悬停展示）。 */
  labelTitle?: string;
  /** 标签放置位置：中心 / 源端 stub / 目标端 stub。 */
  labelPlacement?: "center" | "source-stub" | "target-stub";
  /** 路由模式；stored = 必须用存储路由，不做本地修补。 */
  routeMode?: "stored";
  /** 是否为聚焦边（影响标签可见性）。 */
  focusedEdge?: boolean;
  /** 源端装饰：圆点 / stub 短线。 */
  sourceAdornment?: "dot" | "stub";
  /** 目标端装饰：空心菱形 / 实心菱形。 */
  targetAdornment?: "diamond" | "filled-diamond";
}

/** 项目内 React Flow 边类型别名。 */
export type RoutedGraphEdge = Edge<RoutedEdgeData, "routedEdge">;

/** 节点数超过此阈值时启用"密集图"策略：只考虑路由附近的障碍物。 */
const LOCAL_OBSTACLE_ROUTING_NODE_LIMIT = 48;
/** 密集图障碍物上限：超过时按距离裁剪。 */
const DENSE_OBSTACLE_ROUTING_LIMIT = 32;
/** 密集图障碍物检索的 padding（路由周围多少像素内的节点算"附近"）。 */
const DENSE_OBSTACLE_ROUTING_PADDING = 180;

/** 把段展开为点列表（起点 + 转折点 + 终点）。 */
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

/** 兜底直线 path：路由完全缺失时用直线连接。 */
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

/** 兜底正交路由：路由完全缺失时用一次折线连接（取中点 y 折一下）。 */
function fallbackOrthogonalRoute(
  startPoint: GraphPosition,
  endPoint: GraphPosition,
): LinkGraphEdgeRoute {
  const midY = Math.round((startPoint.y + endPoint.y) / 2);
  return {
    sections: [
      sectionFromPoints([
        startPoint,
        { x: startPoint.x, y: midY },
        { x: endPoint.x, y: midY },
        endPoint,
      ]),
    ],
  };
}

/** 把路由对象序列化为 SVG path 字符串（M/L 命令）。 */
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

/** 取路由首点；不存在时返回 null。 */
function routeStartPoint(route: LinkGraphEdgeRoute | undefined): GraphPosition | null {
  if (!route || route.sections.length === 0) {
    return null;
  }
  return route.sections[0]?.startPoint ?? null;
}

/** 取路由末点；不存在时返回 null。 */
function routeEndPoint(route: LinkGraphEdgeRoute | undefined): GraphPosition | null {
  if (!route || route.sections.length === 0) {
    return null;
  }
  return route.sections[route.sections.length - 1]?.endPoint ?? null;
}

/** 两点欧氏距离。 */
function pointDistance(left: GraphPosition, right: GraphPosition): number {
  return Math.hypot(left.x - right.x, left.y - right.y);
}

/** 把 React Flow 的连接柄位置字符串映射为本模块的 OrthogonalSide；未识别返回 null。 */
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

/**
 * 把 React Flow 的节点对象转换为 OrthogonalRect。
 * 缺少 measured（尺寸未确定）或 positionAbsolute（位置未确定）时返回 null。
 */
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

/** 把路由展平为点列表（所有段拼接）。 */
function routePoints(route: LinkGraphEdgeRoute | undefined): GraphPosition[] {
  return route?.sections.flatMap((section) => sectionPoints(section)) ?? [];
}

/**
 * 判断路由是否"全部为正交"（每相邻两点要么 x 相同要么 y 相同）。
 * 用于决定是否需要本地修补。
 */
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

/** 判断段是否从给定边的内侧出来（用于检查路由是否与连接柄方向一致）。 */
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

/** 判断段是否从给定边的内侧进入（与 [segmentMatchesSide] 对称）。 */
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

/** 判断路由首尾两段是否与给定的起止连接柄方向一致。 */
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

/** 判断线段是否穿过矩形（正交线段版本，带 0.5 容差）。 */
function segmentIntersectsRect(
  startPoint: GraphPosition,
  endPoint: GraphPosition,
  rect: OrthogonalRect,
): boolean {
  // 垂直段：检查 x 是否在矩形内 + y 区间是否重叠
  if (Math.abs(startPoint.x - endPoint.x) <= 0.5) {
    const x = startPoint.x;
    if (x <= rect.x + 0.5 || x >= rect.x + rect.width - 0.5) {
      return false;
    }
    const top = Math.min(startPoint.y, endPoint.y);
    const bottom = Math.max(startPoint.y, endPoint.y);
    return Math.max(top, rect.y) < Math.min(bottom, rect.y + rect.height);
  }
  // 水平段：同理
  if (Math.abs(startPoint.y - endPoint.y) <= 0.5) {
    const y = startPoint.y;
    if (y <= rect.y + 0.5 || y >= rect.y + rect.height - 0.5) {
      return false;
    }
    const left = Math.min(startPoint.x, endPoint.x);
    const right = Math.max(startPoint.x, endPoint.x);
    return Math.max(left, rect.x) < Math.min(right, rect.x + rect.width);
  }
  // 斜线（理论上不应出现）：保守视为相交
  return true;
}

/** 判断整条路由是否穿过任一障碍物。 */
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

/** 矩形边界框（用于"附近"判定）。 */
interface Bounds {
  left: number;
  right: number;
  top: number;
  bottom: number;
}

/**
 * 计算路由的边界框。
 * 路由缺失时退化为 startPoint/endPoint 的连线 bbox。
 * @param padding 边界外扩像素
 */
function routeBounds(
  route: LinkGraphEdgeRoute | undefined,
  startPoint: GraphPosition,
  endPoint: GraphPosition,
  padding = 0,
): Bounds {
  const points = routePoints(route);
  const boundPoints = points.length > 1 ? points : [startPoint, endPoint];
  const xs = boundPoints.map((point) => point.x);
  const ys = boundPoints.map((point) => point.y);
  return {
    left: Math.min(...xs) - padding,
    right: Math.max(...xs) + padding,
    top: Math.min(...ys) - padding,
    bottom: Math.max(...ys) + padding,
  };
}

/** 判断矩形与边界框是否相交（用于"附近障碍物"筛选）。 */
function rectIntersectsBounds(rect: OrthogonalRect, bounds: Bounds): boolean {
  return rect.x <= bounds.right
    && rect.x + rect.width >= bounds.left
    && rect.y <= bounds.bottom
    && rect.y + rect.height >= bounds.top;
}

/** 点到线段的距离（用于"附近障碍物"排序）。 */
function pointToSegmentDistance(
  point: GraphPosition,
  startPoint: GraphPosition,
  endPoint: GraphPosition,
): number {
  const dx = endPoint.x - startPoint.x;
  const dy = endPoint.y - startPoint.y;
  // 退化情况：线段长度为 0 时按"点到点距离"处理
  if (Math.abs(dx) <= 0.5 && Math.abs(dy) <= 0.5) {
    return pointDistance(point, startPoint);
  }
  // 投影比例：把点投影到线段上，clamp 到 [0, 1]
  const ratio = Math.max(
    0,
    Math.min(1, ((point.x - startPoint.x) * dx + (point.y - startPoint.y) * dy) / (dx * dx + dy * dy)),
  );
  return pointDistance(point, {
    x: startPoint.x + ratio * dx,
    y: startPoint.y + ratio * dy,
  });
}

/** 计算矩形中心点到路由的最短距离（用于排序"附近障碍物"）。 */
function rectDistanceToRoute(
  rect: OrthogonalRect,
  route: LinkGraphEdgeRoute | undefined,
  startPoint: GraphPosition,
  endPoint: GraphPosition,
): number {
  const points = routePoints(route);
  const routeLine = points.length > 1 ? points : [startPoint, endPoint];
  const center = {
    x: rect.x + rect.width / 2,
    y: rect.y + rect.height / 2,
  };
  return routeLine.slice(1).reduce((distance, point, index) => Math.min(
    distance,
    pointToSegmentDistance(center, routeLine[index]!, point),
  ), Number.POSITIVE_INFINITY);
}

/**
 * 在密集图场景下裁剪障碍物集合。
 *
 * 流程：
 * 1) 取路由周围 padding 像素内的矩形作为附近集合；
 * 2) 若附近数量 ≤ DENSE_OBSTACLE_ROUTING_LIMIT：原样返回；
 * 3) 否则：按"到路由的距离"升序取前 LIMIT 个。
 *
 * 这样避免密集图下 A* 算法处理过多障碍物导致性能问题。
 */
function denseRoutingObstacles(
  obstacleRects: OrthogonalRect[],
  route: LinkGraphEdgeRoute | undefined,
  startPoint: GraphPosition,
  endPoint: GraphPosition,
): OrthogonalRect[] {
  const bounds = routeBounds(route, startPoint, endPoint, DENSE_OBSTACLE_ROUTING_PADDING);
  const nearby = obstacleRects.filter((rect) => rectIntersectsBounds(rect, bounds));
  if (nearby.length <= DENSE_OBSTACLE_ROUTING_LIMIT) {
    return nearby;
  }
  // 距离路由最近的 LIMIT 个
  return [...nearby]
    .sort((left, right) =>
      rectDistanceToRoute(left, route, startPoint, endPoint)
      - rectDistanceToRoute(right, route, startPoint, endPoint),
    )
    .slice(0, DENSE_OBSTACLE_ROUTING_LIMIT);
}

/**
 * 判断是否需要本地重新路由。
 *
 * 触发条件：
 * - 路由本身需要本地修补（端点偏移、非正交、不符合连接柄方向）；
 * - 或路由穿过任一障碍物。
 */
function shouldUseLocalRoute(
  route: LinkGraphEdgeRoute | undefined,
  currentStartPoint: GraphPosition,
  currentEndPoint: GraphPosition,
  startSide: OrthogonalSide | null,
  endSide: OrthogonalSide | null,
  obstacleRects: OrthogonalRect[],
): boolean {
  if (routeNeedsLocalRepair(route, currentStartPoint, currentEndPoint, startSide, endSide)) {
    return true;
  }
  return routeIntersectsObstacles(route, obstacleRects);
}

/**
 * 判断路由是否需要本地修补。
 *
 * 触发条件：
 * - 路由或连接柄信息缺失；
 * - 端点位置与路由实际端点偏差大于 0.5 像素；
 * - 路由非正交（含斜线）；
 * - 路由首尾段与连接柄方向不一致。
 */
function routeNeedsLocalRepair(
  route: LinkGraphEdgeRoute | undefined,
  currentStartPoint: GraphPosition,
  currentEndPoint: GraphPosition,
  startSide: OrthogonalSide | null,
  endSide: OrthogonalSide | null,
): boolean {
  if (!route || !startSide || !endSide) {
    return true;
  }
  const routeStart = routeStartPoint(route);
  const routeEnd = routeEndPoint(route);
  if (!routeStart || !routeEnd) {
    return true;
  }
  // 端点偏差大于 0.5 像素视为需要修补
  if (pointDistance(routeStart, currentStartPoint) > 0.5 || pointDistance(routeEnd, currentEndPoint) > 0.5) {
    return true;
  }
  if (!routeIsOrthogonal(route)) {
    return true;
  }
  if (!routeConformsToSides(route, startSide, endSide)) {
    return true;
  }
  return false;
}

/** 单条线段长度。 */
function segmentLength(startPoint: GraphPosition, endPoint: GraphPosition): number {
  return Math.hypot(endPoint.x - startPoint.x, endPoint.y - startPoint.y);
}

/**
 * 沿多段线移动 offset 像素，返回到达的点。
 * 用于在路由上"按距离找位置"（例如标签放在距离源端 88 像素处）。
 *
 * @param segments 已知每段长度的线段列表
 * @param offset 要移动的距离
 * @return 到达的点；超出总长时返回末点
 */
function pointAlongSegments(
  segments: Array<{ startPoint: GraphPosition; endPoint: GraphPosition; length: number }>,
  offset: number,
): GraphPosition | null {
  let traversed = 0;
  for (const segment of segments) {
    if (traversed + segment.length >= offset) {
      const ratio = (offset - traversed) / segment.length;
      return {
        x: segment.startPoint.x + (segment.endPoint.x - segment.startPoint.x) * ratio,
        y: segment.startPoint.y + (segment.endPoint.y - segment.startPoint.y) * ratio,
      };
    }
    traversed += segment.length;
  }
  return segments[segments.length - 1]?.endPoint ?? null;
}

/**
 * 计算路由上标签应该出现的位置。
 *
 * @param placement 放置策略：center（中点）/ source-stub（源端 88 像素内）/ target-stub（目标端同理）
 * @return 标签坐标；路由无效时返回 null
 */
function routeLabelPosition(
  route: LinkGraphEdgeRoute | undefined,
  placement: RoutedEdgeData["labelPlacement"],
): GraphPosition | null {
  if (!route || route.sections.length === 0) {
    return null;
  }
  // 把路由展平为带长度的段列表
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
    // 退化情况：取首段的中间点
    const firstSectionPoints = sectionPoints(route.sections[0]!);
    return firstSectionPoints[Math.floor((firstSectionPoints.length - 1) / 2)] ?? null;
  }
  const totalLength = segments.reduce((sum, segment) => sum + segment.length, 0);
  // 源端 stub：从源端走 88 像素（或半程，取较小）
  if (placement === "source-stub") {
    return pointAlongSegments(segments, Math.min(88, totalLength / 2));
  }
  // 目标端 stub：从目标端反向走 88 像素
  if (placement === "target-stub") {
    return pointAlongSegments([...segments].reverse().map((segment) => ({
      startPoint: segment.endPoint,
      endPoint: segment.startPoint,
      length: segment.length,
    })), Math.min(88, totalLength / 2));
  }
  // 默认中心：走到总长度的一半
  const targetOffset = totalLength / 2;
  return pointAlongSegments(segments, targetOffset);
}

/** 计算从 start 到 end 的单位向量；退化时返回 (1,0)。 */
function unitVector(startPoint: GraphPosition, endPoint: GraphPosition): GraphPosition {
  const dx = endPoint.x - startPoint.x;
  const dy = endPoint.y - startPoint.y;
  const length = Math.hypot(dx, dy);
  if (length <= 0.5) {
    return { x: 1, y: 0 };
  }
  return { x: dx / length, y: dy / length };
}

/** 取端点（源/目标）的位置与方向向量；用于装饰物（圆点/菱形）的定位与朝向。 */
function endpointVector(points: GraphPosition[], endpoint: "source" | "target"): { point: GraphPosition; vector: GraphPosition } | null {
  if (points.length < 2) {
    return null;
  }
  if (endpoint === "source") {
    const point = points[0]!;
    // 方向向量：从第二个点指向端点（即"边出来的方向"）
    return { point, vector: unitVector(points[1]!, point) };
  }
  const point = points[points.length - 1]!;
  return { point, vector: unitVector(points[points.length - 2]!, point) };
}

/**
 * 构造菱形装饰物的 SVG points 字符串。
 * 菱形朝向由 vector 决定（菱形尖端 = 端点）。
 */
function diamondPoints(endpoint: GraphPosition, vector: GraphPosition): string {
  // 法向量（与 vector 垂直）
  const perpendicular = { x: -vector.y, y: vector.x };
  const tip = endpoint;
  const center = {
    x: endpoint.x - vector.x * 9,
    y: endpoint.y - vector.y * 9,
  };
  const back = {
    x: endpoint.x - vector.x * 18,
    y: endpoint.y - vector.y * 18,
  };
  const left = {
    x: center.x + perpendicular.x * 6,
    y: center.y + perpendicular.y * 6,
  };
  const right = {
    x: center.x - perpendicular.x * 6,
    y: center.y - perpendicular.y * 6,
  };
  return [tip, left, back, right].map((point) => `${point.x},${point.y}`).join(" ");
}

/**
 * 已路由边渲染组件。
 *
 * 综合处理路由的多个来源：
 * - data.route：后端或上次布局的存储路由；
 * - adjustedRoute：把存储路由按当前端点重新锚定后的版本；
 * - localRoute：必要时本地重算的正交路由（处理拖动 / 障碍物穿越等）。
 *
 * 渲染时优先使用 localRoute；其次 adjustedRoute；最后兜底折线。
 *
 * 同时处理标签位置、装饰物（圆点 / stub / 菱形）与渲染埋点。
 */
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
  selected,
}: EdgeProps<RoutedGraphEdge>) {
  // 取 React Flow 内部节点查找表
  const nodeLookup = useStore((state) => state.nodeLookup);
  const currentStartPoint = { x: sourceX, y: sourceY };
  const currentEndPoint = { x: targetX, y: targetY };
  const fallback = fallbackPath(sourceX, sourceY, targetX, targetY);
  // stored 模式：必须用存储路由，不做本地修补
  const preserveStoredRoute = data?.routeMode === "stored";

  // 第一步：把存储路由按当前端点重新锚定（节点拖动后路由跟着调整）
  const adjustedRoute = useMemo(
    () => reanchorRouteToEndpoints(
      data?.route,
      currentStartPoint,
      sourcePosition,
      currentEndPoint,
      targetPosition,
    ),
    [
      currentEndPoint.x,
      currentEndPoint.y,
      currentStartPoint.x,
      currentStartPoint.y,
      data?.route,
      sourcePosition,
      targetPosition,
    ],
  );

  // 第二步：判断是否需要本地重新路由；必要时跑正交路由算法
  const localRoute = useMemo(() => {
    const startSide = positionToSide(sourcePosition);
    const endSide = positionToSide(targetPosition);
    const needsLocalRepair = routeNeedsLocalRepair(
      adjustedRoute,
      currentStartPoint,
      currentEndPoint,
      startSide,
      endSide,
    );
    // 节点数过多 + 不需要修补 + 非 stored 模式：跳过本地路由（性能优化）
    if (!preserveStoredRoute && !needsLocalRepair && nodeLookup.size > LOCAL_OBSTACLE_ROUTING_NODE_LIMIT) {
      return null;
    }
    const sourceRect = nodeRect(nodeLookup.get(source));
    const targetRect = nodeRect(nodeLookup.get(target));
    // 缺少连接柄方向或节点尺寸：无法本地路由
    if (!startSide || !endSide || !sourceRect || !targetRect) {
      return null;
    }
    // 收集障碍物矩形（排除源/目标节点本身）
    const obstacleRects = Array.from(nodeLookup.values())
      .map((node) => nodeRect(node))
      .filter((rect): rect is OrthogonalRect => rect !== null && rect.id !== source && rect.id !== target);
    // 密集图场景：裁剪障碍物集合
    const routeObstacleRects = nodeLookup.size > LOCAL_OBSTACLE_ROUTING_NODE_LIMIT
      ? denseRoutingObstacles(obstacleRects, adjustedRoute, currentStartPoint, currentEndPoint)
      : obstacleRects;
    const routeCrossesObstacle = routeIntersectsObstacles(adjustedRoute, routeObstacleRects);
    // stored 模式 + 不需要修补 + 不穿越障碍：跳过本地路由
    if (preserveStoredRoute && !needsLocalRepair && !routeCrossesObstacle) {
      return null;
    }
    // 不需要修补 + 不穿越障碍 + 不需要本地路由（综合判定）：跳过
    if (
      !needsLocalRepair
      && !routeCrossesObstacle
      && !shouldUseLocalRoute(adjustedRoute, currentStartPoint, currentEndPoint, startSide, endSide, routeObstacleRects)
    ) {
      return null;
    }
    // 执行本地正交路由计算
    return buildOrthogonalEdgeRoute({
      startPoint: currentStartPoint,
      startSide,
      startRect: sourceRect,
      endPoint: currentEndPoint,
      endSide,
      endRect: targetRect,
      obstacleRects: routeObstacleRects,
    });
  }, [
    adjustedRoute,
    currentEndPoint.x,
    currentEndPoint.y,
    currentStartPoint.x,
    currentStartPoint.y,
    nodeLookup,
    preserveStoredRoute,
    source,
    sourcePosition,
    target,
    targetPosition,
  ]);

  // 选择最终渲染的路由：localRoute > adjustedRoute > fallback
  const renderedRoute = localRoute ?? adjustedRoute ?? fallbackOrthogonalRoute(currentStartPoint, currentEndPoint);
  const path = buildRoutePath(renderedRoute) ?? fallback.path;
  // 标签位置：按 placement 计算，缺失时回退到中点
  const labelPosition = routeLabelPosition(renderedRoute, data?.labelPlacement ?? "center") ?? fallback.labelPosition;
  const labelVisibility = data?.labelVisibility ?? "always";
  const focused = selected || data?.focusedEdge === true;
  // 标签可见性策略：hidden / selected-only / focus-only / always
  const renderedLabel = labelVisibility === "hidden"
    || (labelVisibility === "selected" && !selected)
    || (labelVisibility === "focus" && !focused)
    ? undefined
    : label;
  const renderedPoints = routePoints(renderedRoute);
  // 计算源/目标端的端点 + 方向向量（装饰物需要）
  const sourceEndpoint = endpointVector(renderedPoints, "source");
  const targetEndpoint = endpointVector(renderedPoints, "target");
  // 装饰物颜色：跟随边描边色
  const adornmentColor = typeof style?.stroke === "string" ? style.stroke : "var(--edge-default)";
  // 用于埋点的原始路由端点
  const originalRouteStart = routeStartPoint(data?.route);
  const originalRouteEnd = routeEndPoint(data?.route);
  const adjustedRouteStart = routeStartPoint(renderedRoute);
  const adjustedRouteEnd = routeEndPoint(renderedRoute);

  // 渲染埋点：记录本次渲染用的什么路由模式
  useEffect(() => {
    traceLinkGraph("routedEdge.render", {
      id,
      mode: localRoute ? "local-orthogonal" : adjustedRoute ? "stored-route" : "fallback",
      routeMode: data?.routeMode ?? null,
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
    <>
      {/* 鼠标悬停时的 title 提示 */}
      {data?.labelTitle ? <title>{data.labelTitle}</title> : null}
      <BaseEdge
        id={id}
        path={path}
        label={renderedLabel}
        labelX={labelPosition.x}
        labelY={labelPosition.y}
        markerStart={markerStart}
        markerEnd={markerEnd}
        interactionWidth={interactionWidth}
        style={style}
      />
      {/* 源端装饰：圆点 */}
      {data?.sourceAdornment === "dot" && sourceEndpoint ? (
        <circle
          className="routed-edge-source-dot"
          cx={sourceEndpoint.point.x}
          cy={sourceEndpoint.point.y}
          r={4.2}
          fill={adornmentColor}
          stroke="#fff8ea"
          strokeWidth={1.4}
        />
      ) : null}
      {/* 源端装饰：stub 短线 */}
      {data?.sourceAdornment === "stub" && sourceEndpoint ? (
        <line
          className="routed-edge-source-stub"
          x1={sourceEndpoint.point.x}
          y1={sourceEndpoint.point.y}
          x2={sourceEndpoint.point.x + sourceEndpoint.vector.x * 14}
          y2={sourceEndpoint.point.y + sourceEndpoint.vector.y * 14}
          stroke={adornmentColor}
          strokeWidth={3}
          strokeLinecap="round"
        />
      ) : null}
      {/* 目标端装饰：菱形 */}
      {data?.targetAdornment && targetEndpoint ? (
        <polygon
          className={`routed-edge-target-${data.targetAdornment}`}
          points={diamondPoints(targetEndpoint.point, targetEndpoint.vector)}
          fill={data.targetAdornment === "filled-diamond" ? adornmentColor : "#fff8ea"}
          stroke={adornmentColor}
          strokeWidth={2.2}
          strokeLinejoin="round"
        />
      ) : null}
    </>
  );
}

/** React Flow 边类型注册表：把 routedEdge 类型映射到 RoutedEdge 组件。 */
export const ROUTED_EDGE_TYPES: EdgeTypes = {
  routedEdge: RoutedEdge,
};
