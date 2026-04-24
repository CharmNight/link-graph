import type {
  DraftCompareProjection,
  DraftCompareStatus,
  DraftWorkbenchEntry,
  LinkGraphDocument,
  LinkGraphEdge,
  LinkGraphNode,
  ResultEvidenceReference,
} from "./types";

interface BuildDraftCompareProjectionArgs {
  compareMode: "after" | "compare";
  selectedEntry: DraftWorkbenchEntry | null;
  visibleGraph: LinkGraphDocument;
  referenceGraph?: LinkGraphDocument | null;
  workingGraph: LinkGraphDocument;
}

interface DraftCompareElement {
  currentId: string | null;
  referenceId: string | null;
  status: DraftCompareStatus;
}

const GHOST_NODE_PREFIX = "draft-ghost-node:";
const GHOST_EDGE_PREFIX = "draft-ghost-edge:";
const FLOWCHART_ALIAS_IDS_KEY = "flowchart.projectedFromNodeIds";

interface OrderedCompareGraph {
  nodes: LinkGraphNode[];
  nodeIndexById: Map<string, number>;
  edges: LinkGraphEdge[];
  edgeIndexById: Map<string, number>;
}

export function buildDraftCompareProjection({
  compareMode,
  selectedEntry,
  visibleGraph,
  referenceGraph,
  workingGraph,
}: BuildDraftCompareProjectionArgs): DraftCompareProjection | null {
  if (compareMode !== "compare" || selectedEntry?.kind !== "CHANGE" || referenceGraph == null) {
    return null;
  }

  const scopeNodeIds = collectScopedNodeIds(selectedEntry);
  if (scopeNodeIds.size === 0) {
    return null;
  }

  const nodeComparisons = buildNodeComparisons(scopeNodeIds, referenceGraph, workingGraph);
  const edgeComparisons = buildEdgeComparisons(scopeNodeIds, referenceGraph, workingGraph);
  if (nodeComparisons.length === 0 && edgeComparisons.length === 0) {
    return null;
  }

  const visibleNodeIds = new Set(visibleGraph.nodes.map((node) => node.id));
  const visibleEdgeIds = new Set(
    visibleGraph.edges
      .filter((edge) => edge.metadata?.["flowchart.synthetic"] !== "entry-edge")
      .map((edge) => edge.id),
  );
  const referenceNodesById = new Map(referenceGraph.nodes.map((node) => [node.id, node]));
  const workingNodesById = new Map(workingGraph.nodes.map((node) => [node.id, node]));
  const compareGraph = createOrderedCompareGraph(visibleGraph);
  let hiddenNodeCount = 0;
  let hiddenEdgeCount = 0;

  const nodeStatuses: Record<string, DraftCompareStatus> = {};
  for (const comparison of nodeComparisons) {
    const projectedVisibleNodeId = resolveProjectedVisibleNodeId(
      visibleGraph.nodes,
      comparison.currentId ?? comparison.referenceId,
    );
    if (comparison.status === "REMOVED" && comparison.referenceId) {
      const ghostNode = ensureGhostNode({
        compareGraph,
        referenceNodesById,
        entryId: selectedEntry.entryId,
        referenceNodeId: comparison.referenceId,
      });
      if (ghostNode) {
        nodeStatuses[ghostNode.id] = "REMOVED";
        hiddenNodeCount += projectedVisibleNodeId == null ? 1 : 0;
      }
      continue;
    }
    if (!comparison.currentId) {
      continue;
    }
    const currentNode = workingNodesById.get(comparison.currentId);
    if (!currentNode) {
      continue;
    }
    if (projectedVisibleNodeId) {
      const visibleNode = compareGraph.nodes[compareGraph.nodeIndexById.get(projectedVisibleNodeId) ?? -1] ?? null;
      const compareNode = visibleNode ? mergeProjectedPresentationNode(visibleNode, currentNode) : currentNode;
      upsertCompareNode(compareGraph, compareNode);
      nodeStatuses[compareNode.id] = comparison.status;
      continue;
    }
    upsertCompareNode(compareGraph, currentNode);
    if (currentNode || visibleNodeIds.has(comparison.currentId)) {
      nodeStatuses[comparison.currentId] = comparison.status;
    }
    hiddenNodeCount += visibleNodeIds.has(comparison.currentId) ? 0 : 1;
  }

  const edgeStatuses: Record<string, DraftCompareStatus> = {};
  for (const comparison of edgeComparisons) {
    if (comparison.status === "REMOVED" && comparison.referenceId) {
      const referenceEdge = referenceGraph.edges.find((edge) => edge.id === comparison.referenceId);
      if (!referenceEdge) {
        continue;
      }
      const sourceId = ensureEdgeEndpointNode({
        nodeId: referenceEdge.source,
        entryId: selectedEntry.entryId,
        compareGraph,
        referenceNodesById,
        workingNodesById,
        removed: !workingNodesById.has(referenceEdge.source),
      });
      const targetId = ensureEdgeEndpointNode({
        nodeId: referenceEdge.target,
        entryId: selectedEntry.entryId,
        compareGraph,
        referenceNodesById,
        workingNodesById,
        removed: !workingNodesById.has(referenceEdge.target),
      });
      const ghostEdgeId = `${GHOST_EDGE_PREFIX}${selectedEntry.entryId}:${referenceEdge.id}`;
      upsertCompareEdge(compareGraph, {
        ...referenceEdge,
        id: ghostEdgeId,
        source: sourceId,
        target: targetId,
        metadata: {
          ...(referenceEdge.metadata ?? {}),
          "draft.compare.ghost": "true",
          "draft.compare.referenceEdgeId": referenceEdge.id,
        },
      });
      edgeStatuses[ghostEdgeId] = "REMOVED";
      hiddenEdgeCount += 1;
      continue;
    }
    if (!comparison.currentId) {
      continue;
    }
    const currentEdge = workingGraph.edges.find((edge) => edge.id === comparison.currentId);
    if (currentEdge && !compareGraph.edgeIndexById.has(currentEdge.id)) {
      upsertCompareEdge(compareGraph, currentEdge);
    }
    if (currentEdge || visibleEdgeIds.has(comparison.currentId)) {
      edgeStatuses[comparison.currentId] = comparison.status;
    }
    hiddenEdgeCount += visibleEdgeIds.has(comparison.currentId) ? 0 : 1;
  }

  return {
    entryId: selectedEntry.entryId,
    entryTitle: selectedEntry.title,
    compareGraph: {
      nodes: compareGraph.nodes,
      edges: compareGraph.edges,
    },
    nodeStatuses,
    edgeStatuses,
    summary: {
      scopeNodeCount: scopeNodeIds.size,
      visibleNodeCount: Object.keys(nodeStatuses).length,
      visibleEdgeCount: Object.keys(edgeStatuses).length,
      hiddenNodeCount,
      hiddenEdgeCount,
    },
  };
}

