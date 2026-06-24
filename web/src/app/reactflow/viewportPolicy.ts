// React Flow 视口策略：决定何时聚焦锚点、何时在增量更新中保留当前视口。
// 主要解决两个问题：
// 1) 大图首次加载时是否要把镜头主动聚焦到锚点（避免用户看不到主节点）；
// 2) 增量更新（小量增删节点/边）时是否保留当前视口（避免镜头跳动）。
import { DEFAULT_NODE_CARD_WIDTH } from "../graphNodeSizing";
import type { GraphPosition } from "../types";

/** 默认节点卡片高度；与 CSS 中的卡片高度保持一致。 */
const NODE_CARD_HEIGHT = 156;
/** 触发"宽图聚焦"的宽度阈值（像素）。超过此值的图视为宽图。 */
const WIDE_GRAPH_WIDTH_THRESHOLD = 3600;
/** 触发"宽图聚焦"的纵横比阈值；图很扁平时也视为宽图。 */
const WIDE_GRAPH_ASPECT_RATIO_THRESHOLD = 2.8;
/** 触发"宽图聚焦"的节点数阈值（与纵横比组合判断）。 */
const WIDE_GRAPH_NODE_COUNT_THRESHOLD = 12;
/** 增量更新允许保留视口的最大新增节点数。 */
const VIEWPORT_PRESERVE_MAX_ADDED_NODES = 8;
/** 增量更新允许保留视口的最大新增边数。 */
const VIEWPORT_PRESERVE_MAX_ADDED_EDGES = 16;

/** 视口判断所需的最小节点信息。 */
export interface ViewportPositionedNode {
  /** 节点 ID。 */
  id: string;
  /** 节点坐标；为空时按 (0,0) 处理。 */
  position?: GraphPosition;
}

/** 一次视口快照：记录锚点、节点/边集合与可选 resetKey。 */
export interface GraphViewportSnapshot {
  /** 当前锚点节点 ID。 */
  anchorNodeId: string | null;
  /** 当前所有节点 ID 集合。 */
  nodeIds: Set<string>;
  /** 当前所有边 ID 集合。 */
  edgeIds: Set<string>;
  /** 重置键；变化时强制不保留旧视口。 */
  resetKey?: string | null;
}

/** 解析节点坐标，缺失时回退到 (0,0)。 */
function resolveNodePosition(node: ViewportPositionedNode) {
  return node.position ?? { x: 0, y: 0 };
}

/**
 * 计算节点集合的边界框。
 * 每个节点的占用区域 = 位置 + 默认卡片宽高。
 * 空集合返回 null，让调用方决定如何处理。
 */
export function graphBounds(nodes: ViewportPositionedNode[]) {
  if (nodes.length === 0) {
    return null;
  }
  let minX = Number.POSITIVE_INFINITY;
  let minY = Number.POSITIVE_INFINITY;
  let maxX = Number.NEGATIVE_INFINITY;
  let maxY = Number.NEGATIVE_INFINITY;
  nodes.forEach((node) => {
    const position = resolveNodePosition(node);
    minX = Math.min(minX, position.x);
    minY = Math.min(minY, position.y);
    maxX = Math.max(maxX, position.x + DEFAULT_NODE_CARD_WIDTH);
    maxY = Math.max(maxY, position.y + NODE_CARD_HEIGHT);
  });
  return {
    minX,
    minY,
    maxX,
    maxY,
    width: maxX - minX,
    height: maxY - minY,
  };
}

/**
 * 判断是否应主动聚焦锚点。
 *
 * 满足以下任一条件即聚焦：
 * - 图宽度超过阈值（图过宽时不聚焦用户会看不到锚点）；
 * - 节点数较多且纵横比偏扁（同样会导致锚点视觉上不显眼）。
 */
export function shouldFocusAnchor(nodes: ViewportPositionedNode[]): boolean {
  const bounds = graphBounds(nodes);
  if (!bounds) {
    return false;
  }
  const aspectRatio = bounds.width / Math.max(bounds.height, 1);
  return (
    bounds.width >= WIDE_GRAPH_WIDTH_THRESHOLD ||
    (nodes.length >= WIDE_GRAPH_NODE_COUNT_THRESHOLD && aspectRatio >= WIDE_GRAPH_ASPECT_RATIO_THRESHOLD)
  );
}

/**
 * 判断增量更新时是否应保留旧视口（不重置镜头）。
 *
 * 决策流程：
 * 1) resetKey 不一致 → 强制重置；
 * 2) 无上次快照 / 无锚点 / 锚点变了 → 重置；
 * 3) 增删完全为空 → 保留；
 * 4) 同时有增有删 → 重置（结构变化大）；
 * 5) 仅少量边变化（节点数不变）→ 保留；
 * 6) 仅少量新增（无删）→ 保留；
 * 7) 仅少量删除（无增）→ 保留；
 * 8) 其他 → 重置。
 *
 * 这种策略避免小幅增量更新导致镜头跳动，同时保证大变化时及时重置。
 */
export function shouldPreserveViewportForIncrementalUpdate(
  previous: GraphViewportSnapshot | null,
  next: GraphViewportSnapshot,
): boolean {
  // resetKey 不一致：视图语义已变（例如切换场景），强制重置
  if (previous?.resetKey !== next.resetKey) {
    return false;
  }
  // 锚点变化时重置，让用户能看到新锚点
  if (!previous || !next.anchorNodeId || previous.anchorNodeId !== next.anchorNodeId) {
    return false;
  }
  // 统计本次更新的增量
  const addedNodeCount = [...next.nodeIds].filter((nodeId) => !previous.nodeIds.has(nodeId)).length;
  const addedEdgeCount = [...next.edgeIds].filter((edgeId) => !previous.edgeIds.has(edgeId)).length;
  const removedNodeCount = [...previous.nodeIds].filter((nodeId) => !next.nodeIds.has(nodeId)).length;
  const removedEdgeCount = [...previous.edgeIds].filter((edgeId) => !next.edgeIds.has(edgeId)).length;

  // 无任何变化：保留
  if (addedNodeCount === 0 && addedEdgeCount === 0 && removedNodeCount === 0 && removedEdgeCount === 0) {
    return true;
  }

  // 同时增删：结构变化大，重置
  if (addedNodeCount > 0 && removedNodeCount > 0) {
    return false;
  }

  // 仅少量边变化（节点数不变）：保留
  const isSmallEdgeOnlyChange =
    addedNodeCount === 0 &&
    removedNodeCount === 0 &&
    (addedEdgeCount > 0 || removedEdgeCount > 0) &&
    addedEdgeCount <= VIEWPORT_PRESERVE_MAX_ADDED_EDGES &&
    removedEdgeCount <= VIEWPORT_PRESERVE_MAX_ADDED_EDGES;

  if (isSmallEdgeOnlyChange) {
    return true;
  }

  // 仅少量新增（无删）：保留
  const isSmallExpansion =
    addedNodeCount > 0 &&
    removedNodeCount === 0 &&
    removedEdgeCount === 0 &&
    addedNodeCount <= VIEWPORT_PRESERVE_MAX_ADDED_NODES &&
    addedEdgeCount <= VIEWPORT_PRESERVE_MAX_ADDED_EDGES;

  if (isSmallExpansion) {
    return true;
  }

  // 仅少量删除（无增）：保留
  return (
    removedNodeCount > 0 &&
    addedNodeCount === 0 &&
    addedEdgeCount === 0 &&
    removedNodeCount <= VIEWPORT_PRESERVE_MAX_ADDED_NODES &&
    removedEdgeCount <= VIEWPORT_PRESERVE_MAX_ADDED_EDGES
  );
}
