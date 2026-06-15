import type { NodeMeasuredSize } from "../../graph/nodeSizeRegistry";
import type { LinkGraphNode } from "../../types";
import {
  dataColumn,
  laneOf,
  nodeBounds,
  nodeSortKey,
  nodeTop,
  orthogonalRectForNode,
  type ClassDiagramLane,
  type ClassNodeBounds,
  type ClassRouteContext,
} from "./classDiagramLayoutModel";

export function buildClassRouteContext(
  nodes: LinkGraphNode[],
  sizeSnapshot: ReadonlyMap<string, NodeMeasuredSize>,
): ClassRouteContext {
  const laneNodes = new Map<ClassDiagramLane, LinkGraphNode[]>();
  nodes.forEach((node) => {
    const lane = laneOf(node);
    laneNodes.set(lane, [...(laneNodes.get(lane) ?? []), node]);
  });
  laneNodes.forEach((bucket, lane) => {
    laneNodes.set(
      lane,
      [...bucket].sort((left, right) => nodeTop(left) - nodeTop(right) || nodeSortKey(left).localeCompare(nodeSortKey(right))),
    );
  });
  let dataBounds: ClassNodeBounds | null = null;
  const dataColumnBounds = new Map<number, ClassNodeBounds>();
  nodes
    .filter((node) => laneOf(node) === "DATA")
    .forEach((node) => {
      const bounds = nodeBounds(node, sizeSnapshot);
      const column = dataColumn(node);
      dataBounds = dataBounds ? {
        left: Math.min(dataBounds.left, bounds.left),
        right: Math.max(dataBounds.right, bounds.right),
        top: Math.min(dataBounds.top, bounds.top),
        bottom: Math.max(dataBounds.bottom, bounds.bottom),
      } : bounds;
      const existing = dataColumnBounds.get(column);
      dataColumnBounds.set(column, existing ? {
        left: Math.min(existing.left, bounds.left),
        right: Math.max(existing.right, bounds.right),
        top: Math.min(existing.top, bounds.top),
        bottom: Math.max(existing.bottom, bounds.bottom),
      } : bounds);
    });
  const obstacleRects = nodes.map((node) => orthogonalRectForNode(node, sizeSnapshot));
  return { laneNodes, dataBounds, dataColumnBounds, obstacleRects };
}

export function routeContextNodes(context: ClassRouteContext): LinkGraphNode[] {
  return Array.from(context.laneNodes.values()).flat();
}