function collectScopedNodeIds(entry: DraftWorkbenchEntry): Set<string> {
  const scopeNodeIds = new Set(entry.targetNodeIds);
  for (const operation of entry.graphPatch?.operations ?? []) {
    if (operation.node?.id) {
      scopeNodeIds.add(operation.node.id);
    }
    if (operation.edge?.source) {
      scopeNodeIds.add(operation.edge.source);
    }
    if (operation.edge?.target) {
      scopeNodeIds.add(operation.edge.target);
    }
  }
  for (const reference of entry.evidence.flatMap((finding) => finding.references)) {
    addReferenceNodeId(scopeNodeIds, reference);
  }
  return scopeNodeIds;
}

function addReferenceNodeId(scopeNodeIds: Set<string>, reference: ResultEvidenceReference) {
  if (reference.nodeId?.trim()) {
    scopeNodeIds.add(reference.nodeId.trim());
  }
}

function createOrderedCompareGraph(visibleGraph: LinkGraphDocument): OrderedCompareGraph {
  return {
    nodes: [...visibleGraph.nodes],
    nodeIndexById: new Map(visibleGraph.nodes.map((node, index) => [node.id, index])),
    edges: [...visibleGraph.edges],
    edgeIndexById: new Map(visibleGraph.edges.map((edge, index) => [edge.id, index])),
  };
}

