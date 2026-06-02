import type { LayoutOptions } from "elkjs/lib/elk-api";
import { measureDuration, measureStart, traceLinkGraph } from "../../debug";
import type { NodeMeasuredSize } from "../../graph/nodeSizeRegistry";
import {
  executeElkLayout,
  resolveMeasuredNodeSize,
  type ElkEdgeDefinition,
  type ElkNodeDefinition,
} from "../../reactflow/elkGraph";
import type { LayoutSizeSignatureResolver, MeasuredLayoutRequest } from "../../reactflow/useMeasuredLayout";

export const FACT_GRAPH_LAYOUT_OPTIONS: LayoutOptions = {
  "elk.algorithm": "layered",
  "org.eclipse.elk.direction": "RIGHT",
  "org.eclipse.elk.partitioning.activate": "true",
  "org.eclipse.elk.edgeRouting": "ORTHOGONAL",
  "org.eclipse.elk.layered.nodePlacement.strategy": "BRANDES_KOEPF",
  "org.eclipse.elk.layered.spacing.nodeNodeBetweenLayers": "176",
  "org.eclipse.elk.layered.spacing.edgeNodeBetweenLayers": "92",
  "org.eclipse.elk.spacing.nodeNode": "88",
};

type FactDirection = "UPSTREAM" | "CURRENT" | "DOWNSTREAM";

const CURRENT_FACT_NODE_TYPES = new Set(["FLOW_SCOPE", "FLOW_ACTION", "MERGE", "TERMINAL"]);
const EMPTY_SIZE_SNAPSHOT = new Map<string, NodeMeasuredSize>();
const PARTITION_INDEX: Record<FactDirection, number> = {
  UPSTREAM: 0,
  CURRENT: 1,
  DOWNSTREAM: 2,
};

interface FactElkOptionExperimentRequest {
  nodes: ElkNodeDefinition[];
  edges: ElkEdgeDefinition[];
  baseOptions: LayoutOptions;
}

function emptyDirectionCounts(): Record<FactDirection, number> {
  return {
    UPSTREAM: 0,
    CURRENT: 0,
    DOWNSTREAM: 0,
  };
}

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

function factPresentationLaneId(direction: FactDirection): string {
  if (direction === "UPSTREAM") {
    return "upstream";
  }
  if (direction === "DOWNSTREAM") {
    return "downstream";
  }
  return "current";
}

