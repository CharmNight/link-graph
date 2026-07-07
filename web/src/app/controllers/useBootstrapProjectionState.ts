import { useRef, type Dispatch, type MutableRefObject, type SetStateAction } from "react";
import { measureDuration, measureStart, summarizeBootstrapState, summarizeGraph, traceLinkGraph } from "../debug";
import { applyLayoutOnlyNodePositions, resolveNodePosition, syncNodePosition } from "../graphState";
import type {
  AnalysisDisplayMode,
  ArchitectureGraphViewDocument,
  AsyncRequestState,
  ClassDiagramViewDocument,
  FactGraphViewDocument,
  FlowchartViewDocument,
  InvocationExpansionSceneState,
  LinkGraphBootstrapState,
  LinkGraphDocument,
  LinkGraphLayoutState,
  LinkGraphNode,
  LinkGraphSceneId,
  LinkGraphSceneState,
  ResourceRelationViewDocument,
  ReviewGraphViewDocument,
  SourceNavigationState,
} from "../types";
import type {
  WorkbenchCanvasState,
  WorkbenchProjectionState,
} from "./useWorkbenchState";
import { resolveIndexedGraphRequestStates } from "./useWorkbenchState";

/** 默认的分析展示模式，在 bootstrap 快照未携带模式信息时兜底使用，优先选择流程图视图。 */
const DEFAULT_ANALYSIS_DISPLAY_MODE: AnalysisDisplayMode = "FLOWCHART";

/** 视图文档的最小结构契约，复用于各种布局/场景合并工具函数，避免每个具体视图类型重复定义同样的形状。 */
type GraphViewDocumentLike = {
  visibleGraph: LinkGraphDocument;
  fullGraph: LinkGraphDocument;
  anchorNodeId?: string | null;
};

/** 工作台六类场景视图文档的聚合快照，用于按场景 id 统一获取对应视图的节点或锚点信息。 */
type SceneGraphViews = {
  factGraphView: FactGraphViewDocument;
  flowchartView: FlowchartViewDocument;
  resourceRelationView: ResourceRelationViewDocument;
  architectureGraphView: ArchitectureGraphViewDocument;
  classDiagramView: ClassDiagramViewDocument;
  reviewGraphView: ReviewGraphViewDocument;
};

/** useBootstrapProjectionState hook 的入参集合：包含对外部可变引用（节点、边、草稿、锚点等 ref）、画布与投影状态写入器，以及一组负责从 bootstrap 快照解析派生视图/图/请求状态的策略函数。 */
interface UseBootstrapProjectionStateArgs {
  nodesRef: MutableRefObject<LinkGraphNode[]>;
  edgesRef: MutableRefObject<LinkGraphDocument["edges"]>;
  draftGraphRef: MutableRefObject<LinkGraphDocument | null>;
  anchorNodeIdRef: MutableRefObject<string | null>;
  analysisDisplayModeRef: MutableRefObject<AnalysisDisplayMode>;
  semanticRevisionRef: MutableRefObject<number | null>;
  layoutRevisionRef: MutableRefObject<number | null>;
  canvasState: WorkbenchCanvasState;
  setCanvasState: Dispatch<SetStateAction<WorkbenchCanvasState>>;
  projectionState: WorkbenchProjectionState;
  setProjectionState: Dispatch<SetStateAction<WorkbenchProjectionState>>;
  explanationLocalOverrideRef: MutableRefObject<boolean>;
  setSelectionGroupNodeIds: Dispatch<SetStateAction<string[]>>;
  setDiffTargetItemIds: Dispatch<SetStateAction<string[]>>;
  syncManualNodeIdCounters: (nextNodes: Array<{ id: string }>) => void;
  resolveSourceNavigationState: (state: LinkGraphBootstrapState) => SourceNavigationState;
  resolveFactGraphView: (state: LinkGraphBootstrapState) => FactGraphViewDocument;
  resolveFlowchartView: (state: LinkGraphBootstrapState) => FlowchartViewDocument;
  resolveResourceRelationView: (state: LinkGraphBootstrapState) => ResourceRelationViewDocument;
  resolveArchitectureGraphView: (state: LinkGraphBootstrapState) => ArchitectureGraphViewDocument;
  resolveClassDiagramView: (state: LinkGraphBootstrapState) => ClassDiagramViewDocument;
  resolveReviewGraphView: (state: LinkGraphBootstrapState) => ReviewGraphViewDocument;
  resolveWorkingGraph: (state: LinkGraphBootstrapState) => LinkGraphDocument;
  resolveActiveViewDocument: (
    state: LinkGraphBootstrapState & {
      factGraphView: FactGraphViewDocument;
      flowchartView: FlowchartViewDocument;
      resourceRelationView: ResourceRelationViewDocument;
      architectureGraphView: ArchitectureGraphViewDocument;
      classDiagramView: ClassDiagramViewDocument;
      reviewGraphView: ReviewGraphViewDocument;
    },
    displayMode: AnalysisDisplayMode,
  ) => { visibleGraph: LinkGraphDocument };
  applyBootstrapRoutesToViewDocument: <T extends GraphViewDocumentLike>(
    nextView: T,
    currentView: T,
  ) => T;
  reuseCurrentViewGraphs: <T extends GraphViewDocumentLike>(
    nextView: T,
    currentView: T,
    reuseCurrentGraphs: boolean,
  ) => T;
  resolveAnchorNodeId: (nodes: LinkGraphNode[], preferredNodeId: string | null) => string | null;
  resolveWorkspaceBaseGraph: (state: LinkGraphBootstrapState) => LinkGraphDocument | null;
  resolveSemanticFactGraph: (state: LinkGraphBootstrapState) => LinkGraphDocument | null;
  resolveDesignBaselineGraph: (state: LinkGraphBootstrapState) => LinkGraphDocument | null;
  resolveRequestState: (state?: AsyncRequestState | null) => AsyncRequestState;
}

