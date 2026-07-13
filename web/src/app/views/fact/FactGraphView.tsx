import { useMemo, useState } from "react";
import { buildEdgeActions, buildNodeActions, buildPaneActions } from "../../components/graph/actions/actionSchema";
import { CanvasEmptyState } from "../../components/graph/CanvasEmptyState";
import { overflowPresentation } from "../../components/graph/nodes/nodePresentation";
import { canEditProjectedEdge, canEditProjectedNode } from "../../graphProjectionPermissions";
import { nodeCardWidth } from "../../graphNodeSizing";
import { GraphCanvasLanes } from "../../presentation/GraphCanvasLanes";
import { GraphViewShell } from "../../presentation/GraphViewShell";
import { useGraphView } from "../../reactflow/useGraphView";
import { shouldFocusAnchor } from "../../reactflow/viewportPolicy";
import { GraphFlowSurface } from "../../reactflow/GraphFlowSurface";
import { canNavigateToSource } from "../../sourceNavigation";
import type { FactGraphViewDocument, LinkGraphNode } from "../../types";
import { DraftCompareSummary } from "../../components/DraftCompareSummary";
import type { EditableStageProps } from "../viewStageProps";
import { factGraphLayoutSizeSignature, layoutFactGraphView } from "./factGraphLayout";
import { buildFactGraphEdges, buildFactGraphNodes, FACT_GRAPH_NODE_TYPES } from "./factGraphNodes";

/** 事实图主视图的属性：继承可编辑舞台通用属性，并接收具体的事实图视图文档。 */
interface FactGraphViewProps extends EditableStageProps {
  view: FactGraphViewDocument;
}

/** 当节点索引查不到时使用的占位空节点，避免后续逻辑因 undefined 崩溃。 */
function emptyNode(): LinkGraphNode {
  return {
    id: "",
    type: "DOC_PAGE" as const,
    title: "",
    inputs: [],
    outputs: [],
    confidence: "VERIFIED" as const,
    binding: "CODE_BOUND" as const,
    metadata: {},
  };
}

/**
 * 节点搜索匹配：把查询串归一化为小写后，
 * 在节点的标题、签名、定位、文档以及若干架构/流程元数据字段中做包含判断。
 */
function nodeMatchesFactQuery(node: LinkGraphNode, query: string): boolean {
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
    node.metadata?.["flow.owner"],
  ].some((value) => value?.toLowerCase().includes(normalized));
}

/**
 * 事实图主视图组件：
 * 负责把视图文档中的节点/边经过搜索过滤、作用域切换后交给布局与 React Flow 表面渲染，
 * 同时整合工具栏、空状态、上下文菜单（节点/边/画布）以及与外部编辑流程的回调。
 */
