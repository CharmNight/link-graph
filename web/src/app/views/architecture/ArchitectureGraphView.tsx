import { useCallback, useMemo, useState } from "react";
import { buildEdgeActions as buildSharedEdgeActions } from "../../components/graph/actions/actionSchema";
import { CanvasEmptyState } from "../../components/graph/CanvasEmptyState";
import type { GraphContextMenuAction } from "../../components/graph/actions/actionSchema";
import { createNodeSizeRegistry } from "../../graph/nodeSizeRegistry";
import { architectureGraphNodeCardWidth } from "../../graphNodeSizing";
import { GraphViewShell } from "../../presentation/GraphViewShell";
import { GraphFlowSurface } from "../../reactflow/GraphFlowSurface";
import { useMeasuredLayout, type MeasuredLayoutRequest } from "../../reactflow/useMeasuredLayout";
import { canNavigateToSource } from "../../sourceNavigation";
import type { ArchitectureGraphViewDocument, LinkGraphEdge, LinkGraphNode } from "../../types";
import type { IndexedReadonlyStageProps } from "../viewStageProps";
import { resolveIndexedGraphEmptyState } from "../indexedGraphEmptyState";
import { layoutArchitectureGraphView } from "./architectureGraphLayout";
import {
  ARCHITECTURE_GRAPH_NODE_TYPES,
  buildArchitectureGraphEdges,
  buildArchitectureGraphNodes,
} from "./architectureGraphNodes";

interface ArchitectureGraphViewProps extends IndexedReadonlyStageProps {
  view: ArchitectureGraphViewDocument;
}

const LAYER_FILTERS = [
  { value: "ALL", label: "全部来源" },
  { value: "PROJECT_SOURCE", label: "项目代码" },
  { value: "EXTERNAL_LIBRARY", label: "三方依赖" },
  { value: "JDK", label: "JDK" },
  { value: "RESOURCE", label: "资源文件" },
  { value: "AGGREGATE", label: "聚合分组" },
] as const;

type ArchitectureLayerFilter = typeof LAYER_FILTERS[number]["value"];

const DEPENDENCY_LAYER_FILTERS = new Set<ArchitectureLayerFilter>(["EXTERNAL_LIBRARY", "JDK"]);

function isProjectStructureGraph(view: ArchitectureGraphViewDocument): boolean {
  return view.summary.indexed?.scopeKind === "PROJECT";
}

function nodeMatchesQuery(node: LinkGraphNode, query: string): boolean {
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
    node.metadata?.["architecture.module"],
  ].some((value) => value?.toLowerCase().includes(normalized));
}

function isDependencyLayerFilter(layerFilter: ArchitectureLayerFilter): boolean {
  return DEPENDENCY_LAYER_FILTERS.has(layerFilter);
}

function isLayerLoaded(
  layerFilter: ArchitectureLayerFilter,
  includeExternalLibraries: boolean,
  includeJdk: boolean,
): boolean {
  if (layerFilter === "EXTERNAL_LIBRARY") {
    return includeExternalLibraries;
  }
  if (layerFilter === "JDK") {
    return includeJdk;
  }
  return true;
}

function connectedDependencyNodeIds(
  graph: { nodes: LinkGraphNode[]; edges: LinkGraphEdge[] },
  layerFilter: ArchitectureLayerFilter,
  query: string,
  hiddenNodeIds: ReadonlySet<string>,
): Set<string> {
  const nodeById = new Map(graph.nodes.map((node) => [node.id, node]));
  const dependencyNodeIds = new Set(
    graph.nodes
      .filter((node) =>
        !hiddenNodeIds.has(node.id) &&
        node.metadata?.["indexed.layerKind"] === layerFilter &&
        nodeMatchesQuery(node, query)
      )
      .map((node) => node.id),
  );
  const connectedNodeIds = new Set(dependencyNodeIds);
  graph.edges.forEach((edge) => {
    const sourceIsDependency = dependencyNodeIds.has(edge.source);
    const targetIsDependency = dependencyNodeIds.has(edge.target);
    if (!sourceIsDependency && !targetIsDependency) {
      return;
    }
    const sourceNode = nodeById.get(edge.source);
    const targetNode = nodeById.get(edge.target);
    if (sourceNode && !hiddenNodeIds.has(sourceNode.id)) {
      connectedNodeIds.add(sourceNode.id);
    }
    if (targetNode && !hiddenNodeIds.has(targetNode.id)) {
      connectedNodeIds.add(targetNode.id);
    }
  });
  return connectedNodeIds;
}