/** 构造一个没有任何选中、布局或折叠信息的空白场景状态，作为缺失场景数据时的统一兜底。 */
function createEmptySceneState(): LinkGraphSceneState {
  return {
    selectedNodeId: null,
    anchorNodeId: null,
    layoutState: {
      positions: {},
    },
    layoutRevision: 0,
    collapsedNodeIds: [],
    invocationExpansionState: createEmptyInvocationExpansionSceneState(),
  };
}

function createEmptyInvocationExpansionSceneState(): InvocationExpansionSceneState {
  return {
    activeExpansionId: null,
    activeExpansionPath: [],
    collapsedExpansionIds: [],
    activeSiblingByParentContext: {},
    blockPositions: {},
    lastChildStateByExpansionId: {},
    contextMode: "ACTIVE_CHAIN",
  };
}

function sameStringList(left: string[] | null | undefined, right: string[] | null | undefined): boolean {
  const normalizedLeft = left ?? [];
  const normalizedRight = right ?? [];
  return normalizedLeft.length === normalizedRight.length &&
    normalizedLeft.every((item, index) => item === normalizedRight[index]);
}

function sameLayoutState(
  left: LinkGraphLayoutState | null | undefined,
  right: LinkGraphLayoutState | null | undefined,
): boolean {
  const leftPositions = left?.positions ?? {};
  const rightPositions = right?.positions ?? {};
  const leftKeys = Object.keys(leftPositions);
  const rightKeys = Object.keys(rightPositions);
  return leftKeys.length === rightKeys.length &&
    leftKeys.every((nodeId) => {
      const leftPosition = leftPositions[nodeId];
      const rightPosition = rightPositions[nodeId];
      return rightPosition != null &&
        leftPosition.x === rightPosition.x &&
        leftPosition.y === rightPosition.y;
    });
}

function sameInvocationExpansionSceneState(
  left: InvocationExpansionSceneState | null | undefined,
  right: InvocationExpansionSceneState | null | undefined,
): boolean {
  return JSON.stringify(left ?? createEmptyInvocationExpansionSceneState()) ===
    JSON.stringify(right ?? createEmptyInvocationExpansionSceneState());
}

function sameSceneStateField<K extends keyof LinkGraphSceneState>(
  key: K,
  left: LinkGraphSceneState[K],
  right: LinkGraphSceneState[K],
): boolean {
  if (key === "collapsedNodeIds") {
    return sameStringList(left as string[] | null | undefined, right as string[] | null | undefined);
  }
  if (key === "layoutState") {
    return sameLayoutState(
      left as LinkGraphLayoutState | null | undefined,
      right as LinkGraphLayoutState | null | undefined,
    );
  }
  if (key === "invocationExpansionState") {
    return sameInvocationExpansionSceneState(
      left as InvocationExpansionSceneState | null | undefined,
      right as InvocationExpansionSceneState | null | undefined,
    );
  }
  return Object.is(left, right);
}

/** 判断 bootstrap 快照是否显式携带了某个字段（区分"未提供"与"显式为 null"），用于像图谱美化结果这类需要保留本地覆写、仅在后台明确下发时才更新的字段。 */
function hasOwnBootstrapField(
  state: LinkGraphBootstrapState,
  field: keyof LinkGraphBootstrapState,
): boolean {
  return Object.prototype.hasOwnProperty.call(state, field);
}

/** 仅保留当前节点集合对应的布局坐标，丢弃已不存在的节点坐标，避免场景状态携带陈旧布局数据。 */
function filterLayoutState(
  layoutState: LinkGraphLayoutState | null | undefined,
  nodes: LinkGraphNode[],
): LinkGraphLayoutState {
  const positions = layoutState?.positions ?? {};
  return {
    positions: Object.fromEntries(
      nodes.flatMap((node) => {
        const position = positions[node.id];
        return position ? [[node.id, position]] : [];
      }),
    ),
  };
}

function expansionIdsInGraph(nodes: LinkGraphNode[]): Set<string> {
  return new Set(
    nodes
      .map((node) => node.metadata?.["linkGraph.expansion.id"]?.trim())
      .filter((value): value is string => Boolean(value)),
  );
}

function filterInvocationExpansionSceneState(
  state: InvocationExpansionSceneState | null | undefined,
  nodes: LinkGraphNode[],
): InvocationExpansionSceneState {
  const baseState = state ?? createEmptyInvocationExpansionSceneState();
  const expansionIds = expansionIdsInGraph(nodes);
  const hasExpansion = (expansionId: string | null | undefined): expansionId is string =>
    Boolean(expansionId && expansionIds.has(expansionId));
  return {
    activeExpansionId: hasExpansion(baseState.activeExpansionId) ? baseState.activeExpansionId : null,
    activeExpansionPath: (baseState.activeExpansionPath ?? []).filter((expansionId) => expansionIds.has(expansionId)),
    collapsedExpansionIds: (baseState.collapsedExpansionIds ?? []).filter((expansionId) => expansionIds.has(expansionId)),
    activeSiblingByParentContext: Object.fromEntries(
      Object.entries(baseState.activeSiblingByParentContext ?? {})
        .filter(([, expansionId]) => expansionIds.has(expansionId)),
    ),
    blockPositions: Object.fromEntries(
      Object.entries(baseState.blockPositions ?? {})
        .filter(([expansionId]) => expansionIds.has(expansionId)),
    ),
    lastChildStateByExpansionId: Object.fromEntries(
      Object.entries(baseState.lastChildStateByExpansionId ?? {})
        .filter(([expansionId]) => expansionIds.has(expansionId)),
    ),
    contextMode: "ACTIVE_CHAIN",
  };
}

