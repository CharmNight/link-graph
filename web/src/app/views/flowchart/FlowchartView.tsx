import { useEffect, useMemo } from "react";
import { buildEdgeActions as buildSharedEdgeActions, buildPaneActions } from "../../components/graph/actions/actionSchema";
import { traceLinkGraph } from "../../debug";
import { createNodeSizeRegistry } from "../../graph/nodeSizeRegistry";
import { flowchartNodeCardWidth } from "../../graphNodeSizing";
import { useMeasuredLayout } from "../../reactflow/useMeasuredLayout";
import { GraphFlowSurface } from "../../reactflow/GraphFlowSurface";
import { canNavigateToSource } from "../../sourceNavigation";
import type { FlowchartViewDocument, LinkGraphDocument, LinkGraphEdge } from "../../types";
import type { ViewStageProps } from "../viewStageProps";
import { layoutFlowchartView } from "./flowchartLayout";
import {
  buildFlowchartEdges,
  buildFlowchartNodes,
  FLOWCHART_NODE_TYPES,
} from "./flowchartNodes";

interface FlowchartViewProps extends ViewStageProps {
  view: FlowchartViewDocument;
}

function fallbackSourceNode() {
  return {
    type: "DOC_PAGE" as const,
    location: undefined,
    signature: undefined,
  };
}

function sanitizeFlowchartGraph(
  graph: LinkGraphDocument,
  anchorNodeId: string | null | undefined,
): LinkGraphDocument {
  const visibleEdges = graph.edges.filter((edge) => edge.type !== "CONTAINS_FLOW");
  const anchorId = anchorNodeId ?? null;
  if (!anchorId) {
    return visibleEdges.length === graph.edges.length ? graph : { ...graph, edges: visibleEdges };
  }
  const hasAnchorControlEdge = visibleEdges.some(
    (edge) => edge.source === anchorId && edge.type === "CONTROL_FLOW",
  );
  if (hasAnchorControlEdge) {
    return visibleEdges.length === graph.edges.length ? graph : { ...graph, edges: visibleEdges };
  }
  const entryContainsCandidates = graph.edges.filter(
    (edge) => edge.type === "CONTAINS_FLOW" && edge.source === anchorId,
  );
  if (entryContainsCandidates.length === 0) {
    return visibleEdges.length === graph.edges.length ? graph : { ...graph, edges: visibleEdges };
  }
  const controlFlowTargetIds = new Set(
    visibleEdges
      .filter((edge) => edge.type === "CONTROL_FLOW")
      .map((edge) => edge.target),
  );
  const preferredEntryEdge = entryContainsCandidates.find((edge) => !controlFlowTargetIds.has(edge.target))
    ?? entryContainsCandidates[0]!;
  const syntheticEntryEdge: LinkGraphEdge = {
    ...preferredEntryEdge,
    id: `flowchart-entry:${preferredEntryEdge.source}->${preferredEntryEdge.target}`,
    type: "CONTROL_FLOW",
    metadata: {
      ...(preferredEntryEdge.metadata ?? {}),
      "flowchart.synthetic": "entry-edge",
    },
  };
  return {
    ...graph,
    edges: [...visibleEdges, syntheticEntryEdge],
  };
}

