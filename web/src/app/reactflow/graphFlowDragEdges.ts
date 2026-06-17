import type { Edge } from "@xyflow/react";
import type { GraphPosition } from "../types";

function edgeTouchesNodeIds(edge: Edge, nodeIds: ReadonlySet<string>): boolean {
  return nodeIds.has(edge.source) || nodeIds.has(edge.target);
}

function edgeWithoutStoredRoute(edge: Edge): Edge {
  if (!edge.data || !("route" in edge.data)) {
    return edge;
  }
  const data = { ...edge.data };
  delete data.route;
  return {
    ...edge,
    data,
  };
}

export function buildRenderedFlowEdges(
  flowEdges: Edge[],
  liveDragPositions: Record<string, GraphPosition>,
  selectedEdgeId: string | null,
): Edge[] {
  const liveDragNodeIds = new Set(Object.keys(liveDragPositions));
  if (!selectedEdgeId && liveDragNodeIds.size === 0) {
    return flowEdges;
  }
  return flowEdges.map((edge) => ({
    ...(liveDragNodeIds.size > 0 && edgeTouchesNodeIds(edge, liveDragNodeIds)
      ? edgeWithoutStoredRoute(edge)
      : edge),
    selected: edge.id === selectedEdgeId ? true : edge.selected,
  }));
}