/** 将任意来源的场景状态标准化为合法形态：清理掉指向已不存在节点的选中/锚点、回收陈旧布局与折叠列表，并在缺少选中时按优先级回退出一个合法锚点。 */
function normalizeSceneState(
  sceneState: LinkGraphSceneState | null | undefined,
  nodes: LinkGraphNode[],
  resolveAnchorNodeId: (nodes: LinkGraphNode[], preferredNodeId: string | null) => string | null,
  authoritativeAnchorNodeId?: string | null,
): LinkGraphSceneState {
  const baseSceneState = sceneState ?? createEmptySceneState();
  const nodeIds = new Set(nodes.map((node) => node.id));
  const resolvedAuthoritativeAnchorNodeId = authoritativeAnchorNodeId && nodeIds.has(authoritativeAnchorNodeId)
    ? authoritativeAnchorNodeId
    : null;
  const sceneAnchorNodeId = baseSceneState.anchorNodeId && nodeIds.has(baseSceneState.anchorNodeId)
    ? baseSceneState.anchorNodeId
    : null;
  const sceneSelectedNodeId = baseSceneState.selectedNodeId && nodeIds.has(baseSceneState.selectedNodeId)
    ? baseSceneState.selectedNodeId
    : null;
  const sceneSelectionMirrorsStaleAnchor = resolvedAuthoritativeAnchorNodeId != null
    && sceneAnchorNodeId != null
    && sceneAnchorNodeId !== resolvedAuthoritativeAnchorNodeId
    && sceneSelectedNodeId === sceneAnchorNodeId;
  const selectedNodeId = sceneSelectionMirrorsStaleAnchor
    ? resolvedAuthoritativeAnchorNodeId
    : sceneSelectedNodeId ?? resolvedAuthoritativeAnchorNodeId ?? nodes[0]?.id ?? null;
  const preferredAnchorNodeId = resolvedAuthoritativeAnchorNodeId
    ?? sceneAnchorNodeId
    ?? selectedNodeId;

  return {
    ...baseSceneState,
    selectedNodeId,
    anchorNodeId: resolveAnchorNodeId(nodes, preferredAnchorNodeId),
    layoutState: filterLayoutState(baseSceneState.layoutState, nodes),
    collapsedNodeIds: (baseSceneState.collapsedNodeIds ?? []).filter((nodeId) => nodeIds.has(nodeId)),
    invocationExpansionState: filterInvocationExpansionSceneState(baseSceneState.invocationExpansionState, nodes),
  };
}

/** 把场景状态里保存的布局坐标写回到图谱节点上；对于场景未覆盖的节点，则尽量沿用上一帧同 id 节点的坐标，减少无谓的位置抖动。 */
function applySceneLayoutToGraph(
  graph: LinkGraphDocument,
  currentGraph: LinkGraphDocument,
  sceneState: LinkGraphSceneState,
): LinkGraphDocument {
  const currentNodeById = new Map(currentGraph.nodes.map((node) => [node.id, node]));
  let changed = false;
  const nextNodes = graph.nodes.map((node) => {
    const layoutPosition = sceneState.layoutState.positions[node.id];
    if (layoutPosition) {
      const currentPosition = resolveNodePosition(node);
      if (currentPosition?.x === layoutPosition.x && currentPosition?.y === layoutPosition.y) {
        return node;
      }
      changed = true;
      return syncNodePosition(node, layoutPosition);
    }
    if (resolveNodePosition(node)) {
      return node;
    }
    const currentPosition = resolveNodePosition(currentNodeById.get(node.id));
    if (!currentPosition) {
      return node;
    }
    changed = true;
    return syncNodePosition(node, currentPosition);
  });
  return !changed
    ? graph
    : {
        ...graph,
        nodes: nextNodes,
      };
}

/** 将 bootstrap 快照中"仅布局"的节点坐标合并到目标图谱，用于在后端重新计算布局后把新坐标同步到前端缓存的图上。 */
function applyBootstrapLayoutToGraph(
  graph: LinkGraphDocument,
  bootstrapGraph: LinkGraphDocument,
): LinkGraphDocument {
  const nextNodes = applyLayoutOnlyNodePositions(graph.nodes, bootstrapGraph.nodes);
  return nextNodes === graph.nodes
    ? graph
    : {
        ...graph,
        nodes: nextNodes,
      };
}

/** 对视图文档的 visible/full 两个图谱分别应用 bootstrap 布局坐标，统一处理"仅替换位置、不重建结构"的同步场景，未发生变化时返回原对象以保持引用稳定。 */
function applyBootstrapLayoutToViewDocument<T extends GraphViewDocumentLike>(
  view: T,
  bootstrapView: T,
): T {
  const nextVisibleGraph = applyBootstrapLayoutToGraph(view.visibleGraph, bootstrapView.visibleGraph);
  const nextFullGraph = applyBootstrapLayoutToGraph(view.fullGraph, bootstrapView.fullGraph);

  if (nextVisibleGraph === view.visibleGraph && nextFullGraph === view.fullGraph) {
    return view;
  }

  return {
    ...view,
    visibleGraph: nextVisibleGraph,
    fullGraph: nextFullGraph,
  };
}