export function FactGraphView({
  view,
  selectedNodeId,
  focusNodeRequest = null,
  explanationFocusNodeId = null,
  draftChangedNodeIds = [],
  draftCompareProjection = null,
  selectedGroupNodeIds = [],
  hiddenNodeIds = [],
  collapsedNodeIds = [],
  collapsedDescendantCountByNodeId = {},
  experiments = null,
  onAddNode,
  onSelectNode,
  onSelectionGroupChange = () => undefined,
  onInspectNode,
  onDeleteNode,
  onDeleteNodeSubtree = () => undefined,
  onCreateEdge,
  onDeleteEdge,
  onMoveNode,
  onMoveNodes,
  onRequestSourceNavigation,
  onRequestBeautification = () => undefined,
  onPrimeQuestionComposer = () => undefined,
  onToggleCollapseNode = () => undefined,
  onOpenQa = () => undefined,
  onImportMermaid,
  onExpandOverflowNode = () => undefined,
  onExpandInvocation = () => undefined,
  onRemoveInvocationExpansion = () => undefined,
}: FactGraphViewProps) {
  const [query, setQuery] = useState("");
  const [scope, setScope] = useState(
    view.presentation.controls.primaryScope || view.presentation.controls.availableScopes[0] || "",
  );
  const scopedGraph = useMemo(() => {
    if (draftCompareProjection) {
      return draftCompareProjection.compareGraph;
    }
    return scope === "全部" && view.fullGraph.nodes.length > 0 ? view.fullGraph : view.visibleGraph;
  }, [draftCompareProjection, scope, view.fullGraph, view.visibleGraph]);
  const presentedGraph = useMemo(() => {
    if (!query.trim()) {
      return scopedGraph;
    }
    const nodes = scopedGraph.nodes.filter((node) => nodeMatchesFactQuery(node, query));
    const nodeIds = new Set(nodes.map((node) => node.id));
    return {
      ...scopedGraph,
      nodes,
      edges: scopedGraph.edges.filter((edge) => nodeIds.has(edge.source) && nodeIds.has(edge.target)),
      nodeCount: nodes.length,
    };
  }, [query, scopedGraph]);
  const collapsedNodeIdSet = useMemo(() => new Set(collapsedNodeIds), [collapsedNodeIds]);
  const {
    visibleNodes,
    visibleEdges,
    nodeIndex,
    nodeSizeRegistry,
    isLayoutLoading,
    requestRelayout,
  } = useGraphView({
    graph: presentedGraph,
    anchorNodeId: view.anchorNodeId ?? null,
    selectedNodeId,
    hiddenNodeIds,
    collapsedNodeIds,
    layout: layoutFactGraphView,
    layoutSizeSignature: factGraphLayoutSizeSignature,
    debugLabel: "fact",
  });

  const flowNodes = useMemo(
    () =>
      buildFactGraphNodes({
        nodes: visibleNodes,
        selectedNodeId,
        explanationFocusNodeId,
        draftChangedNodeIds,
        draftCompareNodeStatuses: draftCompareProjection?.nodeStatuses,
        collapsedNodeIds,
        collapsedDescendantCountByNodeId,
        projectionIndex: view.projectionIndex ?? null,
        onExpandOverflowNode,
        nodeSizeRegistry,
      }),
    [
      visibleNodes,
      selectedNodeId,
      explanationFocusNodeId,
      draftChangedNodeIds,
      draftCompareProjection?.nodeStatuses,
      collapsedNodeIds,
      collapsedDescendantCountByNodeId,
      view.projectionIndex,
      onExpandOverflowNode,
      nodeSizeRegistry,
    ],
  );
  const flowEdges = useMemo(
    () => buildFactGraphEdges({
      edges: visibleEdges,
      draftCompareEdgeStatuses: draftCompareProjection?.edgeStatuses,
    }),
    [draftCompareProjection?.edgeStatuses, visibleEdges],
  );

  const fullNodeCount = view.summary.fullNodeCount || view.fullGraph.nodes.length || scopedGraph.nodes.length;
  const locateTargetNodeId = view.presentation.target.nodeId ?? view.anchorNodeId ?? visibleNodes[0]?.id ?? null;
  const expandableNodeId = view.presentation.hiddenBuckets
    .flatMap((bucket) => bucket.nodeIds)
    .find((nodeId) => nodeId.trim().length > 0) ?? locateTargetNodeId;

  return (
    <section className="graph-stage-view fact-graph-view" data-testid="fact-graph-view">
      <GraphViewShell
        presentation={view.presentation}
        visibleNodeCount={visibleNodes.length}
        fullNodeCount={fullNodeCount}
        query={query}
        scope={scope}
        onQueryChange={setQuery}
        onScopeChange={setScope}
        onLocateTarget={() => {
          if (locateTargetNodeId) {
            onSelectNode(locateTargetNodeId);
          }
        }}
        onExpand={() => {
          if (expandableNodeId) {
            onExpandOverflowNode(expandableNodeId);
          }
        }}
      >
        {draftCompareProjection ? <DraftCompareSummary projection={draftCompareProjection} /> : null}
      <GraphFlowSurface
        nodes={visibleNodes}
        edges={visibleEdges}
        flowNodes={flowNodes}
        flowEdges={flowEdges}
        nodeTypes={FACT_GRAPH_NODE_TYPES}
        viewportMode="FACT_GRAPH"
        viewportOverlay={({ nodes }) => (
          <GraphCanvasLanes
            lanes={view.presentation.lanes}
            nodes={nodes}
          />
        )}
        anchorNodeId={view.anchorNodeId ?? null}
        selectedNodeId={selectedNodeId}
        focusNodeRequest={focusNodeRequest}
        selectedGroupNodeIds={selectedGroupNodeIds}
        experiments={experiments}
        editable
        layoutEditable
        emptyState={(
          <CanvasEmptyState
            isLoading={isLayoutLoading}
            loadingTitle="正在整理链路画布"
            idleTitle="画布里还没有节点"
          />
        )}
        buildPaneActions={({ position, hasGroupedSelection, visibleNodeCount, close }) =>
          buildPaneActions({
            analysisDisplayMode: "FACT_GRAPH",
            editable: true,
            visibleNodeCount,
            hasGroupedSelection,
            position,
            onAddNode,
            onImportMermaid,
            onFormatLayout: requestRelayout,
            onOpenQa,
            onClose: close,
          })
        }
        buildNodeActions={({ nodeId, close }) =>
          {
            const node = nodeIndex.get(nodeId) ?? emptyNode();
            const isExpandableInvocation = node.type === "FLOW_ACTION" &&
              node.metadata?.["flow.kind"] === "INVOCATION" &&
              Boolean(node.signature?.trim());
            return buildNodeActions({
              analysisDisplayMode: "FACT_GRAPH",
              editable: true,
              nodeId,
              canNavigateToSource: canNavigateToSource(node),
              collapsed: collapsedNodeIdSet.has(nodeId),
              overflowActionLabel: overflowPresentation(node)?.expandActionLabel ?? null,
              invocationExpansionActionLabel: isExpandableInvocation ? "展开被调方法" : null,
              expansionId: node.metadata?.["linkGraph.expansion.id"] ?? null,
              onInspectNode,
              onRequestSourceNavigation,
              onRequestBeautification,
              onPrimeQuestionComposer,
              onOpenQa,
              onToggleCollapseNode,
              onExpandOverflowNode,
              onExpandInvocation,
              onRemoveInvocationExpansion,
              onFormatLayout: requestRelayout,
              onDeleteNodeSubtree,
              onDeleteNode,
              canEditNode: (command) => canEditProjectedNode(view.projectionIndex, nodeId, command),
              onClose: close,
            });
          }
        }
        buildEdgeActions={({ edgeId, close }) =>
          buildEdgeActions({
            analysisDisplayMode: "FACT_GRAPH",
            editable: true,
            edgeId,
            canEditEdge: (command) => canEditProjectedEdge(view.projectionIndex, edgeId, command),
            onDeleteEdge,
            onClose: close,
          })
        }
        onSelectNode={onSelectNode}
        onSelectionGroupChange={onSelectionGroupChange}
        onInspectNode={onInspectNode}
        onCreateEdge={onCreateEdge}
        onMoveNode={onMoveNode}
        onMoveNodes={onMoveNodes}
        shouldFocusAnchorOnLoad={shouldFocusAnchor(visibleNodes)}
        nodeViewportSize={(node) => ({ width: nodeCardWidth(node), height: 156 })}
      />
      </GraphViewShell>
    </section>
  );
}
