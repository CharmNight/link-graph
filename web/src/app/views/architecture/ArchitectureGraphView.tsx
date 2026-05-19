import { useMemo, useState } from "react";
import { buildEdgeActions as buildSharedEdgeActions } from "../../components/graph/actions/actionSchema";
import type { GraphContextMenuAction } from "../../components/graph/actions/actionSchema";
import { DraftCompareSummary } from "../../components/DraftCompareSummary";
import { createNodeSizeRegistry } from "../../graph/nodeSizeRegistry";
import { nodeCardWidth } from "../../graphNodeSizing";
import { GraphFlowSurface } from "../../reactflow/GraphFlowSurface";
import { useMeasuredLayout } from "../../reactflow/useMeasuredLayout";
import { canNavigateToSource } from "../../sourceNavigation";
import type { ArchitectureGraphViewDocument, LinkGraphNode, NodeType } from "../../types";
import type { ViewStageProps } from "../viewStageProps";
import { layoutArchitectureGraphView } from "./architectureGraphLayout";
import {
  ARCHITECTURE_GRAPH_NODE_TYPES,
  buildArchitectureGraphEdges,
  buildArchitectureGraphNodes,
} from "./architectureGraphNodes";

interface ArchitectureGraphViewProps extends ViewStageProps {
  view: ArchitectureGraphViewDocument;
}

const FILTER_TYPES: Array<NodeType | "ALL"> = ["ALL", "MODULE", "PACKAGE", "SERVICE", "LAYER", "RESOURCE"];

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

