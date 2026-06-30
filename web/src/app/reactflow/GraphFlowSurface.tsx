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

/** 节点点击事件判定的最小位移阈值，小于该值的点击不会触发选中以避免误触。 */
const DEFAULT_NODE_CLICK_DISTANCE = 6;

/**
 * 画布右键菜单上下文，承载菜单弹出位置、当前是否处于多选状态以及可见节点数量等信息，
 * 供外部构造画布级动作列表时使用。
 */
interface PaneActionContext {
  position?: GraphPosition;
  hasGroupedSelection: boolean;
  visibleNodeCount: number;
  close: () => void;
}

/**
 * 节点右键菜单上下文，标识当前触发菜单的节点，并提供关闭菜单的回调。
 */
interface NodeActionContext {
  nodeId: string;
  close: () => void;
}

/**
 * 连线右键菜单上下文，标识当前触发菜单的边，并提供关闭菜单的回调。
 */
interface EdgeActionContext {
  edgeId: string;
  close: () => void;
}

/**
 * 视口覆盖层渲染上下文，把经过拖拽位置修正后的节点与边传给外部以自定义叠加在画布上的内容。
 */
interface ViewportOverlayContext {
  nodes: LinkGraphNode[];
  edges: LinkGraphEdge[];
}

/**
 * 画布组件对外暴露的契约，集中接收图谱数据、视口策略、交互开关、菜单构建回调以及各类事件回调，
 * 是 LinkGraph 前端承载节点/边渲染、视口调度、右键菜单和拖拽编辑的中枢组件。
 */
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

/** 视口重算触发来源：图谱数据变化或画布尺寸变化，用于区分自动适配是否应让位于用户操作。 */
type ViewportScheduleReason = "graph" | "resize";

/**
 * 对 DOM 元素的边界框做取整，便于在调试日志中以稳定、可比较的整数形式输出节点尺寸。
 */
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

/**
 * 采集选择器命中的 DOM 元素的布局与样式摘要，仅在调试模式开启时用于排查画布层级、可见性等渲染异常。
 */
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

