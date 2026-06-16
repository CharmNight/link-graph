import {
  CLASS_DIAGRAM_COMPACT_NODE_CARD_WIDTH,
  classDiagramNodeCardWidth,
} from "../../graphNodeSizing";
import type { NodeMeasuredSize } from "../../graph/nodeSizeRegistry";
import type { GraphPosition, LinkGraphEdge, LinkGraphNode } from "../../types";
import type { OrthogonalRect, OrthogonalSide } from "../../reactflow/orthogonalEdgeRouting";
import {
  classDiagramRelationLabel,
  classDiagramRelationSortRank,
  classDiagramRenderedRelationRank,
} from "./classDiagramRelations";

export const TOP_PORT = "target-top";
export const SOURCE_TOP_PORT = "source-top";
export const BOTTOM_PORT = "source-bottom";
export const TARGET_BOTTOM_PORT = "target-bottom";
export const LEFT_PORT = "target-left";
export const SOURCE_LEFT_PORT = "source-left";
export const RIGHT_PORT = "source-right";
export const TARGET_RIGHT_PORT = "target-right";

export const FALLBACK_HEIGHT = 260;
export const COMPACT_FALLBACK_HEIGHT = 104;
export const MIN_NODE_WIDTH = CLASS_DIAGRAM_COMPACT_NODE_CARD_WIDTH;
export const X_GAP = 500;
export const SIDE_LANE_BASE_GAP = MIN_NODE_WIDTH + 128;
export const Y_GAP = 260;
export const STACK_GAP = 92;
export const PARENT_ANCHOR_GAP = 148;
export const DATA_COLUMN_GAP = 112;
export const OUTGOING_COLUMN_GAP = 112;
export const DATA_COLUMN_VERTICAL_STAGGER = 56;
export const DATA_ANCHOR_GAP = 190;
export const DATA_RIGHT_OFFSET = 128;
export const ROUTE_GAP = 38;
export const ROUTE_LANE_GAP = 58;
export const OUTER_ROUTE_MARGIN_X = 140;
export const OUTER_ROUTE_MARGIN_Y = 118;
export const DATA_ROUTE_TOP_GAP = 54;
export const DATA_ROUTE_TOP_OFFSET_LIMIT = 96;
export const DATA_ROUTE_BOTTOM_OFFSET_LIMIT = 180;
export const ANCHOR_ROUTE_SOURCE_MARGIN_X = 76;
export const ANCHOR_ROUTE_TARGET_MARGIN_X = 56;
export const ANCHOR_ROUTE_CHANNEL_GAP = 116;
export const ORIGIN: GraphPosition = { x: 128, y: 128 };
export const SIDE_FANOUT_SLOT_COUNT = 7;
export const SIDE_FANOUT_CENTER_SLOT = Math.floor(SIDE_FANOUT_SLOT_COUNT / 2);
export const EDGE_CROSSING_REPAIR_MAX_PASSES = 4;
export const EDGE_CROSSING_REPAIR_NODE_PENALTY = 100_000;
export const EDGE_CROSSING_REPAIR_CROSSING_PENALTY = 4_000;
export const EDGE_CROSSING_REPAIR_OVERLAP_PENALTY = 2_500;
export const EDGE_CROSSING_REPAIR_NODE_CLEARANCE = 18;
export const EDGE_CROSSING_REPAIR_CHANNEL_GAP = 42;
export const EDGE_CROSSING_REPAIR_LOCALITY_IMPROVEMENT = 8;
export const CROSS_LANE_SECONDARY_RAIL_GAP = 56;
export const CROSS_LANE_SECONDARY_RAIL_STEP = 24;
export const CROSS_LANE_SECONDARY_STUB_GAP = 36;

export type ClassDiagramLane = "PARENT" | "INCOMING" | "ANCHOR" | "OUTGOING" | "DATA" | "RELATED";

export interface ClassLaneEntry {
  node: LinkGraphNode;
  lane: ClassDiagramLane;
  depth: number;
}

