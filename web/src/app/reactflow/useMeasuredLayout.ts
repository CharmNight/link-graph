import { useEffect, useMemo, useRef, useState } from "react";
import { measureDuration, measureStart, summarizeGraph, traceLinkGraph } from "../debug";
import {
  defaultNodeSizeRegistry,
  type NodeMeasuredSize,
  type NodeSizeRegistry,
} from "../graph/nodeSizeRegistry";
import type { GraphPosition, LinkGraphDocument, LinkGraphEdge, LinkGraphNode } from "../types";

export type LayoutTriggerReason = "graph" | "measurement" | "manual" | "position";

export interface MeasuredLayoutRequest {
  graph: LinkGraphDocument;
  nodes: LinkGraphNode[];
  edges: LinkGraphEdge[];
  anchorNodeId?: string | null;
  sizeSnapshot: ReadonlyMap<string, NodeMeasuredSize>;
  reason: LayoutTriggerReason;
}

export type LayoutSizeSignatureResolver = (
  nodes: LinkGraphNode[],
  sizeSnapshot: ReadonlyMap<string, NodeMeasuredSize>,
) => string;

export interface UseMeasuredLayoutOptions {
  graph: LinkGraphDocument;
  anchorNodeId?: string | null;
  collapsedNodeIds?: string[];
  nodeSizeRegistry?: NodeSizeRegistry;
  layout: (request: MeasuredLayoutRequest) => Promise<{ nodes: LinkGraphNode[]; edges: LinkGraphEdge[] }>;
  layoutSizeSignature?: LayoutSizeSignatureResolver;
  layoutOnPositionChange?: boolean;
  debugLabel?: string;
}

export interface UseMeasuredLayoutResult {
  nodes: LinkGraphNode[];
  edges: LinkGraphEdge[];
  layoutPending: boolean;
  requestRelayout: () => void;
}

interface LayoutState {
  nodes: LinkGraphNode[];
  edges: LinkGraphEdge[];
  layoutPending: boolean;
}

interface LayoutTriggerSnapshot {
  graphSignature: string;
  collapsedSignature: string;
  positionSignature: string;
  manualNonce: number;
}

function normalizeLayoutMetadata(metadata?: Record<string, string>): Record<string, string> | undefined {
  if (!metadata) {
    return undefined;
  }
  const nextMetadata = Object.fromEntries(
    Object.entries(metadata).filter(([key]) => !key.startsWith("ui.") && !key.startsWith("layout.")),
  );
  return Object.keys(nextMetadata).length > 0 ? nextMetadata : undefined;
}

function nodeSignature(node: LinkGraphNode): string {
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
    JSON.stringify(normalizeLayoutMetadata(node.metadata) ?? {}),
  ].join("|");
}

function edgeSignature(edge: LinkGraphEdge): string {
  return [
    edge.id,
    edge.type,
    edge.source,
    edge.target,
    edge.sourceHandle ?? "",
    edge.targetHandle ?? "",
    edge.label ?? "",
    JSON.stringify(edge.metadata ?? {}),
    edge.sourceTag ?? "",
  ].join("|");
}

function areNodeSetsEquivalent(left: LinkGraphNode[], right: LinkGraphNode[]): boolean {
  if (left.length !== right.length) {
    return false;
  }
  const rightIndex = new Map(right.map((node) => [node.id, node]));
  return left.every((node) => {
    const candidate = rightIndex.get(node.id);
    if (!candidate) {
      return false;
    }
    const position = resolvePosition(node);
    const candidatePosition = resolvePosition(candidate);
    return nodeSignature(node) === nodeSignature(candidate)
      && (
        (!position && !candidatePosition)
        || (position && candidatePosition && position.x === candidatePosition.x && position.y === candidatePosition.y)
      );
  });
}

function areRetainedNodesEquivalent(nextNodes: LinkGraphNode[], currentNodes: LinkGraphNode[]): boolean {
  const currentNodeIndex = new Map(currentNodes.map((node) => [node.id, node]));
  const retainedNodes = nextNodes.filter((node) => currentNodeIndex.has(node.id));
  return retainedNodes.length === currentNodes.length && areNodeSetsEquivalent(retainedNodes, currentNodes);
}

function areEdgeSetsEquivalent(left: LinkGraphEdge[], right: LinkGraphEdge[]): boolean {
  if (left.length !== right.length) {
    return false;
  }
  const rightIndex = new Map(right.map((edge) => [edge.id, edge]));
  return left.every((edge) => {
    const candidate = rightIndex.get(edge.id);
    return candidate != null && edgeSignature(edge) === edgeSignature(candidate);
  });
}

function graphSignature(graph: LinkGraphDocument): string {
  return [
    graph.nodes.map(nodeSignature).sort().join("::"),
    graph.edges.map(edgeSignature).sort().join("::"),
  ].join("##");
}

