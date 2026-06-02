import { useMemo, useState } from "react";
import { buildEdgeActions as buildSharedEdgeActions } from "../../components/graph/actions/actionSchema";
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

interface ClassDiagramViewProps extends IndexedReadonlyStageProps {
  view: ClassDiagramViewDocument;
}

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

function positiveMetadataNumber(node: LinkGraphNode, key: string): number | null {
  const value = Number(node.metadata?.[key]);
  return Number.isFinite(value) && value > 0 ? value : null;
}

function classDiagramViewportNodeSize(node: LinkGraphNode) {
  return {
    width: classDiagramNodeCardWidth(node),
    height: positiveMetadataNumber(node, "layout.estimatedHeight") ?? estimateUmlNodeHeight(node),
  };
}

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

function classDiagramNodeActions(args: {
  nodeId: string;
  node: LinkGraphNode | null;
  canOpenSource: boolean;
  collapsed: boolean;
  onInspectNode: (nodeId: string) => void;
  onRequestSourceNavigation: (nodeId: string) => void;
  onRequestBeautification: (selectedNodeId?: string) => void;
  onRequestQa: (selectedNodeId?: string) => void;
  onRequestClassDiagram: (scopeNodeId?: string | null) => void;
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
        args.onRequestQa(args.nodeId);
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
  onRequestQa = () => undefined,
  onRequestClassDiagram = () => undefined,
  onRequestClassDiagramWithOptions = () => undefined,
  onToggleCollapseNode = () => undefined,
  onOpenQa = () => undefined,
}: ClassDiagramViewProps) {
  const [query, setQuery] = useState("");
  const nodeSizeRegistry = useMemo(() => createNodeSizeRegistry(), []);
  const effectiveClassDiagramRequestState = indexedGraphRequestStates?.CLASS_DIAGRAM
    ?? (view.summary.indexed ? { phase: "SUCCEEDED" as const } : null);
  const isStructureOnlyStillLoading = view.summary.relationCompleteness === "STRUCTURE_ONLY"
    && effectiveClassDiagramRequestState?.phase === "RUNNING";
  const baseGraph = isStructureOnlyStillLoading
    ? { nodes: [], edges: [] }
    : view.visibleGraph;
  const presentedGraph = draftCompareProjection?.compareGraph ?? baseGraph;
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
  const layoutState = useMeasuredLayout({
    graph: layoutGraph,
    anchorNodeId: view.anchorNodeId ?? null,
    nodeSizeRegistry,
    layout: layoutClassDiagramView,
    debugLabel: "class-diagram",
  });

  const hiddenNodeIdSet = useMemo(() => new Set(hiddenNodeIds), [hiddenNodeIds]);
  const collapsedNodeIdSet = useMemo(() => new Set(collapsedNodeIds), [collapsedNodeIds]);
  const layoutNodeIds = useMemo(() => new Set(layoutState.nodes.map((node) => node.id)), [layoutState.nodes]);
  const effectiveHiddenNodeIdSet = useMemo(() => {
    if (layoutState.nodes.length === 0 || hiddenNodeIdSet.size === 0) {
      return hiddenNodeIdSet;
    }
    const wouldHideAllLayoutNodes = layoutState.nodes.every((node) => hiddenNodeIdSet.has(node.id));
    return wouldHideAllLayoutNodes ? new Set<string>() : hiddenNodeIdSet;
  }, [hiddenNodeIdSet, layoutState.nodes]);
  const visibleNodes = useMemo(
    () => layoutState.nodes.filter((node) => !effectiveHiddenNodeIdSet.has(node.id)),
    [effectiveHiddenNodeIdSet, layoutState.nodes],
  );
  const visibleNodeIds = useMemo(() => new Set(visibleNodes.map((node) => node.id)), [visibleNodes]);
  const visibleEdges = useMemo(() => filterVisibleClassDiagramEdges(layoutState.edges, visibleNodeIds), [layoutState.edges, visibleNodeIds]);
  const viewportResetKey = useMemo(
    () => [
      view.summary.relationCompleteness ?? "UNKNOWN",
      visibleNodes.length,
      visibleEdges.length,
      view.anchorNodeId ?? "",
    ].join(":"),
    [view.anchorNodeId, view.summary.relationCompleteness, visibleEdges.length, visibleNodes.length],
  );
  const isLayoutLoading = layoutState.layoutPending && layoutGraph.nodes.length > 0 && layoutState.nodes.length === 0;
  const nodeIndex = useMemo(() => new Map(visibleNodes.map((node) => [node.id, node])), [visibleNodes]);
  const visibleTypeCount = baseGraph.nodes.length;
  const neighborhoodLimit = view.summary.neighborhoodLimit;
  const memberLimit = view.summary.memberLimit ?? 5;
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
  const locateTargetNodeId = view.presentation.target.nodeId ?? view.anchorNodeId ?? visibleNodes[0]?.id ?? null;
  const expandableNodeId = view.presentation.hiddenBuckets
    .flatMap((bucket) => bucket.nodeIds)
    .find((nodeId) => nodeId.trim().length > 0)
    ?? view.summary.anchorTypeNodeId
    ?? view.anchorNodeId
    ?? null;

  function requestExpandedClassDiagram() {
    const anchorTypeNodeId = view.summary.anchorTypeNodeId ?? view.anchorNodeId ?? view.presentation.target.nodeId ?? null;
    onRequestClassDiagramWithOptions(anchorTypeNodeId, {
      neighborhoodLimit: Math.max((neighborhoodLimit ?? 24) + 24, visibleTypeCount + 24),
      memberLimit,
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
      <GraphFlowSurface
        nodes={visibleNodes}
        edges={visibleEdges}
        flowNodes={flowNodes}
        flowEdges={flowEdges}
        nodeTypes={CLASS_DIAGRAM_NODE_TYPES}
        viewportMode="CLASS_DIAGRAM"
        viewportPolicy="readable-fit"
        viewportResetKey={viewportResetKey}
        anchorNodeId={view.anchorNodeId ?? null}
        selectedNodeId={selectedNodeId}
        focusNodeRequest={focusNodeRequest}
        selectedGroupNodeIds={selectedGroupNodeIds}
        experiments={experiments}
        editable={false}
        layoutEditable={false}
        emptyState={(
          isLayoutLoading ? (
            <div className="canvas-empty-state">
              <strong>正在整理类图</strong>
            </div>
          ) : (
            <div className="canvas-empty-state">
              <strong>{emptyStateCopy.title}</strong>
            </div>
          )
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
              {
                id: "class-diagram-more-members",
                label: "显示更多成员",
                onSelect: () => {
                  onRequestClassDiagramWithOptions(anchorTypeNodeId, {
                    neighborhoodLimit: neighborhoodLimit ?? 24,
                    memberLimit: memberLimit + 5,
                  });
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
          classDiagramNodeActions({
            nodeId,
            node: nodeIndex.get(nodeId) ?? null,
            canOpenSource: canNavigateToSource(nodeIndex.get(nodeId) ?? { type: "CLASS", location: undefined, signature: undefined }),
            collapsed: collapsedNodeIdSet.has(nodeId),
            onInspectNode,
            onRequestSourceNavigation,
            onRequestBeautification,
            onRequestQa,
            onRequestClassDiagram,
            onOpenQa,
            onToggleCollapseNode,
            onFormatLayout: layoutState.requestRelayout,
            onClose: close,
          })
        }
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
        showViewportControls={false}
        showLocateAnchorButton={false}
        nodeViewportSize={classDiagramViewportNodeSize}
      />
      </GraphViewShell>
    </section>
  );
}