function flowchartNodeActions(args: {
  nodeId: string;
  canOpenSource: boolean;
  editable: boolean;
  onInspectNode: (nodeId: string) => void;
  onDeleteNode: (nodeId: string) => void;
  onRequestSourceNavigation: (nodeId: string) => void;
  onRequestBeautification: (selectedNodeId?: string) => void;
  onRequestAudit: (selectedNodeId?: string) => void;
  onOpenAudit: (selectedNodeId?: string) => void;
  onFormatLayout: () => void;
  onClose: () => void;
}) {
  const actions = [
    {
      id: "inspect-node",
      label: "查看详情",
      onSelect: () => {
        args.onInspectNode(args.nodeId);
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
      label: "讲解当前流程",
      onSelect: () => {
        args.onRequestBeautification(args.nodeId);
        args.onClose();
      },
    },
    {
      id: "audit-node",
      label: "问答当前节点",
      onSelect: () => {
        args.onRequestAudit(args.nodeId);
        args.onClose();
      },
    },
    {
      id: "set-audit-anchor",
      label: "设为问答范围起点",
      onSelect: () => {
        args.onOpenAudit(args.nodeId);
        args.onClose();
      },
    },
    {
      id: "format-layout",
      label: "重新整理流程图",
      onSelect: () => {
        args.onFormatLayout();
        args.onClose();
      },
    },
  );
  if (args.editable) {
    actions.push({
      id: "delete-node",
      label: "删除节点",
      onSelect: () => {
        args.onDeleteNode(args.nodeId);
        args.onClose();
      },
    });
  }
  return actions;
}

export function FlowchartView({
  view,
  selectedNodeId,
  focusNodeRequest = null,
  explanationFocusNodeId = null,
  draftChangedNodeIds = [],
  selectedGroupNodeIds = [],
  hiddenNodeIds = [],
  experiments = null,
  onAddNode,
  onSelectNode,
  onSelectionGroupChange = () => undefined,
  onInspectNode,
  onDeleteNode,
  onCreateEdge,
  onDeleteEdge,
  onInsertNodeIntoEdge = () => undefined,
  onMoveNode,
  onMoveNodes,
  onRequestSourceNavigation,
  onRequestBeautification = () => undefined,
  onRequestAudit = () => undefined,
  onOpenAudit = () => undefined,
  onImportMermaid,
}: FlowchartViewProps) {
  const nodeSizeRegistry = useMemo(() => createNodeSizeRegistry(), []);
  const viewGraph = useMemo(
    () => sanitizeFlowchartGraph(view.visibleGraph, view.anchorNodeId ?? null),
    [view.anchorNodeId, view.visibleGraph],
  );
  const layoutState = useMeasuredLayout({
    graph: viewGraph,
    anchorNodeId: view.anchorNodeId ?? null,
    nodeSizeRegistry,
    layout: layoutFlowchartView,
    debugLabel: "flowchart",
  });

  const hiddenNodeIdSet = useMemo(() => new Set(hiddenNodeIds), [hiddenNodeIds]);
  const visibleNodes = useMemo(
    () => layoutState.nodes.filter((node) => !hiddenNodeIdSet.has(node.id)),
    [layoutState.nodes, hiddenNodeIdSet],
  );
  const visibleEdges = useMemo(
    () => layoutState.edges.filter((edge) => !hiddenNodeIdSet.has(edge.source) && !hiddenNodeIdSet.has(edge.target)),
    [layoutState.edges, hiddenNodeIdSet],
  );
  const isLayoutLoading = layoutState.layoutPending && viewGraph.nodes.length > 0 && layoutState.nodes.length === 0;
  const syntheticEntryAssisted = useMemo(
    () => viewGraph.edges.some((edge) => edge.metadata?.["flowchart.synthetic"] === "entry-edge"),
    [viewGraph.edges],
  );
  const nodeIndex = useMemo(
    () => new Map(visibleNodes.map((node) => [node.id, node])),
    [visibleNodes],
  );
  const anchorNode = useMemo(
    () => visibleNodes.find((node) => node.id === view.anchorNodeId) ?? null,
    [view.anchorNodeId, visibleNodes],
  );
  const selectedNode = useMemo(
    () => visibleNodes.find((node) => node.id === selectedNodeId) ?? null,
    [visibleNodes, selectedNodeId],
  );
  const flowNodes = useMemo(
    () => buildFlowchartNodes({
      nodes: visibleNodes,
      edges: visibleEdges,
      selectedNodeId,
      explanationFocusNodeId,
      draftChangedNodeIds,
      nodeSizeRegistry,
    }),
    [visibleNodes, visibleEdges, selectedNodeId, explanationFocusNodeId, draftChangedNodeIds, nodeSizeRegistry],
  );
  const flowEdges = useMemo(
    () => buildFlowchartEdges({ edges: visibleEdges, nodeIndex }),
    [visibleEdges, nodeIndex],
  );

  useEffect(() => {
    if (visibleNodes.length === 0 || flowEdges.length === 0) {
      return;
    }
    traceLinkGraph("flowchartView.runtimeHandles", {
      nodes: visibleNodes.slice(0, 12).map((node) => ({
        id: node.id,
        kind: node.metadata?.["flowchart.kind"] ?? null,
        hasExceptionSource: node.metadata?.["flowchart.hasExceptionSource"] ?? null,
      })),
      edges: flowEdges.slice(0, 24).map((edge) => ({
        id: edge.id,
        sourceHandle: edge.sourceHandle ?? null,
        targetHandle: edge.targetHandle ?? null,
      })),
    });
  }, [flowEdges, visibleNodes]);

  useEffect(() => {
    if (flowNodes.length === 0) {
      return;
    }
    const highlightedNodes = flowNodes
      .filter((node) => typeof node.className === "string" && node.className.length > 0)
      .map((node) => ({
        id: node.id,
        className: node.className ?? "",
        selected: node.selected === true,
      }));
    traceLinkGraph("flowchartView.renderState", {
      nodeCount: flowNodes.length,
      edgeCount: flowEdges.length,
      selectedNodeId: selectedNodeId ?? null,
      selectedCount: flowNodes.filter((node) => node.selected === true).length,
      explanationFocusNodeId: explanationFocusNodeId ?? null,
      explanationFocusCount: flowNodes.filter((node) => node.className?.includes("is-explanation-focus")).length,
      draftChangedCount: flowNodes.filter((node) => node.className?.includes("is-draft-change")).length,
      highlightedNodeCount: highlightedNodes.length,
      highlightedNodes: highlightedNodes.slice(0, 12),
    });
  }, [explanationFocusNodeId, flowEdges.length, flowNodes, selectedNodeId]);

  const fidelityFlags = [
    view.summary.semanticallyIncomplete ? { label: "语义不完整", tone: "warning" as const } : null,
    syntheticEntryAssisted ? { label: "含合成入口", tone: "info" as const } : null,
  ].filter((flag): flag is { label: string; tone: "warning" | "info" } => flag != null);

  const header = (
    <section className="canvas-reading-summary" aria-label="流程图摘要">
      <div className="canvas-reading-grid">
        <article className="canvas-reading-card is-anchor">
          <span className="canvas-reading-label">当前方法</span>
          <strong
            className="canvas-reading-title"
            title={view.summary.nodeCount > 0 ? (anchorNode?.title ?? "流程图") : "流程图"}
          >
            {view.summary.nodeCount > 0 ? (anchorNode?.title ?? "流程图") : "流程图"}
          </strong>
          <span className="canvas-reading-detail">
            共 {view.summary.nodeCount} 个流程节点，{view.summary.branchCount} 个分支判断，异常路径 {view.summary.exceptionPathCount} 条。
          </span>
          {fidelityFlags.length > 0 ? (
            <div className="canvas-reading-flags" aria-label="流程图状态">
              {fidelityFlags.map((flag) => (
                <span
                  key={flag.label}
                  className={`canvas-reading-flag is-${flag.tone}`}
                >
                  {flag.label}
                </span>
              ))}
            </div>
          ) : null}
        </article>
        {selectedNode ? (
          <article className="canvas-reading-card">
            <span className="canvas-reading-label">当前选中</span>
            <strong className="canvas-reading-title" title={selectedNode.title}>{selectedNode.title}</strong>
            <span className="canvas-reading-detail">查看当前流程节点的类型、条件与证据。</span>
          </article>
        ) : null}
      </div>
    </section>
  );

  return (
    <section className="graph-stage-view flowchart-view" data-testid="flowchart-view">
      <GraphFlowSurface
        nodes={visibleNodes}
        edges={visibleEdges}
        flowNodes={flowNodes}
        flowEdges={flowEdges}
        nodeTypes={FLOWCHART_NODE_TYPES}
        viewportMode="FLOWCHART"
        anchorNodeId={view.anchorNodeId ?? null}
        selectedNodeId={selectedNodeId}
        focusNodeRequest={focusNodeRequest}
        selectedGroupNodeIds={selectedGroupNodeIds}
        experiments={experiments}
        editable
        layoutEditable
        header={header}
        emptyState={(
          isLayoutLoading ? (
            <div className="canvas-empty-state">
              <strong>正在整理流程图</strong>
              <p className="muted">链路识别已完成，正在计算稳定布局。</p>
            </div>
          ) : (
            <div className="canvas-empty-state">
              <strong>当前没有可展示的流程节点</strong>
              <p className="muted">请先选择方法并完成分析，再查看控制流视图。</p>
            </div>
          )
        )}
        buildPaneActions={({ position, hasGroupedSelection, visibleNodeCount, close }) =>
          buildPaneActions({
            analysisDisplayMode: "FLOWCHART",
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
          flowchartNodeActions({
            nodeId,
            canOpenSource: canNavigateToSource(nodeIndex.get(nodeId) ?? fallbackSourceNode()),
            editable: true,
            onInspectNode,
            onDeleteNode,
            onRequestSourceNavigation,
            onRequestBeautification,
            onRequestAudit,
            onOpenAudit,
            onFormatLayout: layoutState.requestRelayout,
            onClose: close,
          })
        }
        buildEdgeActions={({ edgeId, close }) =>
          [
            {
              id: "insert-method",
              label: "在线路中插入方法节点",
              onSelect: () => {
                onInsertNodeIntoEdge(edgeId, "METHOD");
                close();
              },
            },
            {
              id: "insert-doc",
              label: "在线路中插入说明节点",
              onSelect: () => {
                onInsertNodeIntoEdge(edgeId, "DOC_PAGE");
                close();
              },
            },
            ...buildSharedEdgeActions({
              analysisDisplayMode: "FLOWCHART",
              editable: true,
              edgeId,
              onDeleteEdge,
              onClose: close,
            }),
          ]
        }
        onSelectNode={onSelectNode}
        onSelectionGroupChange={onSelectionGroupChange}
        onInspectNode={onInspectNode}
        onCreateEdge={onCreateEdge}
        onMoveNode={onMoveNode}
        onMoveNodes={onMoveNodes}
        shouldFocusAnchorOnLoad={false}
        nodeViewportSize={(node) => ({ width: flowchartNodeCardWidth(node), height: 156 })}
      />
    </section>
  );
}