function sizeSignature(
  nodes: LinkGraphNode[],
  sizeSnapshot: ReadonlyMap<string, NodeMeasuredSize>,
): string {
  return nodes
    .map((node) => {
      const size = sizeSnapshot.get(node.id);
      return `${node.id}:${size?.width ?? ""}x${size?.height ?? ""}`;
    })
    .join("::");
}

function positionSignature(nodes: LinkGraphNode[]): string {
  return nodes
    .map((node) => {
      const position = resolvePosition(node);
      return `${node.id}:${position?.x ?? ""}:${position?.y ?? ""}`;
    })
    .join("::");
}

function resolvePosition(node?: LinkGraphNode | null): GraphPosition | null {
  if (!node) {
    return null;
  }
  if (node.position) {
    return node.position;
  }
  const x = Number(node.metadata?.["ui.x"]);
  const y = Number(node.metadata?.["ui.y"]);
  return Number.isFinite(x) && Number.isFinite(y) ? { x, y } : null;
}

function fallbackLayoutPosition(index: number): GraphPosition {
  return {
    x: 120 + (index % 4) * 360,
    y: 96 + Math.floor(index / 4) * 220,
  };
}

function hasResolvedLayoutPositions(nodes: LinkGraphNode[]): boolean {
  return nodes.length > 0 && nodes.every((node) => resolvePosition(node) !== null);
}

