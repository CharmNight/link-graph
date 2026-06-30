import { useMemo, useState } from "react";
import { buildEdgeActions as buildSharedEdgeActions } from "../../components/graph/actions/actionSchema";
import { Button } from "../../components/Button";
import { CanvasEmptyState } from "../../components/graph/CanvasEmptyState";
import type { GraphContextMenuAction } from "../../components/graph/actions/actionSchema";
import { createNodeSizeRegistry } from "../../graph/nodeSizeRegistry";
import { classDiagramNodeCardWidth } from "../../graphNodeSizing";
import { GraphViewShell } from "../../presentation/GraphViewShell";
import { GraphFlowSurface } from "../../reactflow/GraphFlowSurface";
import { useMeasuredLayout } from "../../reactflow/useMeasuredLayout";
import { canNavigateToSource } from "../../sourceNavigation";
import type { ClassDiagramViewDocument, LinkGraphEdge, LinkGraphNode } from "../../types";
import { resolveIndexedGraphEmptyState } from "../indexedGraphEmptyState";
import type { IndexedReadonlyStageProps } from "../viewStageProps";
import { layoutClassDiagramView } from "./classDiagramLayout";
import { estimateUmlNodeHeight } from "./classDiagramLayoutModel";
import {
  buildClassDiagramEdges,
  buildClassDiagramNodes,
  CLASS_DIAGRAM_NODE_TYPES,
} from "./classDiagramNodes";
import {
  classDiagramRelationDisplayLabel,
} from "./classDiagramRelations";
import { ClassUsagePanel } from "./ClassUsagePanel";

/** 类图视图的属性，基于只读阶段属性扩展，并附加类图专属文档数据。 */
interface ClassDiagramViewProps extends IndexedReadonlyStageProps {
  view: ClassDiagramViewDocument;
}

/** 查找类使用处请求的扩展选项，控制目标、范围、来源与分页条数等。 */
interface ClassUsageRequestOptions {
  scopeNodeId?: string | null;
  targetQualifiedName?: string | null;
  sourceVirtualFileUrl?: string | null;
  sourcePath?: string | null;
  maxUsageGroups?: number | null;
  maxUsageEntries?: number | null;
  includeImports?: boolean | null;
}

/**
 * 根据当前可见节点集合过滤边，仅保留两端都在视图中的边，
 * 同时根据关系类型替换为面向用户的中文展示标签。
 */
function filterVisibleClassDiagramEdges(
  edges: LinkGraphEdge[],
  visibleNodeIds: ReadonlySet<string>,
): LinkGraphEdge[] {
  return edges
    .filter((edge) =>
      visibleNodeIds.has(edge.source)
      && visibleNodeIds.has(edge.target),
    )
    .map((edge) => ({
      ...edge,
      label: classDiagramRelationDisplayLabel(edge),
    }));
}

/** 从节点元数据中读取一个正数数值；非有限或非正则返回 null。 */
function positiveMetadataNumber(node: LinkGraphNode, key: string): number | null {
  const value = Number(node.metadata?.[key]);
  return Number.isFinite(value) && value > 0 ? value : null;
}

/** 视口尺寸计算：宽度按类图节点卡片宽度，高度优先取布局估算值否则走 UML 高度估测。 */
function classDiagramViewportNodeSize(node: LinkGraphNode) {
  return {
    width: classDiagramNodeCardWidth(node),
    height: positiveMetadataNumber(node, "layout.estimatedHeight") ?? estimateUmlNodeHeight(node),
  };
}

/** 关键字匹配：判断节点标题/签名/位置/文档/全限定名等是否包含搜索关键词（大小写不敏感）。 */
function nodeMatchesClassDiagramQuery(node: LinkGraphNode, query: string): boolean {
  const normalized = query.trim().toLowerCase();
  if (!normalized) {
    return true;
  }
  return [
    node.title,
    node.signature,
    node.location,
    node.doc,
    node.metadata?.["architecture.qualifiedName"],
    node.metadata?.["architecture.package"],
    node.metadata?.["jvm.class.kind"],
  ].some((value) => value?.toLowerCase().includes(normalized));
}