export interface RouteChannel {
  index: number;
  count: number;
}

export interface EdgeRouteChannels {
  source: RouteChannel;
  target: RouteChannel;
}

export interface ClassNodeBounds {
  left: number;
  right: number;
  top: number;
  bottom: number;
}

export interface ClassRouteContext {
  laneNodes: Map<ClassDiagramLane, LinkGraphNode[]>;
  dataBounds: ClassNodeBounds | null;
  dataColumnBounds: Map<number, ClassNodeBounds>;
  obstacleRects: OrthogonalRect[];
}

export interface AxisInterval {
  start: number;
  end: number;
}

export const LANE_RANK: Record<ClassDiagramLane, number> = {
  PARENT: 0,
  INCOMING: 1,
  ANCHOR: 2,
  OUTGOING: 3,
  DATA: 4,
  RELATED: 5,
};

export function relationRank(edge: LinkGraphEdge): number {
  return classDiagramRelationSortRank(edge);
}

export function renderedEdgeRank(edge: LinkGraphEdge): number {
  return classDiagramRenderedRelationRank(edge);
}

export function nodeSortKey(node: LinkGraphNode): string {
  return `${node.title}|${node.signature ?? ""}|${node.id}`;
}

export function edgeSortKey(edge: LinkGraphEdge): string {
  return `${relationRank(edge)}|${classDiagramRelationLabel(edge)}|${edge.source}|${edge.target}|${edge.id}`;
}

export function hashText(value: string): number {
  let hash = 0;
  for (let index = 0; index < value.length; index += 1) {
    hash = ((hash * 31) + value.charCodeAt(index)) >>> 0;
  }
  return hash;
}

export function clamp(value: number, min: number, max: number): number {
  return Math.min(max, Math.max(min, value));
}

function metadataLines(value?: string | null): number {
  return (value ?? "")
    .split("\n")
    .map((line) => line.trim())
    .filter(Boolean)
    .length;
}

export function numericMetadata(value?: string | null): number {
  const parsed = Number(value ?? "0");
  return Number.isFinite(parsed) ? Math.max(0, parsed) : 0;
}

function estimateCommentLines(node: LinkGraphNode): number {
  const comment = node.doc ?? node.metadata?.["uml.comment"] ?? node.metadata?.["jvm.class.docComment"];
  if (!comment?.trim()) {
    return 0;
  }
  const explicitLines = comment.split("\n").filter((line) => line.trim()).length;
  const wrappedLines = Math.ceil(comment.trim().length / 58);
  return clamp(Math.max(explicitLines, wrappedLines, 1), 1, 3);
}

function estimateCompartmentHeight(lineCount: number): number {
  const visibleLines = clamp(lineCount, 1, 5);
  return 16 + visibleLines * 17;
}

function isCompactNode(node: LinkGraphNode, lane?: ClassDiagramLane): boolean {
  if (lane) {
    return lane !== "ANCHOR";
  }
  if (node.metadata?.["presentation.compact"] === "true") {
    return true;
  }
  if (node.metadata?.["presentation.compact"] === "false") {
    return false;
  }
  const presentationRole = node.metadata?.["presentation.role"];
  if (presentationRole) {
    return presentationRole !== "ANCHOR";
  }
  const layoutDirection = lane ?? node.metadata?.["layout.direction"];
  return layoutDirection !== "ANCHOR";
}

function estimateCompactNodeHeight(node: LinkGraphNode): number {
  const methodLines = metadataLines(node.metadata?.["uml.method.items"]);
  const detailLineCount = methodLines > 0 || node.signature || node.location || node.metadata?.["architecture.package"] ? 1 : 0;
  const estimated = 74 + detailLineCount * 18;
  return clamp(estimated, 88, 132);
}