function architectureNodeActions(args: {
  nodeId: string;
  node: LinkGraphNode | null;
  canOpenSource: boolean;
  collapsed: boolean;
  onInspectNode: (nodeId: string) => void;
  onRequestSourceNavigation: (nodeId: string) => void;
  onRequestBeautification: (selectedNodeId?: string) => void;
  onRequestQa: (selectedNodeId?: string) => void;
  onOpenQa: (selectedNodeId?: string) => void;
  onToggleCollapseNode: (nodeId: string) => void;
  onRequestClassDiagram: (scopeNodeId?: string | null) => void;
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
      label: "打开类图范围",
      onSelect: () => {
        args.onRequestClassDiagram(args.node?.metadata?.["architecture.drillDownClassScope"] ?? args.nodeId);
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
        args.onRequestQa(args.nodeId);
        args.onClose();
      },
    },
    {
      id: "set-qa-anchor",
      label: "设为问答范围起点",
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
  onSelectNode,
  onSelectionGroupChange = () => undefined,
  onInspectNode,
  onCreateEdge,
  onDeleteEdge,
  onMoveNode,
  onMoveNodes,
  onRequestSourceNavigation,
  onRequestBeautification = () => undefined,
  onRequestQa = () => undefined,
  onRequestClassDiagram = () => undefined,
  onToggleCollapseNode = () => undefined,
  onOpenQa = () => undefined,
}: ArchitectureGraphViewProps) {
  const [query, setQuery] = useState("");
  const [typeFilter, setTypeFilter] = useState<NodeType | "ALL">("ALL");
  const nodeSizeRegistry = useMemo(() => createNodeSizeRegistry(), []);
  const presentedGraph = draftCompareProjection?.compareGraph ?? view.visibleGraph;
  const layoutState = useMeasuredLayout({
    graph: presentedGraph,
    anchorNodeId: view.anchorNodeId ?? null,
    nodeSizeRegistry,
    layout: layoutArchitectureGraphView,
    debugLabel: "architecture",
  });

  const hiddenNodeIdSet = useMemo(() => new Set(hiddenNodeIds), [hiddenNodeIds]);
  const collapsedNodeIdSet = useMemo(() => new Set(collapsedNodeIds), [collapsedNodeIds]);
  const visibleNodes = useMemo(
    () => layoutState.nodes.filter((node) =>
      !hiddenNodeIdSet.has(node.id)
      && (typeFilter === "ALL" || node.type === typeFilter)
      && nodeMatchesQuery(node, query)
    ),
    [hiddenNodeIdSet, layoutState.nodes, query, typeFilter],
  );
  const visibleNodeIds = useMemo(() => new Set(visibleNodes.map((node) => node.id)), [visibleNodes]);
  const visibleEdges = useMemo(
    () => layoutState.edges.filter((edge) => visibleNodeIds.has(edge.source) && visibleNodeIds.has(edge.target)),
    [layoutState.edges, visibleNodeIds],
  );
  const isLayoutLoading = layoutState.layoutPending && presentedGraph.nodes.length > 0 && layoutState.nodes.length === 0;
  const nodeIndex = useMemo(() => new Map(visibleNodes.map((node) => [node.id, node])), [visibleNodes]);
  const selectedNode = useMemo(
    () => visibleNodes.find((node) => node.id === selectedNodeId) ?? visibleNodes[0] ?? null,
    [selectedNodeId, visibleNodes],
  );
  const flowNodes = useMemo(
    () => buildArchitectureGraphNodes({
      nodes: visibleNodes,
      selectedNodeId,
      explanationFocusNodeId,
      draftChangedNodeIds,
      draftCompareNodeStatuses: draftCompareProjection?.nodeStatuses,
      nodeSizeRegistry,
    }),
    [draftChangedNodeIds, draftCompareProjection?.nodeStatuses, explanationFocusNodeId, nodeSizeRegistry, selectedNodeId, visibleNodes],
  );
  const flowEdges = useMemo(
    () => buildArchitectureGraphEdges({
      edges: visibleEdges,
      draftCompareEdgeStatuses: draftCompareProjection?.edgeStatuses,
    }),
    [draftCompareProjection?.edgeStatuses, visibleEdges],
  );

  const header = (
    <section className="canvas-reading-summary" aria-label="架构图摘要">
      {draftCompareProjection ? <DraftCompareSummary projection={draftCompareProjection} /> : null}
      <div className="canvas-reading-grid">
        <article className="canvas-reading-card is-anchor">
          <span className="canvas-reading-label">架构范围</span>
          <strong className="canvas-reading-title">{selectedNode?.title ?? "架构图"}</strong>
          <span className="canvas-reading-detail">
            模块 {view.summary.moduleCount} · 包 {view.summary.packageCount} · 关系 {view.summary.relationCount}
          </span>
        </article>
        <article className="canvas-reading-card">
          <span className="canvas-reading-label">筛选</span>
          <input
            className="compact-input"
            value={query}
            placeholder="搜索"
            onChange={(event) => setQuery(event.target.value)}
          />
          <select
            className="compact-input"
            value={typeFilter}
            onChange={(event) => setTypeFilter(event.target.value as NodeType | "ALL")}
          >
            {FILTER_TYPES.map((type) => (
              <option key={type} value={type}>{type === "ALL" ? "全部" : type}</option>
            ))}
          </select>
        </article>
      </div>
    </section>
  );

  return (
    <section className="graph-stage-view architecture-graph-view" data-testid="architecture-graph-view">
      <GraphFlowSurface
        nodes={visibleNodes}
        edges={visibleEdges}
        flowNodes={flowNodes}
        flowEdges={flowEdges}
        nodeTypes={ARCHITECTURE_GRAPH_NODE_TYPES}
        viewportMode="ARCHITECTURE_GRAPH"
        anchorNodeId={view.anchorNodeId ?? null}
        selectedNodeId={selectedNodeId}
        focusNodeRequest={focusNodeRequest}
        selectedGroupNodeIds={selectedGroupNodeIds}
        experiments={experiments}
        editable={false}
        layoutEditable={false}
        header={header}
        emptyState={(
          isLayoutLoading ? (
            <div className="canvas-empty-state">
              <strong>正在整理架构图</strong>
              <p className="muted">架构索引已完成，正在计算布局。</p>
            </div>
          ) : (
            <div className="canvas-empty-state">
              <strong>当前没有架构图结果</strong>
              <p className="muted">请从工具栏加载架构图。</p>
            </div>
          )
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
            onRequestQa,
            onOpenQa,
            onToggleCollapseNode,
            onRequestClassDiagram,
            onFormatLayout: layoutState.requestRelayout,
            onClose: close,
          })
        }
        buildEdgeActions={({ edgeId, close }) =>
          buildSharedEdgeActions({
            analysisDisplayMode: "ARCHITECTURE_GRAPH",
            editable: false,
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
        shouldFocusAnchorOnLoad={false}
        nodeViewportSize={(node) => ({ width: nodeCardWidth(node), height: 156 })}
      />
    </section>
  );
}