/** 判断节点是否是可触发"查找使用处"的类型（类/接口/枚举/注解/记录/对象）。 */
function canRequestClassUsagesForNode(node: LinkGraphNode | null): boolean {
  if (!node) {
    return false;
  }
  return ["CLASS", "INTERFACE", "ENUM", "ANNOTATION", "RECORD", "OBJECT"].includes(node.type);
}

/** 计算使用处面板的作用域节点 ID：usage 模式取目标节点，否则取类图锚点。 */
function classDiagramUsageScopeNodeId(view: ClassDiagramViewDocument): string | null {
  // usage 模式下定位被查询的目标类；否则用作用域锚点
  if (view.usage) {
    return view.usage.summary.targetNodeId
      ?? view.summary.anchorTypeNodeId
      ?? view.presentation.target.nodeId
      ?? null;
  }
  return view.summary.anchorTypeNodeId
    ?? view.anchorNodeId
    ?? view.presentation.target.nodeId
    ?? null;
}

/**
 * 根据选中的类节点构造"查找使用处"请求的完整选项：
 * 携带目标全限定名、源文件信息与可选作用域，供后端精确定位使用位置。
 */
function classUsageRequestOptionsForNode(
  node: LinkGraphNode | null,
  scopeNodeId?: string | null,
): ClassUsageRequestOptions {
  if (!node) {
    return scopeNodeId ? { scopeNodeId } : {};
  }
  const options: ClassUsageRequestOptions = {
    targetQualifiedName: node.metadata?.["architecture.qualifiedName"]
      ?? node.metadata?.["class.qualifiedName"]
      ?? node.signature
      ?? null,
    sourceVirtualFileUrl: node.metadata?.["source.virtualFileUrl"] ?? null,
    sourcePath: node.metadata?.["source.filePath"] ?? node.location ?? null,
  };
  if (scopeNodeId) {
    options.scopeNodeId = scopeNodeId;
  }
  return options;
}

/**
 * 构造类图节点的右键菜单动作集合：查看详情、折叠/展开下游、打开源码、查找使用处、
 * 围绕当前类打开类图、讲解关系、问答、设为问答目标、重新整理布局等。
 */
function classDiagramNodeActions(args: {
  nodeId: string;
  node: LinkGraphNode | null;
  canOpenSource: boolean;
  canRequestClassUsages: boolean;
  collapsed: boolean;
  onInspectNode: (nodeId: string) => void;
  onRequestSourceNavigation: (nodeId: string) => void;
  onRequestBeautification: (selectedNodeId?: string) => void;
  onPrimeQuestionComposer: (selectedNodeId?: string) => void;
  onRequestClassDiagram: (scopeNodeId?: string | null) => void;
  onRequestClassUsages: (targetNodeId: string, options?: ClassUsageRequestOptions) => void;
  usageScopeNodeId?: string | null;
  onOpenQa: (selectedNodeId?: string) => void;
  onToggleCollapseNode: (nodeId: string) => void;
  onFormatLayout: () => void;
  onClose: () => void;
}): GraphContextMenuAction[] {
  const actions: GraphContextMenuAction[] = [
    {
      id: "inspect-node",
      label: "查看详情",
      onSelect: () => {
        args.onInspectNode(args.nodeId);
        args.onClose();
      },
    },
    {
      id: "toggle-collapse",
      label: args.collapsed ? "展开下游" : "折叠下游",
      onSelect: () => {
        args.onToggleCollapseNode(args.nodeId);
        args.onClose();
      },
    },
  ];
  if (args.canOpenSource) {
    actions.push({
      id: "open-source",
      label: "打开源码",
      onSelect: () => {
        args.onRequestSourceNavigation(args.nodeId);
        args.onClose();
      },
    });
  }
  if (args.canRequestClassUsages) {
    actions.push({
      id: "find-class-usages",
      label: "查找使用处",
      onSelect: () => {
        args.onRequestClassUsages(args.nodeId, classUsageRequestOptionsForNode(args.node, args.usageScopeNodeId));
        args.onClose();
      },
    });
  }
  actions.push(
    {
      id: "open-class-diagram-anchor",
      label: "以此类为中心打开类图",
      onSelect: () => {
        args.onRequestClassDiagram(args.nodeId);
        args.onClose();
      },
    },
    {
      id: "beautify-node",
      label: "讲解当前类关系",
      onSelect: () => {
        args.onRequestBeautification(args.nodeId);
        args.onClose();
      },
    },
    {
      id: "qa-node",
      label: "问答当前节点",
      onSelect: () => {
        args.onPrimeQuestionComposer(args.nodeId);
        args.onClose();
      },
    },
    {
      id: "set-qa-anchor",
      label: "设为问答目标",
      onSelect: () => {
        args.onOpenQa(args.nodeId);
        args.onClose();
      },
    },
    {
      id: "format-layout",
      label: "重新整理布局",
      onSelect: () => {
        args.onFormatLayout();
        args.onClose();
      },
    },
  );
  return actions;
}