/** 判断视图文档中是否存在任何节点或边，用于决定是否需要在请求进行中保留旧视图以避免画面闪空。 */
function hasGraphElements(view: GraphViewDocumentLike): boolean {
  return view.visibleGraph.nodes.length > 0
    || view.visibleGraph.edges.length > 0
    || view.fullGraph.nodes.length > 0
    || view.fullGraph.edges.length > 0;
}

/** 当某个图索引请求仍在进行中、且本次 bootstrap 解析出的视图是空时，沿用当前视图以避免清空画面；其余情况一律采用新视图。 */
function preserveCurrentViewDuringRunningRequest<T extends GraphViewDocumentLike>(
  nextView: T,
  currentView: T,
  requestState: AsyncRequestState | null | undefined,
  reuseCurrentProjectionGraphs: boolean,
): T {
  if (
    !reuseCurrentProjectionGraphs
    || requestState?.phase !== "RUNNING"
    || hasGraphElements(nextView)
    || !hasGraphElements(currentView)
  ) {
    return nextView;
  }
  return currentView;
}

/** 把合并后的场景状态（布局、锚点）回写到视图文档的 visible/full 图谱上，确保最终落盘的视图文档携带了用户在本地的视图编排结果。 */
function applySceneStateToViewDocument<T extends GraphViewDocumentLike>(
  view: T,
  currentView: T,
  sceneState: LinkGraphSceneState,
): T {
  const nextVisibleGraph = applySceneLayoutToGraph(
    view.visibleGraph,
    currentView.visibleGraph,
    sceneState,
  );
  const nextFullGraph = applySceneLayoutToGraph(
    view.fullGraph,
    currentView.fullGraph,
    sceneState,
  );

  if (
    nextVisibleGraph === view.visibleGraph
    && nextFullGraph === view.fullGraph
    && (view.anchorNodeId ?? null) === sceneState.anchorNodeId
  ) {
    return view;
  }

  return {
    ...view,
    visibleGraph: nextVisibleGraph,
    fullGraph: nextFullGraph,
    anchorNodeId: sceneState.anchorNodeId,
  };
}

/** 按场景 id 从聚合视图快照中取出该场景对应的可见节点集合，DIFF 场景仅在仍处于差异视图时才返回 fallback 图谱的节点。 */
function resolveSceneNodes(
  sceneId: LinkGraphSceneId,
  views: SceneGraphViews,
  fallbackVisibleGraph: LinkGraphDocument,
  currentSceneId: LinkGraphSceneId,
): LinkGraphNode[] {
  switch (sceneId) {
    case "WORKSPACE_FACT":
      return views.factGraphView.visibleGraph.nodes;
    case "WORKSPACE_FLOWCHART":
      return views.flowchartView.visibleGraph.nodes;
    case "WORKSPACE_RESOURCE_RELATION":
      return views.resourceRelationView.visibleGraph.nodes;
    case "WORKSPACE_ARCHITECTURE_GRAPH":
      return views.architectureGraphView.visibleGraph.nodes;
    case "WORKSPACE_CLASS_DIAGRAM":
      return views.classDiagramView.visibleGraph.nodes;
    case "WORKSPACE_REVIEW_GRAPH":
      return views.reviewGraphView.visibleGraph.nodes;
    case "DIFF":
      return currentSceneId === "DIFF" ? fallbackVisibleGraph.nodes : [];
    default:
      return [];
  }
}

/** 按场景 id 取出对应视图文档自带的锚点节点 id，作为合并场景状态时判断"锚点是否被权威源改动"的依据。 */
function resolveSceneViewAnchorNodeId(
  sceneId: LinkGraphSceneId,
  views: SceneGraphViews,
): string | null {
  switch (sceneId) {
    case "WORKSPACE_FACT":
      return views.factGraphView.anchorNodeId ?? null;
    case "WORKSPACE_FLOWCHART":
      return views.flowchartView.anchorNodeId ?? null;
    case "WORKSPACE_RESOURCE_RELATION":
      return views.resourceRelationView.anchorNodeId ?? null;
    case "WORKSPACE_ARCHITECTURE_GRAPH":
      return views.architectureGraphView.anchorNodeId ?? null;
    case "WORKSPACE_CLASS_DIAGRAM":
      return views.classDiagramView.anchorNodeId ?? null;
    case "WORKSPACE_REVIEW_GRAPH":
      return views.reviewGraphView.anchorNodeId ?? null;
    case "DIFF":
    default:
      return null;
  }
}

