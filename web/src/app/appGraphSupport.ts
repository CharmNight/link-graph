import { FifoQueue } from "./fifoQueue";
import {
  applyBootstrapEdgeRoutes,
  resolveNodePosition,
  syncNodePosition,
} from "./graphState";
import type {
  AnalysisDisplayMode,
  CandidateDraftChange,
  DiffItem,
  DraftWorkbenchEntry,
  FactGraphViewDocument,
  FlowchartViewDocument,
  GraphPatch,
  GraphPatchResult,
  GraphPosition,
  InvestigationTurnOutcome,
  LinkGraphBootstrapState,
  LinkGraphDocument,
  LinkGraphEdge,
  LinkGraphNode,
  ResourceRelationViewDocument,
} from "./types";

export function resolveGraphPatchNodeIds(patch: GraphPatch | null | undefined): string[] {
  if (!patch) {
    return [];
  }
  const nodeIds: string[] = [];
  for (const operation of patch.operations) {
    if (operation.node?.id) {
      nodeIds.push(operation.node.id);
    }
    if (operation.edge?.source) {
      nodeIds.push(operation.edge.source);
    }
    if (operation.edge?.target) {
      nodeIds.push(operation.edge.target);
    }
  }
  return Array.from(new Set(nodeIds));
}

export function resolveDraftEntryTargetNodeIds(entry: DraftWorkbenchEntry | CandidateDraftChange | null): string[] {
  if (!entry) {
    return [];
  }
  const patchNodeIds = resolveGraphPatchNodeIds(entry.graphPatch ?? null);
  const evidenceNodeIds = entry.evidence.flatMap(
    (finding) => finding.references.map((reference) => reference.nodeId).filter(Boolean) as string[],
  );
  const resolvedNodeIds = Array.from(new Set([...patchNodeIds, ...evidenceNodeIds]));
  if (resolvedNodeIds.length > 0) {
    return resolvedNodeIds;
  }
  return Array.from(new Set(entry.targetNodeIds));
}

export function resolveDraftEntryPrimaryNodeId(entry: DraftWorkbenchEntry | CandidateDraftChange | null): string | null {
  return resolveDraftEntryTargetNodeIds(entry)[0] ?? null;
}

export function resolveNodeOwnerSignature(node: LinkGraphNode | null | undefined): string | null {
  if (!node) {
    return null;
  }
  return node.metadata?.["flow.ownerMethod"]?.trim()
    || node.signature?.trim()
    || null;
}

export function scopeFlowchartGraphToAnchorMethod(
  graph: LinkGraphDocument,
  anchorNodeId: string | null | undefined,
): LinkGraphDocument {
  const anchorNode = graph.nodes.find((node) => node.id === anchorNodeId) ?? null;
  const anchorSignature = resolveNodeOwnerSignature(anchorNode);
  if (!anchorSignature) {
    return graph;
  }
  const scopedNodes = graph.nodes.filter((node) => {
    if (node.id === anchorNodeId) {
      return true;
    }
    return resolveNodeOwnerSignature(node) === anchorSignature;
  });
  if (scopedNodes.length === 0 || scopedNodes.length === graph.nodes.length) {
    return graph;
  }
  const scopedNodeIds = new Set(scopedNodes.map((node) => node.id));
  const scopedEdges = graph.edges.filter((edge) => scopedNodeIds.has(edge.source) && scopedNodeIds.has(edge.target));
  return {
    ...graph,
    nodes: scopedNodes,
    edges: scopedEdges,
  };
}

export function projectedAliasNodeIds(node: LinkGraphNode): string[] {
  const rawAliasNodeIds = node.metadata?.["flowchart.projectedFromNodeIds"];
  if (!rawAliasNodeIds) {
    return [];
  }
  return rawAliasNodeIds
    .split(",")
    .map((value) => value.trim())
    .filter((value) => value.length > 0);
}

export function resolveDisplayedNodeId(
  requestedNodeId: string | null | undefined,
  nodes: LinkGraphNode[],
): string | null {
  const normalizedRequestedNodeId = requestedNodeId?.trim();
  if (!normalizedRequestedNodeId) {
    return null;
  }
  if (nodes.some((node) => node.id === normalizedRequestedNodeId)) {
    return normalizedRequestedNodeId;
  }
  return nodes.find((node) => projectedAliasNodeIds(node).includes(normalizedRequestedNodeId))?.id ?? null;
}

