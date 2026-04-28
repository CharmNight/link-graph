import { useMemo } from "react";
import { buildEdgeActions, buildNodeActions, buildPaneActions } from "../../components/graph/actions/actionSchema";
import {
  hasHierarchyDirections,
  overflowPresentation,
  readingSummaryDetail,
} from "../../components/graph/nodes/nodePresentation";
import { createNodeSizeRegistry } from "../../graph/nodeSizeRegistry";
import { nodeCardWidth } from "../../graphNodeSizing";
import { useMeasuredLayout } from "../../reactflow/useMeasuredLayout";
import { shouldFocusAnchor } from "../../reactflow/viewportPolicy";
import { GraphFlowSurface } from "../../reactflow/GraphFlowSurface";
import { canNavigateToSource } from "../../sourceNavigation";
import type { FactGraphViewDocument } from "../../types";
import { DraftCompareSummary } from "../../components/DraftCompareSummary";
import type { ViewStageProps } from "../viewStageProps";
import { layoutFactGraphView } from "./factGraphLayout";
import { buildFactGraphEdges, buildFactGraphNodes, FACT_GRAPH_NODE_TYPES } from "./factGraphNodes";

interface FactGraphViewProps extends ViewStageProps {
  view: FactGraphViewDocument;
}

function emptyNode() {
  return {
    id: "",
    type: "DOC_PAGE" as const,
    title: "",
    inputs: [],
    outputs: [],
    certainty: "PROVEN" as const,
    bindingStatus: "BOUND" as const,
  };
}

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
  onRequestAudit = () => undefined,
  onToggleCollapseNode = () => undefined,
  onOpenAudit = () => undefined,
  onImportMermaid,
  onExpandOverflowNode = () => undefined,
}: FactGraphViewProps) {
  const nodeSizeRegistry = useMemo(() => createNodeSizeRegistry(), []);
  const presentedGraph = draftCompareProjection?.compareGraph ?? view.visibleGraph;
  const layoutState = useMeasuredLayout({
    graph: presentedGraph,
    anchorNodeId: view.anchorNodeId ?? null,
    collapsedNodeIds,
    nodeSizeRegistry,
    layout: layoutFactGraphView,
    debugLabel: "fact",
  });

  const hiddenNodeIdSet = useMemo(() => new Set(hiddenNodeIds), [hiddenNodeIds]);
  const collapsedNodeIdSet = useMemo(() => new Set(collapsedNodeIds), [collapsedNodeIds]);
  const visibleNodes = useMemo(
    () => layoutState.nodes.filter((node) => !hiddenNodeIdSet.has(node.id)),
    [layoutState.nodes, hiddenNodeIdSet],
  );
  const visibleEdges = useMemo(
    () => layoutState.edges.filter((edge) => !hiddenNodeIdSet.has(edge.source) && !hiddenNodeIdSet.has(edge.target)),
    [layoutState.edges, hiddenNodeIdSet],
  );
  const isLayoutLoading = layoutState.layoutPending && presentedGraph.nodes.length > 0 && layoutState.nodes.length === 0;
  const nodeIndex = useMemo(
    () => new Map(visibleNodes.map((node) => [node.id, node])),
    [visibleNodes],
  );
  const anchorNode = useMemo(
    () =>
      visibleNodes.find((node) => node.id === (view.anchorNodeId ?? null))
      ?? visibleNodes.find((node) => node.id === selectedNodeId)
      ?? visibleNodes.find((node) => node.type === "METHOD")
      ?? visibleNodes[0]
      ?? null,
    [visibleNodes, view.anchorNodeId, selectedNodeId],
  );
  const selectedNode = useMemo(
    () => visibleNodes.find((node) => node.id === selectedNodeId) ?? null,
    [visibleNodes, selectedNodeId],
  );
  const showSelectionSummary = selectedNode !== null && selectedNode.id !== anchorNode?.id;
  const hasFlowStructure = useMemo(
    () =>
      visibleNodes.some((node) =>
        node.type === "FLOW_SCOPE" || node.type === "TERMINAL" || node.type === "MERGE",
      ) ||
      visibleEdges.some((edge) => edge.type === "CONTAINS_FLOW" || edge.type === "CONTROL_FLOW"),
    [visibleNodes, visibleEdges],
  );
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

  const header = anchorNode ? (
    <section className="canvas-reading-summary" aria-label="图阅读摘要">
      {draftCompareProjection ? <DraftCompareSummary projection={draftCompareProjection} /> : null}
      <div className="canvas-reading-grid">
        <article className="canvas-reading-card is-anchor">
          <span className="canvas-reading-label">当前方法</span>
          <strong className="canvas-reading-title">{view.summary.anchorTitle ?? anchorNode.title}</strong>
          <span className="canvas-reading-detail">{readingSummaryDetail(anchorNode)}</span>
        </article>
        {showSelectionSummary && selectedNode ? (
          <article className="canvas-reading-card">
            <span className="canvas-reading-label">当前选中</span>
            <strong className="canvas-reading-title">{selectedNode.title}</strong>
            <span className="canvas-reading-detail">{readingSummaryDetail(selectedNode)}</span>
          </article>
        ) : null}
      </div>
      {showSelectionSummary ? (
        <p className="canvas-reading-hint">当前选中只是你正在看的节点，整张图仍围绕“当前方法”展开。</p>
      ) : null}
      <p className="canvas-reading-hint">
        当前展示 {visibleNodes.length} / {view.summary.fullNodeCount} 个节点。
      </p>
      {hasFlowStructure ? (
        <p className="canvas-reading-hint">实线表示真实调用，虚线表示 if / lambda / 循环 这类流程展开。</p>
      ) : null}
    </section>
  ) : null;

  const canvasMarkers = hasHierarchyDirections(visibleNodes) ? (
    <div className="canvas-column-markers" aria-label="链路列标记">
      <span className="canvas-column-marker is-upstream">上游</span>
      <span className="canvas-column-marker is-current">当前</span>
      <span className="canvas-column-marker is-downstream">下游</span>
    </div>
  ) : null;

  return (
    <section className="graph-stage-view fact-graph-view" data-testid="fact-graph-view">
      <GraphFlowSurface
        nodes={visibleNodes}
        edges={visibleEdges}
        flowNodes={flowNodes}
        flowEdges={flowEdges}
        nodeTypes={FACT_GRAPH_NODE_TYPES}
        viewportMode="FACT_GRAPH"
        anchorNodeId={view.anchorNodeId ?? null}
        selectedNodeId={selectedNodeId}
        focusNodeRequest={focusNodeRequest}
        selectedGroupNodeIds={selectedGroupNodeIds}
        experiments={experiments}
        editable
        layoutEditable
        header={header}
        canvasMarkers={canvasMarkers}
        emptyState={(
          isLayoutLoading ? (
            <div className="canvas-empty-state">
              <strong>正在整理链路画布</strong>
              <p className="muted">链路分析已完成，正在计算稳定布局。</p>
            </div>
          ) : (
            <div className="canvas-empty-state">
              <strong>画布里还没有节点</strong>
              <p className="muted">请在代码中右键方法，直接查看完整链路或追加到当前画布。</p>
            </div>
          )
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
            onFormatLayout: layoutState.requestRelayout,
            onOpenAudit,
            onClose: close,
          })
        }
        buildNodeActions={({ nodeId, close }) =>
          buildNodeActions({
            analysisDisplayMode: "FACT_GRAPH",
            editable: true,
            nodeId,
            canNavigateToSource: canNavigateToSource(nodeIndex.get(nodeId) ?? emptyNode()),
            collapsed: collapsedNodeIdSet.has(nodeId),
            overflowActionLabel: overflowPresentation(nodeIndex.get(nodeId) ?? emptyNode())?.expandActionLabel ?? null,
            onInspectNode,
            onRequestSourceNavigation,
            onRequestBeautification,
            onRequestAudit,
            onOpenAudit,
            onToggleCollapseNode,
            onExpandOverflowNode,
            onFormatLayout: layoutState.requestRelayout,
            onDeleteNodeSubtree,
            onDeleteNode,
            onClose: close,
          })
        }
        buildEdgeActions={({ edgeId, close }) =>
          buildEdgeActions({
            analysisDisplayMode: "FACT_GRAPH",
            editable: true,
            edgeId,
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
    </section>
  );
}