function upsertCompareNode(compareGraph: OrderedCompareGraph, node: LinkGraphNode) {
  const existingIndex = compareGraph.nodeIndexById.get(node.id);
  if (existingIndex == null) {
    compareGraph.nodes.push(node);
    compareGraph.nodeIndexById.set(node.id, compareGraph.nodes.length - 1);
    return;
  }
  compareGraph.nodes[existingIndex] = node;
}

function upsertCompareEdge(compareGraph: OrderedCompareGraph, edge: LinkGraphEdge) {
  const existingIndex = compareGraph.edgeIndexById.get(edge.id);
  if (existingIndex == null) {
    compareGraph.edges.push(edge);
    compareGraph.edgeIndexById.set(edge.id, compareGraph.edges.length - 1);
    return;
  }
  compareGraph.edges[existingIndex] = edge;
}

function projectedAliasNodeIds(node: LinkGraphNode): string[] {
  const rawAliasNodeIds = node.metadata?.[FLOWCHART_ALIAS_IDS_KEY];
  if (!rawAliasNodeIds) {
    return [];
  }
  return rawAliasNodeIds
    .split(",")
    .map((value) => value.trim())
    .filter((value) => value.length > 0);
}

function resolveProjectedVisibleNodeId(
  visibleNodes: LinkGraphNode[],
  nodeId: string | null,
): string | null {
  if (!nodeId) {
    return null;
  }
  const directMatch = visibleNodes.find((visibleNode) => visibleNode.id === nodeId);
  if (directMatch) {
    return directMatch.id;
  }
  return visibleNodes.find((visibleNode) => projectedAliasNodeIds(visibleNode).includes(nodeId))?.id ?? null;
}

function mergeProjectedPresentationNode(
  visibleNode: LinkGraphNode,
  nextNode: LinkGraphNode,
): LinkGraphNode {
  return {
    ...visibleNode,
    ...nextNode,
    id: visibleNode.id,
    position: visibleNode.position ?? nextNode.position,
    metadata: {
      ...(visibleNode.metadata ?? {}),
      ...(nextNode.metadata ?? {}),
    },
  };
}

function ensureGhostNode(args: {
  compareGraph: OrderedCompareGraph;
  referenceNodesById: Map<string, LinkGraphNode>;
  entryId: string;
  referenceNodeId: string;
}): LinkGraphNode | null {
  const referenceNode = args.referenceNodesById.get(args.referenceNodeId);
  if (!referenceNode) {
    return null;
  }
  const ghostNodeId = `${GHOST_NODE_PREFIX}${args.entryId}:${referenceNode.id}`;
  const existingIndex = args.compareGraph.nodeIndexById.get(ghostNodeId);
  const existing = existingIndex == null ? null : args.compareGraph.nodes[existingIndex] ?? null;
  if (existing) {
    return existing;
  }
  const ghostNode: LinkGraphNode = {
    ...referenceNode,
    id: ghostNodeId,
    metadata: {
      ...(referenceNode.metadata ?? {}),
      "draft.compare.ghost": "true",
      "draft.compare.referenceNodeId": referenceNode.id,
    },
  };
  upsertCompareNode(args.compareGraph, ghostNode);
  return ghostNode;
}

