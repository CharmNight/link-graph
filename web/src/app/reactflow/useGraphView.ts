import { useMemo } from "react";
import type { LinkGraphDocument, LinkGraphEdge, LinkGraphNode } from "../types";
import { createNodeSizeRegistry, type NodeSizeRegistry } from "../graph/nodeSizeRegistry";
import {
  useMeasuredLayout,
  type LayoutSizeSignatureResolver,
  type MeasuredLayoutRequest,
} from "./useMeasuredLayout";

export interface UseGraphViewOptions {
  /** The graph to lay out (apply draft-compare overlay upstream if needed). */
  graph: LinkGraphDocument;
  anchorNodeId?: string | null;
  /** The currently selected node id — drives the `selectedNode` lookup. */
  selectedNodeId?: string | null;
  /** Nodes currently hidden by the window/filter — excluded from visibleNodes/edges. */
  hiddenNodeIds?: string[];
  /** Nodes collapsed in the outline — forwarded to the layout engine. */
  collapsedNodeIds?: string[];
  /** Shared size registry (created once per view if omitted). */
  nodeSizeRegistry?: NodeSizeRegistry;
  /** View-specific layout function. */
  layout: (request: MeasuredLayoutRequest) => Promise<{ nodes: LinkGraphNode[]; edges: LinkGraphEdge[] }>;
  /** View-specific size signature (skip relayout when only sizes change irrelevantly). */
  layoutSizeSignature?: LayoutSizeSignatureResolver;
  layoutOnPositionChange?: boolean;
  debugLabel?: string;
}

export interface UseGraphViewResult {
  /** Raw measured+positioned nodes/edges from the layout engine (pre-filter). */
  layoutNodes: LinkGraphNode[];
  layoutEdges: LinkGraphEdge[];
  /** Nodes/edges after applying the hidden-window filter. */
  visibleNodes: LinkGraphNode[];
  visibleEdges: LinkGraphEdge[];
  /** id → node lookup over visibleNodes. */
  nodeIndex: Map<string, LinkGraphNode>;
  /** The selected node object, if present in the visible set. */
  selectedNode: LinkGraphNode | null;
  /** True while layout is pending for a non-empty graph (drives the loading empty-state). */
  isLayoutLoading: boolean;
  /** The shared size registry (so view-specific node builders can register/measure). */
  nodeSizeRegistry: NodeSizeRegistry;
  layoutPending: boolean;
  /** Imperative relayout trigger (e.g. for "重新整理布局" action menus). */
  requestRelayout: () => void;
}

/**
 * Shared tail of the graph-view pipeline.
 *
 * Every view (Fact / Flowchart / Resource / Architecture / ClassDiagram / Review)
 * previously inlined the same scaffolding after its view-specific pre-layout
 * shaping: register the node-size registry, run {@link useMeasuredLayout},
 * derive the hidden-node filter, compute visible nodes/edges, the node index,
 * the selected-node lookup, and the loading flag. This hook centralises that
 * identical tail so each view only declares its divergent parts (layout fn,
 * size signature, pre-shaped graph, node/edge builders).
 *
 * Deliberately does NOT build flowNodes/flowEdges — those use view-specific
 * builders and stay in the view. The view reads visibleNodes/visibleEdges/
 * nodeIndex/nodeSizeRegistry from this hook's result.
 */
export function useGraphView({
  graph,
  anchorNodeId,
  selectedNodeId,
  hiddenNodeIds = [],
  collapsedNodeIds,
  nodeSizeRegistry,
  layout,
  layoutSizeSignature,
  layoutOnPositionChange,
  debugLabel,
}: UseGraphViewOptions): UseGraphViewResult {
  const registry = useMemo(() => nodeSizeRegistry ?? createNodeSizeRegistry(), [nodeSizeRegistry]);
  const layoutState = useMeasuredLayout({
    graph,
    anchorNodeId,
    collapsedNodeIds,
    nodeSizeRegistry: registry,
    layout,
    layoutSizeSignature,
    layoutOnPositionChange,
    debugLabel,
  });

  const hiddenNodeIdSet = useMemo(() => new Set(hiddenNodeIds), [hiddenNodeIds]);
  const visibleNodes = useMemo(
    () => layoutState.nodes.filter((node) => !hiddenNodeIdSet.has(node.id)),
    [layoutState.nodes, hiddenNodeIdSet],
  );
  const visibleEdges = useMemo(
    () => layoutState.edges.filter(
      (edge) => !hiddenNodeIdSet.has(edge.source) && !hiddenNodeIdSet.has(edge.target),
    ),
    [layoutState.edges, hiddenNodeIdSet],
  );
  const isLayoutLoading = layoutState.layoutPending && graph.nodes.length > 0 && layoutState.nodes.length === 0;
  const nodeIndex = useMemo(
    () => new Map(visibleNodes.map((node) => [node.id, node])),
    [visibleNodes],
  );
  const selectedNode = useMemo(
    () => (selectedNodeId ? (visibleNodes.find((node) => node.id === selectedNodeId) ?? null) : null),
    [visibleNodes, selectedNodeId],
  );

  return {
    layoutNodes: layoutState.nodes,
    layoutEdges: layoutState.edges,
    visibleNodes,
    visibleEdges,
    nodeIndex,
    selectedNode,
    isLayoutLoading,
    nodeSizeRegistry: registry,
    layoutPending: layoutState.layoutPending,
    requestRelayout: layoutState.requestRelayout,
  };
}
