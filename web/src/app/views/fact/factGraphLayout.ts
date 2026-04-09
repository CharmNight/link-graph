import type { LayoutOptions } from "elkjs/lib/elk-api";
import { executeElkLayout, resolveMeasuredNodeSize } from "../../reactflow/elkGraph";
import type { MeasuredLayoutRequest } from "../../reactflow/useMeasuredLayout";

const FACT_GRAPH_LAYOUT_OPTIONS: LayoutOptions = {
  "elk.algorithm": "layered",
  "org.eclipse.elk.direction": "RIGHT",
  "org.eclipse.elk.partitioning.activate": "true",
  "org.eclipse.elk.edgeRouting": "ORTHOGONAL",
  "org.eclipse.elk.layered.nodePlacement.strategy": "BRANDES_KOEPF",
  "org.eclipse.elk.layered.spacing.nodeNodeBetweenLayers": "176",
  "org.eclipse.elk.layered.spacing.edgeNodeBetweenLayers": "92",
  "org.eclipse.elk.spacing.nodeNode": "88",
  "org.eclipse.elk.layered.considerModelOrder.strategy": "NODES_AND_EDGES",
  "org.eclipse.elk.layered.crossingMinimization.forceNodeModelOrder": "true",
};

type FactDirection = "UPSTREAM" | "CURRENT" | "DOWNSTREAM";

const CURRENT_FACT_NODE_TYPES = new Set(["FLOW_SCOPE", "FLOW_ACTION", "MERGE", "TERMINAL"]);
const PARTITION_INDEX: Record<FactDirection, number> = {
  UPSTREAM: 0,
  CURRENT: 1,
  DOWNSTREAM: 2,
};

function buildOutgoing(edges: MeasuredLayoutRequest["edges"]) {
  const outgoing = new Map<string, string[]>();
  edges.forEach((edge) => {
    const nextTargets = outgoing.get(edge.source) ?? [];
    nextTargets.push(edge.target);
    outgoing.set(edge.source, nextTargets);
  });
  return outgoing;
}

function buildIncoming(edges: MeasuredLayoutRequest["edges"]) {
  const incoming = new Map<string, string[]>();
  edges.forEach((edge) => {
    const nextSources = incoming.get(edge.target) ?? [];
    nextSources.push(edge.source);
    incoming.set(edge.target, nextSources);
  });
  return incoming;
}

function bfs(startId: string, adjacency: Map<string, string[]>): Map<string, number> {
  const distances = new Map<string, number>([[startId, 0]]);
  const queue = [startId];
  while (queue.length > 0) {
    const current = queue.shift();
    if (!current) {
      continue;
    }
    const baseDistance = distances.get(current) ?? 0;
    (adjacency.get(current) ?? []).forEach((nextId) => {
      if (distances.has(nextId)) {
        return;
      }
      distances.set(nextId, baseDistance + 1);
      queue.push(nextId);
    });
  }
  return distances;
}

function resolveAnchorNodeId(
  nodes: MeasuredLayoutRequest["nodes"],
  anchorNodeId?: string | null,
): string | null {
  if (anchorNodeId && nodes.some((node) => node.id === anchorNodeId)) {
    return anchorNodeId;
  }
  return nodes.find((node) => node.type === "METHOD")?.id ?? nodes[0]?.id ?? null;
}

function factDirectionLabel(direction: FactDirection, depth: number): string {
  if (direction === "CURRENT") {
    return depth <= 0 ? "当前锚点" : "当前方法流程";
  }
  return `${direction === "UPSTREAM" ? "上游" : "下游"} ${Math.max(depth, 1)} 层`;
}

function resolveFactDirection(
  node: MeasuredLayoutRequest["nodes"][number],
  anchorId: string,
  upstreamDistances: Map<string, number>,
  downstreamDistances: Map<string, number>,
): { direction: FactDirection; depth: number } {
  if (node.id === anchorId) {
    return { direction: "CURRENT", depth: 0 };
  }
  if (CURRENT_FACT_NODE_TYPES.has(node.type) && downstreamDistances.has(node.id)) {
    return { direction: "CURRENT", depth: downstreamDistances.get(node.id) ?? 1 };
  }
  if (upstreamDistances.has(node.id) && !downstreamDistances.has(node.id)) {
    return { direction: "UPSTREAM", depth: upstreamDistances.get(node.id) ?? 1 };
  }
  if (downstreamDistances.has(node.id)) {
    return { direction: "DOWNSTREAM", depth: downstreamDistances.get(node.id) ?? 1 };
  }
  if (upstreamDistances.has(node.id)) {
    return { direction: "UPSTREAM", depth: upstreamDistances.get(node.id) ?? 1 };
  }
  return { direction: "CURRENT", depth: 1 };
}

export async function layoutFactGraphView({
  nodes,
  edges,
  anchorNodeId,
  sizeSnapshot,
}: MeasuredLayoutRequest) {
  const anchorId = resolveAnchorNodeId(nodes, anchorNodeId);
  if (!anchorId) {
    return nodes;
  }
  const outgoing = buildOutgoing(edges);
  const incoming = buildIncoming(edges);
  const upstreamDistances = bfs(anchorId, incoming);
  const downstreamDistances = bfs(anchorId, outgoing);

  return executeElkLayout({
    mode: "FACT_GRAPH",
    layoutOptions: FACT_GRAPH_LAYOUT_OPTIONS,
    nodes: nodes.map((node) => {
      const direction = resolveFactDirection(node, anchorId, upstreamDistances, downstreamDistances);
      return {
        node,
        ...resolveMeasuredNodeSize(node, sizeSnapshot, "FACT_GRAPH"),
        metadata: {
          "layout.direction": direction.direction,
          "layout.levelLabel": factDirectionLabel(direction.direction, direction.depth),
        },
        layoutOptions: {
          "org.eclipse.elk.partitioning.partition": String(PARTITION_INDEX[direction.direction]),
        },
      };
    }),
    edges: edges.map((edge) => ({ edge })),
  });
}