/** 把 bootstrap 下发的场景状态与前端当前的场景状态合并：当后端语义未变化且锚点未被权威源切换时保留本地的选中/折叠/布局；否则以后端为准并最终归一化。 */
function mergeSceneState(args: {
  nextSceneState: LinkGraphSceneState | undefined;
  previousServerSceneState: LinkGraphSceneState | undefined;
  currentSceneState: LinkGraphSceneState | undefined;
  nodes: LinkGraphNode[];
  authoritativeAnchorNodeId?: string | null;
  preserveLocalSceneUi: boolean;
  resolveAnchorNodeId: (nodes: LinkGraphNode[], preferredNodeId: string | null) => string | null;
}): LinkGraphSceneState {
  const bootstrapSceneState = args.nextSceneState ?? createEmptySceneState();
  const previousServerSceneState = args.previousServerSceneState ?? bootstrapSceneState;
  const currentSceneState = args.currentSceneState ?? bootstrapSceneState;
  const nodeIds = new Set(args.nodes.map((node) => node.id));
  const authoritativeAnchorNodeId = args.authoritativeAnchorNodeId && nodeIds.has(args.authoritativeAnchorNodeId)
    ? args.authoritativeAnchorNodeId
    : null;
  const incomingAnchorChanged = Boolean(
    authoritativeAnchorNodeId
      ? currentSceneState.anchorNodeId && authoritativeAnchorNodeId !== currentSceneState.anchorNodeId
      : bootstrapSceneState.anchorNodeId
        && currentSceneState.anchorNodeId
        && bootstrapSceneState.anchorNodeId !== currentSceneState.anchorNodeId,
  );
  const preserveLocalSceneUi = args.preserveLocalSceneUi && !incomingAnchorChanged;
  const serverFieldChanged = <K extends keyof LinkGraphSceneState>(key: K): boolean =>
    !sameSceneStateField(key, bootstrapSceneState[key], previousServerSceneState[key]);
  const sceneField = <K extends keyof LinkGraphSceneState>(key: K): LinkGraphSceneState[K] =>
    preserveLocalSceneUi && !serverFieldChanged(key)
      ? currentSceneState[key]
      : bootstrapSceneState[key];
  const serverLayoutChanged = serverFieldChanged("layoutState") || serverFieldChanged("layoutRevision");
  const preserveLocalLayout = preserveLocalSceneUi
    && !serverLayoutChanged
    && currentSceneState.layoutRevision >= bootstrapSceneState.layoutRevision;

  const mergedSceneState = preserveLocalSceneUi
    ? {
        ...bootstrapSceneState,
        selectedNodeId: sceneField("selectedNodeId"),
        anchorNodeId: sceneField("anchorNodeId"),
        collapsedNodeIds: sceneField("collapsedNodeIds"),
        invocationExpansionState: sceneField("invocationExpansionState"),
        layoutState: preserveLocalLayout ? currentSceneState.layoutState : bootstrapSceneState.layoutState,
        layoutRevision: preserveLocalLayout ? currentSceneState.layoutRevision : bootstrapSceneState.layoutRevision,
    }
    : bootstrapSceneState;

  return normalizeSceneState(
    mergedSceneState,
    args.nodes,
    args.resolveAnchorNodeId,
    authoritativeAnchorNodeId,
  );
}

/**
 * 接收后端推送的 LinkGraphBootstrapState，把初始快照投影成工作台所需的派生状态：
 * 解析六类场景视图、合并用户本地场景编排、应用布局坐标、计算选中/锚点，
 * 并把结果同步到画布状态、投影状态以及一系列 ref 上，供上层视图与下游副作用消费。
 */