function mergeFlowchartPresentationNode(
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

function resolveFlowchartPatchNode(
  entry: DraftWorkbenchEntry,
  node: LinkGraphNode,
): LinkGraphNode | null {
  for (const operation of entry.graphPatch?.operations ?? []) {
    if (!operation.node) {
      continue;
    }
    const patchTargetId = operation.node.id || operation.elementId;
    if (patchTargetId === node.id || projectedAliasNodeIds(node).includes(patchTargetId)) {
      return operation.node;
    }
  }
  return null;
}

function flowchartPresentationNodeChanged(currentNode: LinkGraphNode, nextNode: LinkGraphNode): boolean {
  return currentNode.title !== nextNode.title
    || currentNode.doc !== nextNode.doc
    || currentNode.signature !== nextNode.signature
    || currentNode.sourceTag !== nextNode.sourceTag
    || currentNode.type !== nextNode.type
    || currentNode.certainty !== nextNode.certainty
    || currentNode.bindingStatus !== nextNode.bindingStatus;
}

export function overlayDraftEntryOntoFlowchartView(args: {
  view: FlowchartViewDocument;
  workingGraph: LinkGraphDocument | null;
  entry: DraftWorkbenchEntry | null;
  activeWorkbenchTab: "explanation" | "audit" | "draft" | "code";
  compareMode: "after" | "compare";
}): FlowchartViewDocument {
  const {
    view,
    workingGraph,
    entry,
    activeWorkbenchTab,
    compareMode,
  } = args;
  if (
    activeWorkbenchTab !== "draft"
    || compareMode !== "after"
    || entry?.kind !== "CHANGE"
  ) {
    return view;
  }

  const scopedNodeIds = new Set(resolveDraftEntryTargetNodeIds(entry));
  if (scopedNodeIds.size === 0) {
    return view;
  }
  const workingNodesById = new Map((workingGraph?.nodes ?? []).map((node) => [node.id, node]));
  let changed = false;
  const overlayNode = (node: LinkGraphNode): LinkGraphNode => {
    const isTargetedVisibleNode = scopedNodeIds.has(node.id)
      || projectedAliasNodeIds(node).some((aliasNodeId) => scopedNodeIds.has(aliasNodeId));
    if (!isTargetedVisibleNode) {
      return node;
    }
    const patchNode = resolveFlowchartPatchNode(entry, node);
    if (patchNode) {
      const mergedNode = mergeFlowchartPresentationNode(node, patchNode);
      if (flowchartPresentationNodeChanged(node, mergedNode)) {
        changed = true;
      }
      return mergedNode;
    }
    const workingNode = workingNodesById.get(node.id);
    if (workingNode) {
      const mergedNode = mergeFlowchartPresentationNode(node, workingNode);
      if (flowchartPresentationNodeChanged(node, mergedNode)) {
        changed = true;
      }
      return mergedNode;
    }
    return node;
  };

  const nextVisibleGraph = {
    ...view.visibleGraph,
    nodes: view.visibleGraph.nodes.map(overlayNode),
  };
  if (!changed) {
    return view;
  }
  return {
    ...view,
    visibleGraph: nextVisibleGraph,
    fullGraph: {
      ...view.fullGraph,
      nodes: view.fullGraph.nodes.map(overlayNode),
    },
  };
}

export function resolveEntryOwnerSignatures(
  entry: DraftWorkbenchEntry | CandidateDraftChange | null,
  graph: LinkGraphDocument,
): Set<string> {
  const signatures = new Set<string>();
  const nodesById = new Map(graph.nodes.map((node) => [node.id, node]));
  for (const nodeId of resolveDraftEntryTargetNodeIds(entry)) {
    const signature = resolveNodeOwnerSignature(nodesById.get(nodeId) ?? null);
    if (signature) {
      signatures.add(signature);
    }
  }
  for (const scope of entry?.editScopes ?? []) {
    const signature = scope.symbolSignature?.trim();
    if (signature) {
      signatures.add(signature);
    }
  }
  return signatures;
}

export function toDraftWorkbenchEntry(change: CandidateDraftChange): DraftWorkbenchEntry {
  return {
    entryId: `draft-${change.changeId}`,
    kind: "CHANGE",
    title: change.title,
    sourceChangeId: change.changeId,
    targetStepIds: change.targetStepIds,
    targetNodeIds: resolveDraftEntryTargetNodeIds(change),
    beforeState: change.beforeState ?? null,
    afterState: change.afterState ?? null,
    reason: change.reason,
    impactSummary: change.impactSummary,
    claimType: change.claimType ?? null,
    evidence: change.evidence ?? [],
    editScopes: change.editScopes ?? [],
    patchIntent: change.patchIntent ?? null,
    graphPatch: change.graphPatch ?? null,
  };
}

export function resolveEvidenceTargetNodeId(
  targetNodeIds: string[],
  evidence?: Array<{ references: Array<{ nodeId?: string | null }> }>,
): string | null {
  return targetNodeIds[0]
    ?? evidence?.flatMap((finding) => finding.references).find((reference) => reference.nodeId)?.nodeId
    ?? null;
}

export function deriveLatestTurnOutcome(result: GraphPatchResult | null): InvestigationTurnOutcome | null {
  if (!result) {
    return null;
  }
  return result.latestTurnOutcome
    ?? result.recentTurnOutcomes[result.recentTurnOutcomes.length - 1]
    ?? result.auditSession?.turnOutcomes?.[result.auditSession.turnOutcomes.length - 1]
    ?? null;
}

export function updateGraphPatchResultCandidateStatus(
  result: GraphPatchResult | null,
  changeId: string,
  status: CandidateDraftChange["status"],
): GraphPatchResult | null {
  if (!result) {
    return result;
  }
  return {
    ...result,
    candidateChanges: result.candidateChanges.map((candidate) =>
      candidate.changeId === changeId ? { ...candidate, status } : candidate),
    newCandidateChanges: result.newCandidateChanges.map((candidate) =>
      candidate.changeId === changeId ? { ...candidate, status } : candidate),
    auditSession: result.auditSession
      ? {
          ...result.auditSession,
          candidateChanges: result.auditSession.candidateChanges.map((candidate) =>
            candidate.changeId === changeId ? { ...candidate, status } : candidate),
        }
      : null,
  };
}

export function deriveFactGraphSummary(
  visibleGraph: LinkGraphDocument,
  fullGraph: LinkGraphDocument,
  anchorNodeId?: string | null,
) {
  return {
    anchorTitle: fullGraph.nodes.find((node) => node.id === anchorNodeId)?.title
      ?? visibleGraph.nodes.find((node) => node.id === anchorNodeId)?.title
      ?? null,
    visibleNodeCount: visibleGraph.nodes.length,
    fullNodeCount: fullGraph.nodes.length,
  };
}

export function deriveFlowchartSummary(
  visibleGraph: LinkGraphDocument,
  fullGraph: LinkGraphDocument = visibleGraph,
) {
  const hiddenNodeCount = (fullGraph.nodes?.length ?? 0) - visibleGraph.nodes.length;
  const hiddenEdgeCount = (fullGraph.edges?.length ?? 0) - visibleGraph.edges.length;
  const incompleteNodeCount = visibleGraph.nodes.filter((node) => node.metadata?.["flow.incomplete"] === "true").length;
  const incompleteEdgeCount = visibleGraph.edges.filter((edge) => edge.metadata?.["flow.incomplete"] === "true").length;
  const syntheticEdgeCount = visibleGraph.edges.filter((edge) => edge.metadata?.["flow.synthetic"] === "true").length;
  const syntheticEntryEdgeCount = visibleGraph.edges.filter(
    (edge) => edge.metadata?.["flow.synthetic"] === "true" && edge.metadata?.["flow.provenance"] === "SYNTHETIC_PROJECTION",
  ).length;
  return {
    nodeCount: visibleGraph.nodes.length,
    branchCount: visibleGraph.nodes.filter((node) => node.metadata?.["flowchart.kind"] === "DECISION").length,
    exceptionPathCount: visibleGraph.edges.filter((edge) => edge.label?.trim().toUpperCase() === "EXCEPTION").length,
    fullNodeCount: fullGraph.nodes.length,
    fullEdgeCount: fullGraph.edges.length,
    hiddenNodeCount: Math.max(0, hiddenNodeCount),
    hiddenEdgeCount: Math.max(0, hiddenEdgeCount),
    truncated: hiddenNodeCount > 0 || hiddenEdgeCount > 0,
    incompleteNodeCount,
    incompleteEdgeCount,
    semanticallyIncomplete: incompleteNodeCount > 0 || incompleteEdgeCount > 0,
    syntheticEdgeCount,
    syntheticEntryEdgeCount,
  };
}

export function deriveResourceRelationSummary(visibleGraph: LinkGraphDocument) {
  return {
    visibleNodeCount: visibleGraph.nodes.length,
    laneCounts: visibleGraph.nodes.reduce<Record<string, number>>((counts, node) => {
      const lane = node.metadata?.["resource.lane"] ?? "CODE";
      counts[lane] = (counts[lane] ?? 0) + 1;
      return counts;
    }, {}),
  };
}

export function resolveAnchorNodeId(
  nodes: LinkGraphNode[],
  preferredNodeId?: string | null,
): string | null {
  if (preferredNodeId && nodes.some((node) => node.id === preferredNodeId)) {
    return preferredNodeId;
  }
  return nodes.find((node) => node.type === "METHOD")?.id ?? nodes[0]?.id ?? null;
}

export function shouldResetAnchorNode(
  state: LinkGraphBootstrapState,
  graphChanged: boolean,
): boolean {
  if (graphChanged) {
    return true;
  }
  return state.lastMessageType === "loadGraph" || state.lastMessageType === "graphChanged";
}

function mergeVisibleGraphIntoFactFullGraph(
  currentView: FactGraphViewDocument,
  nextVisibleGraph: LinkGraphDocument,
): LinkGraphDocument {
  const nextVisibleNodeById = new Map(nextVisibleGraph.nodes.map((node) => [node.id, node]));
  const nextVisibleEdgeById = new Map(nextVisibleGraph.edges.map((edge) => [edge.id, edge]));
  const currentFullNodeIds = new Set(currentView.fullGraph.nodes.map((node) => node.id));
  const currentFullEdgeIds = new Set(currentView.fullGraph.edges.map((edge) => edge.id));
  const removedVisibleNodeIds = new Set(
    currentView.visibleGraph.nodes
      .filter((node) => !nextVisibleNodeById.has(node.id))
      .map((node) => node.id),
  );
  const removedVisibleEdgeIds = new Set(
    currentView.visibleGraph.edges
      .filter((edge) => !nextVisibleEdgeById.has(edge.id))
      .map((edge) => edge.id),
  );

  const mergedNodes = currentView.fullGraph.nodes
    .filter((node) => !removedVisibleNodeIds.has(node.id))
    .map((node) => nextVisibleNodeById.get(node.id) ?? node);
  nextVisibleGraph.nodes.forEach((node) => {
    if (!currentFullNodeIds.has(node.id)) {
      mergedNodes.push(node);
    }
  });

  const mergedEdges = currentView.fullGraph.edges
    .filter((edge) =>
      !removedVisibleEdgeIds.has(edge.id)
      && !removedVisibleNodeIds.has(edge.source)
      && !removedVisibleNodeIds.has(edge.target),
    )
    .map((edge) => nextVisibleEdgeById.get(edge.id) ?? edge)
    .filter((edge) => !removedVisibleNodeIds.has(edge.source) && !removedVisibleNodeIds.has(edge.target));
  nextVisibleGraph.edges.forEach((edge) => {
    if (!currentFullEdgeIds.has(edge.id)) {
      mergedEdges.push(edge);
    }
  });

  return {
    ...currentView.fullGraph,
    nodes: mergedNodes,
    edges: mergedEdges,
  };
}

export function syncFactGraphViewDocument(
  currentView: FactGraphViewDocument,
  nextVisibleGraph: LinkGraphDocument,
  nextAnchorNodeId: string | null,
): FactGraphViewDocument {
  const fullGraph = mergeVisibleGraphIntoFactFullGraph(currentView, nextVisibleGraph);
  return {
    ...currentView,
    visibleGraph: nextVisibleGraph,
    fullGraph,
    anchorNodeId: nextAnchorNodeId,
    summary: deriveFactGraphSummary(nextVisibleGraph, fullGraph, nextAnchorNodeId),
  };
}

export function applyBootstrapRoutesToDocument(
  nextDocument: LinkGraphDocument,
  currentDocument: LinkGraphDocument,
): LinkGraphDocument {
  const nextEdges = applyBootstrapEdgeRoutes(nextDocument.edges, currentDocument.edges);
  return nextEdges === nextDocument.edges
    ? nextDocument
    : {
        ...nextDocument,
        edges: nextEdges,
      };
}

export function applyBootstrapRoutesToViewDocument<
  T extends {
    visibleGraph: LinkGraphDocument;
    fullGraph: LinkGraphDocument;
  },
>(
  nextView: T,
  currentView: T,
): T {
  return {
    ...nextView,
    visibleGraph: applyBootstrapRoutesToDocument(nextView.visibleGraph, currentView.visibleGraph),
    fullGraph: applyBootstrapRoutesToDocument(nextView.fullGraph, currentView.fullGraph),
  };
}

export function reuseCurrentViewGraphs<
  T extends {
    visibleGraph: LinkGraphDocument;
    fullGraph: LinkGraphDocument;
  },
>(
  nextView: T,
  currentView: T,
  reuseCurrentGraphs: boolean,
): T {
  if (!reuseCurrentGraphs) {
    return nextView;
  }
  if (nextView.visibleGraph === currentView.visibleGraph && nextView.fullGraph === currentView.fullGraph) {
    return nextView;
  }
  return {
    ...nextView,
    visibleGraph: currentView.visibleGraph,
    fullGraph: currentView.fullGraph,
  };
}

export function applyLayoutUpdatesToGraphDocument(
  currentGraph: LinkGraphDocument,
  updates: Array<{ id: string; position: GraphPosition }>,
): LinkGraphDocument {
  if (updates.length === 0 || currentGraph.nodes.length === 0) {
    return currentGraph;
  }
  const updateMap = new Map(updates.map((update) => [update.id, update.position]));
  let changed = false;
  const nextNodes = currentGraph.nodes.map((node) => {
    const nextPosition = updateMap.get(node.id);
    if (!nextPosition) {
      return node;
    }
    const currentPosition = resolveNodePosition(node);
    if (currentPosition?.x === nextPosition.x && currentPosition?.y === nextPosition.y) {
      return node;
    }
    changed = true;
    return syncNodePosition(node, nextPosition);
  });
  return changed
    ? {
        ...currentGraph,
        nodes: nextNodes,
        edges: currentGraph.edges,
      }
    : currentGraph;
}

export function syncFlowchartViewLayout(
  currentView: FlowchartViewDocument,
  updates: Array<{ id: string; position: GraphPosition }>,
): FlowchartViewDocument {
  const visibleGraph = applyLayoutUpdatesToGraphDocument(currentView.visibleGraph, updates);
  const fullGraph = applyLayoutUpdatesToGraphDocument(currentView.fullGraph, updates);
  return {
    ...currentView,
    visibleGraph,
    fullGraph,
    summary: deriveFlowchartSummary(visibleGraph, fullGraph),
  };
}

export function syncResourceRelationViewLayout(
  currentView: ResourceRelationViewDocument,
  updates: Array<{ id: string; position: GraphPosition }>,
): ResourceRelationViewDocument {
  const visibleGraph = applyLayoutUpdatesToGraphDocument(currentView.visibleGraph, updates);
  const fullGraph = applyLayoutUpdatesToGraphDocument(currentView.fullGraph, updates);
  return {
    ...currentView,
    visibleGraph,
    fullGraph,
    summary: deriveResourceRelationSummary(visibleGraph),
  };
}

export function resolveCollapsedDescendantSummary(
  nodes: LinkGraphNode[],
  edges: LinkGraphEdge[],
  collapsedNodeIds: string[],
): {
  hiddenNodeIds: Set<string>;
  descendantCountByNodeId: Record<string, number>;
} {
  if (collapsedNodeIds.length === 0) {
    return {
      hiddenNodeIds: new Set(),
      descendantCountByNodeId: {},
    };
  }
  const outgoingEdgeMap = new Map<string, string[]>();
  edges.forEach((edge) => {
    const nextTargets = outgoingEdgeMap.get(edge.source) ?? [];
    nextTargets.push(edge.target);
    outgoingEdgeMap.set(edge.source, nextTargets);
  });
  const nodeById = new Map(nodes.map((node) => [node.id, node]));

  const hiddenNodeIds = new Set<string>();
  const descendantCountByNodeId: Record<string, number> = {};
  collapsedNodeIds.forEach((collapsedNodeId) => {
    const descendants = new Set<string>();
    let overflowHiddenCount = 0;
    const queue = new FifoQueue([...(outgoingEdgeMap.get(collapsedNodeId) ?? [])]);
    while (true) {
      const nextNodeId = queue.dequeue();
      if (!nextNodeId) {
        break;
      }
      if (descendants.has(nextNodeId) || nextNodeId === collapsedNodeId) {
        continue;
      }
      descendants.add(nextNodeId);
      hiddenNodeIds.add(nextNodeId);
      const overflowCount = Number(
        nodeById.get(nextNodeId)?.metadata?.["linkGraph.overflow.hiddenMethodCount"]
          ?? nodeById.get(nextNodeId)?.metadata?.["linkGraph.hiddenNodeCount"],
      );
      if (Number.isFinite(overflowCount) && overflowCount > 0) {
        overflowHiddenCount += overflowCount;
      }
      queue.enqueue(...(outgoingEdgeMap.get(nextNodeId) ?? []));
    }
    descendantCountByNodeId[collapsedNodeId] = descendants.size + overflowHiddenCount;
  });
  return {
    hiddenNodeIds,
    descendantCountByNodeId,
  };
}

export function resolveAuditTargetNodeIds(
  targetNodeId: string | undefined,
  selectionGroupNodeIds: string[],
): string[] {
  if (targetNodeId) {
    return [targetNodeId];
  }
  return selectionGroupNodeIds.length > 1 ? selectionGroupNodeIds : [];
}
