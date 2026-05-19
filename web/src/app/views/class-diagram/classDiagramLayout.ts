import type { LayoutOptions } from "elkjs/lib/elk-api";
import { classDiagramNodeCardWidth } from "../../graphNodeSizing";
import type { NodeMeasuredSize } from "../../graph/nodeSizeRegistry";
import { traceLinkGraph } from "../../debug";
import type { GraphPosition, LinkGraphEdge, LinkGraphNode } from "../../types";
import { executeElkLayout } from "../../reactflow/elkGraph";
import type { MeasuredLayoutRequest } from "../../reactflow/useMeasuredLayout";

const CLASS_DIAGRAM_LAYOUT_OPTIONS: LayoutOptions = {
  "elk.algorithm": "layered",
  "org.eclipse.elk.direction": "RIGHT",
  "org.eclipse.elk.edgeRouting": "ORTHOGONAL",
  "org.eclipse.elk.layered.nodePlacement.strategy": "NETWORK_SIMPLEX",
  "org.eclipse.elk.layered.spacing.nodeNodeBetweenLayers": "220",
  "org.eclipse.elk.layered.spacing.edgeNodeBetweenLayers": "120",
  "org.eclipse.elk.spacing.nodeNode": "104",
  "org.eclipse.elk.layered.considerModelOrder.strategy": "NODES_AND_EDGES",
  "org.eclipse.elk.layered.crossingMinimization.forceNodeModelOrder": "true",
  "org.eclipse.elk.portConstraints": "FIXED_SIDE",
  "org.eclipse.elk.layered.mergeEdges": "false",
};

const TYPE_HIERARCHY_RELATIONS = new Set(["EXTENDS", "IMPLEMENTS"]);
const TOP_PORT = "target-top";
const BOTTOM_PORT = "source-bottom";
const LEFT_PORT = "target-left";
const RIGHT_PORT = "source-right";
const FALLBACK_HEIGHT = 260;
const MIN_NODE_WIDTH = classDiagramNodeCardWidth();
const X_GAP = 460;
const Y_GAP = 260;
const STACK_GAP = 96;
const PARENT_ANCHOR_GAP = 120;
const ORIGIN: GraphPosition = { x: 120, y: 96 };

function relationKind(edge: LinkGraphEdge): string {
  return edge.metadata?.["jvm.relation.kind"] ?? edge.type;
}

function relationRank(edge: LinkGraphEdge): number {
  switch (relationKind(edge)) {
    case "EXTENDS":
      return 0;
    case "IMPLEMENTS":
      return 1;
    case "INJECTS":
      return 2;
    case "USES_TYPE":
      return 3;
    default:
      return 9;
  }
}

function nodeSortKey(node: LinkGraphNode): string {
  return `${node.title}|${node.signature ?? ""}|${node.id}`;
}

function edgeSortKey(edge: LinkGraphEdge): string {
  return `${relationRank(edge)}|${edge.label ?? ""}|${edge.source}|${edge.target}|${edge.id}`;
}

function measuredNodeSize(
  node: LinkGraphNode,
  sizeSnapshot: ReadonlyMap<string, NodeMeasuredSize>,
) {
  const measured = sizeSnapshot.get(node.id);
  const estimatedHeight = estimateUmlNodeHeight(node);
  return {
    width: Math.max(measured?.width ?? MIN_NODE_WIDTH, MIN_NODE_WIDTH),
    height: Math.max(measured?.height ?? estimatedHeight, estimatedHeight),
  };
}

function metadataLines(value?: string | null): number {
  return (value ?? "")
    .split("\n")
    .map((line) => line.trim())
    .filter(Boolean)
    .length;
}

function numericMetadata(value?: string | null): number {
  const parsed = Number(value ?? "0");
  return Number.isFinite(parsed) ? Math.max(0, parsed) : 0;
}

