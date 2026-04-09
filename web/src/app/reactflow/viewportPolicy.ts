import { DEFAULT_NODE_CARD_WIDTH } from "../graphNodeSizing";
import type { GraphPosition } from "../types";

const NODE_CARD_HEIGHT = 156;
const WIDE_GRAPH_WIDTH_THRESHOLD = 3600;
const WIDE_GRAPH_ASPECT_RATIO_THRESHOLD = 2.8;
const WIDE_GRAPH_NODE_COUNT_THRESHOLD = 12;
const VIEWPORT_PRESERVE_MAX_ADDED_NODES = 8;
const VIEWPORT_PRESERVE_MAX_ADDED_EDGES = 16;

export interface ViewportPositionedNode {
  id: string;
  position?: GraphPosition;
}

export interface GraphViewportSnapshot {
  anchorNodeId: string | null;
  nodeIds: Set<string>;
  edgeIds: Set<string>;
}

function resolveNodePosition(node: ViewportPositionedNode) {
  return node.position ?? { x: 0, y: 0 };
}

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

export function shouldPreserveViewportForIncrementalUpdate(
  previous: GraphViewportSnapshot | null,
  next: GraphViewportSnapshot,
): boolean {
  if (!previous || !next.anchorNodeId || previous.anchorNodeId !== next.anchorNodeId) {
    return false;
  }
  const addedNodeCount = [...next.nodeIds].filter((nodeId) => !previous.nodeIds.has(nodeId)).length;
  const addedEdgeCount = [...next.edgeIds].filter((edgeId) => !previous.edgeIds.has(edgeId)).length;
  const removedNodeCount = [...previous.nodeIds].filter((nodeId) => !next.nodeIds.has(nodeId)).length;
  const removedEdgeCount = [...previous.edgeIds].filter((edgeId) => !next.edgeIds.has(edgeId)).length;

  if (addedNodeCount > 0 && removedNodeCount > 0) {
    return false;
  }

  const isSmallEdgeOnlyChange =
    addedNodeCount === 0 &&
    removedNodeCount === 0 &&
    (addedEdgeCount > 0 || removedEdgeCount > 0) &&
    addedEdgeCount <= VIEWPORT_PRESERVE_MAX_ADDED_EDGES &&
    removedEdgeCount <= VIEWPORT_PRESERVE_MAX_ADDED_EDGES;

  if (isSmallEdgeOnlyChange) {
    return true;
  }

  const isSmallExpansion =
    addedNodeCount > 0 &&
    removedNodeCount === 0 &&
    removedEdgeCount === 0 &&
    addedNodeCount <= VIEWPORT_PRESERVE_MAX_ADDED_NODES &&
    addedEdgeCount <= VIEWPORT_PRESERVE_MAX_ADDED_EDGES;

  if (isSmallExpansion) {
    return true;
  }

  return (
    removedNodeCount > 0 &&
    addedNodeCount === 0 &&
    addedEdgeCount === 0 &&
    removedNodeCount <= VIEWPORT_PRESERVE_MAX_ADDED_NODES &&
    removedEdgeCount <= VIEWPORT_PRESERVE_MAX_ADDED_EDGES
  );
}