export function useBootstrapProjectionState(args: UseBootstrapProjectionStateArgs) {
  // 以 ref 形式镜像最新的画布状态，便于 applyBootstrapState 在闭包内读取最新值而无需把它塞进依赖
  const canvasStateRef = useRef(args.canvasState);
  canvasStateRef.current = args.canvasState;

  /** 处理一次完整的 bootstrap 快照：完成视图解析、布局/场景合并、ref 与 state 写回等所有副作用，是本 hook 对外暴露的核心入口。 */
  function applyBootstrapState(nextState: LinkGraphBootstrapState) {
    // 记录本次投影的起始时间，用于在 trace 中输出耗时
    const startedAt = measureStart();
    // 读取最新的画布状态作为"上一帧"基线，用于判断语义是否变化、决定是否复用本地投影
    const currentCanvasState = canvasStateRef.current;
    // 按优先级确定本次采用的展示模式：bootstrap 显式 > ref 中暂存的 > 当前画布 > 默认值
    const nextAnalysisDisplayMode = nextState.analysisDisplayMode
      ?? args.analysisDisplayModeRef.current
      ?? currentCanvasState.analysisDisplayMode
      ?? DEFAULT_ANALYSIS_DISPLAY_MODE;
    // 当前已落地的语义版本号，用于和 bootstrap 中的语义版本比较，判定是否可以复用本地投影结果
    const currentSemanticRevision = args.semanticRevisionRef.current;
    // 工作区与语义版本都未变化时视为"只是重发同一份快照"，此时优先复用本地已渲染的图，避免布局抖动
    const reuseCurrentProjectionGraphs = nextState.workspaceRevision === currentCanvasState.workspaceRevision
      && nextState.semanticRevision === currentSemanticRevision;
    // 解析源码导航状态（光标位置/打开的文件等），后续写入投影状态供导航相关 UI 使用
    const nextSourceNavigationState = args.resolveSourceNavigationState(nextState);
    // 把 bootstrap 携带的各类图索引请求状态规范化为完整的 AsyncRequestState 形态
    const nextIndexedGraphRequestStates = resolveIndexedGraphRequestStates(
      nextState.indexedGraphRequestStates,
      args.resolveRequestState,
    );

    // 对每个视图先解析后端下发的结构、再把当前视图中的路由高亮信息合并上去，得到 bootstrap 视角的视图文档
    const bootstrapFactGraphView = args.applyBootstrapRoutesToViewDocument(
      args.resolveFactGraphView(nextState),
      currentCanvasState.factGraphView,
    );
    const bootstrapFlowchartView = args.applyBootstrapRoutesToViewDocument(
      args.resolveFlowchartView(nextState),
      currentCanvasState.flowchartView,
    );
    const bootstrapResourceRelationView = args.applyBootstrapRoutesToViewDocument(
      args.resolveResourceRelationView(nextState),
      currentCanvasState.resourceRelationView,
    );
    const bootstrapArchitectureGraphView = preserveCurrentViewDuringRunningRequest(
      args.applyBootstrapRoutesToViewDocument(
        args.resolveArchitectureGraphView(nextState),
        currentCanvasState.architectureGraphView,
      ),
      currentCanvasState.architectureGraphView,
      nextIndexedGraphRequestStates.ARCHITECTURE,
      reuseCurrentProjectionGraphs,
    );
    const bootstrapClassDiagramView = preserveCurrentViewDuringRunningRequest(
      args.applyBootstrapRoutesToViewDocument(
        args.resolveClassDiagramView(nextState),
        currentCanvasState.classDiagramView,
      ),
      currentCanvasState.classDiagramView,
      nextIndexedGraphRequestStates.CLASS_DIAGRAM,
      reuseCurrentProjectionGraphs,
    );
    const bootstrapReviewGraphView = preserveCurrentViewDuringRunningRequest(
      args.applyBootstrapRoutesToViewDocument(
        args.resolveReviewGraphView(nextState),
        currentCanvasState.reviewGraphView,
      ),
      currentCanvasState.reviewGraphView,
      nextIndexedGraphRequestStates.REVIEW,
      reuseCurrentProjectionGraphs,
    );

    // 在 bootstrap 视图基础上合并"是否复用当前视图"策略并应用后端布局坐标，得到最终投影视图
    const projectedFactGraphView = applyBootstrapLayoutToViewDocument(
      args.reuseCurrentViewGraphs(
        bootstrapFactGraphView,
        currentCanvasState.factGraphView,
        reuseCurrentProjectionGraphs,
      ),
      bootstrapFactGraphView,
    );
    const projectedFlowchartView = applyBootstrapLayoutToViewDocument(
      args.reuseCurrentViewGraphs(
        bootstrapFlowchartView,
        currentCanvasState.flowchartView,
        reuseCurrentProjectionGraphs,
      ),
      bootstrapFlowchartView,
    );
    const projectedResourceRelationView = applyBootstrapLayoutToViewDocument(
      args.reuseCurrentViewGraphs(
        bootstrapResourceRelationView,
        currentCanvasState.resourceRelationView,
        reuseCurrentProjectionGraphs,
      ),
      bootstrapResourceRelationView,
    );
    const projectedArchitectureGraphView = applyBootstrapLayoutToViewDocument(
      args.reuseCurrentViewGraphs(
        bootstrapArchitectureGraphView,
        currentCanvasState.architectureGraphView,
        reuseCurrentProjectionGraphs,
      ),
      bootstrapArchitectureGraphView,
    );
    const projectedClassDiagramView = applyBootstrapLayoutToViewDocument(
      args.reuseCurrentViewGraphs(
        bootstrapClassDiagramView,
        currentCanvasState.classDiagramView,
        reuseCurrentProjectionGraphs,
      ),
      bootstrapClassDiagramView,
    );
    const projectedReviewGraphView = applyBootstrapLayoutToViewDocument(
      args.reuseCurrentViewGraphs(
        bootstrapReviewGraphView,
        currentCanvasState.reviewGraphView,
        reuseCurrentProjectionGraphs,
      ),
      bootstrapReviewGraphView,
    );

    // 在未应用场景状态前，先解析出当前展示模式对应的主可见图，作为 DIFF 场景的 fallback 节点来源
    const provisionalVisibleGraph = args.resolveActiveViewDocument({
      ...nextState,
      factGraphView: projectedFactGraphView,
      flowchartView: projectedFlowchartView,
      resourceRelationView: projectedResourceRelationView,
      architectureGraphView: projectedArchitectureGraphView,
      classDiagramView: projectedClassDiagramView,
      reviewGraphView: projectedReviewGraphView,
    }, nextAnalysisDisplayMode).visibleGraph;

    // 汇总 bootstrap 与当前画布中出现过的所有场景 id，确保不会因为某一方未提及就丢失对应场景的状态
    const currentServerSceneStates = currentCanvasState.serverSceneStates ?? currentCanvasState.sceneStates;
    const sceneIds = Array.from(new Set([
      ...Object.keys(nextState.sceneStates),
      ...Object.keys(currentCanvasState.sceneStates),
      ...Object.keys(currentServerSceneStates),
    ])) as LinkGraphSceneId[];
    const projectedViews = {
      factGraphView: projectedFactGraphView,
      flowchartView: projectedFlowchartView,
      resourceRelationView: projectedResourceRelationView,
      architectureGraphView: projectedArchitectureGraphView,
      classDiagramView: projectedClassDiagramView,
      reviewGraphView: projectedReviewGraphView,
    };
    const sceneNodesById = Object.fromEntries(
      sceneIds.map((sceneId) => [
        sceneId,
        resolveSceneNodes(
          sceneId,
          projectedViews,
          provisionalVisibleGraph,
          nextState.currentSceneId,
        ),
      ]),
    ) as Record<LinkGraphSceneId, LinkGraphNode[]>;
    const nextServerSceneStates = Object.fromEntries(
      sceneIds.map((sceneId) => [
        sceneId,
        normalizeSceneState(
          nextState.sceneStates[sceneId] ?? currentServerSceneStates[sceneId],
          sceneNodesById[sceneId] ?? [],
          args.resolveAnchorNodeId,
          resolveSceneViewAnchorNodeId(sceneId, projectedViews),
        ),
      ]),
    ) as Record<LinkGraphSceneId, LinkGraphSceneState>;

    // 逐个场景合并 bootstrap 与本地场景状态，得到这一帧最终采用的场景状态集合
    const mergedSceneStates = Object.fromEntries(
      sceneIds.map((sceneId) => {
        const nodes = sceneNodesById[sceneId] ?? [];
        return [
          sceneId,
          mergeSceneState({
            nextSceneState: nextServerSceneStates[sceneId],
            previousServerSceneState: currentServerSceneStates[sceneId],
            currentSceneState: currentCanvasState.sceneStates[sceneId],
            nodes,
            authoritativeAnchorNodeId: resolveSceneViewAnchorNodeId(sceneId, projectedViews),
            preserveLocalSceneUi: reuseCurrentProjectionGraphs,
            resolveAnchorNodeId: args.resolveAnchorNodeId,
          }),
        ];
      }),
    ) as Record<LinkGraphSceneId, LinkGraphSceneState>;

    const nextFactGraphView = applySceneStateToViewDocument(
      projectedFactGraphView,
      currentCanvasState.factGraphView,
      mergedSceneStates.WORKSPACE_FACT ?? createEmptySceneState(),
    );
    const nextFlowchartView = applySceneStateToViewDocument(
      projectedFlowchartView,
      currentCanvasState.flowchartView,
      mergedSceneStates.WORKSPACE_FLOWCHART ?? createEmptySceneState(),
    );
    const nextResourceRelationView = applySceneStateToViewDocument(
      projectedResourceRelationView,
      currentCanvasState.resourceRelationView,
      mergedSceneStates.WORKSPACE_RESOURCE_RELATION ?? createEmptySceneState(),
    );
    const nextArchitectureGraphView = applySceneStateToViewDocument(
      projectedArchitectureGraphView,
      currentCanvasState.architectureGraphView,
      mergedSceneStates.WORKSPACE_ARCHITECTURE_GRAPH ?? createEmptySceneState(),
    );
    const nextClassDiagramView = applySceneStateToViewDocument(
      projectedClassDiagramView,
      currentCanvasState.classDiagramView,
      mergedSceneStates.WORKSPACE_CLASS_DIAGRAM ?? createEmptySceneState(),
    );
    const nextReviewGraphView = applySceneStateToViewDocument(
      projectedReviewGraphView,
      currentCanvasState.reviewGraphView,
      mergedSceneStates.WORKSPACE_REVIEW_GRAPH ?? createEmptySceneState(),
    );

    // 在应用了场景状态后再次解析主可见图，得到最终用于渲染和后续派生的图谱
    const resolvedVisibleGraph = args.resolveActiveViewDocument({
      ...nextState,
      factGraphView: nextFactGraphView,
      flowchartView: nextFlowchartView,
      resourceRelationView: nextResourceRelationView,
      architectureGraphView: nextArchitectureGraphView,
      classDiagramView: nextClassDiagramView,
      reviewGraphView: nextReviewGraphView,
      sceneStates: mergedSceneStates,
    }, nextAnalysisDisplayMode).visibleGraph;
    const visibleGraph = resolvedVisibleGraph;
    // 当前激活场景的状态，决定选中/锚点/折叠等用户可见的视图编排
    const nextSceneState = mergedSceneStates[nextState.currentSceneId] ?? createEmptySceneState();
    const nextNodes = visibleGraph.nodes;
    const nextEdges = visibleGraph.edges;
    // 选中的节点：优先沿用场景中的选中，否则回退到第一个节点
    const nextSelectedNodeId = nextSceneState.selectedNodeId ?? nextNodes[0]?.id ?? null;
    // 锚点节点：优先场景中的锚点，否则按节点集合与偏好选中解算一个合法锚点
    const nextAnchorNodeId = nextSceneState.anchorNodeId ?? args.resolveAnchorNodeId(nextNodes, nextSelectedNodeId);
    // 工作区底图，作为草稿图等派生数据的来源
    const nextWorkspaceGraph = args.resolveWorkingGraph(nextState);
    // 草稿图：若可复用本地投影则尽量保留已有草稿（避免丢失未提交的本地修改），否则采用工作区底图
    const nextDraftGraph = reuseCurrentProjectionGraphs
      ? args.draftGraphRef.current ?? nextWorkspaceGraph
      : nextWorkspaceGraph;

    traceLinkGraph("app.applyBootstrapState", {
      bootstrap: summarizeBootstrapState(nextState),
      visibleGraph: summarizeGraph(visibleGraph),
      reuseCurrentProjectionGraphs,
      durationMs: measureDuration(startedAt),
    });

    args.nodesRef.current = nextNodes;
    args.edgesRef.current = nextEdges;
    args.draftGraphRef.current = nextDraftGraph;
    args.anchorNodeIdRef.current = nextAnchorNodeId;
    args.analysisDisplayModeRef.current = nextAnalysisDisplayMode;
    args.semanticRevisionRef.current = nextState.semanticRevision ?? args.semanticRevisionRef.current;
    args.layoutRevisionRef.current = nextSceneState.layoutRevision ?? null;
    // 组装新的画布状态：合并视图文档、场景状态、工作区底图与各类展示元数据，作为下一帧的渲染基线
    const nextCanvasState: WorkbenchCanvasState = {
      ...currentCanvasState,
      nodes: nextNodes,
      edges: nextEdges,
      selectedNodeId: nextSelectedNodeId,
      analysisDisplayMode: nextAnalysisDisplayMode,
      anchorNodeId: nextAnchorNodeId,
      currentSceneId: nextState.currentSceneId,
      sceneStates: mergedSceneStates,
      serverSceneStates: nextServerSceneStates,
      workspaceGraph: nextWorkspaceGraph,
      workspaceBaseGraph: args.resolveWorkspaceBaseGraph(nextState),
      semanticFactGraph: args.resolveSemanticFactGraph(nextState),
      workspaceRevision: nextState.workspaceRevision ?? currentCanvasState.workspaceRevision,
      factGraphView: nextFactGraphView,
      flowchartView: nextFlowchartView,
      resourceRelationView: nextResourceRelationView,
      architectureGraphView: nextArchitectureGraphView,
      classDiagramView: nextClassDiagramView,
      reviewGraphView: nextReviewGraphView,
      draftGraph: nextDraftGraph,
    };
    canvasStateRef.current = nextCanvasState;

    args.setCanvasState(() => nextCanvasState);

    args.setProjectionState((current) => ({
      ...current,
      designBaseline: args.resolveDesignBaselineGraph(nextState),
      draftWorkbenchState: nextState.draftWorkbenchState ?? { draftChanges: [], draftNotes: [] },
      draftPatchPreview: nextState.draftPatchPreview ?? null,
      canUndoDraftPatchApply: nextState.canUndoDraftPatchApply ?? false,
      lastAppliedDraftPatchSummary: nextState.lastAppliedDraftPatchSummary ?? null,
      lastDraftPatchApplyResult: nextState.lastDraftPatchApplyResult ?? null,
      qaResult: nextState.qaResult ?? null,
      qaRequestState: args.resolveRequestState(nextState.qaRequestState),
      qaRequestRecoveryState: nextState.qaRequestRecoveryState ?? { lastSubmittedRequest: null, lastFailedRequest: null },
      diffReviewResult: nextState.diffReviewResult ?? null,
      diffReviewRequestState: args.resolveRequestState(nextState.diffReviewRequestState),
      mermaidIssues: nextState.mermaidIssues ?? [],
      diffItems: nextState.diffItems ?? [],
      syncPreviewItems: nextState.syncPreviewItems ?? [],
      draftVersion: nextState.draftVersion ?? null,
      generationPlan: nextState.generationPlan ?? null,
      generationPlanDraftVersion: nextState.generationPlanDraftVersion ?? null,
      generationPlanRequestState: args.resolveRequestState(nextState.generationPlanRequestState),
      draftValidationState: nextState.draftValidationState ?? null,
      generationPlanDiscussionSession: nextState.generationPlanDiscussionSession ?? null,
      generationPlanDiscussionRequestState: args.resolveRequestState(nextState.generationPlanDiscussionRequestState),
      graphBeautificationResult: args.explanationLocalOverrideRef.current
        ? current.graphBeautificationResult
        : hasOwnBootstrapField(nextState, "graphBeautificationResult")
          ? nextState.graphBeautificationResult ?? null
          : current.graphBeautificationResult,
      graphBeautificationRequestState: args.explanationLocalOverrideRef.current
        ? current.graphBeautificationRequestState
        : hasOwnBootstrapField(nextState, "graphBeautificationRequestState")
          ? args.resolveRequestState(nextState.graphBeautificationRequestState)
          : current.graphBeautificationRequestState,
      generatedCodeDrafts: nextState.generatedCodeDrafts ?? [],
      generatedCodeDraftVersion: nextState.generatedCodeDraftVersion ?? null,
      generatedCodeDraftWarnings: nextState.generatedCodeDraftWarnings ?? [],
      generatedCodeDraftSource: nextState.generatedCodeDraftSource ?? null,
      generatedCodeDraftPromptPreview: nextState.generatedCodeDraftPromptPreview ?? null,
      generatedCodeDraftPromptPreviewArtifactId: nextState.generatedCodeDraftPromptPreviewArtifactId ?? null,
      generatedCodeDraftWriteReport: nextState.generatedCodeDraftWriteReport ?? null,
      codeDraftRequestState: args.resolveRequestState(nextState.codeDraftRequestState),
      codeEligibilityDecision: nextState.codeEligibilityDecision ?? null,
      indexedGraphRequestStates: nextIndexedGraphRequestStates,
      sourceNavigationState: nextSourceNavigationState,
      operationFeedback: nextState.operationFeedback ?? null,
      assistantSessionState: nextState.assistantSessionState ?? current.assistantSessionState,
      assistantResultStore: nextState.assistantResultStore ?? current.assistantResultStore,
      lastMessageType: nextState.lastMessageType ?? null,
      graphSurfaceExperiments: nextState.graphSurfaceExperiments ?? null,
      artifactContents: nextState.artifactContents
        ? {
            ...current.artifactContents,
            ...nextState.artifactContents,
          }
        : current.artifactContents,
    }));

    // 同步手动新建节点的 id 计数器，避免后续手建节点 id 与本次新节点发生冲突
    args.syncManualNodeIdCounters(nextNodes);
    // 选中分组与差异目标列表都要剔除本次已不存在的条目，保证 UI 不会引用陈旧数据
    args.setSelectionGroupNodeIds((current) => current.filter((nodeId) => nextNodes.some((node) => node.id === nodeId)));
    args.setDiffTargetItemIds((current) => current.filter((itemId) => nextState.diffItems.some((item) => item.id === itemId)));
  }

  return {
    applyBootstrapState,
  };
}