export function estimateUmlNodeHeight(node: LinkGraphNode, lane?: ClassDiagramLane): number {
  if (isCompactNode(node, lane)) {
    return estimateCompactNodeHeight(node);
  }
  const fieldLines = metadataLines(node.metadata?.["uml.field.items"]) +
    (numericMetadata(node.metadata?.["uml.field.hiddenCount"]) > 0 ? 1 : 0);
  const methodLines = metadataLines(node.metadata?.["uml.method.items"]) +
    (numericMetadata(node.metadata?.["uml.method.hiddenCount"]) > 0 ? 1 : 0);
  const commentLines = estimateCommentLines(node);
  const commentHeight = commentLines > 0 ? 18 + commentLines * 17 : 0;
  const estimated = 76 + commentHeight + estimateCompartmentHeight(fieldLines) + estimateCompartmentHeight(methodLines);
  return clamp(estimated, FALLBACK_HEIGHT, 380);
}

export function measuredNodeSize(
  node: LinkGraphNode,
  sizeSnapshot: ReadonlyMap<string, NodeMeasuredSize>,
  lane?: ClassDiagramLane,
) {
  const measured = sizeSnapshot.get(node.id);
  const estimatedHeight = estimateUmlNodeHeight(node, lane);
  const estimatedWidth = lane === "ANCHOR"
    ? classDiagramNodeCardWidth()
    : classDiagramNodeCardWidth(node);
  return {
    width: Math.max(measured?.width ?? estimatedWidth, estimatedWidth),
    height: Math.max(measured?.height ?? estimatedHeight, estimatedHeight),
  };
}

export function laneOf(node: LinkGraphNode): ClassDiagramLane {
  return (node.metadata?.["layout.direction"] as ClassDiagramLane | undefined) ?? "RELATED";
}

export function dataColumn(node: LinkGraphNode): number {
  const parsed = Number(node.metadata?.["layout.column"] ?? "0");
  return Number.isFinite(parsed) ? Math.max(0, Math.round(parsed)) : 0;
}

export function dataRow(node: LinkGraphNode): number {
  const parsed = Number(node.metadata?.["layout.row"] ?? "0");
  return Number.isFinite(parsed) ? Math.max(0, Math.round(parsed)) : 0;
}

export function dataOuterSide(node: LinkGraphNode): "left" | "right" {
  return dataColumn(node) === 0 ? "left" : "right";
}

export function dataOuterSideAwayFromTarget(source: LinkGraphNode, target: LinkGraphNode): "left" | "right" {
  if (target.position && source.position) {
    return target.position.x >= source.position.x ? "right" : "left";
  }
  return dataOuterSide(source);
}

export function laneColumn(node: LinkGraphNode): number {
  if (laneOf(node) === "DATA") {
    return dataColumn(node);
  }
  const parsed = Number(node.metadata?.["layout.column"] ?? "0");
  return Number.isFinite(parsed) ? Math.max(0, Math.round(parsed)) : 0;
}

export function nodeTop(node: LinkGraphNode) {
  return node.position?.y ?? ORIGIN.y;
}

export function nodeBottom(node: LinkGraphNode, sizeSnapshot: ReadonlyMap<string, NodeMeasuredSize>) {
  return (node.position?.y ?? ORIGIN.y) + measuredNodeSize(node, sizeSnapshot).height;
}

export function nodeLeft(node: LinkGraphNode) {
  return node.position?.x ?? ORIGIN.x;
}

export function nodeRight(node: LinkGraphNode, sizeSnapshot: ReadonlyMap<string, NodeMeasuredSize>) {
  return (node.position?.x ?? ORIGIN.x) + measuredNodeSize(node, sizeSnapshot).width;
}

export function nodeCenterY(
  node: LinkGraphNode,
  sizeSnapshot: ReadonlyMap<string, NodeMeasuredSize>,
): number {
  return nodeTop(node) + measuredNodeSize(node, sizeSnapshot).height / 2;
}

export function nodeCenterX(
  node: LinkGraphNode,
  sizeSnapshot: ReadonlyMap<string, NodeMeasuredSize>,
): number {
  return nodeLeft(node) + measuredNodeSize(node, sizeSnapshot).width / 2;
}

