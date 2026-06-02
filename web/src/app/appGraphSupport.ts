import { FifoQueue } from "./fifoQueue";
import { resolveFlowchartKind } from "./flowchartKind";
import {
  applyBootstrapEdgeRoutes,
  resolveNodePosition,
  syncNodePosition,
} from "./graphState";
import type {
  AnalysisDisplayMode,
  ArchitectureGraphViewDocument,
  CandidateDraftChange,
  ClassDiagramViewDocument,
  DiffItem,
  DraftWorkbenchEntry,
  FactGraphViewDocument,
  FlowchartViewDocument,
  GraphPatch,
  GraphPatchResult,
  GraphPosition,
  InvestigationThread,
  InvestigationTurnOutcome,
  LinkGraphBootstrapState,
  LinkGraphDocument,
  LinkGraphEdge,
  LinkGraphNode,
  ResourceRelationViewDocument,
  ReviewGraphViewDocument,
  RiskResolutionStatus,
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
    if (operation.elementKind === "NODE" && operation.elementId) {
      nodeIds.push(operation.elementId);
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
  const evidenceNodeIds = (entry.evidence ?? []).flatMap(
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
  const ownerScopedNodes = graph.nodes.filter((node) => {
    if (node.id === anchorNodeId) {
      return true;
    }
    return resolveNodeOwnerSignature(node) === anchorSignature;
  });
  const ownerScopedNodeIds = new Set(ownerScopedNodes.map((node) => node.id));
  const ownerScopedCanonicalNodeIds = new Set(ownerScopedNodeIds);
  ownerScopedNodes.forEach((node) => {
    projectedAliasNodeIds(node).forEach((aliasNodeId) => ownerScopedCanonicalNodeIds.add(aliasNodeId));
  });
  const expansionScopedNodes = graph.nodes.filter((node) => {
    const sourceInvocationNodeId = node.metadata?.["linkGraph.expansion.sourceInvocationNodeId"]?.trim();
    return Boolean(sourceInvocationNodeId && ownerScopedCanonicalNodeIds.has(sourceInvocationNodeId));
  });
  const entryScopedNodes = graph.nodes.filter((node) => {
    if (ownerScopedNodeIds.has(node.id)) {
      return false;
    }
    if (node.metadata?.["flowchart.kind"] !== "ENTRY" && node.type !== "METHOD") {
      return false;
    }
    return graph.edges.some((edge) => edge.source === node.id && ownerScopedNodeIds.has(edge.target));
  });
  const scopedNodes = Array.from(new Map(
    [...ownerScopedNodes, ...entryScopedNodes, ...expansionScopedNodes].map((node) => [node.id, node]),
  ).values());
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
  fallbackTargetNodeIds: Set<string>,
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
  const afterStateTitle = entry.afterState?.trim();
  const isFallbackTarget = fallbackTargetNodeIds.size === 0
    || fallbackTargetNodeIds.has(node.id)
    || projectedAliasNodeIds(node).some((aliasNodeId) => fallbackTargetNodeIds.has(aliasNodeId));
  if (!afterStateTitle || afterStateTitle === node.title.trim() || !isFallbackTarget || node.type === "METHOD") {
    return null;
  }
  return {
    ...node,
    title: afterStateTitle,
    sourceTag: "DRAFT_AI",
    metadata: {
      ...(node.metadata ?? {}),
      "draft.afterStateFallback": "true",
    },
  };
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
  activeWorkbenchTab: "explanation" | "qa" | "draft" | "code";
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
  const fallbackTargetNodeIds = new Set(resolveGraphPatchNodeIds(entry.graphPatch ?? null));
  const workingNodesById = new Map((workingGraph?.nodes ?? []).map((node) => [node.id, node]));
  let changed = false;
  const overlayNode = (node: LinkGraphNode): LinkGraphNode => {
    const isTargetedVisibleNode = scopedNodeIds.has(node.id)
      || projectedAliasNodeIds(node).some((aliasNodeId) => scopedNodeIds.has(aliasNodeId));
    if (!isTargetedVisibleNode) {
      return node;
    }
    const patchNode = resolveFlowchartPatchNode(entry, node, fallbackTargetNodeIds);
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
  const recentTurnOutcomes = result.recentTurnOutcomes ?? [];
  const sessionTurnOutcomes = result.qaSession?.turnOutcomes ?? [];
  return result.latestTurnOutcome
    ?? recentTurnOutcomes[recentTurnOutcomes.length - 1]
    ?? sessionTurnOutcomes[sessionTurnOutcomes.length - 1]
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
    qaSession: result.qaSession
      ? {
          ...result.qaSession,
          candidateChanges: result.qaSession.candidateChanges.map((candidate) =>
            candidate.changeId === changeId ? { ...candidate, status } : candidate),
        }
      : null,
  };
}

export function updateGraphPatchResultThreadResolution(
  result: GraphPatchResult | null,
  threadId: string,
  status: RiskResolutionStatus,
  note = "",
): GraphPatchResult | null {
  if (!result) {
    return result;
  }

  const updateThread = (thread: InvestigationThread): InvestigationThread => {
    if (thread.threadId !== threadId) {
      return thread;
    }
    return {
      ...thread,
      resolution: {
        threadId,
        status,
        note: note || thread.resolution?.note || "",
      },
    };
  };

  return {
    ...result,
    investigationThreads: result.investigationThreads?.map(updateThread),
    qaSession: result.qaSession
      ? {
          ...result.qaSession,
          investigationThreads: (result.qaSession.investigationThreads ?? []).map(updateThread),
        }
      : null,
  };
}

export function deriveFactGraphSummary(
  visibleGraph: LinkGraphDocument,
  fullGraph: LinkGraphDocument,
  anchorNodeId?: string | null,
  currentSummary?: FactGraphViewDocument["summary"],
) {
  return {
    ...currentSummary,
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
  currentSummary?: FlowchartViewDocument["summary"],
) {
  const incompleteNodeCount = visibleGraph.nodes.filter((node) => node.metadata?.["flow.incomplete"] === "true").length;
  const incompleteEdgeCount = visibleGraph.edges.filter((edge) => edge.metadata?.["flow.incomplete"] === "true").length;
  const syntheticEdgeCount = visibleGraph.edges.filter((edge) => edge.metadata?.["flow.synthetic"] === "true").length;
  const syntheticEntryEdgeCount = visibleGraph.edges.filter(
    (edge) => edge.metadata?.["flow.synthetic"] === "true" && edge.metadata?.["flow.provenance"] === "SYNTHETIC_PROJECTION",
  ).length;
  return {
    ...currentSummary,
    nodeCount: visibleGraph.nodes.length,
    branchCount: visibleGraph.nodes.filter((node) => resolveFlowchartKind(node) === "DECISION").length,
    exceptionPathCount: visibleGraph.edges.filter((edge) => edge.label?.trim().toUpperCase() === "EXCEPTION").length,
    fullNodeCount: fullGraph.nodes.length,
    fullEdgeCount: fullGraph.edges.length,
    incompleteNodeCount,
    incompleteEdgeCount,
    semanticallyIncomplete: incompleteNodeCount > 0 || incompleteEdgeCount > 0,
    syntheticEdgeCount,
    syntheticEntryEdgeCount,
  };
}

export function deriveResourceRelationSummary(visibleGraph: LinkGraphDocument) {
  const resourceCount = visibleGraph.nodes.filter(isResourceRelationNode).length;
  return {
    visibleNodeCount: visibleGraph.nodes.length,
    relationCount: visibleGraph.edges.length,
    resourceCount,
    fallbackReason: visibleGraph.edges.length > 0
      ? "NONE"
      : resourceCount === 0
        ? "NO_RESOURCE_UNITS"
        : "NO_BINDING_RELATIONS",
    laneCounts: visibleGraph.nodes.reduce<Record<string, number>>((counts, node) => {
      const lane = node.metadata?.["resource.lane"] ?? "CODE";
      counts[lane] = (counts[lane] ?? 0) + 1;
      return counts;
    }, {}),
  };
}

function isResourceRelationNode(node: LinkGraphNode): boolean {
  return node.metadata?.["resource.lane"] != null ||
    node.type.includes("RESOURCE") ||
    ["SQL", "HTTP_ENDPOINT", "MQ_TOPIC", "CONFIG_ITEM"].includes(node.type);
}

export function deriveArchitectureGraphSummary(visibleGraph: LinkGraphDocument) {
  return {
    moduleCount: visibleGraph.nodes.filter((node) => node.type === "MODULE").length,
    packageCount: visibleGraph.nodes.filter((node) => node.type === "PACKAGE").length,
    serviceCount: visibleGraph.nodes.filter((node) => node.type === "SERVICE").length,
    componentCount: visibleGraph.nodes.filter((node) => node.type === "COMPONENT").length,
    resourceCount: visibleGraph.nodes.filter((node) => node.type === "RESOURCE").length,
    layerCount: visibleGraph.nodes.filter((node) => node.type === "LAYER").length,
    libraryCount: visibleGraph.nodes.filter((node) => node.type === "LIBRARY").length,
    jdkCount: visibleGraph.nodes.filter((node) => node.metadata?.["architecture.node.kind"] === "JDK").length,
    relationCount: visibleGraph.edges.length,
    classCount: visibleGraph.nodes
      .map((node) => Number(node.metadata?.["architecture.classCount"] ?? "0"))
      .filter(Number.isFinite)
      .reduce((sum, count) => sum + count, 0),
    truncated: visibleGraph.truncated === true,
  };
}

export function deriveClassDiagramSummary(visibleGraph: LinkGraphDocument) {
  return {
    classCount: visibleGraph.nodes.filter((node) => node.type === "CLASS").length,
    fieldCount: visibleGraph.nodes
      .map((node) => Number(node.metadata?.["uml.field.count"] ?? "0"))
      .filter(Number.isFinite)
      .reduce((sum, count) => sum + count, 0),
    interfaceCount: visibleGraph.nodes.filter((node) => node.type === "INTERFACE").length,
    enumCount: visibleGraph.nodes.filter((node) => node.type === "ENUM").length,
    annotationCount: visibleGraph.nodes.filter((node) => node.type === "ANNOTATION").length,
    recordCount: visibleGraph.nodes.filter((node) => node.type === "RECORD").length,
    objectCount: visibleGraph.nodes.filter((node) => node.type === "OBJECT").length,
    relationCount: visibleGraph.edges.length,
    spiProviderCount: 0,
    reflectionRelationCount: 0,
    relationCompleteness: "COMPLETE",
    scopeTypeCount: visibleGraph.nodes.length,
    projectTypeCount: visibleGraph.nodes.length,
    projectClassCount: visibleGraph.nodes.filter((node) => node.type === "CLASS").length,
    scopeBasis: "CLASS_NEIGHBORHOOD",
    anchorTypeNodeId: visibleGraph.nodes[0]?.id ?? null,
    anchorTypeTitle: visibleGraph.nodes[0]?.title ?? null,
    anchorTypeQualifiedName: visibleGraph.nodes[0]?.signature ?? null,
    neighborhoodLimit: visibleGraph.nodes.length,
    memberLimit: 5,
    neighborhoodCandidateTypeCount: visibleGraph.nodes.length,
    neighborhoodTruncated: false,
  };
}

function packageFromSignature(signature?: string | null): string | null {
  if (!signature) {
    return null;
  }
  const owner = signature.split("(")[0] ?? signature;
  const index = owner.lastIndexOf(".");
  return index > 0 ? owner.slice(0, index) : null;
}

export function deriveReviewGraphSummary(visibleGraph: LinkGraphDocument) {
  return {
    changedSymbolCount: visibleGraph.nodes.filter((node) => node.metadata?.["review.role"] === "CHANGED").length,
    upstreamCount: visibleGraph.nodes.filter((node) => node.metadata?.["review.role"] === "UPSTREAM").length,
    downstreamCount: visibleGraph.nodes.filter((node) => node.metadata?.["review.role"] === "DOWNSTREAM").length,
    relatedTestCount: visibleGraph.nodes.filter((node) => node.metadata?.["review.role"] === "RELATED_TEST").length,
    affectedPackageCount: Array.from(new Set(visibleGraph.nodes
      .map((node) => node.metadata?.["architecture.package"] ?? packageFromSignature(node.signature))
      .filter(Boolean))).length,
    affectedModuleCount: Array.from(new Set(visibleGraph.nodes
      .map((node) => node.metadata?.["architecture.module"])
      .filter(Boolean))).length,
    evidenceRefCount: visibleGraph.edges.filter((edge) => edge.metadata?.["review.edgeRole"] === "RELATION").length,
    truncated: Boolean(visibleGraph.truncated),
    hiddenNodeCount: 0,
    hiddenEdgeCount: 0,
    selectedDiffItemIds: [],
    maxChangedNodes: 120,
    maxUpstreamNodes: 40,
    maxDownstreamNodes: 40,
    maxRelatedTestNodes: 40,
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
  workspaceGraphChanged: boolean,
): boolean {
  if (workspaceGraphChanged) {
    return true;
  }
  return state.lastMessageType === "loadGraph" || state.lastMessageType === "workspaceGraphChanged";
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
    summary: deriveFactGraphSummary(nextVisibleGraph, fullGraph, nextAnchorNodeId, currentView.summary),
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
  if (
    !reuseCurrentGraphs ||
    !haveSameGraphElementIds(nextView.visibleGraph, currentView.visibleGraph) ||
    !haveSameGraphElementIds(nextView.fullGraph, currentView.fullGraph)
  ) {
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

function haveSameGraphElementIds(left: LinkGraphDocument, right: LinkGraphDocument): boolean {
  return haveSameIds(left.nodes.map((node) => node.id), right.nodes.map((node) => node.id))
    && haveSameIds(left.edges.map((edge) => edge.id), right.edges.map((edge) => edge.id));
}

function haveSameIds(leftIds: string[], rightIds: string[]): boolean {
  if (leftIds.length !== rightIds.length) {
    return false;
  }
  const rightIdSet = new Set(rightIds);
  return leftIds.every((id) => rightIdSet.has(id));
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
    summary: deriveFlowchartSummary(visibleGraph, fullGraph, currentView.summary),
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

export function syncArchitectureGraphViewLayout(
  currentView: ArchitectureGraphViewDocument,
  updates: Array<{ id: string; position: GraphPosition }>,
): ArchitectureGraphViewDocument {
  const visibleGraph = applyLayoutUpdatesToGraphDocument(currentView.visibleGraph, updates);
  const fullGraph = applyLayoutUpdatesToGraphDocument(currentView.fullGraph, updates);
  return {
    ...currentView,
    visibleGraph,
    fullGraph,
    summary: currentView.summary,
  };
}

export function syncClassDiagramViewLayout(
  currentView: ClassDiagramViewDocument,
  updates: Array<{ id: string; position: GraphPosition }>,
): ClassDiagramViewDocument {
  const visibleGraph = applyLayoutUpdatesToGraphDocument(currentView.visibleGraph, updates);
  const fullGraph = applyLayoutUpdatesToGraphDocument(currentView.fullGraph, updates);
  return {
    ...currentView,
    visibleGraph,
    fullGraph,
    summary: currentView.summary,
  };
}

export function syncReviewGraphViewLayout(
  currentView: ReviewGraphViewDocument,
  updates: Array<{ id: string; position: GraphPosition }>,
): ReviewGraphViewDocument {
  const visibleGraph = applyLayoutUpdatesToGraphDocument(currentView.visibleGraph, updates);
  const fullGraph = applyLayoutUpdatesToGraphDocument(currentView.fullGraph, updates);
  return {
    ...currentView,
    visibleGraph,
    fullGraph,
    summary: currentView.summary,
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

export function resolveQaTargetNodeIds(
  targetNodeId: string | undefined,
  selectionGroupNodeIds: string[],
): string[] {
  if (targetNodeId) {
    return [targetNodeId];
  }
  return selectionGroupNodeIds.length > 1 ? selectionGroupNodeIds : [];
}
