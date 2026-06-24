// 正交边路由器：在节点之间生成不穿过其他节点的折线路由。
// 采用 A* 算法在"正交网格"上寻路：
// - 候选点集 = 起点/终点的 x/y 坐标与所有障碍物边界坐标的笛卡尔积；
// - 用障碍物扩展矩形（加 padding）模拟"安全距离"；
// - 转弯有额外代价，让算法尽量走直线；
// - 寻路失败时退化到手工折线兜底。
import type { GraphPosition, LinkGraphEdgeRoute } from "../types";
import { RoutePriorityQueue } from "./routePriorityQueue";

/** 节点连接柄所在边。 */
export type OrthogonalSide = "top" | "right" | "bottom" | "left";

/** 节点矩形（带可选 ID）。 */
export interface OrthogonalRect {
  /** 节点 ID（用于排除自身障碍物）。 */
  id?: string;
  /** 左上角 x。 */
  x: number;
  /** 左上角 y。 */
  y: number;
  /** 宽度。 */
  width: number;
  /** 高度。 */
  height: number;
}

/** 扩展后的矩形（已加 padding）。 */
interface ExpandedRect {
  /** 节点 ID。 */
  id?: string;
  /** 左边界。 */
  left: number;
  /** 右边界。 */
  right: number;
  /** 上边界。 */
  top: number;
  /** 下边界。 */
  bottom: number;
}

/** buildOrthogonalEdgeRoute 的入参。 */
export interface BuildOrthogonalEdgeRouteOptions {
  /** 起点。 */
  startPoint: GraphPosition;
  /** 起点连接柄所在边。 */
  startSide: OrthogonalSide;
  /** 起点节点矩形。 */
  startRect: OrthogonalRect;
  /** 终点。 */
  endPoint: GraphPosition;
  /** 终点连接柄所在边。 */
  endSide: OrthogonalSide;
  /** 终点节点矩形。 */
  endRect: OrthogonalRect;
  /** 障碍物矩形列表（路径不能穿过）。 */
  obstacleRects: OrthogonalRect[];
}

/** 障碍物扩展 padding（让边与节点保持一定距离）。 */
const OBSTACLE_PADDING = 24;
/** 端口 stub 长度：边从节点边缘出来先走一段直线再转向。 */
const PORT_STUB = 32;
/** 转弯惩罚：让算法优先走直线（少转弯）。 */
const TURN_PENALTY = 28;
/** 浮点比较容差。 */
const EPSILON = 0.5;

/** 取 3 位小数（避免浮点噪声）。 */
function round(value: number): number {
  return Math.round(value * 1000) / 1000;
}

/** 把点序列化为唯一 key（用于 map key）。 */
function pointKey(point: GraphPosition): string {
  return `${round(point.x)}:${round(point.y)}`;
}

/**
 * 把节点矩形扩展为障碍物矩形（加 padding）。
 * @param padding 扩展量，默认 OBSTACLE_PADDING
 */
function expandedRect(rect: OrthogonalRect, padding = OBSTACLE_PADDING): ExpandedRect {
  return {
    id: rect.id,
    left: rect.x - padding,
    right: rect.x + rect.width + padding,
    top: rect.y - padding,
    bottom: rect.y + rect.height + padding,
  };
}

/** 判断点是否在矩形内部（带容差）。 */
function pointInsideExpandedRect(point: GraphPosition, rect: ExpandedRect): boolean {
  return point.x > rect.left + EPSILON
    && point.x < rect.right - EPSILON
    && point.y > rect.top + EPSILON
    && point.y < rect.bottom - EPSILON;
}

/** 判断两点连线是否为水平（y 相同，带容差）。 */
function isHorizontalSegment(startPoint: GraphPosition, endPoint: GraphPosition): boolean {
  return Math.abs(startPoint.y - endPoint.y) <= EPSILON;
}

/** 判断两点连线是否为垂直（x 相同，带容差）。 */
function isVerticalSegment(startPoint: GraphPosition, endPoint: GraphPosition): boolean {
  return Math.abs(startPoint.x - endPoint.x) <= EPSILON;
}