function ensureEdgeEndpointNode(args: {
  nodeId: string;
  entryId: string;
  compareGraph: OrderedCompareGraph;
  referenceNodesById: Map<string, LinkGraphNode>;
  workingNodesById: Map<string, LinkGraphNode>;
  removed: boolean;
}): string {
  if (!args.removed && args.workingNodesById.has(args.nodeId)) {
    const workingNode = args.workingNodesById.get(args.nodeId)!;
    if (!args.compareGraph.nodeIndexById.has(workingNode.id)) {
      upsertCompareNode(args.compareGraph, workingNode);
    }
    return workingNode.id;
  }
  const ghostNode = ensureGhostNode({
    compareGraph: args.compareGraph,
    referenceNodesById: args.referenceNodesById,
    entryId: args.entryId,
    referenceNodeId: args.nodeId,
  });
  return ghostNode?.id ?? args.nodeId;
}

function buildNodeComparisons(
  scopeNodeIds: ReadonlySet<string>,
  referenceGraph: LinkGraphDocument,
  workingGraph: LinkGraphDocument,
): DraftCompareElement[] {
  const referenceNodesById = new Map(referenceGraph.nodes.map((node) => [node.id, node]));
  const workingNodesById = new Map(workingGraph.nodes.map((node) => [node.id, node]));
  const comparisons: DraftCompareElement[] = [];

  for (const nodeId of Array.from(scopeNodeIds).sort()) {
    const referenceNode = referenceNodesById.get(nodeId);
    const workingNode = workingNodesById.get(nodeId);
    if (referenceNode == null && workingNode == null) {
      continue;
    }
    if (referenceNode == null && workingNode != null) {
      comparisons.push({ currentId: workingNode.id, referenceId: null, status: "ADDED" });
      continue;
    }
    if (referenceNode != null && workingNode == null) {
      comparisons.push({ currentId: null, referenceId: referenceNode.id, status: "REMOVED" });
      continue;
    }
    if (!referenceNode || !workingNode || !nodesDiffer(referenceNode, workingNode)) {
      continue;
    }
    comparisons.push({ currentId: workingNode.id, referenceId: referenceNode.id, status: "MODIFIED" });
  }

  return comparisons;
}

function buildEdgeComparisons(
  scopeNodeIds: ReadonlySet<string>,
  referenceGraph: LinkGraphDocument,
  workingGraph: LinkGraphDocument,
): DraftCompareElement[] {
  const referenceEdges = referenceGraph.edges.filter((edge) => edgeTouchesScope(edge, scopeNodeIds));
  const workingEdges = workingGraph.edges.filter((edge) => edgeTouchesScope(edge, scopeNodeIds));
  const remainingReference = new Map(referenceEdges.map((edge) => [edge.id, edge]));
  const remainingWorking = new Map(workingEdges.map((edge) => [edge.id, edge]));
  const comparisons: DraftCompareElement[] = [];

  for (const edgeId of Array.from(remainingReference.keys()).filter((candidateEdgeId) => remainingWorking.has(candidateEdgeId)).sort()) {
    const referenceEdge = remainingReference.get(edgeId);
    const workingEdge = remainingWorking.get(edgeId);
    if (!referenceEdge || !workingEdge) {
      continue;
    }
    remainingReference.delete(edgeId);
    remainingWorking.delete(edgeId);
    if (edgesDiffer(referenceEdge, workingEdge)) {
      comparisons.push({
        currentId: workingEdge.id,
        referenceId: referenceEdge.id,
        status: "MODIFIED",
      });
    }
  }

  for (const referenceEdge of Array.from(remainingReference.values()).sort((left, right) => left.id.localeCompare(right.id))) {
    const matchedWorking = findModifiedEdgeCandidate(referenceEdge, Array.from(remainingWorking.values()));
    if (matchedWorking == null) {
      continue;
    }
    remainingReference.delete(referenceEdge.id);
    remainingWorking.delete(matchedWorking.id);
    comparisons.push({
      currentId: matchedWorking.id,
      referenceId: referenceEdge.id,
      status: "MODIFIED",
    });
  }

  for (const workingEdge of Array.from(remainingWorking.values()).sort((left, right) => left.id.localeCompare(right.id))) {
    comparisons.push({
      currentId: workingEdge.id,
      referenceId: null,
      status: "ADDED",
    });
  }
  for (const referenceEdge of Array.from(remainingReference.values()).sort((left, right) => left.id.localeCompare(right.id))) {
    comparisons.push({
      currentId: null,
      referenceId: referenceEdge.id,
      status: "REMOVED",
    });
  }

  return comparisons;
}

