import {
  measureDuration,
  measureStart,
  summarizeGraph,
  traceLinkGraph,
} from "./debug";
import { FifoQueue } from "./fifoQueue";
import type {
  AnalysisDisplayMode,
  GraphPosition,
  LinkGraphDocument,
  LinkGraphEdge,
  LinkGraphLayoutState,
  LinkGraphNode,
} from "./types";

export function fallbackDesignPosition(index: number): GraphPosition {
  return {
    x: 120 + (index % 3) * 420,
    y: 120 + Math.floor(index / 3) * 220,
  };
}

export function withStoredNodePosition(node: LinkGraphNode): LinkGraphNode {
  if (node.position) {
    return node;
  }
  const x = Number(node.metadata?.["ui.x"]);
  const y = Number(node.metadata?.["ui.y"]);
  if (Number.isFinite(x) && Number.isFinite(y)) {
    return {
      ...node,
      position: { x, y },
    };
  }
  return {
    ...node,
    position: undefined,
  };
}

export function syncNodePosition(node: LinkGraphNode, position: GraphPosition): LinkGraphNode {
  return {
    ...node,
    position,
    metadata: {
      ...(node.metadata ?? {}),
      "ui.x": String(position.x),
      "ui.y": String(position.y),
    },
  };
}

export function resolveNodePosition(node: LinkGraphNode | undefined): GraphPosition | null {
  if (node?.position) {
    return node.position;
  }
  const x = Number(node?.metadata?.["ui.x"]);
  const y = Number(node?.metadata?.["ui.y"]);
  if (Number.isFinite(x) && Number.isFinite(y)) {
    return { x, y };
  }
  return null;
}

export function clearStoredNodePosition(node: LinkGraphNode): LinkGraphNode {
  const nextMetadata = { ...(node.metadata ?? {}) };
  delete nextMetadata["ui.x"];
  delete nextMetadata["ui.y"];
  return {
    ...node,
    position: undefined,
    metadata: Object.keys(nextMetadata).length > 0 ? nextMetadata : undefined,
  };
}

export function normalizeGraphNodes(
  nextNodes: LinkGraphNode[],
  nextEdges: LinkGraphEdge[],
  anchorNodeId?: string | null,
  analysisDisplayMode: AnalysisDisplayMode = "FACT_GRAPH",
): LinkGraphNode[] {
  const startedAt = measureStart();
  const positionedNodes = nextNodes.map(withStoredNodePosition);
  traceLinkGraph("app.normalizeGraphNodes.passThrough", {
    analysisDisplayMode,
    anchorNodeId: anchorNodeId ?? null,
    inputGraph: summarizeGraph({ nodes: nextNodes, edges: nextEdges }),
    outputGraph: summarizeGraph({ nodes: positionedNodes, edges: nextEdges }),
    durationMs: measureDuration(startedAt),
  });
  return positionedNodes;
}

export function applyBootstrapNodePositions(
  nextNodes: LinkGraphNode[],
  currentNodes: LinkGraphNode[],
  layoutState?: LinkGraphLayoutState | null,
  reuseCurrentPositions = true,
): LinkGraphNode[] {
  const currentNodeById = new Map(currentNodes.map((node) => [node.id, node]));
  return nextNodes.map((node) => {
    const layoutPosition = layoutState?.positions[node.id];
    if (layoutPosition) {
      return syncNodePosition(node, layoutPosition);
    }
    const existingPosition = resolveNodePosition(node);
    if (existingPosition) {
      return syncNodePosition(node, existingPosition);
    }
    if (!reuseCurrentPositions) {
      return node;
    }
    const currentPosition = resolveNodePosition(currentNodeById.get(node.id) ?? node);
    return currentPosition ? syncNodePosition(node, currentPosition) : node;
  });
}

function normalizeSemanticMetadata(
  metadata?: Record<string, string>,
): Record<string, string> | undefined {
  if (!metadata) {
    return undefined;
  }
  const nextMetadata = Object.fromEntries(
    Object.entries(metadata).filter(([key]) => !key.startsWith("ui.") && !key.startsWith("layout.")),
  );
  return Object.keys(nextMetadata).length > 0 ? nextMetadata : undefined;
}

function nodeSemanticSignature(node: LinkGraphNode): string {
  return [
    node.id,
    node.type,
    node.title,
    node.location ?? "",
    node.signature ?? "",
    node.inputs.join(","),
    node.outputs.join(","),
    node.doc ?? "",
    node.certainty,
    node.bindingStatus,
    node.diffStatus ?? "",
    node.sourceTag ?? "",
    JSON.stringify(normalizeSemanticMetadata(node.metadata) ?? {}),
  ].join("|");
}

function edgeSemanticSignature(edge: LinkGraphEdge): string {
  return [
    edge.id,
    edge.type,
    edge.source,
    edge.target,
    edge.label ?? "",
    JSON.stringify(edge.metadata ?? {}),
    edge.sourceTag ?? "",
  ].join("|");
}

export function graphSemanticSignature(document: LinkGraphDocument): string {
  return [
    document.nodes.map(nodeSemanticSignature).sort().join("::"),
    document.edges.map(edgeSemanticSignature).sort().join("::"),
  ].join("##");
}

export function graphLayoutSignature(nodes: LinkGraphNode[]): string {
  return nodes
    .map((node) => {
      const position = resolveNodePosition(node);
      return [node.id, position?.x ?? "", position?.y ?? ""].join("|");
    })
    .sort()
    .join("::");
}

export function applyLayoutOnlyNodePositions(
  currentNodes: LinkGraphNode[],
  nextNodes: LinkGraphNode[],
): LinkGraphNode[] {
  const nextNodeById = new Map(nextNodes.map((node) => [node.id, node]));
  return currentNodes.map((currentNode) => {
    const nextNode = nextNodeById.get(currentNode.id);
    if (!nextNode) {
      return currentNode;
    }
    const nextPosition = resolveNodePosition(nextNode);
    return nextPosition ? syncNodePosition(currentNode, nextPosition) : currentNode;
  });
}

export function hasRevision(revision?: number): revision is number {
  return Number.isFinite(revision);
}

export function extractLayoutPayload(nodes: LinkGraphNode[]): Array<{ nodeId: string; x: number; y: number }> {
  return nodes.flatMap((node) => (node.position
    ? [{
        nodeId: node.id,
        x: node.position.x,
        y: node.position.y,
      }]
    : []));
}

export function sameNodeIdList(left: string[], right: string[]): boolean {
  if (left.length !== right.length) {
    return false;
  }
  return left.every((value, index) => value === right[index]);
}

export function collectDownstreamSubtreeNodeIds(
  rootNodeId: string,
  nodes: LinkGraphNode[],
  edges: LinkGraphEdge[],
): Set<string> {
  const existingNodeIds = new Set(nodes.map((node) => node.id));
  if (!existingNodeIds.has(rootNodeId)) {
    return new Set();
  }

  const outgoing = new Map<string, string[]>();
  edges.forEach((edge) => {
    const nextTargets = outgoing.get(edge.source) ?? [];
    nextTargets.push(edge.target);
    outgoing.set(edge.source, nextTargets);
  });

  const collected = new Set<string>();
  const queue = new FifoQueue([rootNodeId]);
  while (true) {
    const nodeId = queue.dequeue();
    if (!nodeId) {
      break;
    }
    if (collected.has(nodeId)) {
      continue;
    }
    collected.add(nodeId);
    (outgoing.get(nodeId) ?? []).forEach((targetId) => {
      if (!collected.has(targetId)) {
        queue.enqueue(targetId);
      }
    });
  }
  return collected;
}