export function mergeBounds(left: ClassNodeBounds | null, right: ClassNodeBounds): ClassNodeBounds {
  if (!left) {
    return right;
  }
  return {
    left: Math.min(left.left, right.left),
    right: Math.max(left.right, right.right),
    top: Math.min(left.top, right.top),
    bottom: Math.max(left.bottom, right.bottom),
  };
}

export function nodeBounds(
  node: LinkGraphNode,
  sizeSnapshot: ReadonlyMap<string, NodeMeasuredSize>,
): ClassNodeBounds {
  return {
    left: nodeLeft(node),
    right: nodeRight(node, sizeSnapshot),
    top: nodeTop(node),
    bottom: nodeBottom(node, sizeSnapshot),
  };
}

export function expandBounds(bounds: ClassNodeBounds, padding: number): ClassNodeBounds {
  return {
    left: bounds.left - padding,
    right: bounds.right + padding,
    top: bounds.top - padding,
    bottom: bounds.bottom + padding,
  };
}

export function orthogonalRectForNode(
  node: LinkGraphNode,
  sizeSnapshot: ReadonlyMap<string, NodeMeasuredSize>,
): OrthogonalRect {
  const size = measuredNodeSize(node, sizeSnapshot);
  return {
    id: node.id,
    x: nodeLeft(node),
    y: nodeTop(node),
    width: size.width,
    height: size.height,
  };
}

export function routeSectionPoints(route: LinkGraphEdge["route"]): GraphPosition[] {
  const section = route?.sections[0];
  return section ? [section.startPoint, ...(section.bendPoints ?? []), section.endPoint] : [];
}

export function routePoints(edge: LinkGraphEdge): GraphPosition[] {
  return routeSectionPoints(edge.route);
}

export function simplifyRoutePoints(points: GraphPosition[]): GraphPosition[] {
  const deduped: GraphPosition[] = [];
  points.forEach((point) => {
    const previous = deduped[deduped.length - 1];
    if (!previous || Math.abs(previous.x - point.x) > 0.5 || Math.abs(previous.y - point.y) > 0.5) {
      deduped.push({ x: Math.round(point.x), y: Math.round(point.y) });
    }
  });
  if (deduped.length <= 2) {
    return deduped;
  }
  const simplified: GraphPosition[] = [deduped[0]!];
  for (let index = 1; index < deduped.length - 1; index += 1) {
    const previous = simplified[simplified.length - 1]!;
    const current = deduped[index]!;
    const next = deduped[index + 1]!;
    const sameVertical = Math.abs(previous.x - current.x) <= 0.5 && Math.abs(current.x - next.x) <= 0.5;
    const sameHorizontal = Math.abs(previous.y - current.y) <= 0.5 && Math.abs(current.y - next.y) <= 0.5;
    if (sameVertical || sameHorizontal) {
      continue;
    }
    simplified.push(current);
  }
  simplified.push(deduped[deduped.length - 1]!);
  return simplified;
}