function clamp(value: number, min: number, max: number): number {
  return Math.min(max, Math.max(min, value));
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

function estimateUmlNodeHeight(node: LinkGraphNode): number {
  const fieldLines = metadataLines(node.metadata?.["uml.field.items"]) +
    (numericMetadata(node.metadata?.["uml.field.hiddenCount"]) > 0 ? 1 : 0);
  const methodLines = metadataLines(node.metadata?.["uml.method.items"]) +
    (numericMetadata(node.metadata?.["uml.method.hiddenCount"]) > 0 ? 1 : 0);
  const commentLines = estimateCommentLines(node);
  const commentHeight = commentLines > 0 ? 18 + commentLines * 17 : 0;
  const estimated = 76 + commentHeight + estimateCompartmentHeight(fieldLines) + estimateCompartmentHeight(methodLines);
  return clamp(estimated, FALLBACK_HEIGHT, 380);
}

function resolveAnchorNodeId(
  nodes: LinkGraphNode[],
  anchorNodeId?: string | null,
): string | null {
  if (anchorNodeId && nodes.some((node) => node.id === anchorNodeId)) {
    return anchorNodeId;
  }
  return nodes.find((node) => node.type === "CLASS")?.id ?? nodes[0]?.id ?? null;
}

function buildIncoming(edges: LinkGraphEdge[]) {
  const incoming = new Map<string, LinkGraphEdge[]>();
  edges.forEach((edge) => {
    incoming.set(edge.target, [...(incoming.get(edge.target) ?? []), edge]);
  });
  return incoming;
}

function buildOutgoing(edges: LinkGraphEdge[]) {
  const outgoing = new Map<string, LinkGraphEdge[]>();
  edges.forEach((edge) => {
    outgoing.set(edge.source, [...(outgoing.get(edge.source) ?? []), edge]);
  });
  return outgoing;
}

function shortestDistances(anchorId: string, adjacency: Map<string, LinkGraphEdge[]>, direction: "source" | "target") {
  const distances = new Map<string, number>([[anchorId, 0]]);
  const queue = [anchorId];
  while (queue.length > 0) {
    const current = queue.shift();
    if (!current) {
      continue;
    }
    const baseDistance = distances.get(current) ?? 0;
    (adjacency.get(current) ?? [])
      .slice()
      .sort((left, right) => edgeSortKey(left).localeCompare(edgeSortKey(right)))
      .forEach((edge) => {
        const nextId = direction === "source" ? edge.source : edge.target;
        if (distances.has(nextId)) {
          return;
        }
        distances.set(nextId, baseDistance + 1);
        queue.push(nextId);
      });
  }
  return distances;
}

type ClassDiagramLane = "PARENT" | "INCOMING" | "ANCHOR" | "OUTGOING" | "RELATED";

const LANE_RANK: Record<ClassDiagramLane, number> = {
  PARENT: 0,
  INCOMING: 1,
  ANCHOR: 2,
  OUTGOING: 3,
  RELATED: 4,
};

function resolveLane(
  node: LinkGraphNode,
  anchorId: string,
  incomingDistances: Map<string, number>,
  outgoingDistances: Map<string, number>,
  incoming: Map<string, LinkGraphEdge[]>,
  outgoing: Map<string, LinkGraphEdge[]>,
): { lane: ClassDiagramLane; depth: number } {
  if (node.id === anchorId) {
    return { lane: "ANCHOR", depth: 0 };
  }
  const anchorOutgoingEdges = outgoing.get(anchorId) ?? [];
  const anchorIncomingEdges = incoming.get(anchorId) ?? [];
  const hasAnchorHierarchyEdge = anchorOutgoingEdges.some((edge) => edge.target === node.id && TYPE_HIERARCHY_RELATIONS.has(relationKind(edge)))
    || anchorIncomingEdges.some((edge) => edge.source === node.id && TYPE_HIERARCHY_RELATIONS.has(relationKind(edge)));
  if (hasAnchorHierarchyEdge) {
    return { lane: "PARENT", depth: outgoingDistances.get(node.id) ?? incomingDistances.get(node.id) ?? 1 };
  }
  if (incomingDistances.has(node.id) && !outgoingDistances.has(node.id)) {
    return { lane: "INCOMING", depth: incomingDistances.get(node.id) ?? 1 };
  }
  if (outgoingDistances.has(node.id)) {
    return { lane: "OUTGOING", depth: outgoingDistances.get(node.id) ?? 1 };
  }
  if (incomingDistances.has(node.id)) {
    return { lane: "INCOMING", depth: incomingDistances.get(node.id) ?? 1 };
  }
  return { lane: "RELATED", depth: 1 };
}

function classLayoutLabel(lane: ClassDiagramLane, depth: number): string {
  switch (lane) {
    case "PARENT":
      return "继承/实现";
    case "INCOMING":
      return `入向关系 ${Math.max(depth, 1)} 层`;
    case "ANCHOR":
      return "当前类型";
    case "OUTGOING":
      return `出向关系 ${Math.max(depth, 1)} 层`;
    case "RELATED":
      return "相关类型";
  }
}

function laneOverlapSummary(
  nodes: LinkGraphNode[],
  sizeSnapshot: ReadonlyMap<string, NodeMeasuredSize>,
) {
  let overlapCount = 0;
  let minVerticalGap = Number.POSITIVE_INFINITY;
  const lanes = new Map<string, LinkGraphNode[]>();
  nodes.forEach((node) => {
    const lane = node.metadata?.["layout.direction"] ?? "UNKNOWN";
    lanes.set(lane, [...(lanes.get(lane) ?? []), node]);
  });
  lanes.forEach((laneNodes) => {
    const ordered = laneNodes
      .filter((node) => node.position)
      .sort((left, right) => (left.position?.y ?? 0) - (right.position?.y ?? 0));
    for (let index = 1; index < ordered.length; index += 1) {
      const previous = ordered[index - 1];
      const current = ordered[index];
      if (!previous?.position || !current?.position) {
        continue;
      }
      const previousBottom = previous.position.y + measuredNodeSize(previous, sizeSnapshot).height;
      const gap = current.position.y - previousBottom;
      minVerticalGap = Math.min(minVerticalGap, gap);
      if (gap < 0) {
        overlapCount += 1;
      }
    }
  });
  return {
    overlapCount,
    minVerticalGap: Number.isFinite(minVerticalGap) ? Math.round(minVerticalGap) : null,
  };
}

function manualClassDiagramLayout({
  nodes,
  edges,
  anchorNodeId,
  sizeSnapshot,
}: MeasuredLayoutRequest) {
  const anchorId = resolveAnchorNodeId(nodes, anchorNodeId);
  if (!anchorId) {
    return { nodes, edges };
  }
  const incoming = buildIncoming(edges);
  const outgoing = buildOutgoing(edges);
  const incomingDistances = shortestDistances(anchorId, incoming, "source");
  const outgoingDistances = shortestDistances(anchorId, outgoing, "target");
  const nodesWithLane = nodes
    .map((node) => ({
      node,
      ...resolveLane(node, anchorId, incomingDistances, outgoingDistances, incoming, outgoing),
    }))
    .sort((left, right) =>
      LANE_RANK[left.lane] - LANE_RANK[right.lane]
      || left.depth - right.depth
      || nodeSortKey(left.node).localeCompare(nodeSortKey(right.node)),
    );
  const laneBuckets = new Map<ClassDiagramLane, typeof nodesWithLane>();
  nodesWithLane.forEach((entry) => {
    laneBuckets.set(entry.lane, [...(laneBuckets.get(entry.lane) ?? []), entry]);
  });
  const laneX: Record<ClassDiagramLane, number> = {
    INCOMING: ORIGIN.x,
    ANCHOR: ORIGIN.x + X_GAP,
    PARENT: ORIGIN.x + X_GAP,
    OUTGOING: ORIGIN.x + X_GAP * 2,
    RELATED: ORIGIN.x + X_GAP * 3,
  };
  const laneStartY = (lane: ClassDiagramLane, count: number) => {
    return ORIGIN.y + Math.max(0, 2 - Math.ceil(count / 2)) * (Y_GAP / 2);
  };
  const stackHeight = (bucket: typeof nodesWithLane): number => {
    if (bucket.length === 0) {
      return 0;
    }
    return bucket.reduce((height, entry, index) => {
      const size = measuredNodeSize(entry.node, sizeSnapshot);
      return height + size.height + (index === bucket.length - 1 ? 0 : STACK_GAP);
    }, 0);
  };
  const centeredStartY = (centerY: number, bucket: typeof nodesWithLane): number =>
    Math.max(ORIGIN.y, Math.round(centerY - stackHeight(bucket) / 2));
  const parentBucket = laneBuckets.get("PARENT") ?? [];
  const anchorBucket = laneBuckets.get("ANCHOR") ?? [];
  const parentStackHeight = stackHeight(parentBucket);
  const anchorStartY = parentStackHeight > 0
    ? ORIGIN.y + parentStackHeight + PARENT_ANCHOR_GAP
    : ORIGIN.y + Y_GAP;
  const anchorCenterY = anchorStartY +
    ((anchorBucket[0] ? measuredNodeSize(anchorBucket[0].node, sizeSnapshot).height : FALLBACK_HEIGHT) / 2);
  const resolvedLaneStartY: Record<ClassDiagramLane, number> = {
    PARENT: ORIGIN.y,
    ANCHOR: anchorStartY,
    INCOMING: centeredStartY(anchorCenterY, laneBuckets.get("INCOMING") ?? []),
    OUTGOING: centeredStartY(anchorCenterY, laneBuckets.get("OUTGOING") ?? []),
    RELATED: laneStartY("RELATED", laneBuckets.get("RELATED")?.length ?? 0),
  };
  const laidOutNodes: LinkGraphNode[] = [];
  for (const lane of ["PARENT", "INCOMING", "ANCHOR", "OUTGOING", "RELATED"] as ClassDiagramLane[]) {
    const bucket = laneBuckets.get(lane) ?? [];
    let cursorY = resolvedLaneStartY[lane];
    bucket.forEach((entry) => {
      const size = measuredNodeSize(entry.node, sizeSnapshot);
      const y = Math.round(cursorY);
      laidOutNodes.push({
        ...entry.node,
        position: {
          x: laneX[lane],
          y,
        },
        metadata: {
          ...(entry.node.metadata ?? {}),
          "layout.mode": "CLASS_DIAGRAM",
          "layout.direction": entry.lane,
          "layout.levelLabel": classLayoutLabel(entry.lane, entry.depth),
          "layout.estimatedHeight": String(size.height),
          "ui.x": String(laneX[lane]),
          "ui.y": String(y),
        },
      });
      cursorY += size.height + STACK_GAP;
    });
  }
  const nodeIndex = new Map(laidOutNodes.map((node) => [node.id, node]));
  const edgeIndexByTarget = new Map<string, LinkGraphEdge[]>();
  const edgeIndexBySource = new Map<string, LinkGraphEdge[]>();
  [...edges].sort((left, right) => edgeSortKey(left).localeCompare(edgeSortKey(right))).forEach((edge) => {
    edgeIndexByTarget.set(edge.target, [...(edgeIndexByTarget.get(edge.target) ?? []), edge]);
    edgeIndexBySource.set(edge.source, [...(edgeIndexBySource.get(edge.source) ?? []), edge]);
  });
  const laidOutEdges = [...edges]
    .sort((left, right) => edgeSortKey(left).localeCompare(edgeSortKey(right)))
    .map((edge) => routeManualClassEdge(edge, nodeIndex, sizeSnapshot, edgeIndexBySource, edgeIndexByTarget));
  const overlapSummary = laneOverlapSummary(laidOutNodes, sizeSnapshot);
  traceLinkGraph("classDiagramLayout.manual.complete", {
    nodeCount: laidOutNodes.length,
    edgeCount: laidOutEdges.length,
    anchorNodeId: anchorId,
    laneCounts: Object.fromEntries(
      (["PARENT", "INCOMING", "ANCHOR", "OUTGOING", "RELATED"] as ClassDiagramLane[])
        .map((lane) => [lane, laneBuckets.get(lane)?.length ?? 0]),
    ),
    laneStartY: resolvedLaneStartY,
    maxNodeHeight: Math.max(...laidOutNodes.map((node) => measuredNodeSize(node, sizeSnapshot).height)),
    ...overlapSummary,
  });
  return {
    nodes: laidOutNodes,
    edges: laidOutEdges,
  };
}

function nodeCenterY(
  node: LinkGraphNode,
  sizeSnapshot: ReadonlyMap<string, NodeMeasuredSize>,
) {
  return (node.position?.y ?? ORIGIN.y) + measuredNodeSize(node, sizeSnapshot).height / 2;
}

function edgeOffset(
  edge: LinkGraphEdge,
  edgeIndex: Map<string, LinkGraphEdge[]>,
  key: string,
) {
  const siblings = edgeIndex.get(key) ?? [edge];
  const index = siblings.findIndex((candidate) => candidate.id === edge.id);
  return (index - (siblings.length - 1) / 2) * 20;
}

function portForSource(edge: LinkGraphEdge) {
  return TYPE_HIERARCHY_RELATIONS.has(relationKind(edge)) ? BOTTOM_PORT : RIGHT_PORT;
}

function portForTarget(edge: LinkGraphEdge) {
  return TYPE_HIERARCHY_RELATIONS.has(relationKind(edge))
    ? TOP_PORT
    : LEFT_PORT;
}

function routeManualClassEdge(
  edge: LinkGraphEdge,
  nodeIndex: Map<string, LinkGraphNode>,
  sizeSnapshot: ReadonlyMap<string, NodeMeasuredSize>,
  edgeIndexBySource: Map<string, LinkGraphEdge[]>,
  edgeIndexByTarget: Map<string, LinkGraphEdge[]>,
): LinkGraphEdge {
  const source = nodeIndex.get(edge.source);
  const target = nodeIndex.get(edge.target);
  if (!source?.position || !target?.position) {
    return edge;
  }
  const sourceSize = measuredNodeSize(source, sizeSnapshot);
  const targetSize = measuredNodeSize(target, sizeSnapshot);
  const sourceOffset = edgeOffset(edge, edgeIndexBySource, edge.source);
  const targetOffset = edgeOffset(edge, edgeIndexByTarget, edge.target);
  const sourcePort = portForSource(edge);
  const targetPort = portForTarget(edge);
  const sourcePoint = sourcePort === BOTTOM_PORT
    ? {
        x: source.position.x + sourceSize.width / 2 + sourceOffset,
        y: source.position.y + sourceSize.height,
      }
    : {
        x: source.position.x + sourceSize.width,
        y: nodeCenterY(source, sizeSnapshot) + sourceOffset,
      };
  const targetPoint = targetPort === TOP_PORT
    ? {
        x: target.position.x + targetSize.width / 2 + targetOffset,
        y: target.position.y,
      }
    : targetPort === RIGHT_PORT
      ? {
          x: target.position.x + targetSize.width,
          y: nodeCenterY(target, sizeSnapshot) + targetOffset,
        }
      : {
          x: target.position.x,
          y: nodeCenterY(target, sizeSnapshot) + targetOffset,
        };
  const isHierarchy = TYPE_HIERARCHY_RELATIONS.has(relationKind(edge));
  const midX = Math.round((sourcePoint.x + targetPoint.x) / 2);
  const midY = Math.round((sourcePoint.y + targetPoint.y) / 2);
  const route = isHierarchy
    ? {
        sections: [
          {
            startPoint: sourcePoint,
            bendPoints: [
              { x: sourcePoint.x, y: midY },
              { x: targetPoint.x, y: midY },
            ],
            endPoint: targetPoint,
          },
        ],
      }
    : {
        sections: [
          {
            startPoint: sourcePoint,
            bendPoints: [
              { x: midX, y: sourcePoint.y },
              { x: midX, y: targetPoint.y },
            ],
            endPoint: targetPoint,
          },
        ],
      };
  return {
    ...edge,
    sourceHandle: sourcePort,
    targetHandle: targetPort,
    route,
    metadata: {
      ...(edge.metadata ?? {}),
      "layout.sourcePort": sourcePort,
      "layout.targetPort": targetPort,
      "layout.route": "class-diagram-lane",
    },
  };
}

export async function layoutClassDiagramView({
  nodes,
  edges,
  anchorNodeId,
  sizeSnapshot,
}: MeasuredLayoutRequest) {
  if (nodes.length <= 32) {
    return manualClassDiagramLayout({ graph: { nodes, edges }, nodes, edges, anchorNodeId, sizeSnapshot, reason: "graph" });
  }
  return executeElkLayout({
    mode: "CLASS_DIAGRAM",
    layoutOptions: CLASS_DIAGRAM_LAYOUT_OPTIONS,
    nodes: nodes.map((node) => ({
      node,
      ...measuredNodeSize(node, sizeSnapshot),
      ports: [
        { id: TOP_PORT, side: "NORTH" },
        { id: RIGHT_PORT, side: "EAST" },
        { id: BOTTOM_PORT, side: "SOUTH" },
        { id: LEFT_PORT, side: "WEST" },
      ],
    })),
    edges: edges.map((edge) => ({
      edge: {
        ...edge,
        sourceHandle: portForSource(edge),
        targetHandle: portForTarget(edge),
        metadata: {
          ...(edge.metadata ?? {}),
          "layout.sourcePort": portForSource(edge),
          "layout.targetPort": portForTarget(edge),
          "layout.route": "class-diagram-elk",
        },
      },
      sourcePort: portForSource(edge),
      targetPort: portForTarget(edge),
    })),
  });
}