function factPresentationRole(direction: FactDirection): string {
  return direction === "CURRENT" ? "ANCHOR" : direction;
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

function resolveConservativeFactLayoutSize(
  node: MeasuredLayoutRequest["nodes"][number],
  sizeSnapshot: ReadonlyMap<string, NodeMeasuredSize>,
): NodeMeasuredSize {
  const fallback = resolveMeasuredNodeSize(node, EMPTY_SIZE_SNAPSHOT, "FACT_GRAPH");
  const measured = sizeSnapshot.get(node.id);
  return {
    width: Math.max(measured?.width ?? fallback.width, fallback.width),
    height: Math.max(measured?.height ?? fallback.height, fallback.height),
  };
}

export const factGraphLayoutSizeSignature: LayoutSizeSignatureResolver = (nodes, sizeSnapshot) =>
  nodes
    .map((node) => {
      const size = resolveConservativeFactLayoutSize(node, sizeSnapshot);
      return `${node.id}:${size.width}x${size.height}`;
    })
    .join("::");

function omitLayoutOptions(
  options: LayoutOptions,
  keys: string[],
): LayoutOptions {
  const nextOptions = { ...options };
  keys.forEach((key) => {
    delete nextOptions[key];
  });
  return nextOptions;
}

function factElkExperimentVariants(baseOptions: LayoutOptions): Array<{ variant: string; layoutOptions: LayoutOptions }> {
  return [
    {
      variant: "polyline-routing",
      layoutOptions: {
        ...baseOptions,
        "org.eclipse.elk.edgeRouting": "POLYLINE",
      },
    },
    {
      variant: "no-forced-model-order",
      layoutOptions: omitLayoutOptions(baseOptions, [
        "org.eclipse.elk.layered.crossingMinimization.forceNodeModelOrder",
      ]),
    },
    {
      variant: "no-model-order",
      layoutOptions: omitLayoutOptions(baseOptions, [
        "org.eclipse.elk.layered.considerModelOrder.strategy",
        "org.eclipse.elk.layered.crossingMinimization.forceNodeModelOrder",
      ]),
    },
    {
      variant: "no-partitioning",
      layoutOptions: {
        ...baseOptions,
        "org.eclipse.elk.partitioning.activate": "false",
      },
    },
    {
      variant: "simple-node-placement",
      layoutOptions: {
        ...baseOptions,
        "org.eclipse.elk.layered.nodePlacement.strategy": "SIMPLE",
      },
    },
  ];
}

export async function runFactElkOptionExperiments({
  nodes,
  edges,
  baseOptions,
}: FactElkOptionExperimentRequest): Promise<void> {
  for (const experiment of factElkExperimentVariants(baseOptions)) {
    const startedAt = measureStart();
    try {
      const result = await executeElkLayout({
        mode: "FACT_GRAPH",
        layoutOptions: experiment.layoutOptions,
        nodes,
        edges,
      });
      traceLinkGraph("factGraphLayout.elkOptionExperiment", {
        variant: experiment.variant,
        nodeCount: nodes.length,
        edgeCount: edges.length,
        routedEdgeCount: result.edges.filter((edge) => edge.route).length,
        durationMs: measureDuration(startedAt),
      });
    } catch (error) {
      traceLinkGraph("factGraphLayout.elkOptionExperiment.failed", {
        variant: experiment.variant,
        nodeCount: nodes.length,
        edgeCount: edges.length,
        errorMessage: error instanceof Error ? error.message : String(error),
        durationMs: measureDuration(startedAt),
      });
    }
  }
}

export async function layoutFactGraphView({
  nodes,
  edges,
  anchorNodeId,
  sizeSnapshot,
}: MeasuredLayoutRequest) {
  const startedAt = measureStart();
  const anchorId = resolveAnchorNodeId(nodes, anchorNodeId);
  if (!anchorId) {
    return { nodes, edges };
  }
  const adjacencyStartedAt = measureStart();
  const outgoing = buildOutgoing(edges);
  const incoming = buildIncoming(edges);
  const adjacencyDurationMs = measureDuration(adjacencyStartedAt);
  const traversalStartedAt = measureStart();
  const upstreamDistances = bfs(anchorId, incoming);
  const downstreamDistances = bfs(anchorId, outgoing);
  const traversalDurationMs = measureDuration(traversalStartedAt);

  const definitionStartedAt = measureStart();
  const directionCounts = emptyDirectionCounts();
  const layoutNodes = nodes.map((node) => {
    const direction = resolveFactDirection(node, anchorId, upstreamDistances, downstreamDistances);
    directionCounts[direction.direction] += 1;
    return {
      node,
      ...resolveMeasuredNodeSize(node, sizeSnapshot, "FACT_GRAPH"),
      metadata: {
        "layout.direction": direction.direction,
        "layout.levelLabel": factDirectionLabel(direction.direction, direction.depth),
        "presentation.laneId": factPresentationLaneId(direction.direction),
        "presentation.role": factPresentationRole(direction.direction),
        "presentation.priority": String(PARTITION_INDEX[direction.direction] * 10 + 10),
        "presentation.compact": String(direction.direction !== "CURRENT"),
      },
      layoutOptions: {
        "org.eclipse.elk.partitioning.partition": String(PARTITION_INDEX[direction.direction]),
      },
    };
  });
  const layoutEdges = edges.map((edge) => ({ edge }));
  const definitionDurationMs = measureDuration(definitionStartedAt);
  traceLinkGraph("factGraphLayout.prepared", {
    nodeCount: nodes.length,
    edgeCount: edges.length,
    anchorNodeId: anchorId,
    upstreamReachableCount: upstreamDistances.size,
    downstreamReachableCount: downstreamDistances.size,
    directionCounts,
    measuredSizeCount: sizeSnapshot.size,
    adjacencyDurationMs,
    traversalDurationMs,
    definitionDurationMs,
    totalPreElkDurationMs: measureDuration(startedAt),
  });
  return executeElkLayout({
    mode: "FACT_GRAPH",
    layoutOptions: FACT_GRAPH_LAYOUT_OPTIONS,
    nodes: layoutNodes,
    edges: layoutEdges,
  });
}
