import type { NodeMeasuredSize } from "../../graph/nodeSizeRegistry";
import type { LinkGraphNode } from "../../types";
import {
  dataColumn,
  laneColumn,
  laneColumnBoundsKey,
  laneOf,
  mergeBounds,
  nodeBounds,
  nodeSortKey,
  nodeTop,
  numericMetadata,
  orthogonalRectForNode,
  type ClassDiagramLane,
  type ClassNodeBounds,
  type ClassRouteContext,
} from "./classDiagramLayoutModel";

function laneNodeOrderKey(
  node: LinkGraphNode,
): string {
  const column = laneColumn(node);
  const row = numericMetadata(node.metadata?.["layout.row"]);
  const top = nodeTop(node);
  return `${String(column).padStart(2, "0")}|${String(row).padStart(3, "0")}|${String(Math.round(top)).padStart(8, "0")}|${nodeSortKey(node)}`;
}

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
      [...bucket].sort((left, right) =>
        laneNodeOrderKey(left).localeCompare(laneNodeOrderKey(right)),
      ),
    );
  });

  const laneBounds = new Map<ClassDiagramLane, ClassNodeBounds>();
  const laneColumnBounds = new Map<string, ClassNodeBounds>();
  let dataBounds: ClassNodeBounds | null = null;
  const dataColumnBounds = new Map<number, ClassNodeBounds>();

  nodes.forEach((node) => {
    const lane = laneOf(node);
    const bounds = nodeBounds(node, sizeSnapshot);
    const existingLane = laneBounds.get(lane);
    if (existingLane) {
      laneBounds.set(lane, mergeBounds(existingLane, bounds));
    } else {
      laneBounds.set(lane, bounds);
    }
    const columnKey = laneColumnBoundsKey(lane, laneColumn(node));
    const existingColumn = laneColumnBounds.get(columnKey);
    if (existingColumn) {
      laneColumnBounds.set(columnKey, mergeBounds(existingColumn, bounds));
    } else {
      laneColumnBounds.set(columnKey, bounds);
    }
    if (lane === "DATA") {
      const column = dataColumn(node);
      dataBounds = dataBounds ? mergeBounds(dataBounds, bounds) : bounds;
      const existingDataColumn = dataColumnBounds.get(column);
      dataColumnBounds.set(column, existingDataColumn ? mergeBounds(existingDataColumn, bounds) : bounds);
    }
  });

  const obstacleRects = nodes.map((node) => orthogonalRectForNode(node, sizeSnapshot));
  return { laneNodes, laneBounds, laneColumnBounds, dataBounds, dataColumnBounds, obstacleRects };
}

export function routeContextNodes(context: ClassRouteContext): LinkGraphNode[] {
  return Array.from(context.laneNodes.values()).flat();
}