function edgeTouchesScope(edge: LinkGraphEdge, scopeNodeIds: ReadonlySet<string>): boolean {
  return scopeNodeIds.has(edge.source) || scopeNodeIds.has(edge.target);
}

function findModifiedEdgeCandidate(
  referenceEdge: LinkGraphEdge,
  workingEdges: LinkGraphEdge[],
): LinkGraphEdge | null {
  const scored = workingEdges
    .filter((candidate) => candidate.type === referenceEdge.type)
    .map((candidate) => ({ candidate, score: edgeSimilarityScore(referenceEdge, candidate) }))
    .filter(({ score }) => score > 0)
    .sort((left, right) => {
      if (right.score !== left.score) {
        return right.score - left.score;
      }
      return left.candidate.id.localeCompare(right.candidate.id);
    });
  const best = scored[0];
  if (!best) {
    return null;
  }
  const secondBest = scored[1];
  return secondBest == null || best.score > secondBest.score ? best.candidate : null;
}

function edgeSimilarityScore(left: LinkGraphEdge, right: LinkGraphEdge): number {
  let score = 0;
  if (left.source === right.source) {
    score += 4;
  }
  if (left.target === right.target) {
    score += 4;
  }
  if (normalizedText(left.label) === normalizedText(right.label) && normalizedText(left.label).length > 0) {
    score += 2;
  }
  return score;
}

function nodesDiffer(referenceNode: LinkGraphNode, workingNode: LinkGraphNode): boolean {
  return referenceNode.type !== workingNode.type
    || referenceNode.title !== workingNode.title
    || referenceNode.location !== workingNode.location
    || referenceNode.signature !== workingNode.signature
    || !sameStringArray(referenceNode.inputs, workingNode.inputs)
    || !sameStringArray(referenceNode.outputs, workingNode.outputs)
    || normalizedText(referenceNode.doc) !== normalizedText(workingNode.doc)
    || referenceNode.certainty !== workingNode.certainty
    || referenceNode.bindingStatus !== workingNode.bindingStatus
    || referenceNode.sourceTag !== workingNode.sourceTag
    || !sameMetadata(referenceNode.metadata, workingNode.metadata);
}

function edgesDiffer(referenceEdge: LinkGraphEdge, workingEdge: LinkGraphEdge): boolean {
  return referenceEdge.type !== workingEdge.type
    || referenceEdge.source !== workingEdge.source
    || referenceEdge.target !== workingEdge.target
    || normalizedText(referenceEdge.label) !== normalizedText(workingEdge.label)
    || referenceEdge.sourceTag !== workingEdge.sourceTag
    || !sameMetadata(referenceEdge.metadata, workingEdge.metadata);
}

function sameStringArray(left: string[], right: string[]): boolean {
  if (left.length !== right.length) {
    return false;
  }
  return left.every((value, index) => value === right[index]);
}

function normalizedText(value?: string | null): string {
  return value?.trim() ?? "";
}

function sameMetadata(
  left?: Record<string, string>,
  right?: Record<string, string>,
): boolean {
  const normalizedLeft = comparableMetadata(left);
  const normalizedRight = comparableMetadata(right);
  if (normalizedLeft.size !== normalizedRight.size) {
    return false;
  }
  for (const [key, value] of normalizedLeft) {
    if (normalizedRight.get(key) !== value) {
      return false;
    }
  }
  return true;
}

function comparableMetadata(metadata?: Record<string, string>): Map<string, string> {
  const comparable = new Map<string, string>();
  for (const [key, value] of Object.entries(metadata ?? {})) {
    if (key.startsWith("ui.") || key.startsWith("layout.")) {
      continue;
    }
    comparable.set(key, value);
  }
  return comparable;
}
