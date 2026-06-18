import type { MouseEvent as ReactMouseEvent, ReactNode } from "react";
import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import {
  Background,
  Controls,
  ReactFlow,
  SelectionMode,
  ViewportPortal,
  type Connection,
  type Edge,
  type Node,
  type NodeMouseHandler,
  type NodeTypes,
  type PanOnScrollMode,
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
import {
  FIT_VIEW_DELAY_MS,
  FIT_VIEW_RETRY_DELAY_MS,
  GRAPH_SURFACE_MIN_ZOOM,
  READABLE_FIT_MAX_PADDING,
  READABLE_FIT_MAX_ZOOM,
  READABLE_FIT_MIN_PADDING,
  READABLE_FIT_MIN_ZOOM,
  RESIZE_SETTLE_DELAY_MS,
  USER_INTERACTION_GUARD_MS,
  VIEWPORT_EASE,
  VIEWPORT_TRANSITION_DURATION_MS,
  WIDE_GRAPH_FOCUS_ZOOM,
} from "./viewportConfig";
import {
  DEFAULT_NODE_VIEWPORT_SIZE,
  VIEWPORT_TRANSITION,
} from "./graphFlowInteractionModel";
import {
  clamp,
  graphContentBounds,
  graphRenderCommitTraceSignature,
  graphViewportContentSignature,
  hashText,
  locateAnchorButtonLabel,
  positionsMatch,
  reactFlowPaddingPixels,
  resolveAnchorNode,
  resolveNodePosition,
  rounded,
  summarizeGraphShapeSignature,
} from "./graphFlowViewportModel";
import {
  resolveContextMenuPoint,
  resolvePanePositionFromRect,
  type GraphFlowContextMenuState,
} from "./graphFlowContextMenuModel";
import { buildRenderedFlowEdges } from "./graphFlowDragEdges";

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

interface ViewportOverlayContext {
  nodes: LinkGraphNode[];
  edges: LinkGraphEdge[];
}

interface GraphFlowSurfaceProps {
  nodes: LinkGraphNode[];
  edges: LinkGraphEdge[];
  flowNodes: Node[];
  flowEdges: Edge[];
  nodeTypes?: NodeTypes;
  viewportMode?: AnalysisDisplayMode;
  viewportPolicy?: "fit" | "readable-fit";
  viewportResetKey?: string | null;
  anchorNodeId?: string | null;
  selectedNodeId: string | null;
  focusNodeRequest?: GraphFocusRequest | null;
  selectedGroupNodeIds?: string[];
  experiments?: GraphSurfaceExperimentFlags | null;
  editable?: boolean;
  layoutEditable?: boolean;
  panOnDrag?: boolean | number[];
  panOnScroll?: boolean; panOnScrollMode?: "free" | "vertical" | "horizontal"; panOnScrollSpeed?: number;
  zoomOnScroll?: boolean; preventScrolling?: boolean; nodeClickDistance?: number; paneClickDistance?: number;
  groupSelectionEnabled?: boolean;
  header?: ReactNode;
  viewportOverlay?: (context: ViewportOverlayContext) => ReactNode;
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
  fitViewPadding?: number;
  fitViewMaxZoom?: number;
  showViewportControls?: boolean;
  showLocateAnchorButton?: boolean;
}

type ViewportScheduleReason = "graph" | "resize";

function roundedRect(element: Element | null) {
  if (!element) {
    return null;
  }
  const rect = element.getBoundingClientRect();
  return {
    left: Math.round(rect.left),
    top: Math.round(rect.top),
    width: Math.round(rect.width),
    height: Math.round(rect.height),
  };
}

function summarizeElement(selector: string) {
  if (typeof document === "undefined" || typeof window === "undefined") {
    return null;
  }
  const element = document.querySelector(selector);
  if (!element) {
    return null;
  }
  const style = window.getComputedStyle(element);
  return {
    selector,
    rect: roundedRect(element),
    display: style.display,
    visibility: style.visibility,
    opacity: style.opacity,
    position: style.position,
    overflow: style.overflow,
    zIndex: style.zIndex,
    transform: style.transform,
  };
}

export function GraphFlowSurface({
  nodes,
  edges,
  flowNodes,
  flowEdges,
  nodeTypes,
  viewportMode,
  viewportPolicy = "fit",
  viewportResetKey = null,
  anchorNodeId = null,
  selectedNodeId,
  focusNodeRequest = null,
  selectedGroupNodeIds = [],
  experiments = null,
  editable = false,
  layoutEditable = true,
  panOnDrag = true,
  panOnScroll, panOnScrollMode, panOnScrollSpeed,
  zoomOnScroll, preventScrolling, nodeClickDistance, paneClickDistance,
  groupSelectionEnabled = true,
  header = null,
  viewportOverlay,
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
  nodeViewportSize = () => DEFAULT_NODE_VIEWPORT_SIZE,
  fitViewPadding = 0.16,
  fitViewMaxZoom = 1,
  showViewportControls = true,
  showLocateAnchorButton = true,
}: GraphFlowSurfaceProps) {
  const renderStartedAtRef = useRef(0);
  renderStartedAtRef.current = measureStart();
  const supportsResizeObserver = typeof ResizeObserver !== "undefined";
  const [contextMenu, setContextMenu] = useState<GraphFlowContextMenuState | null>(null);
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
  const onSelectionGroupChangeRef = useRef(onSelectionGroupChange);
  const lastSelectionChangeSignatureRef = useRef<string>(selectedGroupNodeIds.join("\u0000"));
  /**
   * Timestamp of the last user-initiated pan/zoom. While
   * {@link USER_INTERACTION_GUARD_MS} hasn't elapsed, automatic viewport moves
   * (incremental re-fit, off-screen selection focus) are suppressed so the
   * canvas stops fighting the user mid-interaction.
   */
  const lastUserViewportMoveRef = useRef(0);

  // P0-3: virtualise large graphs by default; opt back out via experiments flag.
  const onlyRenderVisibleElements = experiments?.onlyRenderVisibleElements !== false;
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
      if (!livePosition) {
        return node;
      }
      return {
        ...node,
        position: livePosition,
      };
    });
  }, [flowNodes, liveDragPositions]);
  const viewportOverlayNodes = useMemo(() => {
    const liveDragNodeIds = Object.keys(liveDragPositions);
    if (liveDragNodeIds.length === 0) {
      return positionedNodes;
    }
    return positionedNodes.map((node) => {
      const livePosition = liveDragPositions[node.id];
      if (!livePosition) {
        return node;
      }
      return {
        ...node,
        position: livePosition,
      };
    });
  }, [positionedNodes, liveDragPositions]);
  const renderedViewportOverlay = viewportOverlay?.({
    nodes: viewportOverlayNodes,
    edges,
  }) ?? null;
  const renderedFlowEdges = useMemo(
    () => buildRenderedFlowEdges(flowEdges, liveDragPositions, selectedEdgeId),
    [flowEdges, liveDragPositions, selectedEdgeId],
  );

  const graphShapeSignature = useMemo(
    () =>
      positionedNodes
        .map((node) => node.id)
        .concat(edges.map((edge) => `${edge.id}:${edge.source}->${edge.target}`))
        .join("|"),
    [positionedNodes, edges],
  );
  const readableFitViewportContentSignature = useMemo(
    () =>
      viewportPolicy === "readable-fit"
        ? graphViewportContentSignature(positionedNodes, edges, nodeViewportSize)
        : "",
    [positionedNodes, edges, nodeViewportSize, viewportPolicy],
  );
  const renderCommitBounds = useMemo(() => graphBounds(positionedNodes), [positionedNodes]);
  const renderCommitTraceSignature = graphRenderCommitTraceSignature({
    graphShapeSignature,
    bounds: renderCommitBounds,
    selectedNodeId,
    selectedGroupNodeCount: selectedGroupNodeIds.length,
    supportsResizeObserver,
  });
  const effectiveViewportResetKey = viewportMode === "CLASS_DIAGRAM"
    ? viewportResetKey
    : viewportPolicy === "readable-fit"
      ? `${viewportResetKey ?? ""}:${hashText(readableFitViewportContentSignature)}`
    : viewportResetKey;

  /**
   * P0-1: was the viewport recently moved by the user? While this is true we
   * suppress automatic fit/selection moves so the canvas does not "jump".
   */
  const isWithinUserInteractionGuard = useCallback(() => {
    if (lastUserViewportMoveRef.current === 0) {
      return false;
    }
    return Date.now() - lastUserViewportMoveRef.current < USER_INTERACTION_GUARD_MS;
  }, []);

  useEffect(() => {
    const liveDragNodeIds = Object.keys(liveDragPositions);
    if (liveDragNodeIds.length === 0) {
      return;
    }
    // Drop live-drag overlays once the parent commits the same coordinates.
    const committedPositions = new Map(
      flowNodes.map((node) => [node.id, node.position]),
    );
    const remaining = Object.fromEntries(
      liveDragNodeIds
        .filter((nodeId) => !positionsMatch(committedPositions.get(nodeId), liveDragPositions[nodeId]))
        .map((nodeId) => [nodeId, liveDragPositions[nodeId]!]),
    );
    if (Object.keys(remaining).length === liveDragNodeIds.length) {
      return;
    }
    setLiveDragPositions(remaining);
  }, [flowNodes, liveDragPositions]);

  useEffect(() => {
    traceLinkGraph("graphFlowSurface.renderCommitted", {
      graph: summarizeGraph({ nodes: positionedNodes, edges }),
      selectedNodeId,
      selectedGroupNodeCount: selectedGroupNodeIds.length,
      bounds: renderCommitBounds,
      supportsResizeObserver,
      durationMs: measureDuration(renderStartedAtRef.current),
    });
  }, [renderCommitTraceSignature]);

  useEffect(() => {
    if (typeof window === "undefined" || window.__linkGraphDebugEnabled !== true) {
      return;
    }
    const timer = window.setTimeout(() => {
      const shell = canvasShellRef.current;
      const nodeElements = Array.from(document.querySelectorAll(".react-flow__node"));
      const cardElements = Array.from(document.querySelectorAll(".uml-class-card"));
      const shellRect = shell?.getBoundingClientRect() ?? null;
      const visibleNodeCountInShell = shellRect
        ? nodeElements.filter((element) => {
            const rect = element.getBoundingClientRect();
            return rect.width > 0
              && rect.height > 0
              && rect.right >= shellRect.left
              && rect.left <= shellRect.right
              && rect.bottom >= shellRect.top
              && rect.top <= shellRect.bottom;
          }).length
        : 0;
      traceLinkGraph("graphFlowSurface.domProbe", {
        viewportMode,
        graph: summarizeGraph({ nodes: positionedNodes, edges }),
        flowNodes: renderedFlowNodes.length,
        flowEdges: renderedFlowEdges.length,
        selectors: [
          summarizeElement("[data-testid='graph-canvas-shell']"),
          summarizeElement(".class-diagram-overlay"),
          summarizeElement(".graph-flow-surface"),
          summarizeElement(".react-flow__renderer"),
          summarizeElement(".react-flow__viewport"),
          summarizeElement(".react-flow__viewport-portal"),
          summarizeElement(".react-flow__controls"),
          summarizeElement(".canvas-locate-anchor-button"),
        ],
        reactFlowNodeCount: nodeElements.length,
        umlClassCardCount: cardElements.length,
        visibleNodeCountInShell,
        firstNodeRect: roundedRect(nodeElements[0] ?? null),
        firstCardRect: roundedRect(cardElements[0] ?? null),
      });
    }, 180);
    return () => window.clearTimeout(timer);
  }, [edges, positionedNodes, renderedFlowEdges.length, renderedFlowNodes.length, viewportMode]);

  function focusNodeInViewport(node: LinkGraphNode, reason: string) {
    if (!flowInstance || !node.position) {
      return;
    }
    const size = nodeViewportSize(node);
    const zoomOptions = reason === "manualAnchor" || reason === "explicitNodeFocus"
      ? { ...VIEWPORT_TRANSITION }
      : {
          zoom: WIDE_GRAPH_FOCUS_ZOOM,
          ...VIEWPORT_TRANSITION,
        };
    traceLinkGraph("graphFlowSurface.viewport.focusNode", {
      reason,
      nodeId: node.id,
      nodePosition: node.position,
      zoomOptions,
    });
    flowInstance.setCenter(
      Math.round((node.position.x + size.width / 2) * 10) / 10,
      Math.round((node.position.y + size.height / 2) * 10) / 10,
      zoomOptions,
    );
  }

  function fitReadableContentInViewport(reason: ViewportScheduleReason): boolean {
    if (!flowInstance) {
      return false;
    }
    const shellRect = canvasShellRef.current?.getBoundingClientRect();
    const shellWidth = shellRect?.width ?? 0;
    const shellHeight = shellRect?.height ?? 0;
    const bounds = graphContentBounds(positionedNodes, edges, nodeViewportSize);
    if (!bounds || shellWidth <= 0 || shellHeight <= 0) {
      traceLinkGraph("graphFlowSurface.viewport.readableFit.skipped", {
        reason,
        hasBounds: Boolean(bounds),
        shell: shellRect ? roundedRect(canvasShellRef.current) : null,
      });
      return false;
    }
    const horizontalPadding = clamp(
      reactFlowPaddingPixels(shellWidth, fitViewPadding),
      READABLE_FIT_MIN_PADDING,
      Math.min(READABLE_FIT_MAX_PADDING, Math.max(0, shellWidth / 3)),
    );
    const verticalPadding = clamp(
      reactFlowPaddingPixels(shellHeight, fitViewPadding),
      READABLE_FIT_MIN_PADDING,
      Math.min(READABLE_FIT_MAX_PADDING, Math.max(0, shellHeight / 3)),
    );
    const availableWidth = Math.max(1, shellWidth - horizontalPadding * 2);
    const availableHeight = Math.max(1, shellHeight - verticalPadding * 2);
    const zoom = Math.min(
      Math.min(fitViewMaxZoom, READABLE_FIT_MAX_ZOOM),
      Math.max(
        READABLE_FIT_MIN_ZOOM,
        Math.min(availableWidth / bounds.width, availableHeight / bounds.height),
      ),
    );
    const centerX = bounds.minX + bounds.width / 2;
    const centerY = bounds.minY + bounds.height / 2;
    traceLinkGraph("graphFlowSurface.viewport.readableFit.apply", {
      reason,
      bounds: {
        minX: rounded(bounds.minX),
        minY: rounded(bounds.minY),
        maxX: rounded(bounds.maxX),
        maxY: rounded(bounds.maxY),
        width: rounded(bounds.width),
        height: rounded(bounds.height),
      },
      shell: {
        width: rounded(shellWidth),
        height: rounded(shellHeight),
      },
      padding: {
        x: rounded(horizontalPadding),
        y: rounded(verticalPadding),
      },
      zoom: rounded(zoom),
      center: {
        x: rounded(centerX),
        y: rounded(centerY),
      },
    });
    flowInstance.setCenter(centerX, centerY, { zoom, ...VIEWPORT_TRANSITION });
    return true;
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
    // P0-1: a resize-induced re-fit is user-driven; never override it. But a
    // graph-induced re-fit right after the user panned/zoomed would fight them.
    if (reason === "graph" && isWithinUserInteractionGuard()) {
      traceLinkGraph("graphFlowSurface.scheduleViewport.suppressedByUserInteraction", {
        reason,
        viewportMode,
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
      let branch: "flowchartAnchor" | "wideGraphAnchor" | "readableFit" | "fitView" = "fitView";
      if (viewportPolicy === "readable-fit") {
        branch = "readableFit";
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
        if (fitReadableContentInViewport(reason)) {
          return;
        }
        branch = "fitView";
      }
      if (shouldFocusAnchorOnLoad && anchorNode?.position && viewportMode !== "ARCHITECTURE_GRAPH" && viewportMode !== "CLASS_DIAGRAM") {
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
        padding: fitViewPadding,
        ...VIEWPORT_TRANSITION,
        maxZoom: fitViewMaxZoom,
        includeHiddenNodes: true,
      });
    };
    if (
      (viewportMode === "CLASS_DIAGRAM" && reason === "graph")
      || (viewportPolicy === "readable-fit" && reason === "graph")
    ) {
      updateViewport();
      return;
    }
    primaryFitViewTimerRef.current = window.setTimeout(() => {
      updateViewport();
      primaryFitViewTimerRef.current = null;
    }, FIT_VIEW_DELAY_MS);
    if (!shouldFocusAnchorOnLoad && !useFlowchartViewport && viewportPolicy !== "readable-fit" && reason === "graph") {
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

  // Selecting a node never moves the viewport — the user is in full control of
  // pan/zoom. (Previously a "nudge into view" fired on selection, which users
  // experienced as the canvas jumping when they clicked a node.)

  useEffect(() => {
    if (!flowInstance) {
      return clearScheduledFitView;
    }
    const nextViewportGraph: GraphViewportSnapshot = {
      anchorNodeId: anchorNode?.id ?? null,
      nodeIds: new Set(positionedNodes.map((node) => node.id)),
      edgeIds: new Set(edges.map((edge) => edge.id)),
      resetKey: effectiveViewportResetKey,
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
      previousResetKey: previousViewportGraphRef.current?.resetKey ?? null,
      resetKey: viewportResetKey,
      effectiveResetKey: effectiveViewportResetKey,
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
  }, [
    flowInstance,
    graphShapeSignature,
    anchorNode?.id,
    shouldFocusAnchorOnLoad,
    viewportMode,
    viewportPolicy,
    effectiveViewportResetKey,
    fitViewPadding,
    fitViewMaxZoom,
  ]);

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
  }, [supportsResizeObserver, flowInstance, positionedNodes.length > 0, shouldFocusAnchorOnLoad, viewportMode, viewportPolicy]);

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
    return resolvePanePositionFromRect(event.clientX, event.clientY, target.getBoundingClientRect());
  }

  function resolveMenuPointForViewport(event: ReactMouseEvent | MouseEvent): { x: number; y: number } {
    return resolveContextMenuPoint(
      event.clientX,
      event.clientY,
      typeof window === "undefined" ? null : { width: window.innerWidth, height: window.innerHeight },
    );
  }

  function focusCanvasShell() {
    canvasShellRef.current?.focus();
  }

  function openPaneMenu(event: ReactMouseEvent | MouseEvent, position?: GraphPosition) {
    event.preventDefault();
    focusCanvasShell();
    const point = resolveMenuPointForViewport(event);
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
    setLiveDragPositions((current) => ({
      ...current,
      ...Object.fromEntries(
        updates.map((update) => [
          update.id,
          update.position,
        ]),
      ),
    }));
  }

  function openNodeMenu(event: ReactMouseEvent, nodeId: string) {
    event.preventDefault();
    event.stopPropagation();
    focusCanvasShell();
    onSelectNode(nodeId);
    setSelectedEdgeId(null);
    const point = resolveMenuPointForViewport(event);
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
    const point = resolveMenuPointForViewport(event);
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

  // P0-1: track user-driven viewport moves so the auto-fit logic can stand down.
  const handleViewportChange = useCallback((event: MouseEvent | TouchEvent | null) => {
    if (!event) {
      return;
    }
    lastUserViewportMoveRef.current = Date.now();
  }, []);

  const handleFlowSelectionChange = useCallback(({ nodes: nextNodes }: { nodes: Node[] }) => {
    const nodeIds = groupSelectionEnabled ? nextNodes.map((node) => node.id) : [];
    const nextSignature = nodeIds.join("\u0000");
    if (lastSelectionChangeSignatureRef.current === nextSignature) {
      return;
    }
    lastSelectionChangeSignatureRef.current = nextSignature;
    onSelectionGroupChangeRef.current(nodeIds);
  }, [groupSelectionEnabled]);

  const selectedEdgeActions = selectedEdgeId
    ? buildEdgeActions({
      edgeId: selectedEdgeId,
      close: () => setSelectedEdgeId(null),
    })
    : [];
  const hasHeader = header !== null && header !== undefined && header !== false;

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
    <section className={hasHeader ? "graph-canvas-panel has-header" : "graph-canvas-panel without-header"}>
      {header}
      <div
        ref={canvasShellRef}
        className={["graph-canvas-shell", dragShieldingEnabled && isExperimentalDragging ? "is-dragging" : ""].join(" ").trim()}
        data-testid="graph-canvas-shell"
        data-graph-density={positionedNodes.length > 80 ? "dense" : "normal"}
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
        <ReactFlow
          className="graph-flow-surface"
          nodes={renderedFlowNodes}
          edges={renderedFlowEdges}
          nodeTypes={nodeTypes}
          edgeTypes={ROUTED_EDGE_TYPES}
          minZoom={viewportPolicy === "readable-fit" ? READABLE_FIT_MIN_ZOOM : GRAPH_SURFACE_MIN_ZOOM}
          onlyRenderVisibleElements={onlyRenderVisibleElements}
          zoomOnDoubleClick={false}
          selectionOnDrag={false}
          selectionMode={SelectionMode.Partial}
          panOnDrag={panOnDrag}
          {...{ panOnScroll, panOnScrollMode: panOnScrollMode as PanOnScrollMode | undefined, panOnScrollSpeed, zoomOnScroll, preventScrolling, nodeClickDistance, paneClickDistance }}
          multiSelectionKeyCode={groupSelectionEnabled ? undefined : null}
          selectNodesOnDrag={groupSelectionEnabled}
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
          // P0-1: stamp the interaction guard whenever the user pans/zooms so
          // the auto-fit effects know to yield.
          onMove={handleViewportChange}
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
            if (!layoutEditable || !groupSelectionEnabled) {
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
            if (!layoutEditable || !groupSelectionEnabled) {
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
          {renderedViewportOverlay ? <ViewportPortal>{renderedViewportOverlay}</ViewportPortal> : null}
          {showViewportControls ? <Controls showInteractive={false} position="bottom-left" /> : null}
          <Background gap={20} size={1} />
        </ReactFlow>

        {anchorNode && showLocateAnchorButton ? (
          <button
            type="button"
            className="canvas-locate-anchor-button"
            onClick={() => focusNodeInViewport(anchorNode, "manualAnchor")}
          >
            {locateAnchorButtonLabel(viewportMode ?? "FACT_GRAPH")}
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
