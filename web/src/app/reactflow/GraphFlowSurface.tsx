import type { MouseEvent as ReactMouseEvent, ReactNode } from "react";
import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import {
  Background,
  Controls,
  ReactFlow,
  SelectionMode,
  type Connection,
  type Edge,
  type Node,
  type NodeMouseHandler,
  type NodeTypes,
  type ReactFlowInstance,
} from "@xyflow/react";
import "@xyflow/react/dist/style.css";
import { DEFAULT_NODE_CARD_WIDTH } from "../graphNodeSizing";
import { measureDuration, measureStart, summarizeGraph, traceLinkGraph } from "../debug";
import type {
  AnalysisDisplayMode,
  GraphFocusRequest,
  GraphPosition,
  GraphSurfaceExperimentFlags,
  LinkGraphEdge,
  LinkGraphNode,
} from "../types";
import { GraphContextMenu } from "../components/graph/actions/GraphContextMenu";
import type { GraphContextMenuAction } from "../components/graph/actions/actionSchema";
import { ROUTED_EDGE_TYPES } from "./RoutedEdge";
import {
  graphBounds,
  shouldPreserveViewportForIncrementalUpdate,
  type GraphViewportSnapshot,
} from "./viewportPolicy";

interface PaneActionContext {
  position?: GraphPosition;
  hasGroupedSelection: boolean;
  visibleNodeCount: number;
  close: () => void;
}

interface NodeActionContext {
  nodeId: string;
  close: () => void;
}

interface EdgeActionContext {
  edgeId: string;
  close: () => void;
}

interface GraphFlowSurfaceProps {
  nodes: LinkGraphNode[];
  edges: LinkGraphEdge[];
  flowNodes: Node[];
  flowEdges: Edge[];
  nodeTypes?: NodeTypes;
  viewportMode?: AnalysisDisplayMode;
  anchorNodeId?: string | null;
  selectedNodeId: string | null;
  focusNodeRequest?: GraphFocusRequest | null;
  selectedGroupNodeIds?: string[];
  experiments?: GraphSurfaceExperimentFlags | null;
  editable?: boolean;
  layoutEditable?: boolean;
  header?: ReactNode;
  canvasMarkers?: ReactNode;
  emptyState: ReactNode;
  buildPaneActions: (context: PaneActionContext) => GraphContextMenuAction[];
  buildNodeActions: (context: NodeActionContext) => GraphContextMenuAction[];
  buildEdgeActions: (context: EdgeActionContext) => GraphContextMenuAction[];
  onSelectNode: (nodeId: string) => void;
  onSelectionGroupChange?: (nodeIds: string[]) => void;
  onInspectNode: (nodeId: string) => void;
  onCreateEdge: (
    sourceId: string,
    targetId: string,
    sourceHandle?: string | null,
    targetHandle?: string | null,
  ) => void;
  onMoveNode: (nodeId: string, position: GraphPosition) => void;
  onMoveNodes?: (updates: Array<{ id: string; position: GraphPosition }>) => void;
  shouldFocusAnchorOnLoad?: boolean;
  nodeViewportSize?: (node: LinkGraphNode) => { width: number; height: number };
}

type ContextMenuState =
  | {
      kind: "pane";
      x: number;
      y: number;
      position?: GraphPosition;
    }
  | {
      kind: "node";
      x: number;
      y: number;
      nodeId: string;
    }
  | {
      kind: "edge";
      x: number;
      y: number;
      edgeId: string;
    };

type ViewportScheduleReason = "graph" | "resize";

const FIT_VIEW_DELAY_MS = 96;
const FIT_VIEW_RETRY_DELAY_MS = 220;
const RESIZE_SETTLE_DELAY_MS = 140;
const WIDE_GRAPH_FOCUS_ZOOM = 0.76;
const CONTEXT_MENU_SAFE_MARGIN = 16;
const CONTEXT_MENU_ESTIMATED_WIDTH = 240;
const CONTEXT_MENU_ESTIMATED_HEIGHT = 360;
const GRAPH_SURFACE_MIN_ZOOM = 0.08;
const DRAG_POSITION_EPSILON = 0.5;

function hashText(value: string): string {
  let hash = 0;
  for (let index = 0; index < value.length; index += 1) {
    hash = ((hash * 31) + value.charCodeAt(index)) >>> 0;
  }
  return hash.toString(36);
}