export function routeFromPoints(points: GraphPosition[]): LinkGraphEdge["route"] {
  const simplified = simplifyRoutePoints(points);
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

export function routeLength(points: GraphPosition[]): number {
  return points.slice(1).reduce((total, point, index) => {
    const previous = points[index]!;
    return total + Math.abs(point.x - previous.x) + Math.abs(point.y - previous.y);
  }, 0);
}

export function portBase(port: string): string {
  return port.replace(/-\d+$/, "");
}

export function portSlot(port: string): number | null {
  const match = /-(\d+)$/.exec(port);
  if (!match) {
    return null;
  }
  const parsed = Number(match[1]);
  return Number.isFinite(parsed) ? clamp(Math.round(parsed), 0, SIDE_FANOUT_SLOT_COUNT - 1) : null;
}

export function isHorizontalPort(port: string): boolean {
  const basePort = portBase(port);
  return basePort === LEFT_PORT || basePort === RIGHT_PORT || basePort === SOURCE_LEFT_PORT || basePort === TARGET_RIGHT_PORT;
}

export function sourceSideForPort(port: string): OrthogonalSide {
  switch (portBase(port)) {
    case SOURCE_TOP_PORT:
    case TOP_PORT:
      return "top";
    case BOTTOM_PORT:
    case TARGET_BOTTOM_PORT:
      return "bottom";
    case SOURCE_LEFT_PORT:
    case LEFT_PORT:
      return "left";
    case RIGHT_PORT:
    case TARGET_RIGHT_PORT:
    default:
      return "right";
  }
}

export function targetSideForPort(port: string): OrthogonalSide {
  switch (portBase(port)) {
    case SOURCE_TOP_PORT:
    case TOP_PORT:
      return "top";
    case BOTTOM_PORT:
    case TARGET_BOTTOM_PORT:
      return "bottom";
    case SOURCE_LEFT_PORT:
    case LEFT_PORT:
      return "left";
    case RIGHT_PORT:
    case TARGET_RIGHT_PORT:
    default:
      return "right";
  }
}

export function sideFanoutSlot(index: number, count: number): number {
  if (count <= 1) {
    return SIDE_FANOUT_CENTER_SLOT;
  }
  if (count === 2) {
    return index <= 0 ? SIDE_FANOUT_CENTER_SLOT - 1 : SIDE_FANOUT_CENTER_SLOT + 1;
  }
  if (count === 3) {
    return [1, SIDE_FANOUT_CENTER_SLOT, SIDE_FANOUT_SLOT_COUNT - 2][clamp(index, 0, 2)] ?? SIDE_FANOUT_CENTER_SLOT;
  }
  return clamp(Math.round((index * (SIDE_FANOUT_SLOT_COUNT - 1)) / (count - 1)), 0, SIDE_FANOUT_SLOT_COUNT - 1);
}

export function portWithSlot(basePort: string, slot: number): string {
  return `${basePort}-${clamp(slot, 0, SIDE_FANOUT_SLOT_COUNT - 1)}`;
}

export function routeChannelRatio(channel: RouteChannel): number {
  return (clamp(channel.index, 0, channel.count - 1) + 1) / (channel.count + 1);
}

export function fanoutChannelX(minX: number, maxX: number, channel: RouteChannel, pairOffset: number): number {
  if (maxX <= minX) {
    return Math.round((minX + maxX) / 2);
  }
  const channelX = minX + (maxX - minX) * routeChannelRatio(channel);
  const singleRouteNudge = channel.count <= 1 ? pairOffset / 3 : 0;
  return clamp(Math.round(channelX + singleRouteNudge), minX, maxX);
}

export function reversedRouteChannel(channel: RouteChannel): RouteChannel {
  return {
    index: Math.max(0, channel.count - channel.index - 1),
    count: channel.count,
  };
}

export function portPoint(
  node: LinkGraphNode,
  port: string,
  sizeSnapshot: ReadonlyMap<string, NodeMeasuredSize>,
  offset: number,
): GraphPosition {
  const size = measuredNodeSize(node, sizeSnapshot);
  const position = node.position ?? ORIGIN;
  const basePort = portBase(port);
  const slot = portSlot(port);
  const verticalSlotRatio = slot == null ? 0.5 : (slot + 1) / (SIDE_FANOUT_SLOT_COUNT + 1);
  const horizontalSlotRatio = verticalSlotRatio;
  switch (basePort) {
    case SOURCE_TOP_PORT:
    case TOP_PORT:
      return { x: position.x + size.width * horizontalSlotRatio + offset, y: position.y };
    case BOTTOM_PORT:
    case TARGET_BOTTOM_PORT:
      return { x: position.x + size.width * horizontalSlotRatio + offset, y: position.y + size.height };
    case SOURCE_LEFT_PORT:
    case LEFT_PORT:
      return { x: position.x, y: position.y + size.height * verticalSlotRatio + offset };
    case RIGHT_PORT:
    case TARGET_RIGHT_PORT:
    default:
      return { x: position.x + size.width, y: position.y + size.height * verticalSlotRatio + offset };
  }
}
