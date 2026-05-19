import { useMemo, useState } from "react";
import { buildEdgeActions as buildSharedEdgeActions } from "../../components/graph/actions/actionSchema";
import type { GraphContextMenuAction } from "../../components/graph/actions/actionSchema";
import { DraftCompareSummary } from "../../components/DraftCompareSummary";
import { createNodeSizeRegistry } from "../../graph/nodeSizeRegistry";
import { classDiagramNodeCardWidth } from "../../graphNodeSizing";
import { GraphFlowSurface } from "../../reactflow/GraphFlowSurface";
import { useMeasuredLayout } from "../../reactflow/useMeasuredLayout";
import { canNavigateToSource } from "../../sourceNavigation";
import type { ClassDiagramViewDocument, LinkGraphNode, NodeType } from "../../types";
import type { ViewStageProps } from "../viewStageProps";
import { layoutClassDiagramView } from "./classDiagramLayout";
import {
  buildClassDiagramEdges,
  buildClassDiagramNodes,
  CLASS_DIAGRAM_NODE_TYPES,
} from "./classDiagramNodes";

interface ClassDiagramViewProps extends ViewStageProps {
  view: ClassDiagramViewDocument;
}

const FILTER_TYPES: Array<NodeType | "ALL"> = [
  "ALL",
  "CLASS",
  "INTERFACE",
  "ENUM",
  "ANNOTATION",
  "RECORD",
  "OBJECT",
];

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
  onToggleCollapseNode = () => undefined,
  onOpenQa = () => undefined,
}: ClassDiagramViewProps) {
  const [query, setQuery] = useState("");
  const [typeFilter, setTypeFilter] = useState<NodeType | "ALL">("ALL");
  const [relationFilter, setRelationFilter] = useState("ALL");
  const nodeSizeRegistry = useMemo(() => createNodeSizeRegistry(), []);
  const presentedGraph = draftCompareProjection?.compareGraph ?? view.visibleGraph;
  const layoutState = useMeasuredLayout({
    graph: presentedGraph,
    anchorNodeId: view.anchorNodeId ?? null,
    nodeSizeRegistry,
    layout: layoutClassDiagramView,
    debugLabel: "class-diagram",
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
    () => layoutState.edges.filter((edge) =>
      visibleNodeIds.has(edge.source)
      && visibleNodeIds.has(edge.target)
      && (relationFilter === "ALL" || edge.type === relationFilter || edge.metadata?.["jvm.relation.kind"] === relationFilter)
    ),
    [layoutState.edges, relationFilter, visibleNodeIds],
  );
  const isLayoutLoading = layoutState.layoutPending && presentedGraph.nodes.length > 0 && layoutState.nodes.length === 0;
  const nodeIndex = useMemo(() => new Map(visibleNodes.map((node) => [node.id, node])), [visibleNodes]);
  const selectedNode = useMemo(
    () => visibleNodes.find((node) => node.id === selectedNodeId) ?? visibleNodes[0] ?? null,
    [selectedNodeId, visibleNodes],
  );
  const relationTypes = useMemo(
    () => Array.from(new Set(layoutState.edges.map((edge) => edge.metadata?.["jvm.relation.kind"] ?? edge.type))).sort(),
    [layoutState.edges],
  );
  const flowNodes = useMemo(
    () => buildClassDiagramNodes({
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
    () => buildClassDiagramEdges({
      edges: visibleEdges,
      draftCompareEdgeStatuses: draftCompareProjection?.edgeStatuses,
    }),
    [draftCompareProjection?.edgeStatuses, visibleEdges],
  );

  const header = (
    <section className="canvas-reading-summary" aria-label="类图摘要">
      {draftCompareProjection ? <DraftCompareSummary projection={draftCompareProjection} /> : null}
      <div className="canvas-reading-grid">
        <article className="canvas-reading-card is-anchor">
          <span className="canvas-reading-label">UML 类图</span>
          <strong className="canvas-reading-title">{selectedNode?.title ?? "类型关系"}</strong>
          <span className="canvas-reading-detail">
            类 {view.summary.classCount} · 接口 {view.summary.interfaceCount} · 字段 {view.summary.fieldCount ?? 0} · 关系 {view.summary.relationCount}
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
              <option key={type} value={type}>{type === "ALL" ? "全部 UML 类型" : type}</option>
            ))}
          </select>
          <select
            className="compact-input"
            value={relationFilter}
            onChange={(event) => setRelationFilter(event.target.value)}
          >
            <option value="ALL">全部关系</option>
            {relationTypes.map((type) => (
              <option key={type} value={type}>{type}</option>
            ))}
          </select>
        </article>
      </div>
    </section>
  );

  return (
    <section className="graph-stage-view class-diagram-view" data-testid="class-diagram-view">
      <GraphFlowSurface
        nodes={visibleNodes}
        edges={visibleEdges}
        flowNodes={flowNodes}
        flowEdges={flowEdges}
        nodeTypes={CLASS_DIAGRAM_NODE_TYPES}
        viewportMode="CLASS_DIAGRAM"
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
              <strong>正在整理类图</strong>
              <p className="muted">类图索引已完成，正在计算布局。</p>
            </div>
          ) : (
            <div className="canvas-empty-state">
              <strong>当前没有类图结果</strong>
              <p className="muted">请从工具栏加载类图，或从架构图节点打开类图范围。</p>
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
          classDiagramNodeActions({
            nodeId,
            node: nodeIndex.get(nodeId) ?? null,
            canOpenSource: canNavigateToSource(nodeIndex.get(nodeId) ?? { type: "CLASS", location: undefined, signature: undefined }),
            collapsed: collapsedNodeIdSet.has(nodeId),
            onInspectNode,
            onRequestSourceNavigation,
            onRequestBeautification,
            onRequestQa,
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
        nodeViewportSize={() => ({ width: classDiagramNodeCardWidth(), height: 156 })}
      />
    </section>
  );
}