/**
 * 类图视图主组件：负责展示一组类型及其关系（继承/实现/依赖等），
 * 处理搜索过滤、可见性、布局、抽屉、节点/边右键菜单、以及"查找使用处"等交互。
 */
export function ClassDiagramView({
  view,
  selectedNodeId,
  focusNodeRequest = null,
  explanationFocusNodeId = null,
  draftChangedNodeIds = [],
  draftCompareProjection = null,
  selectedGroupNodeIds = [],
  hiddenNodeIds = [],
  collapsedNodeIds = [],
  experiments = null,
  indexedGraphRequestStates = null,
  onSelectNode,
  onSelectionGroupChange = () => undefined,
  onInspectNode,
  onMoveNode,
  onMoveNodes,
  onRequestSourceNavigation,
  onRequestBeautification = () => undefined,
  onPrimeQuestionComposer = () => undefined,
  onRequestClassDiagram = () => undefined,
  onRequestClassDiagramWithOptions = () => undefined,
  onRequestClassUsages = () => undefined,
  onToggleCollapseNode = () => undefined,
  onOpenQa = () => undefined,
}: ClassDiagramViewProps) {
  // 当前用户输入的搜索关键词。
  const [query, setQuery] = useState("");
  // 节点尺寸注册表，仅在组件挂载时创建一次，供布局测量复用。
  const nodeSizeRegistry = useMemo(() => createNodeSizeRegistry(), []);
  // 类图索引请求状态：优先用外部传入的阶段状态，否则按索引标志位兜底为成功。
  const effectiveClassDiagramRequestState = indexedGraphRequestStates?.CLASS_DIAGRAM
    ?? (view.summary.indexed ? { phase: "SUCCEEDED" as const } : null);
  // 当关系完整度仅为结构骨架且请求仍在进行时，展示"正在补齐关系"。
  const isStructureOnlyStillLoading = view.summary.relationCompleteness === "STRUCTURE_ONLY"
    && effectiveClassDiagramRequestState?.phase === "RUNNING";
  const baseGraph = view.visibleGraph;
  // 草稿比对投影优先于基础图，用于呈现 diff 视角下的节点/边状态。
  const presentedGraph = draftCompareProjection?.compareGraph ?? baseGraph;
  // 根据关键词对节点进行过滤，并移除两端节点已不可见的边。
  const queryFilteredGraph = useMemo(() => {
    const filteredNodes = presentedGraph.nodes.filter((node) => nodeMatchesClassDiagramQuery(node, query));
    const filteredNodeIds = new Set(filteredNodes.map((node) => node.id));
    return {
      ...presentedGraph,
      nodes: filteredNodes,
      edges: presentedGraph.edges.filter((edge) => filteredNodeIds.has(edge.source) && filteredNodeIds.has(edge.target)),
    };
  }, [presentedGraph, query]);
  const layoutGraph = queryFilteredGraph;
  // 调用测量+布局 Hook，生成最终节点坐标与边；resetKey 变化时清空种子以避免连线残留。
  const layoutState = useMeasuredLayout({
    graph: layoutGraph,
    anchorNodeId: view.anchorNodeId ?? null,
    nodeSizeRegistry,
    layout: layoutClassDiagramView,
    layoutOnPositionChange: true,
    debugLabel: "class-diagram",
    // usage target 变化（查看使用处切换）时完全重置布局，丢弃旧节点/边 seed，
    // 避免旧的类关系连线残留在新的 usage 子图上。
    resetKey: view.usage?.summary.targetNodeId ?? view.presentation.target.nodeId ?? view.anchorNodeId ?? null,
  });

  const hiddenNodeIdSet = useMemo(() => new Set(hiddenNodeIds), [hiddenNodeIds]);
  const collapsedNodeIdSet = useMemo(() => new Set(collapsedNodeIds), [collapsedNodeIds]);
  // 若隐藏集合会让所有已布局节点消失，则忽略隐藏以避免空白画布。
  const effectiveHiddenNodeIdSet = useMemo(() => {
    if (layoutState.nodes.length === 0 || hiddenNodeIdSet.size === 0) {
      return hiddenNodeIdSet;
    }
    const wouldHideAllLayoutNodes = layoutState.nodes.every((node) => hiddenNodeIdSet.has(node.id));
    return wouldHideAllLayoutNodes ? new Set<string>() : hiddenNodeIdSet;
  }, [hiddenNodeIdSet, layoutState.nodes]);
  // 实际渲染的节点：布局结果去除被隐藏的节点。
  const visibleNodes = useMemo(
    () => layoutState.nodes.filter((node) => !effectiveHiddenNodeIdSet.has(node.id)),
    [effectiveHiddenNodeIdSet, layoutState.nodes],
  );
  const visibleNodeIds = useMemo(() => new Set(visibleNodes.map((node) => node.id)), [visibleNodes]);
  const visibleEdges = useMemo(() => filterVisibleClassDiagramEdges(layoutState.edges, visibleNodeIds), [layoutState.edges, visibleNodeIds]);

  // 视口重置 Key：综合关系完整度/节点边数量/锚点变化，触发自动重定位视图。
  const viewportResetKey = useMemo(
    () => [
      view.summary.relationCompleteness ?? "UNKNOWN",
      visibleNodes.length,
      visibleEdges.length,
      view.anchorNodeId ?? "",
    ].join(":"),
    [view.anchorNodeId, view.summary.relationCompleteness, visibleEdges.length, visibleNodes.length],
  );
  // 判定布局是否仍处于"有输入但未产出节点"的加载态，用于显示骨架屏。
  const isLayoutLoading = layoutState.layoutPending && layoutGraph.nodes.length > 0 && layoutState.nodes.length === 0;
  // 节点 ID 索引，便于右键菜单/选中逻辑 O(1) 查找。
  const nodeIndex = useMemo(() => new Map(visibleNodes.map((node) => [node.id, node])), [visibleNodes]);
  // 当前选中的节点是否可触发"查找使用处"。
  const selectedUsageNodeId = useMemo(() => {
    const selectedNode = selectedNodeId ? nodeIndex.get(selectedNodeId) ?? null : null;
    return canRequestClassUsagesForNode(selectedNode) ? selectedNode?.id ?? null : null;
  }, [nodeIndex, selectedNodeId]);
  const selectedUsageNode = selectedUsageNodeId ? nodeIndex.get(selectedUsageNodeId) ?? null : null;
  const usageScopeNodeId = classDiagramUsageScopeNodeId(view);
  const visibleTypeCount = baseGraph.nodes.length;
  const neighborhoodLimit = view.summary.neighborhoodLimit;
  // 根据索引请求状态解析出空状态文案（加载中/失败/无数据等）。
  const emptyStateCopy = resolveIndexedGraphEmptyState(effectiveClassDiagramRequestState, {
    idleTitle: "尚未加载类图",
    idleDetail: "点击类图入口会构建项目级索引。",
    runningTitle: isStructureOnlyStillLoading ? "正在补齐类图关系" : "正在构建项目类图",
    runningDetail: "正在构建项目类图。",
    failedTitle: "类图加载失败",
    failedDetail: "请重新点击类图入口构建项目级索引。",
    succeededTitle: "索引完成，但当前项目范围没有可展示的类关系",
    succeededDetail: "当前索引没有找到满足范围条件的类型或关系。",
  });
  // 把可视节点转化为 React Flow 期望的节点/边数据结构。
  const flowNodes = useMemo(
    () => buildClassDiagramNodes({
      nodes: visibleNodes,
      selectedNodeId,
      explanationFocusNodeId,
      draftChangedNodeIds,
      draftCompareNodeStatuses: draftCompareProjection?.nodeStatuses,
      projectionIndex: view.projectionIndex ?? null,
      nodeSizeRegistry,
    }),
    [draftChangedNodeIds, draftCompareProjection?.nodeStatuses, explanationFocusNodeId, nodeSizeRegistry, selectedNodeId, view.projectionIndex, visibleNodes],
  );
  const flowEdges = useMemo(
    () => buildClassDiagramEdges({
      edges: visibleEdges,
      draftCompareEdgeStatuses: draftCompareProjection?.edgeStatuses,
    }),
    [draftCompareProjection?.edgeStatuses, visibleEdges],
  );
  const fullNodeCount = view.fullGraph.nodes.length || baseGraph.nodes.length || visibleNodes.length;
  // "定位目标"节点：用于工具栏的快速定位按钮。
  const locateTargetNodeId = view.presentation.target.nodeId ?? view.anchorNodeId ?? visibleNodes[0]?.id ?? null;
  // 隐藏桶中第一个有效 ID，作为"展开更多类型"按钮的目标；无则回退到锚点。
  const expandableNodeId = view.presentation.hiddenBuckets
    .flatMap((bucket) => bucket.nodeIds)
    .find((nodeId) => nodeId.trim().length > 0)
    ?? view.summary.anchorTypeNodeId
    ?? view.anchorNodeId
    ?? null;

  /** 触发"显示更多类型"：基于当前邻域上限扩展，至少多展示 24 个，并涵盖当前已显示类型。 */
  function requestExpandedClassDiagram() {
    const anchorTypeNodeId = view.summary.anchorTypeNodeId ?? view.anchorNodeId ?? view.presentation.target.nodeId ?? null;
    onRequestClassDiagramWithOptions(anchorTypeNodeId, {
      neighborhoodLimit: Math.max((neighborhoodLimit ?? 24) + 24, visibleTypeCount + 24),
    });
  }

  /** 触发"显示更多使用处"：当后端允许时按上限分页增量请求（组+50、条目+200）。 */
  function requestMoreClassUsages() {
    const summary = view.usage?.summary;
    if (!summary || !summary.canRequestMore) {
      return;
    }
    onRequestClassUsages(summary.targetNodeId, {
      scopeNodeId: usageScopeNodeId,
      targetQualifiedName: summary.targetQualifiedName,
      maxUsageGroups: summary.maxUsageGroups + 50,
      maxUsageEntries: summary.maxUsageEntries + 200,
      includeImports: summary.includeImports,
    });
  }

  return (
    <section className="graph-stage-view class-diagram-view" data-testid="class-diagram-view">
      <GraphViewShell
        presentation={view.presentation}
        visibleNodeCount={visibleNodes.length}
        fullNodeCount={fullNodeCount}
        query={query}
        scope=""
        onQueryChange={setQuery}
        onScopeChange={() => undefined}
        onLocateTarget={() => {
          if (locateTargetNodeId) {
            onSelectNode(locateTargetNodeId);
          }
        }}
        onExpand={() => {
          if (expandableNodeId) {
            requestExpandedClassDiagram();
          }
        }}
      >
      {selectedUsageNodeId ? (
        <div className="class-diagram-action-strip" aria-label="类图操作">
          <Button
            compact
            onClick={() => onRequestClassUsages(
              selectedUsageNodeId,
              classUsageRequestOptionsForNode(selectedUsageNode, usageScopeNodeId),
            )}
          >
            查找使用处
          </Button>
        </div>
      ) : null}
      <div className={view.usage ? "class-diagram-workspace has-usage-panel" : "class-diagram-workspace"}>
        <GraphFlowSurface
          nodes={visibleNodes}
          edges={visibleEdges}
          flowNodes={flowNodes}
          flowEdges={flowEdges}
          nodeTypes={CLASS_DIAGRAM_NODE_TYPES}
          viewportMode="CLASS_DIAGRAM"
          viewportPolicy="readable-fit"
          viewportResetKey={viewportResetKey}
          anchorNodeId={usageScopeNodeId ?? view.anchorNodeId ?? null}
          selectedNodeId={selectedNodeId}
          focusNodeRequest={focusNodeRequest}
          selectedGroupNodeIds={selectedGroupNodeIds}
          experiments={experiments}
          editable={false}
          layoutEditable
          panOnDrag
          panOnScroll
          panOnScrollMode="free"
          panOnScrollSpeed={0.8}
          zoomOnScroll
          preventScrolling={false}
          nodeClickDistance={6}
          paneClickDistance={6}
          groupSelectionEnabled={false}
          emptyState={(
            <CanvasEmptyState
              isLoading={isLayoutLoading}
              loadingTitle="正在整理类图"
              idleTitle={emptyStateCopy.title}
            />
          )}
          buildPaneActions={({ visibleNodeCount, hasGroupedSelection, close }) => {
            const actions: GraphContextMenuAction[] = [];
            if (visibleNodeCount > 0) {
              const anchorTypeNodeId = view.summary.anchorTypeNodeId ?? view.anchorNodeId ?? null;
              actions.push(
                {
                  id: "format-layout",
                  label: "重新整理布局",
                  onSelect: () => {
                    layoutState.requestRelayout();
                    close();
                  },
                },
                {
                  id: "class-diagram-more-types",
                  label: "显示更多类型",
                  onSelect: () => {
                    requestExpandedClassDiagram();
                    close();
                  },
                },
              );
              if (view.summary.relationCompleteness === "STRUCTURE_ONLY") {
                actions.push({
                  id: "class-diagram-enrich-relations",
                  label: "补齐当前范围调用",
                  onSelect: () => {
                    onRequestClassDiagramWithOptions(anchorTypeNodeId, {
                      neighborhoodLimit: neighborhoodLimit ?? 24,
                      relationDetail: "SCOPED_BODY_RELATIONS",
                    });
                    close();
                  },
                });
              }
              actions.push(
                {
                  id: "open-qa",
                  label: hasGroupedSelection ? "问答已框选范围" : "问答当前范围",
                  onSelect: () => {
                    onOpenQa();
                    close();
                  },
                },
              );
              if (view.usage?.summary.canRequestMore) {
                actions.push({
                  id: "class-diagram-more-usages",
                  label: "显示更多使用处",
                  onSelect: () => {
                    requestMoreClassUsages();
                    close();
                  },
                });
              }
            }
            return actions;
          }}
          buildNodeActions={({ nodeId, close }) => {
            const node = nodeIndex.get(nodeId) ?? null;
            return classDiagramNodeActions({
              nodeId,
              node,
              canOpenSource: canNavigateToSource(node ?? { type: "CLASS", location: undefined, signature: undefined }),
              canRequestClassUsages: canRequestClassUsagesForNode(node),
              collapsed: collapsedNodeIdSet.has(nodeId),
              onInspectNode,
              onRequestSourceNavigation,
              onRequestBeautification,
              onPrimeQuestionComposer,
              onRequestClassDiagram,
              onRequestClassUsages,
              usageScopeNodeId,
              onOpenQa,
              onToggleCollapseNode,
              onFormatLayout: layoutState.requestRelayout,
              onClose: close,
            });
          }}
          buildEdgeActions={({ edgeId, close }) =>
            buildSharedEdgeActions({
              analysisDisplayMode: "CLASS_DIAGRAM",
              editable: false,
              edgeId,
              onDeleteEdge: () => undefined,
              onClose: close,
            })
          }
          onSelectNode={onSelectNode}
          onSelectionGroupChange={onSelectionGroupChange}
          onInspectNode={onInspectNode}
          onCreateEdge={() => undefined}
          onMoveNode={onMoveNode}
          onMoveNodes={onMoveNodes}
          shouldFocusAnchorOnLoad={true}
          fitViewPadding={0.12}
          fitViewMaxZoom={0.9}
          showViewportControls
          showLocateAnchorButton
          nodeViewportSize={classDiagramViewportNodeSize}
        />
        {view.usage ? (
          <div className="class-diagram-usage-dock">
            <ClassUsagePanel usage={view.usage} />
          </div>
        ) : null}
      </div>
      </GraphViewShell>
    </section>
  );
}