function syncNodePosition(node: LinkGraphNode, position: GraphPosition): LinkGraphNode {
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

function layoutMetadataFrom(node?: LinkGraphNode | null): Record<string, string> | undefined {
  if (!node?.metadata) {
    return undefined;
  }
  const nextMetadata = Object.fromEntries(
    Object.entries(node.metadata).filter(([key]) => key.startsWith("layout.")),
  );
  return Object.keys(nextMetadata).length > 0 ? nextMetadata : undefined;
}

function mergeSeedNodeMetadata(
  node: LinkGraphNode,
  previousNode?: LinkGraphNode | null,
): Record<string, string> | undefined {
  const preservedLayoutMetadata = layoutMetadataFrom(previousNode);
  if (!preservedLayoutMetadata) {
    return node.metadata;
  }
  return {
    ...preservedLayoutMetadata,
    ...(node.metadata ?? {}),
  };
}

function seedNodeFromPrevious(
  node: LinkGraphNode,
  previousNode: LinkGraphNode | undefined,
  position: GraphPosition,
): LinkGraphNode {
  return {
    ...node,
    position,
    metadata: {
      ...(mergeSeedNodeMetadata(node, previousNode) ?? {}),
      "ui.x": String(position.x),
      "ui.y": String(position.y),
    },
  };
}

function seedLayoutNodes(nextNodes: LinkGraphNode[], previousNodes: LinkGraphNode[]): LinkGraphNode[] {
  const previousNodeIndex = new Map(previousNodes.map((node) => [node.id, node]));
  return nextNodes.map((node) => {
    const previousNode = previousNodeIndex.get(node.id);
    const explicitPosition = resolvePosition(node);
    if (explicitPosition) {
      return seedNodeFromPrevious(node, previousNode, explicitPosition);
    }
    const previousPosition = resolvePosition(previousNode);
    if (previousPosition) {
      return seedNodeFromPrevious(node, previousNode, previousPosition);
    }
    const mergedMetadata = mergeSeedNodeMetadata(node, previousNode);
    if (mergedMetadata === node.metadata) {
      return node;
    }
    return {
      ...node,
      metadata: mergedMetadata,
    };
  });
}

function ensureResolvedLayoutPositions(nodes: LinkGraphNode[]): LinkGraphNode[] {
  return nodes.map((node, index) => syncNodePosition(node, resolvePosition(node) ?? fallbackLayoutPosition(index)));
}

function canReuseSeededRoute(nextEdge: LinkGraphEdge, previousEdge: LinkGraphEdge): boolean {
  return nextEdge.id === previousEdge.id
    && nextEdge.type === previousEdge.type
    && nextEdge.source === previousEdge.source
    && nextEdge.target === previousEdge.target
    && (nextEdge.sourceHandle ?? "") === (previousEdge.sourceHandle ?? "")
    && (nextEdge.targetHandle ?? "") === (previousEdge.targetHandle ?? "")
    && (nextEdge.label ?? "") === (previousEdge.label ?? "");
}

function seedLayoutEdges(nextEdges: LinkGraphEdge[], previousEdges: LinkGraphEdge[]): LinkGraphEdge[] {
  const previousEdgeIndex = new Map(previousEdges.map((edge) => [edge.id, edge]));
  return nextEdges.map((edge) => {
    const previousEdge = previousEdgeIndex.get(edge.id);
    if (!previousEdge?.route || edge.route || !canReuseSeededRoute(edge, previousEdge)) {
      return edge;
    }
    return {
      ...edge,
      route: previousEdge.route,
    };
  });
}

function isIncrementalPositionedNodeAddition(nextNodes: LinkGraphNode[], currentNodes: LinkGraphNode[]): boolean {
  if (nextNodes.length <= currentNodes.length) {
    return false;
  }
  if (!areRetainedNodesEquivalent(nextNodes, currentNodes)) {
    return false;
  }
  const currentNodeIds = new Set(currentNodes.map((node) => node.id));
  const addedNodes = nextNodes.filter((node) => !currentNodeIds.has(node.id));
  return addedNodes.length > 0
    && addedNodes.every((node) => Boolean(resolvePosition(node)))
    && addedNodes.every((node) => !node.metadata?.["linkGraph.expansion.id"]);
}

function hasInvocationExpansionEdges(edges: LinkGraphEdge[]): boolean {
  return edges.some((edge) => Boolean(edge.metadata?.["linkGraph.expansion.id"]));
}

export function useMeasuredLayout({
  graph,
  anchorNodeId = null,
  collapsedNodeIds = [],
  nodeSizeRegistry = defaultNodeSizeRegistry,
  layout,
  layoutSizeSignature = sizeSignature,
  layoutOnPositionChange = false,
  debugLabel = "graph",
}: UseMeasuredLayoutOptions): UseMeasuredLayoutResult {
  const [manualNonce, setManualNonce] = useState(0);
  const [registryRevision, setRegistryRevision] = useState(() => nodeSizeRegistry.currentRevision());
  const measuredSizes = useMemo(() => nodeSizeRegistry.snapshot(), [nodeSizeRegistry, registryRevision]);
  const nextGraphSignature = useMemo(() => graphSignature(graph), [graph]);
  const nextCollapsedSignature = useMemo(
    () => [...new Set(collapsedNodeIds)].sort().join("|"),
    [collapsedNodeIds],
  );
  const nextSizeSignature = useMemo(
    () => layoutSizeSignature(graph.nodes, measuredSizes),
    [graph.nodes, layoutSizeSignature, measuredSizes],
  );
  const nextPositionSignature = useMemo(() => positionSignature(graph.nodes), [graph.nodes]);
  const trackedPositionSignature = layoutOnPositionChange ? nextPositionSignature : "";
  const [layoutState, setLayoutState] = useState<LayoutState>(() => ({
    nodes: hasResolvedLayoutPositions(seedLayoutNodes(graph.nodes, []))
      ? seedLayoutNodes(graph.nodes, [])
      : [],
    edges: hasResolvedLayoutPositions(seedLayoutNodes(graph.nodes, []))
      ? seedLayoutEdges(graph.edges, [])
      : [],
    layoutPending: graph.nodes.length > 0 && !hasResolvedLayoutPositions(seedLayoutNodes(graph.nodes, [])),
  }));
  const latestLayoutStateRef = useRef<LayoutState>(layoutState);
  const triggerRef = useRef<LayoutTriggerSnapshot | null>(null);
  const requestVersionRef = useRef(0);
  const latestGraphRef = useRef(graph);
  const latestMeasuredSizesRef = useRef(measuredSizes);

  latestLayoutStateRef.current = layoutState;
  latestGraphRef.current = graph;
  latestMeasuredSizesRef.current = measuredSizes;

  useEffect(() => {
    return nodeSizeRegistry.subscribe(() => {
      setRegistryRevision(nodeSizeRegistry.currentRevision());
    });
  }, [nodeSizeRegistry]);

  useEffect(() => {
    setLayoutState((current) => ({
      nodes: hasResolvedLayoutPositions(seedLayoutNodes(graph.nodes, current.nodes))
        ? seedLayoutNodes(graph.nodes, current.nodes)
        : [],
      edges: hasResolvedLayoutPositions(seedLayoutNodes(graph.nodes, current.nodes))
        ? seedLayoutEdges(graph.edges, current.edges)
        : [],
      layoutPending: graph.nodes.length > 0
        ? current.layoutPending || !hasResolvedLayoutPositions(seedLayoutNodes(graph.nodes, current.nodes))
        : false,
    }));
  }, [graph.edges, graph.nodes, nextPositionSignature]);

  useEffect(() => {
    const startedAt = measureStart();
    const nextGraph = latestGraphRef.current;
    const nextMeasuredSizes = latestMeasuredSizesRef.current;
    const currentLayoutState = latestLayoutStateRef.current;
    const seededNodes = seedLayoutNodes(nextGraph.nodes, currentLayoutState.nodes);
    const seededEdges = seedLayoutEdges(nextGraph.edges, currentLayoutState.edges);
    const previousTrigger = triggerRef.current;
    const reason: LayoutTriggerReason = !previousTrigger
      ? "graph"
      : manualNonce !== previousTrigger.manualNonce
        ? "manual"
        : nextGraphSignature !== previousTrigger.graphSignature || nextCollapsedSignature !== previousTrigger.collapsedSignature
          ? "graph"
          : trackedPositionSignature !== previousTrigger.positionSignature
            ? "position"
            : "measurement";
    triggerRef.current = {
      graphSignature: nextGraphSignature,
      collapsedSignature: nextCollapsedSignature,
      positionSignature: trackedPositionSignature,
      manualNonce,
    };

    const edgeOnlyGraphChange = reason === "graph"
      && currentLayoutState.nodes.length > 0
      && areNodeSetsEquivalent(seededNodes, currentLayoutState.nodes)
      && !areEdgeSetsEquivalent(seededEdges, currentLayoutState.edges);

    if (edgeOnlyGraphChange && !hasInvocationExpansionEdges(nextGraph.edges)) {
      setLayoutState({
        nodes: seededNodes,
        edges: seededEdges,
        layoutPending: false,
      });
      traceLinkGraph("useMeasuredLayout.skipEdgeOnlyLayout", {
        debugLabel,
        anchorNodeId,
        graph: summarizeGraph(nextGraph),
        durationMs: measureDuration(startedAt),
      });
      return;
    }

    const positionedNodeAdditionChange = reason === "graph"
      && previousTrigger !== null
      && nextCollapsedSignature === previousTrigger.collapsedSignature
      && isIncrementalPositionedNodeAddition(seededNodes, currentLayoutState.nodes);

    if (positionedNodeAdditionChange) {
      setLayoutState({
        nodes: seededNodes,
        edges: seededEdges,
        layoutPending: false,
      });
      traceLinkGraph("useMeasuredLayout.skipPositionedNodeAdditionLayout", {
        debugLabel,
        anchorNodeId,
        graph: summarizeGraph(nextGraph),
        durationMs: measureDuration(startedAt),
      });
      return;
    }

    if (nextGraph.nodes.length === 0) {
      const clearedEdges = seedLayoutEdges(nextGraph.edges, currentLayoutState.edges);
      setLayoutState((current) => {
        if (current.nodes.length === 0 && areEdgeSetsEquivalent(current.edges, clearedEdges) && !current.layoutPending) {
          return current;
        }
        return {
          nodes: [],
          edges: clearedEdges,
          layoutPending: false,
        };
      });
      return;
    }

    const renderableSeededLayout = hasResolvedLayoutPositions(seededNodes);
    setLayoutState({
      nodes: renderableSeededLayout ? seededNodes : [],
      edges: renderableSeededLayout ? seededEdges : [],
      layoutPending: true,
    });

    const requestVersion = requestVersionRef.current + 1;
    requestVersionRef.current = requestVersion;

    traceLinkGraph("useMeasuredLayout.start", {
      debugLabel,
      reason,
      anchorNodeId,
      graph: summarizeGraph(nextGraph),
    });

    void layout({
      graph: nextGraph,
      nodes: nextGraph.nodes,
      edges: nextGraph.edges,
      anchorNodeId,
      sizeSnapshot: nextMeasuredSizes,
      reason,
    })
      .then((laidOutGraph) => {
        if (requestVersionRef.current !== requestVersion) {
          return;
        }
        setLayoutState({
          nodes: laidOutGraph.nodes,
          edges: laidOutGraph.edges,
          layoutPending: false,
        });
        traceLinkGraph("useMeasuredLayout.complete", {
          debugLabel,
          reason,
          graph: summarizeGraph({ nodes: laidOutGraph.nodes, edges: laidOutGraph.edges }),
          durationMs: measureDuration(startedAt),
        });
      })
      .catch((error: unknown) => {
        if (requestVersionRef.current !== requestVersion) {
          return;
        }
        const fallbackNodes = ensureResolvedLayoutPositions(seededNodes);
        setLayoutState((current) => ({
          ...current,
          nodes: fallbackNodes,
          edges: seedLayoutEdges(nextGraph.edges, current.edges),
          layoutPending: false,
        }));
        traceLinkGraph("useMeasuredLayout.failed", {
          debugLabel,
          reason,
          errorMessage: error instanceof Error ? error.message : String(error),
          durationMs: measureDuration(startedAt),
        });
      });
  }, [
    anchorNodeId,
    debugLabel,
    layout,
    layoutOnPositionChange,
    manualNonce,
    nextCollapsedSignature,
    nextGraphSignature,
    trackedPositionSignature,
    nextSizeSignature,
  ]);

  return {
    nodes: layoutState.nodes,
    edges: layoutState.edges,
    layoutPending: layoutState.layoutPending,
    requestRelayout: () => {
      setManualNonce((current) => current + 1);
    },
  };
}