function summarizeGraphShapeSignature(signature: string) {
  return {
    length: signature.length,
    hash: hashText(signature),
  };
}

function fallbackPosition(index: number): GraphPosition {
  return {
    x: 80 + (index % 3) * 400,
    y: 88 + Math.floor(index / 3) * 220,
  };
}

function resolveNodePosition(node: LinkGraphNode, index: number): GraphPosition {
  return node.position ?? fallbackPosition(index);
}

function positionsMatch(
  left: GraphPosition | undefined,
  right: GraphPosition | undefined,
): boolean {
  if (!left || !right) {
    return false;
  }
  return Math.abs(left.x - right.x) <= DRAG_POSITION_EPSILON
    && Math.abs(left.y - right.y) <= DRAG_POSITION_EPSILON;
}

function resolveAnchorNode(
  nodes: LinkGraphNode[],
  anchorNodeId: string | null | undefined,
  selectedNodeId: string | null,
): LinkGraphNode | null {
  return nodes.find((node) => node.id === anchorNodeId)
    ?? nodes.find((node) => node.id === selectedNodeId)
    ?? nodes.find((node) => node.type === "METHOD")
    ?? nodes[0]
    ?? null;
}

export function GraphFlowSurface({
  nodes,
  edges,
  flowNodes,
  flowEdges,
  nodeTypes,
  viewportMode,
  anchorNodeId = null,
  selectedNodeId,
  focusNodeRequest = null,
  selectedGroupNodeIds = [],
  experiments = null,
  editable = false,
  layoutEditable = true,
  header = null,
  canvasMarkers = null,
  emptyState,
  buildPaneActions,
  buildNodeActions,
  buildEdgeActions,
  onSelectNode,
  onSelectionGroupChange = () => undefined,
  onInspectNode,
  onCreateEdge,
  onMoveNode,
  onMoveNodes,
  shouldFocusAnchorOnLoad = false,
  nodeViewportSize = () => ({ width: DEFAULT_NODE_CARD_WIDTH, height: 156 }),
}: GraphFlowSurfaceProps) {
  const renderStartedAt = measureStart();
  const supportsResizeObserver = typeof ResizeObserver !== "undefined";
  const [contextMenu, setContextMenu] = useState<ContextMenuState | null>(null);
  const [flowInstance, setFlowInstance] = useState<ReactFlowInstance | null>(null);
  const [isExperimentalDragging, setIsExperimentalDragging] = useState(false);
  const [liveDragPositions, setLiveDragPositions] = useState<Record<string, GraphPosition>>({});
  const [selectedEdgeId, setSelectedEdgeId] = useState<string | null>(null);
  const canvasShellRef = useRef<HTMLDivElement | null>(null);
  const primaryFitViewTimerRef = useRef<number | null>(null);
  const retryFitViewTimerRef = useRef<number | null>(null);
  const resizeViewportTimerRef = useRef<number | null>(null);
  const lastResizeShellSizeRef = useRef<{ width: number; height: number } | null>(null);
  const previousViewportGraphRef = useRef<GraphViewportSnapshot | null>(null);
  const handledFocusNonceRef = useRef<number | null>(null);
  const previousSelectedNodeIdRef = useRef<string | null>(selectedNodeId);
  const onSelectionGroupChangeRef = useRef(onSelectionGroupChange);
  const lastSelectionChangeSignatureRef = useRef<string>(selectedGroupNodeIds.join("\u0000"));

  const onlyRenderVisibleElements = experiments?.onlyRenderVisibleElements === true;
  const dragShieldingEnabled = experiments?.dragShielding === true;

  useEffect(() => {
    traceLinkGraph("graphFlowSurface.lifecycle.mount", {
      viewportMode,
      graph: summarizeGraph({ nodes: positionedNodes, edges }),
      anchorNodeId: anchorNode?.id ?? null,
      graphShape: summarizeGraphShapeSignature(graphShapeSignature),
    });
    return () => {
      traceLinkGraph("graphFlowSurface.lifecycle.unmount", {
        viewportMode,
        graph: summarizeGraph({ nodes: positionedNodes, edges }),
        anchorNodeId: anchorNode?.id ?? null,
        graphShape: summarizeGraphShapeSignature(graphShapeSignature),
      });
    };
  }, []);

  useEffect(() => {
    onSelectionGroupChangeRef.current = onSelectionGroupChange;
  }, [onSelectionGroupChange]);

  useEffect(() => {
    if (!dragShieldingEnabled && isExperimentalDragging) {
      setIsExperimentalDragging(false);
    }
  }, [dragShieldingEnabled, isExperimentalDragging]);

  const positionedNodes = useMemo(
    () =>
      nodes.map((node, index) => {
        const position = resolveNodePosition(node, index);
        if (node.position?.x === position.x && node.position?.y === position.y) {
          return node;
        }
        return {
          ...node,
          position,
        };
      }),
    [nodes],
  );

  const anchorNode = useMemo(
    () => resolveAnchorNode(positionedNodes, anchorNodeId, selectedNodeId),
    [positionedNodes, anchorNodeId, selectedNodeId],
  );
  const hasGroupedSelection = selectedGroupNodeIds.length > 1;
  const renderedFlowNodes = useMemo(() => {
    const liveDragNodeIds = Object.keys(liveDragPositions);
    if (liveDragNodeIds.length === 0) {
      return flowNodes;
    }
    return flowNodes.map((node) => {
      const livePosition = liveDragPositions[node.id];
      if (!livePosition || positionsMatch(node.position, livePosition)) {
        return node;
      }
      return {
        ...node,
        position: livePosition,
      };
    });
  }, [flowNodes, liveDragPositions]);

  const graphShapeSignature = useMemo(
    () =>
      positionedNodes
        .map((node) => node.id)
        .concat(edges.map((edge) => `${edge.id}:${edge.source}->${edge.target}`))
        .join("|"),
    [positionedNodes, edges],
  );

  useEffect(() => {
    const liveDragNodeIds = Object.keys(liveDragPositions);
    if (liveDragNodeIds.length === 0) {
      return;
    }
    const committedPositions = new Map(
      flowNodes.map((node) => [node.id, node.position]),
    );
    const nextLivePositions = Object.fromEntries(
      liveDragNodeIds
        .filter((nodeId) => {
          const livePosition = liveDragPositions[nodeId];
          const committedPosition = committedPositions.get(nodeId);
          return livePosition && !positionsMatch(committedPosition, livePosition);
        })
        .map((nodeId) => [nodeId, liveDragPositions[nodeId]!]),
    );
    const nextLivePositionIds = Object.keys(nextLivePositions);
    if (nextLivePositionIds.length === liveDragNodeIds.length) {
      return;
    }
    setLiveDragPositions(nextLivePositions);
  }, [flowNodes, liveDragPositions]);

  useEffect(() => {
    traceLinkGraph("graphFlowSurface.renderCommitted", {
      graph: summarizeGraph({ nodes: positionedNodes, edges }),
      selectedNodeId,
      selectedGroupNodeCount: selectedGroupNodeIds.length,
      bounds: graphBounds(positionedNodes),
      supportsResizeObserver,
      durationMs: measureDuration(renderStartedAt),
    });
  }, [
    positionedNodes,
    edges,
    selectedNodeId,
    selectedGroupNodeIds.length,
    supportsResizeObserver,
    renderStartedAt,
  ]);

  function focusNodeInViewport(node: LinkGraphNode, reason: string) {
    if (!flowInstance || !node.position) {
      return;
    }
    const size = nodeViewportSize(node);
    const zoomOptions = reason === "manualAnchor" || reason === "explicitNodeFocus"
      ? { duration: 0 }
      : { zoom: WIDE_GRAPH_FOCUS_ZOOM, duration: 0 };
    traceLinkGraph("graphFlowSurface.viewport.focusNode", {
      reason,
      nodeId: node.id,
      nodePosition: node.position,
      zoomOptions,
    });
    flowInstance.setCenter(
      node.position.x + size.width / 2,
      node.position.y + size.height / 2,
      zoomOptions,
    );
  }

  function clearScheduledFitView() {
    if (primaryFitViewTimerRef.current !== null) {
      window.clearTimeout(primaryFitViewTimerRef.current);
      primaryFitViewTimerRef.current = null;
    }
    if (retryFitViewTimerRef.current !== null) {
      window.clearTimeout(retryFitViewTimerRef.current);
      retryFitViewTimerRef.current = null;
    }
    if (resizeViewportTimerRef.current !== null) {
      window.clearTimeout(resizeViewportTimerRef.current);
      resizeViewportTimerRef.current = null;
    }
  }

  function scheduleViewport(reason: ViewportScheduleReason) {
    if (!flowInstance || positionedNodes.length === 0) {
      traceLinkGraph("graphFlowSurface.scheduleViewport.skipped", {
        reason,
        hasFlowInstance: Boolean(flowInstance),
        nodeCount: positionedNodes.length,
      });
      return;
    }
    const useFlowchartViewport = viewportMode === "FLOWCHART";
    traceLinkGraph("graphFlowSurface.scheduleViewport", {
      reason,
      graph: summarizeGraph({ nodes: positionedNodes, edges }),
      bounds: graphBounds(positionedNodes),
      viewportMode,
      focusAnchor: shouldFocusAnchorOnLoad,
      anchorNodeId: anchorNode?.id ?? null,
      anchorPosition: anchorNode?.position ?? null,
    });
    if ((shouldFocusAnchorOnLoad || useFlowchartViewport) && reason === "resize") {
      return;
    }
    clearScheduledFitView();
    const updateViewport = () => {
      let branch: "flowchartAnchor" | "wideGraphAnchor" | "fitView" = "fitView";
      if (shouldFocusAnchorOnLoad && anchorNode?.position) {
        branch = "wideGraphAnchor";
        traceLinkGraph("graphFlowSurface.viewport.apply", {
          reason,
          branch,
          viewportMode,
          useFlowchartViewport,
          focusAnchor: shouldFocusAnchorOnLoad,
          anchorNodeId: anchorNode.id,
          anchorPosition: anchorNode.position,
          graphShape: summarizeGraphShapeSignature(graphShapeSignature),
        });
        focusNodeInViewport(anchorNode, "wideGraphAnchor");
        return;
      }
      traceLinkGraph("graphFlowSurface.viewport.apply", {
        reason,
        branch,
        viewportMode,
        useFlowchartViewport,
        focusAnchor: shouldFocusAnchorOnLoad,
        anchorNodeId: anchorNode?.id ?? null,
        anchorPosition: anchorNode?.position ?? null,
        graphShape: summarizeGraphShapeSignature(graphShapeSignature),
      });
      flowInstance.fitView({
        padding: 0.16,
        duration: 0,
        maxZoom: 1,
        includeHiddenNodes: true,
      });
    };
    primaryFitViewTimerRef.current = window.setTimeout(() => {
      updateViewport();
      primaryFitViewTimerRef.current = null;
    }, FIT_VIEW_DELAY_MS);
    if (!shouldFocusAnchorOnLoad && !useFlowchartViewport && reason === "graph") {
      retryFitViewTimerRef.current = window.setTimeout(() => {
        updateViewport();
        retryFitViewTimerRef.current = null;
      }, FIT_VIEW_RETRY_DELAY_MS);
    }
  }

  useEffect(() => {
    if (!focusNodeRequest || handledFocusNonceRef.current === focusNodeRequest.nonce) {
      return;
    }
    const targetNode = positionedNodes.find((node) => node.id === focusNodeRequest.nodeId);
    if (!targetNode?.position || !flowInstance) {
      return;
    }
    handledFocusNonceRef.current = focusNodeRequest.nonce;
    focusNodeInViewport(targetNode, "explicitNodeFocus");
  }, [flowInstance, focusNodeRequest, positionedNodes]);

  useEffect(() => {
    const previousSelectedNodeId = previousSelectedNodeIdRef.current;
    previousSelectedNodeIdRef.current = selectedNodeId;
    if (!flowInstance || !selectedNodeId || previousSelectedNodeId === selectedNodeId) {
      return;
    }
    const targetNode = positionedNodes.find((node) => node.id === selectedNodeId);
    if (!targetNode?.position || selectedNodeId === anchorNode?.id) {
      return;
    }
    traceLinkGraph("graphFlowSurface.viewport.selectionEffect", {
      previousSelectedNodeId,
      selectedNodeId,
      anchorNodeId: anchorNode?.id ?? null,
      viewportMode,
    });
    focusNodeInViewport(targetNode, "selectedNodeChange");
  }, [anchorNode?.id, flowInstance, positionedNodes, selectedNodeId, viewportMode]);

  useEffect(() => {
    if (!flowInstance) {
      return clearScheduledFitView;
    }
    const nextViewportGraph: GraphViewportSnapshot = {
      anchorNodeId: anchorNode?.id ?? null,
      nodeIds: new Set(positionedNodes.map((node) => node.id)),
      edgeIds: new Set(edges.map((edge) => edge.id)),
    };
    const preserveViewport = shouldPreserveViewportForIncrementalUpdate(
      previousViewportGraphRef.current,
      nextViewportGraph,
    );
    traceLinkGraph("graphFlowSurface.viewport.graphEffect", {
      viewportMode,
      graph: summarizeGraph({ nodes: positionedNodes, edges }),
      anchorNodeId: anchorNode?.id ?? null,
      previousAnchorNodeId: previousViewportGraphRef.current?.anchorNodeId ?? null,
      preserveViewport,
      shouldFocusAnchorOnLoad,
      graphShape: summarizeGraphShapeSignature(graphShapeSignature),
      hasFlowInstance: true,
    });
    previousViewportGraphRef.current = nextViewportGraph;
    if (preserveViewport) {
      return clearScheduledFitView;
    }
    lastResizeShellSizeRef.current = null;
    scheduleViewport("graph");
    return clearScheduledFitView;
  }, [flowInstance, graphShapeSignature, anchorNode?.id, shouldFocusAnchorOnLoad, viewportMode]);

  useEffect(() => {
    if (!supportsResizeObserver || !flowInstance || positionedNodes.length === 0 || !canvasShellRef.current) {
      return;
    }
    const initialRect = canvasShellRef.current.getBoundingClientRect();
    lastResizeShellSizeRef.current = {
      width: Math.round(initialRect.width),
      height: Math.round(initialRect.height),
    };
    const observer = new ResizeObserver((entries) => {
      const rect = canvasShellRef.current?.getBoundingClientRect();
      const contentRect = entries[0]?.contentRect;
      const width = Math.round(contentRect?.width ?? rect?.width ?? 0);
      const height = Math.round(contentRect?.height ?? rect?.height ?? 0);
      const lastSize = lastResizeShellSizeRef.current;
      if (lastSize && Math.abs(lastSize.width - width) < 4 && Math.abs(lastSize.height - height) < 4) {
        return;
      }
      lastResizeShellSizeRef.current = { width, height };
      if (resizeViewportTimerRef.current !== null) {
        window.clearTimeout(resizeViewportTimerRef.current);
      }
      resizeViewportTimerRef.current = window.setTimeout(() => {
        resizeViewportTimerRef.current = null;
        scheduleViewport("resize");
      }, RESIZE_SETTLE_DELAY_MS);
    });
    observer.observe(canvasShellRef.current);
    return () => observer.disconnect();
  }, [supportsResizeObserver, flowInstance, positionedNodes.length > 0, shouldFocusAnchorOnLoad, viewportMode]);

  function resolveContextMenuPoint(x: number, y: number): { x: number; y: number } {
    if (typeof window === "undefined") {
      return { x, y };
    }
    return {
      x: Math.min(
        Math.max(CONTEXT_MENU_SAFE_MARGIN, x),
        Math.max(
          CONTEXT_MENU_SAFE_MARGIN,
          window.innerWidth - CONTEXT_MENU_ESTIMATED_WIDTH - CONTEXT_MENU_SAFE_MARGIN,
        ),
      ),
      y: Math.min(
        Math.max(CONTEXT_MENU_SAFE_MARGIN, y),
        Math.max(
          CONTEXT_MENU_SAFE_MARGIN,
          window.innerHeight - CONTEXT_MENU_ESTIMATED_HEIGHT - CONTEXT_MENU_SAFE_MARGIN,
        ),
      ),
    };
  }

  function resolvePanePosition(
    event: ReactMouseEvent | MouseEvent,
    position?: GraphPosition,
  ): GraphPosition | undefined {
    if (position) {
      return position;
    }
    const target = event.currentTarget as HTMLElement | null;
    if (!target) {
      return undefined;
    }
    const rect = target.getBoundingClientRect();
    return {
      x: Math.max(0, event.clientX - rect.left),
      y: Math.max(0, event.clientY - rect.top),
    };
  }

  function focusCanvasShell() {
    canvasShellRef.current?.focus();
  }

  function openPaneMenu(event: ReactMouseEvent | MouseEvent, position?: GraphPosition) {
    event.preventDefault();
    focusCanvasShell();
    const point = resolveContextMenuPoint(event.clientX, event.clientY);
    setContextMenu({
      kind: "pane",
      x: point.x,
      y: point.y,
      position: resolvePanePosition(event, position),
    });
    setSelectedEdgeId(null);
    onSelectNode("");
  }

  function updateLiveDragPositions(updates: Array<{ id: string; position: GraphPosition }>) {
    if (updates.length === 0) {
      return;
    }
    setLiveDragPositions(
      Object.fromEntries(
        updates.map((update) => [
          update.id,
          update.position,
        ]),
      ),
    );
  }

  function openNodeMenu(event: ReactMouseEvent, nodeId: string) {
    event.preventDefault();
    event.stopPropagation();
    focusCanvasShell();
    onSelectNode(nodeId);
    setSelectedEdgeId(null);
    const point = resolveContextMenuPoint(event.clientX, event.clientY);
    setContextMenu({
      kind: "node",
      x: point.x,
      y: point.y,
      nodeId,
    });
  }

  function openEdgeMenu(event: ReactMouseEvent, edgeId: string) {
    event.preventDefault();
    event.stopPropagation();
    focusCanvasShell();
    setSelectedEdgeId(edgeId);
    const point = resolveContextMenuPoint(event.clientX, event.clientY);
    setContextMenu({
      kind: "edge",
      x: point.x,
      y: point.y,
      edgeId,
    });
  }

  const handleNodeClick: NodeMouseHandler = (_, node) => {
    focusCanvasShell();
    onSelectNode(node.id);
    setSelectedEdgeId(null);
    setContextMenu(null);
  };

  const handlePaneClick = () => {
    focusCanvasShell();
    onSelectNode("");
    setSelectedEdgeId(null);
    setContextMenu(null);
  };

  const handleFlowSelectionChange = useCallback(({ nodes: nextNodes }: { nodes: Node[] }) => {
    const nodeIds = nextNodes.map((node) => node.id);
    const nextSignature = nodeIds.join("\u0000");
    if (lastSelectionChangeSignatureRef.current === nextSignature) {
      return;
    }
    lastSelectionChangeSignatureRef.current = nextSignature;
    onSelectionGroupChangeRef.current(nodeIds);
  }, []);

  const selectedEdgeActions = selectedEdgeId
    ? buildEdgeActions({
        edgeId: selectedEdgeId,
        close: () => setSelectedEdgeId(null),
      })
    : [];

  function runSelectedEdgeDeleteAction() {
    if (!selectedEdgeId) {
      return;
    }
    const deleteAction = buildEdgeActions({
      edgeId: selectedEdgeId,
      close: () => setSelectedEdgeId(null),
    }).find((action) => action.id === "delete-edge");
    deleteAction?.onSelect();
  }

  const contextMenuActions =
    contextMenu?.kind === "pane"
      ? buildPaneActions({
          position: contextMenu.position,
          hasGroupedSelection,
          visibleNodeCount: positionedNodes.length,
          close: () => setContextMenu(null),
        })
      : contextMenu?.kind === "node"
        ? buildNodeActions({
            nodeId: contextMenu.nodeId,
            close: () => setContextMenu(null),
          })
        : contextMenu?.kind === "edge"
          ? buildEdgeActions({
              edgeId: contextMenu.edgeId,
              close: () => setContextMenu(null),
            })
          : [];

  return (
    <section className="graph-canvas-panel">
      {header}
      <div
        ref={canvasShellRef}
        className={["graph-canvas-shell", dragShieldingEnabled && isExperimentalDragging ? "is-dragging" : ""].join(" ").trim()}
        data-testid="graph-canvas-shell"
        tabIndex={0}
        onContextMenu={(event) => {
          if (event.defaultPrevented) {
            return;
          }
          openPaneMenu(event);
        }}
        onClick={() => setContextMenu(null)}
        onKeyDown={(event) => {
          if ((event.key !== "Delete" && event.key !== "Backspace") || selectedEdgeId === null) {
            return;
          }
          const target = event.target as HTMLElement | null;
          const editableTarget = target instanceof HTMLInputElement
            || target instanceof HTMLTextAreaElement
            || target?.isContentEditable;
          if (editableTarget) {
            return;
          }
          if (selectedEdgeActions.some((action) => action.id === "delete-edge")) {
            event.preventDefault();
            runSelectedEdgeDeleteAction();
          }
        }}
      >
        {canvasMarkers}

        <ReactFlow
          className="graph-flow-surface"
          nodes={renderedFlowNodes}
          edges={flowEdges}
          nodeTypes={nodeTypes}
          edgeTypes={ROUTED_EDGE_TYPES}
          minZoom={GRAPH_SURFACE_MIN_ZOOM}
          onlyRenderVisibleElements={onlyRenderVisibleElements}
          zoomOnDoubleClick={false}
          selectionOnDrag={false}
          selectionMode={SelectionMode.Partial}
          panOnDrag={true}
          nodesDraggable={layoutEditable}
          nodesConnectable={editable}
          elementsSelectable={true}
          disableKeyboardA11y={true}
          onInit={(instance) => {
            traceLinkGraph("graphFlowSurface.onInit", {
              viewportMode,
              graph: summarizeGraph({ nodes: positionedNodes, edges }),
              anchorNodeId: anchorNode?.id ?? null,
              graphShape: summarizeGraphShapeSignature(graphShapeSignature),
            });
            setFlowInstance(instance);
          }}
          onNodeClick={handleNodeClick}
          onNodeContextMenu={(event, node) => openNodeMenu(event, node.id)}
          onEdgeClick={(event, edge) => {
            event.stopPropagation();
            focusCanvasShell();
            onSelectNode("");
            setSelectedEdgeId(edge.id);
            setContextMenu(null);
          }}
          onEdgeContextMenu={(event, edge) => openEdgeMenu(event, edge.id)}
          onSelectionChange={handleFlowSelectionChange}
          onSelectionDragStop={(_, movedNodes) => {
            if (!layoutEditable) {
              return;
            }
            updateLiveDragPositions(
              movedNodes.map((node) => ({
                id: node.id,
                position: { x: node.position.x, y: node.position.y },
              })),
            );
            onMoveNodes?.(
              movedNodes.map((node) => ({
                id: node.id,
                position: { x: node.position.x, y: node.position.y },
              })),
            );
          }}
          onNodeDragStart={() => {
            if (layoutEditable && dragShieldingEnabled) {
              setIsExperimentalDragging(true);
            }
          }}
          onSelectionDrag={(_, movedNodes) => {
            if (!layoutEditable) {
              return;
            }
            updateLiveDragPositions(
              movedNodes.map((node) => ({
                id: node.id,
                position: { x: node.position.x, y: node.position.y },
              })),
            );
          }}
          onPaneClick={handlePaneClick}
          onPaneContextMenu={(event) =>
            openPaneMenu(
              event,
              flowInstance?.screenToFlowPosition({
                x: event.clientX,
                y: event.clientY,
              }),
            )
          }
          onConnect={(connection: Connection) => {
            if (editable && connection.source && connection.target) {
              onCreateEdge(
                connection.source,
                connection.target,
                connection.sourceHandle ?? null,
                connection.targetHandle ?? null,
              );
            }
          }}
          onNodeDragStop={(_, node) => {
            if (dragShieldingEnabled) {
              setIsExperimentalDragging(false);
            }
            if (layoutEditable) {
              updateLiveDragPositions([
                {
                  id: node.id,
                  position: { x: node.position.x, y: node.position.y },
                },
              ]);
              onMoveNode(node.id, { x: node.position.x, y: node.position.y });
            }
          }}
          onNodeDrag={(_, node) => {
            if (!layoutEditable) {
              return;
            }
            updateLiveDragPositions([
              {
                id: node.id,
                position: { x: node.position.x, y: node.position.y },
              },
            ]);
          }}
        >
          <Controls showInteractive={false} position="bottom-left" />
          <Background gap={20} size={1} />
        </ReactFlow>

        {anchorNode ? (
          <button
            type="button"
            className="canvas-locate-anchor-button"
            onClick={() => focusNodeInViewport(anchorNode, "manualAnchor")}
          >
            定位当前方法
          </button>
        ) : null}

        {positionedNodes.length === 0 ? emptyState : null}

        {hasGroupedSelection ? (
          <div className="canvas-selection-chip" role="status" aria-live="polite">
            已框选 {selectedGroupNodeIds.length} 个节点
          </div>
        ) : null}

        {selectedEdgeActions.length > 0 ? (
          <div className="canvas-edge-action-bar" role="toolbar" aria-label="连线操作">
            {selectedEdgeActions.map((action) => (
              <button
                key={action.id}
                type="button"
                onClick={action.onSelect}
              >
                {action.label}
              </button>
            ))}
          </div>
        ) : null}

        {contextMenu ? <GraphContextMenu x={contextMenu.x} y={contextMenu.y} actions={contextMenuActions} /> : null}
      </div>
    </section>
  );
}