/**
 * 图谱画布主组件：基于 @xyflow/react 渲染节点与边，统筹视口调度（初次适配、增量保持、可读模式聚焦、
 * 用户拖拽后的让位逻辑）、右键菜单、框选、连线编辑、节点拖拽，并把交互事件回传给上层应用。
 */
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
  zoomOnScroll, preventScrolling, nodeClickDistance = DEFAULT_NODE_CLICK_DISTANCE, paneClickDistance,
  groupSelectionEnabled = true,
  header = null,
  viewportOverlay,
  emptyState,
  buildPaneActions,
  buildNodeActions,
  buildEdgeActions,
  onSelectNode,
  onSelectionGroupChange = () => undefined,
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
  const lastRenderCommitTraceSignatureRef = useRef<string | null>(null);
  const onSelectionGroupChangeRef = useRef(onSelectionGroupChange);
  const lastSelectionChangeSignatureRef = useRef<string>(selectedGroupNodeIds.join("\u0000"));
  /**
   * 最近一次用户主动平移/缩放的时间戳。
   * 在交互保护窗口内，自动视口移动会让位于用户操作，避免画布在交互中跳动。
   */
  const lastUserViewportMoveRef = useRef(0);

  // P0-3：默认启用大图虚拟化，可通过 experiments 标记回退。
  const onlyRenderVisibleElements = experiments?.onlyRenderVisibleElements !== false;
  const dragShieldingEnabled = experiments?.dragShielding === true;

  // P1-6: mount/unmount trace 改用 ref 捕获最新 graph，避免 [] deps 导致 stale closure。
  // ref 初始化 + sync effect + lifecycle effect 都放在 positionedNodes/anchorNode/
  // graphShapeSignature 声明之后，否则会触发 TDZ。
  // 实际代码见下方（搜索 lifecycleTraceRef）。

  useEffect(() => {
    onSelectionGroupChangeRef.current = onSelectionGroupChange;
  }, [onSelectionGroupChange]);

  useEffect(() => {
    if (!dragShieldingEnabled && isExperimentalDragging) {
      setIsExperimentalDragging(false);
    }
  }, [dragShieldingEnabled, isExperimentalDragging]);

  /** 对传入节点补齐坐标，缺失坐标的节点按索引兜底推导，保证渲染前所有节点都有有效位置。 */
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

  /** 解析当前锚点节点：优先匹配显式锚点 ID，否则回退到当前选中节点。 */
  const anchorNode = useMemo(
    () => resolveAnchorNode(positionedNodes, anchorNodeId, selectedNodeId),
    [positionedNodes, anchorNodeId, selectedNodeId],
  );
  const hasGroupedSelection = selectedGroupNodeIds.length > 1;
  /** 渲染用节点列表：将拖拽过程中的实时坐标覆盖到 flowNodes，让拖拽视觉反馈先行于父组件提交。 */
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
  /** 视口覆盖层使用的节点列表：与渲染节点一致，把实时拖拽坐标叠加到 positionedNodes 上。 */
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
  /** 渲染用连线列表：依据实时拖拽位置和当前选中边构建最终交给 React Flow 的边集合。 */
  const renderedFlowEdges = useMemo(
    () => buildRenderedFlowEdges(flowEdges, liveDragPositions, selectedEdgeId),
    [flowEdges, liveDragPositions, selectedEdgeId],
  );

  /** 图谱形态签名：节点 ID 与边端点拼接的稳定字符串，用于检测图结构是否发生实质变化。 */
  const graphShapeSignature = useMemo(
    () =>
      positionedNodes
        .map((node) => node.id)
        .concat(edges.map((edge) => `${edge.id}:${edge.source}->${edge.target}`))
        .join("|"),
    [positionedNodes, edges],
  );
  /** 可读适配模式下的内容签名：把节点尺寸、边信息纳入指纹，判断内容是否需要重新适配视口。 */
  const readableFitViewportContentSignature = useMemo(
    () =>
      viewportPolicy === "readable-fit"
        ? graphViewportContentSignature(positionedNodes, edges, nodeViewportSize)
        : "",
    [positionedNodes, edges, nodeViewportSize, viewportPolicy],
  );
  /** 当前图谱的几何边界框，用于渲染埋点和调试输出。 */
  const renderCommitBounds = useMemo(() => graphBounds(positionedNodes), [positionedNodes]);
  /** 渲染提交埋点签名：综合图谱形态、边界、选中态等，控制渲染完成埋点的去重触发。 */
  const renderCommitTraceSignature = graphRenderCommitTraceSignature({
    graphShapeSignature,
    bounds: renderCommitBounds,
    selectedNodeId,
    selectedGroupNodeCount: selectedGroupNodeIds.length,
    supportsResizeObserver,
  });

  // P1-6: mount/unmount trace 需要 [] deps 保证只触发一次，但直接闭包会捕获首渲染的 graph。
  // 用 ref 在每次渲染后同步最新值，lifecycle effect 读 ref.current 即可拿到卸载时的状态。
  const lifecycleTraceRef = useRef({
    viewportMode,
    positionedNodes,
    edges,
    anchorNode,
    graphShapeSignature,
  });
  useEffect(() => {
    lifecycleTraceRef.current = { viewportMode, positionedNodes, edges, anchorNode, graphShapeSignature };
  }, [viewportMode, positionedNodes, edges, anchorNode, graphShapeSignature]);

  useEffect(() => {
    const snapshot = lifecycleTraceRef.current;
    traceLinkGraph("graphFlowSurface.lifecycle.mount", {
      viewportMode: snapshot.viewportMode,
      graph: summarizeGraph({ nodes: snapshot.positionedNodes, edges: snapshot.edges }),
      anchorNodeId: snapshot.anchorNode?.id ?? null,
      graphShape: summarizeGraphShapeSignature(snapshot.graphShapeSignature),
    });
    return () => {
      // 卸载时 ref.current 是最近一次 sync effect 写入的最新值
      const current = lifecycleTraceRef.current;
      traceLinkGraph("graphFlowSurface.lifecycle.unmount", {
        viewportMode: current.viewportMode,
        graph: summarizeGraph({ nodes: current.positionedNodes, edges: current.edges }),
        anchorNodeId: current.anchorNode?.id ?? null,
        graphShape: summarizeGraphShapeSignature(current.graphShapeSignature),
      });
    };
  }, []);

  // 生效的视口重置键：类图模式沿用原始键，可读适配模式叠加内容签名以更精细判断是否需要重置。
  const effectiveViewportResetKey = viewportMode === "CLASS_DIAGRAM"
    ? viewportResetKey
    : viewportPolicy === "readable-fit"
      ? `${viewportResetKey ?? ""}:${hashText(readableFitViewportContentSignature)}`
    : viewportResetKey;

  /**
   * 判断用户近期是否主动移动过视口；为 true 时会抑制自动适配，避免画布跳动。
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
    // 父组件提交同坐标后移除实时拖拽覆盖，避免覆盖层继续接管渲染位置。
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
    if (lastRenderCommitTraceSignatureRef.current === renderCommitTraceSignature) {
      return;
    }
    lastRenderCommitTraceSignatureRef.current = renderCommitTraceSignature;
    traceLinkGraph("graphFlowSurface.renderCommitted", {
      graph: summarizeGraph({ nodes: positionedNodes, edges }),
      selectedNodeId,
      selectedGroupNodeCount: selectedGroupNodeIds.length,
      bounds: renderCommitBounds,
      supportsResizeObserver,
      durationMs: measureDuration(renderStartedAtRef.current),
    });
  }, [
    edges,
    positionedNodes,
    renderCommitBounds,
    renderCommitTraceSignature,
    selectedGroupNodeIds.length,
    selectedNodeId,
    supportsResizeObserver,
  ]);

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

  /**
   * 以平滑过渡方式将视口中心对准指定节点，依据触发原因决定使用全幅缩放还是聚焦缩放，并输出调试埋点。
   */
  const focusNodeInViewport = useCallback((node: LinkGraphNode, reason: string) => {
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
  }, [flowInstance, nodeViewportSize]);

  /**
   * 按“可读适配”策略计算并应用视口：根据内容边界和画布尺寸推导缩放与中心点，
   * 保证节点既不超出可见区域又满足最小可读缩放要求，返回是否成功应用。
   */
  const fitReadableContentInViewport = useCallback((reason: ViewportScheduleReason): boolean => {
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
  }, [edges, fitViewMaxZoom, fitViewPadding, flowInstance, nodeViewportSize, positionedNodes]);

  /**
   * 取消所有待执行的视口重算定时器，避免叠加触发导致视口反复跳动。
   */
  const clearScheduledFitView = useCallback(() => {
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
  }, []);

  /**
   * 视口调度核心：根据触发原因、视口模式与锚点策略，在“聚焦锚点 / 可读适配 / 默认 fitView”等分支中选择，
   * 同时尊重用户最近的交互保护窗口，必要时通过延时重试以确保布局稳定后再应用。
   */
  const scheduleViewport = useCallback((reason: ViewportScheduleReason) => {
    if (!flowInstance || positionedNodes.length === 0) {
      traceLinkGraph("graphFlowSurface.scheduleViewport.skipped", {
        reason,
        hasFlowInstance: Boolean(flowInstance),
        nodeCount: positionedNodes.length,
      });
      return;
    }
    // P0-1：resize 触发的重新适配来自用户环境变化；图谱触发的适配在用户刚交互后需要让位。
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
  }, [
    anchorNode,
    clearScheduledFitView,
    edges,
    fitReadableContentInViewport,
    fitViewMaxZoom,
    fitViewPadding,
    flowInstance,
    focusNodeInViewport,
    graphShapeSignature,
    isWithinUserInteractionGuard,
    positionedNodes,
    shouldFocusAnchorOnLoad,
    viewportMode,
    viewportPolicy,
  ]);

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
  }, [flowInstance, focusNodeInViewport, focusNodeRequest, positionedNodes]);

  // 选中节点不再移动视口，平移/缩放完全由用户控制，避免点击节点时画布跳动。

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
    anchorNode?.id,
    clearScheduledFitView,
    edges,
    effectiveViewportResetKey,
    flowInstance,
    graphShapeSignature,
    positionedNodes,
    scheduleViewport,
    shouldFocusAnchorOnLoad,
    viewportMode,
    viewportResetKey,
  ]);

  const hasPositionedNodes = positionedNodes.length > 0;

  useEffect(() => {
    if (!supportsResizeObserver || !flowInstance || !hasPositionedNodes || !canvasShellRef.current) {
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
  }, [supportsResizeObserver, flowInstance, hasPositionedNodes, scheduleViewport]);

  /**
   * 解析右键事件在画布坐标系下的位置：优先使用传入位置，否则依据事件源 DOM 的边界框推算流式坐标。
   */
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

  /**
   * 将右键事件的视口坐标转换为菜单可显示坐标，并避免菜单超出窗口边界。
   */
  function resolveMenuPointForViewport(event: ReactMouseEvent | MouseEvent): { x: number; y: number } {
    return resolveContextMenuPoint(
      event.clientX,
      event.clientY,
      typeof window === "undefined" ? null : { width: window.innerWidth, height: window.innerHeight },
    );
  }

  /**
   * 让画布容器获得焦点，使后续的键盘事件（如删除连线）可以被画布捕获。
   */
  function focusCanvasShell() {
    canvasShellRef.current?.focus();
  }

  /**
   * 打开画布空白处的右键菜单：取消默认行为、聚焦画布、清空选区并按上下文构造菜单状态。
   */
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

  /**
   * 累积更新拖拽过程中的临时节点坐标，让画布在父组件提交新坐标前先以预览位置渲染。
   */
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

  /**
   * 打开节点右键菜单：阻止冒泡、选中目标节点并按节点上下文构造菜单状态。
   */
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

  /**
   * 打开连线右键菜单：阻止冒泡、选中目标连线并按连线上下文构造菜单状态。
   */
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

  /** 节点单击处理：聚焦画布、选中该节点、清空连线选中和右键菜单。 */
  const handleNodeClick: NodeMouseHandler = (_, node) => {
    focusCanvasShell();
    onSelectNode(node.id);
    setSelectedEdgeId(null);
    setContextMenu(null);
  };

  /** 画布空白单击处理：聚焦画布并清空节点/连线选中状态及右键菜单。 */
  const handlePaneClick = () => {
    focusCanvasShell();
    onSelectNode("");
    setSelectedEdgeId(null);
    setContextMenu(null);
  };

  /**
   * 视口移动事件处理：只要用户存在平移/缩放操作，就刷新交互保护时间戳，
   * 使后续自动适配在保护期内主动让位。
   */
  const handleViewportChange = useCallback((event: MouseEvent | TouchEvent | null) => {
    if (!event) {
      return;
    }
    lastUserViewportMoveRef.current = Date.now();
  }, []);

  /**
   * 框选变化处理：根据多选开关把 React Flow 选中的节点转换成节点 ID 列表，
   * 通过签名比对避免无变化的回调抖动，再向上层广播框选结果。
   */
  const handleFlowSelectionChange = useCallback(({ nodes: nextNodes }: { nodes: Node[] }) => {
    const nodeIds = groupSelectionEnabled ? nextNodes.map((node) => node.id) : [];
    const nextSignature = nodeIds.join("\u0000");
    if (lastSelectionChangeSignatureRef.current === nextSignature) {
      return;
    }
    lastSelectionChangeSignatureRef.current = nextSignature;
    onSelectionGroupChangeRef.current(nodeIds);
  }, [groupSelectionEnabled]);

  /** 当前选中连线对应的操作列表，供操作工具栏渲染使用。 */
  const selectedEdgeActions = selectedEdgeId
    ? buildEdgeActions({
      edgeId: selectedEdgeId,
      close: () => setSelectedEdgeId(null),
    })
    : [];
  const hasHeader = header !== null && header !== undefined && header !== false;

  /**
   * 触发当前选中连线的删除动作，供键盘 Delete/Backspace 快捷键调用。
   */
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

  /** 根据右键菜单当前类型，分别构造画布/节点/连线对应的菜单项列表。 */
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
          // P0-1：用户平移/缩放时刷新交互保护，让自动适配主动让位。
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