/**
 * 判断线段是否穿过矩形。
 * 把线段按"水平/垂直"分支处理：
 * - 垂直线：检查 x 是否在矩形左右边界内，y 区间是否重叠；
 * - 水平线：同理；
 * - 斜线（理论上不应出现）：保守返回 true（视为相交）。
 */
function segmentIntersectsRect(
  startPoint: GraphPosition,
  endPoint: GraphPosition,
  rect: ExpandedRect,
): boolean {
  if (isVerticalSegment(startPoint, endPoint)) {
    const x = startPoint.x;
    // x 不在矩形内：不相交
    if (x <= rect.left + EPSILON || x >= rect.right - EPSILON) {
      return false;
    }
    const top = Math.min(startPoint.y, endPoint.y);
    const bottom = Math.max(startPoint.y, endPoint.y);
    // y 区间有重叠：相交
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
  // 斜线视为相交（正交路由不应产生斜线）
  return true;
}

/** 判断线段是否避开所有障碍物（不与任一相交）。 */
function segmentClear(
  startPoint: GraphPosition,
  endPoint: GraphPosition,
  obstacles: ExpandedRect[],
): boolean {
  return obstacles.every((rect) => !segmentIntersectsRect(startPoint, endPoint, rect));
}

/** 把点向指定边方向偏移 distance 距离。 */
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

/** 把数值加到坐标集合（取整后添加）。 */
function addCoordinate(set: Set<number>, value: number) {
  set.add(round(value));
}

/**
 * 简化点序列：
 * 1) 去除连续重复点；
 * 2) 共线三点合并中间点（同方向的连续段不需要中间点）。
 */
function simplifyPoints(points: GraphPosition[]): GraphPosition[] {
  // 第一步：去重
  const deduped: GraphPosition[] = [];
  points.forEach((point) => {
    const previous = deduped[deduped.length - 1];
    if (!previous || Math.abs(previous.x - point.x) > EPSILON || Math.abs(previous.y - point.y) > EPSILON) {
      deduped.push({ x: round(point.x), y: round(point.y) });
    }
  });
  // 2 个点及以下：不需要进一步简化
  if (deduped.length <= 2) {
    return deduped;
  }
  // 第二步：共线合并
  const simplified: GraphPosition[] = [deduped[0]!];
  for (let index = 1; index < deduped.length - 1; index += 1) {
    const previous = simplified[simplified.length - 1]!;
    const current = deduped[index]!;
    const next = deduped[index + 1]!;
    // 当前点与前后点都同向：跳过当前点
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

/** A* 搜索中的候选节点（含 point 与 key）。 */
interface CandidateNode {
  point: GraphPosition;
  key: string;
}

/** A* 搜索中每个状态记录的元数据。 */
interface RouteState {
  /** 实际代价 g。 */
  cost: number;
  /** 启发式估计 h（到终点的曼哈顿距离）。 */
  estimate: number;
  /** 状态 key（点 key + 方向）。 */
  key: string;
  /** 点 key。 */
  pointKey: string;
  /** 进入该点的方向（水平/垂直/起点）。 */
  direction: "horizontal" | "vertical" | "start";
}

/** 启发式函数：曼哈顿距离。 */
function heuristic(left: GraphPosition, right: GraphPosition): number {
  return Math.abs(left.x - right.x) + Math.abs(left.y - right.y);
}

/**
 * 把候选点集构造成邻接图。
 *
 * 策略：
 * - 把所有候选点按 x 和 y 分别建立索引；
 * - 同一行（y 相同）的点按 x 排序，相邻两点互连（避开障碍物）；
 * - 同一列（x 相同）的点同理。
 *
 * 这种"按行/列连接"策略生成稀疏邻接图，避免 O(n²) 全连接。
 */
function buildAdjacency(points: GraphPosition[], obstacles: ExpandedRect[]): Map<string, Array<{
  point: GraphPosition;
  cost: number;
  direction: "horizontal" | "vertical";
}>> {
  const adjacency = new Map<string, Array<{ point: GraphPosition; cost: number; direction: "horizontal" | "vertical" }>>();
  // 按 x 索引（同列点）与按 y 索引（同行点）
  const byX = new Map<number, CandidateNode[]>();
  const byY = new Map<number, CandidateNode[]>();

  // 建立索引
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

  // 同行相邻点互连（水平方向）
  byY.forEach((nodesOnRow) => {
    nodesOnRow.sort((left, right) => left.point.x - right.point.x);
    for (let index = 1; index < nodesOnRow.length; index += 1) {
      const leftNode = nodesOnRow[index - 1]!;
      const rightNode = nodesOnRow[index]!;
      // 跳过穿过障碍物的连接
      if (!segmentClear(leftNode.point, rightNode.point, obstacles)) {
        continue;
      }
      const cost = Math.abs(leftNode.point.x - rightNode.point.x);
      adjacency.get(leftNode.key)?.push({ point: rightNode.point, cost, direction: "horizontal" });
      adjacency.get(rightNode.key)?.push({ point: leftNode.point, cost, direction: "horizontal" });
    }
  });

  // 同列相邻点互连（垂直方向）
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

/**
 * 从终点回溯到起点，恢复完整路径。
 * 通过 cameFrom 映射逐跳追溯，最后反转得到起点→终点的顺序。
 */
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

/**
 * A* 寻路：在候选点集上找一条从 startStub 到 endStub 的最低代价路径。
 *
 * 关键设计：
 * - 状态 key = "点 key:方向"；方向影响转弯惩罚；
 * - 允许同一 key 重复入堆（更好路径出现时重新 push）；
 * - pop 时通过 bestCost 过滤过期条目；
 * - 启发式用曼哈顿距离（admissible，不会高估）。
 *
 * @return 路径点序列；找不到返回 null
 */
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

  const openQueue = new RoutePriorityQueue<RouteState>();
  const startState: RouteState = {
    key: startStateKey,
    pointKey: startPointKey,
    direction: "start",
    cost: 0,
    estimate: heuristic(startPoint, endPoint),
  };
  openQueue.push(startState, startState.cost + startState.estimate);

  // bestCost 跟踪每个状态 key 的最低已知代价。
  // 允许重新 push 更好路径，pop 时通过此 map 过滤过期条目。
  const bestCost = new Map<string, number>([[startStateKey, 0]]);
  // 父节点映射（用于回溯路径）
  const cameFrom = new Map<string, string>();
  const states = new Map<string, RouteState>([[startStateKey, startState]]);

  while (openQueue.size > 0) {
    const current = openQueue.pop();
    if (!current) {
      break;
    }
    // 跳过被更好路径取代的过期条目
    if (current.cost > (bestCost.get(current.key) ?? Number.POSITIVE_INFINITY)) {
      continue;
    }
    const currentPoint = pointsByKey.get(current.pointKey);
    if (!currentPoint) {
      continue;
    }
    // 到达终点：回溯路径
    if (current.pointKey === endPointKey) {
      return restoreRoute(cameFrom, states, current.key, pointsByKey);
    }
    const neighbors = adjacency.get(current.pointKey) ?? [];
    neighbors.forEach((neighbor) => {
      const nextKey = `${pointKey(neighbor.point)}:${neighbor.direction}`;
      // 转弯惩罚：起点或同方向时为 0，否则 TURN_PENALTY
      const turnPenalty = current.direction === "start" || current.direction === neighbor.direction ? 0 : TURN_PENALTY;
      const nextCost = current.cost + neighbor.cost + turnPenalty;
      // 不是更好路径：跳过
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
      // 允许堆中有重复 key；pop 时过滤过期条目
      openQueue.push(nextState, nextCost + nextState.estimate);
    });
  }

  return null;
}

/** 把点序列转换为边路由结构（首点为起点、末点为终点、中间为转折点）。 */
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

/**
 * 兜底路由：A* 寻路失败时的手工折线。
 *
 * 按起点/终点连接边的方向组合给出合理折线：
 * - 两 stub 已共线：直接 4 点；
 * - 上下/上下：取 y 中点折一下；
 * - 左右/左右：取 x 中点折一下；
 * - 其他：用 corner 点转折。
 */
function fallbackOrthogonalPoints(
  startPoint: GraphPosition,
  startStub: GraphPosition,
  startSide: OrthogonalSide,
  endStub: GraphPosition,
  endSide: OrthogonalSide,
  endPoint: GraphPosition,
): GraphPosition[] {
  // stub 已共线：直接连
  if (isVerticalSegment(startStub, endStub) || isHorizontalSegment(startStub, endStub)) {
    return [startPoint, startStub, endStub, endPoint];
  }
  // 都是上下：在中间 y 折一下
  if ((startSide === "top" || startSide === "bottom") && (endSide === "top" || endSide === "bottom")) {
    const midY = round((startStub.y + endStub.y) / 2);
    return [
      startPoint,
      startStub,
      { x: startStub.x, y: midY },
      { x: endStub.x, y: midY },
      endStub,
      endPoint,
    ];
  }
  // 都是左右：在中间 x 折一下
  if ((startSide === "left" || startSide === "right") && (endSide === "left" || endSide === "right")) {
    const midX = round((startStub.x + endStub.x) / 2);
    return [
      startPoint,
      startStub,
      { x: midX, y: startStub.y },
      { x: midX, y: endStub.y },
      endStub,
      endPoint,
    ];
  }
  // 混合方向：用一个 corner 点转折
  const corner = startSide === "top" || startSide === "bottom"
    ? { x: startStub.x, y: endStub.y }
    : { x: endStub.x, y: startStub.y };
  return [startPoint, startStub, corner, endStub, endPoint];
}

/**
 * 构造一条正交边路由。
 *
 * 流程：
 * 1) 计算 stub（边从节点边缘出来后的延长点）；
 * 2) 收集所有障碍物（含起终点矩形扩展）；
 * 3) 生成候选点集（起终点 x/y 与障碍物边界的笛卡尔积）；
 * 4) 在候选点集上跑 A*；
 * 5) 找到路径：转换成边路由；找不到：用兜底折线。
 */
export function buildOrthogonalEdgeRoute({
  startPoint,
  startSide,
  startRect,
  endPoint,
  endSide,
  endRect,
  obstacleRects,
}: BuildOrthogonalEdgeRouteOptions): LinkGraphEdgeRoute {
  // 计算 stub：起点/终点各向连接边方向偏移 PORT_STUB
  const startStub = offsetFromSide(startPoint, startSide, PORT_STUB);
  const endStub = offsetFromSide(endPoint, endSide, PORT_STUB);
  // 障碍物：起点/终点矩形 + 其他节点矩形（去重）
  const searchObstacles = [
    expandedRect(startRect),
    expandedRect(endRect),
    ...obstacleRects
      .filter((rect) => rect.id !== startRect.id && rect.id !== endRect.id)
      .map((rect) => expandedRect(rect)),
  ];

  // 收集所有 x/y 候选坐标（去重）
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
  // 把障碍物边界也加入候选坐标
  searchObstacles.forEach((rect) => {
    addCoordinate(xCoordinates, rect.left);
    addCoordinate(xCoordinates, rect.right);
    addCoordinate(yCoordinates, rect.top);
    addCoordinate(yCoordinates, rect.bottom);
  });

  // 候选点 = x 坐标 × y 坐标的笛卡尔积；剔除落在障碍物内部的点
  const candidatePoints = Array.from(xCoordinates)
    .flatMap((x) => Array.from(yCoordinates).map((y) => ({ x, y })))
    .filter((point) => !searchObstacles.some((rect) => pointInsideExpandedRect(point, rect)));

  // 确保 stub 点在候选集中
  [startStub, endStub].forEach((point) => {
    const key = pointKey(point);
    if (!candidatePoints.some((candidate) => pointKey(candidate) === key)) {
      candidatePoints.push(point);
    }
  });

  // A* 寻路
  const gridPath = findGridPath(startStub, endStub, searchObstacles, candidatePoints);
  if (!gridPath) {
    // 寻路失败：用兜底折线
    return routeFromPoints(fallbackOrthogonalPoints(
      startPoint,
      startStub,
      startSide,
      endStub,
      endSide,
      endPoint,
    ));
  }
  // 成功：把 stub 端点补回（stub 是节点边缘外一点）
  return routeFromPoints([startPoint, ...gridPath, endPoint]);
}