function architectureNodeActions(args: {
  nodeId: string;
  node: LinkGraphNode | null;
  canOpenSource: boolean;
  collapsed: boolean;
  onInspectNode: (nodeId: string) => void;
  onRequestSourceNavigation: (nodeId: string) => void;
  onRequestBeautification: (selectedNodeId?: string) => void;
  onPrimeQuestionComposer: (selectedNodeId?: string) => void;
  onOpenQa: (selectedNodeId?: string) => void;
  onToggleCollapseNode: (nodeId: string) => void;
  onRequestClassDiagram: (scopeNodeId?: string | null) => void;
  onRequestPackageDependencyGraph: (
    packageName?: string | null,
    options?: { includeExternalLibraries?: boolean; includeJdk?: boolean },
  ) => void;
  includeExternalLibraries: boolean;
  includeJdk: boolean;
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
      id: "open-class-diagram",
      label: "查看此范围类图",
      onSelect: () => {
        args.onRequestClassDiagram(args.node?.metadata?.["architecture.drillDownClassScope"] ?? args.nodeId);
        args.onClose();
      },
    },
    {
      id: "open-package-graph",
      label: "查看包依赖视图",
      onSelect: () => {
        args.onRequestPackageDependencyGraph(args.node?.metadata?.["architecture.package"] ?? null, {
          includeExternalLibraries: args.includeExternalLibraries,
          includeJdk: args.includeJdk,
        });
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
  actions.push(
    {
      id: "beautify-node",
      label: "讲解当前架构节点",
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

export function ArchitectureGraphView({
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
  onRequestArchitectureGraph = () => undefined,
  onRequestClassDiagram = () => undefined,
  onRequestPackageDependencyGraph = () => undefined,
  onToggleCollapseNode = () => undefined,
  onOpenQa = () => undefined,
}: ArchitectureGraphViewProps) {
  const [query, setQuery] = useState("");
  const [layerFilter, setLayerFilter] = useState<ArchitectureLayerFilter>("ALL");
  const [scope, setScope] = useState(view.presentation.controls.primaryScope);
  const nodeSizeRegistry = useMemo(() => createNodeSizeRegistry(), []);
  const presentedGraph = draftCompareProjection?.compareGraph ?? view.visibleGraph;
  const isProjectStructureView = isProjectStructureGraph(view) && draftCompareProjection?.compareGraph == null;
  const indexedSummary = view.summary.indexed ?? null;
  const includeExternalLibraries = indexedSummary?.includeExternalLibraries ?? false;
  const includeJdk = indexedSummary?.includeJdk ?? false;
  const dependencyLayerRequested = isProjectStructureView && isDependencyLayerFilter(layerFilter);
  const dependencyLayerLoaded = dependencyLayerRequested && isLayerLoaded(layerFilter, includeExternalLibraries, includeJdk);
  const filterGraph = dependencyLayerLoaded ? view.fullGraph : presentedGraph;
  const hiddenNodeIdSet = useMemo(() => new Set(hiddenNodeIds), [hiddenNodeIds]);
  const collapsedNodeIdSet = useMemo(() => new Set(collapsedNodeIds), [collapsedNodeIds]);
  const dependencyNodeIds = useMemo(
    () => dependencyLayerLoaded
      ? connectedDependencyNodeIds(filterGraph, layerFilter, query, hiddenNodeIdSet)
      : null,
    [dependencyLayerLoaded, filterGraph, hiddenNodeIdSet, layerFilter, query],
  );
  const baseNodes = useMemo(
    () => filterGraph.nodes.filter((node) => {
      if (hiddenNodeIdSet.has(node.id)) {
        return false;
      }
      if (dependencyNodeIds) {
        return dependencyNodeIds.has(node.id);
      }
      if (dependencyLayerRequested && !dependencyLayerLoaded) {
        return nodeMatchesQuery(node, query);
      }
      return (layerFilter === "ALL" || node.metadata?.["indexed.layerKind"] === layerFilter) &&
        nodeMatchesQuery(node, query);
    }),
    [dependencyLayerLoaded, dependencyLayerRequested, dependencyNodeIds, filterGraph.nodes, hiddenNodeIdSet, layerFilter, query],
  );
  const baseNodeIds = useMemo(() => new Set(baseNodes.map((node) => node.id)), [baseNodes]);
  const baseEdges = useMemo(
    () => filterGraph.edges.filter((edge) => baseNodeIds.has(edge.source) && baseNodeIds.has(edge.target)),
    [baseNodeIds, filterGraph.edges],
  );
  const layoutInputNodes = baseNodes;
  const layoutInputNodeIds = useMemo(() => new Set(layoutInputNodes.map((node) => node.id)), [layoutInputNodes]);
  const layoutInputEdges = useMemo(
    () => baseEdges.filter((edge) =>
      layoutInputNodeIds.has(edge.source) &&
      layoutInputNodeIds.has(edge.target)
    ),
    [baseEdges, layoutInputNodeIds],
  );
  const layoutGraph = useMemo(
    () => ({
      nodes: layoutInputNodes,
      edges: layoutInputEdges,
      nodeCount: layoutInputNodes.length,
      edgeCount: layoutInputEdges.length,
      truncated: filterGraph.truncated,
    }),
    [filterGraph.truncated, layoutInputEdges, layoutInputNodes],
  );
  const graphAnchorNodeId = view.anchorNodeId ?? view.presentation.target.nodeId ?? null;
  const architectureLayout = useCallback(
    (request: MeasuredLayoutRequest) => layoutArchitectureGraphView({
      ...request,
      projectStructureView: isProjectStructureView,
    }),
    [isProjectStructureView],
  );
  const layoutState = useMeasuredLayout({
    graph: layoutGraph,
    anchorNodeId: graphAnchorNodeId,
    nodeSizeRegistry,
    layout: architectureLayout,
    debugLabel: "architecture",
  });
  const visibleNodes = layoutState.nodes;
  const visibleEdges = layoutState.edges;
  const isLayoutLoading = layoutState.layoutPending && layoutInputNodes.length > 0 && layoutState.nodes.length === 0;
  const nodeIndex = useMemo(() => new Map(visibleNodes.map((node) => [node.id, node])), [visibleNodes]);
  const isPackageScope = indexedSummary?.scopeKind === "PACKAGE";
  const flowNodes = useMemo(
    () =>
      buildArchitectureGraphNodes({
        nodes: visibleNodes,
        selectedNodeId,
        targetNodeId: graphAnchorNodeId,
        explanationFocusNodeId,
        draftChangedNodeIds,
        draftCompareNodeStatuses: draftCompareProjection?.nodeStatuses,
        focusNodeIds: !isProjectStructureView && selectedNodeId ? architectureFocusNodeIds(selectedNodeId, visibleEdges) : null,
        projectionIndex: view.projectionIndex ?? null,
        nodeSizeRegistry,
      }),
    [
      draftChangedNodeIds,
      draftCompareProjection?.nodeStatuses,
      explanationFocusNodeId,
      nodeSizeRegistry,
      view.projectionIndex,
      graphAnchorNodeId,
      selectedNodeId,
      visibleEdges,
      visibleNodes,
    ],
  );
  const flowEdges = useMemo(
    () => buildArchitectureGraphEdges({
      edges: visibleEdges,
      labelVisibility: isProjectStructureView ? "selected" : selectedNodeId ? "focus" : "selected",
      draftCompareEdgeStatuses: draftCompareProjection?.edgeStatuses,
      focusEdgeIds: !isProjectStructureView && selectedNodeId ? architectureFocusEdgeIds(selectedNodeId, visibleEdges) : null,
    }),
    [draftCompareProjection?.edgeStatuses, isProjectStructureView, selectedNodeId, visibleEdges],
  );
  const loadedNodeCount = isProjectStructureView
    ? indexedSummary?.scopedNodeCount ?? indexedSummary?.candidateNodeCount ?? indexedSummary?.visibleNodeCount ?? baseNodes.length
    : indexedSummary?.visibleNodeCount ?? baseNodes.length;
  const emptyStateCopy = resolveIndexedGraphEmptyState(indexedGraphRequestStates?.ARCHITECTURE, {
    idleTitle: "尚未加载架构聚合",
    idleDetail: "点击架构入口会构建项目级架构索引。",
    runningTitle: "正在构建架构聚合索引",
    runningDetail: "正在构建架构聚合索引。",
    failedTitle: "架构聚合加载失败",
    failedDetail: "请重新点击架构入口构建项目级架构索引。",
    succeededTitle: "索引完成，但当前项目范围没有可展示的架构聚合",
    succeededDetail: "当前索引没有找到满足筛选条件的聚合节点。",
  });
  const viewportResetKey = [
    indexedSummary?.scopeKind ?? "UNKNOWN",
    indexedSummary?.scopeLabel ?? "",
    includeExternalLibraries ? "external:on" : "external:off",
    includeJdk ? "jdk:on" : "jdk:off",
    `layer:${layerFilter}`,
  ].join("|");

  function requestCurrentArchitectureScope(options: { includeExternalLibraries: boolean; includeJdk: boolean }) {
    if (isPackageScope) {
      onRequestPackageDependencyGraph(indexedSummary?.scopeLabel ?? null, options);
      return;
    }
    onRequestArchitectureGraph(options);
  }

  function handleLayerFilterChange(nextLayerFilter: ArchitectureLayerFilter) {
    setLayerFilter(nextLayerFilter);
    if (nextLayerFilter === "EXTERNAL_LIBRARY" && !includeExternalLibraries) {
      requestCurrentArchitectureScope({
        includeExternalLibraries: true,
        includeJdk,
      });
      return;
    }
    if (nextLayerFilter === "JDK" && !includeJdk) {
      requestCurrentArchitectureScope({
        includeExternalLibraries,
        includeJdk: true,
      });
    }
  }

  function handleScopeChange(nextScope: string) {
    setScope(nextScope);
    if (nextScope === "包") {
      onRequestPackageDependencyGraph(null, {
        includeExternalLibraries: true,
        includeJdk: true,
      });
      return;
    }
    if (nextScope === "类") {
      onRequestClassDiagram(selectedNodeId ?? graphAnchorNodeId);
      return;
    }
    if (nextScope === "组件") {
      requestCurrentArchitectureScope({
        includeExternalLibraries,
        includeJdk,
      });
    }
  }

  const sourceFilter = (
    <div className="architecture-presentation-filters" aria-label="架构图来源筛选">
      <select
        aria-label="节点来源"
        className="compact-input"
        value={layerFilter}
        onChange={(event) => handleLayerFilterChange(event.target.value as ArchitectureLayerFilter)}
      >
        {LAYER_FILTERS.map((filter) => (
          <option key={filter.value} value={filter.value}>{filter.label}</option>
        ))}
      </select>
    </div>
  );

  return (
    <section className="graph-stage-view architecture-graph-view" data-testid="architecture-graph-view">
      <GraphViewShell
        presentation={view.presentation}
        visibleNodeCount={visibleNodes.length}
        fullNodeCount={loadedNodeCount}
        query={query}
        scope={scope}
        onQueryChange={setQuery}
        onScopeChange={handleScopeChange}
        onLocateTarget={() => {
          if (graphAnchorNodeId) {
            onSelectNode(graphAnchorNodeId);
          }
        }}
        onExpand={layoutState.requestRelayout}
      >
        {sourceFilter}
        <GraphFlowSurface
          nodes={visibleNodes}
          edges={visibleEdges}
          flowNodes={flowNodes}
          flowEdges={flowEdges}
          nodeTypes={ARCHITECTURE_GRAPH_NODE_TYPES}
          viewportMode="ARCHITECTURE_GRAPH"
          viewportPolicy={isProjectStructureView ? "readable-fit" : "fit"}
          viewportResetKey={viewportResetKey}
          anchorNodeId={graphAnchorNodeId}
          selectedNodeId={selectedNodeId}
          focusNodeRequest={focusNodeRequest}
          selectedGroupNodeIds={selectedGroupNodeIds}
          experiments={experiments}
          editable={false}
          layoutEditable
          emptyState={(
            <CanvasEmptyState
              isLoading={isLayoutLoading}
              loadingTitle="正在整理架构图"
              idleTitle={emptyStateCopy.title}
            />
          )}
        buildPaneActions={({ visibleNodeCount, hasGroupedSelection, close }) => {
          const actions: GraphContextMenuAction[] = [];
          if (visibleNodeCount > 0) {
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
                id: "open-qa",
                label: hasGroupedSelection ? "问答已框选范围" : "问答当前范围",
                onSelect: () => {
                  onOpenQa();
                  close();
                },
              },
            );
          }
          return actions;
        }}
        buildNodeActions={({ nodeId, close }) =>
          architectureNodeActions({
            nodeId,
            node: nodeIndex.get(nodeId) ?? null,
            canOpenSource: canNavigateToSource(nodeIndex.get(nodeId) ?? { type: "PACKAGE", location: undefined, signature: undefined }),
            collapsed: collapsedNodeIdSet.has(nodeId),
            onInspectNode,
            onRequestSourceNavigation,
            onRequestBeautification,
            onPrimeQuestionComposer,
            onOpenQa,
            onToggleCollapseNode,
            onRequestClassDiagram,
            onRequestPackageDependencyGraph,
            includeExternalLibraries: true,
            includeJdk: true,
            onFormatLayout: layoutState.requestRelayout,
            onClose: close,
          })
        }
        buildEdgeActions={({ edgeId, close }) =>
          buildSharedEdgeActions({
            analysisDisplayMode: "ARCHITECTURE_GRAPH",
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
        shouldFocusAnchorOnLoad
        nodeViewportSize={() => ({ width: architectureGraphNodeCardWidth(), height: 116 })}
        fitViewPadding={isProjectStructureView ? 0.14 : undefined}
        fitViewMaxZoom={isProjectStructureView ? 0.82 : undefined}
        showViewportControls={!isProjectStructureView}
        showLocateAnchorButton={!isProjectStructureView}
      />
      </GraphViewShell>
    </section>
  );
}

function architectureFocusEdgeIds(selectedNodeId: string, edges: LinkGraphEdge[]): Set<string> {
  return new Set(
    edges
      .filter((edge) => edge.source === selectedNodeId || edge.target === selectedNodeId)
      .map((edge) => edge.id),
  );
}

function architectureFocusNodeIds(selectedNodeId: string, edges: LinkGraphEdge[]): Set<string> {
  const nodeIds = new Set<string>([selectedNodeId]);
  edges.forEach((edge) => {
    if (edge.source === selectedNodeId) {
      nodeIds.add(edge.target);
    }
    if (edge.target === selectedNodeId) {
      nodeIds.add(edge.source);
    }
  });
  return nodeIds;
}
