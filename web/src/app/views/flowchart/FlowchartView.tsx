import { useEffect, useMemo } from "react";
import { buildEdgeActions as buildSharedEdgeActions, buildPaneActions } from "../../components/graph/actions/actionSchema";
import { projectedAliasNodeIds } from "../../appGraphSupport";
import { traceLinkGraph } from "../../debug";
import { createNodeSizeRegistry } from "../../graph/nodeSizeRegistry";
import { flowchartNodeCardWidth } from "../../graphNodeSizing";
import { useMeasuredLayout } from "../../reactflow/useMeasuredLayout";
import { GraphFlowSurface } from "../../reactflow/GraphFlowSurface";
import { canNavigateToSource } from "../../sourceNavigation";
import type { FlowchartViewDocument, LinkGraphDocument, LinkGraphEdge } from "../../types";
import { DraftCompareSummary } from "../../components/DraftCompareSummary";
import type { ViewStageProps } from "../viewStageProps";
import { layoutFlowchartView } from "./flowchartLayout";
import {
  buildFlowchartEdges,
  buildFlowchartNodes,
  FLOWCHART_NODE_TYPES,
} from "./flowchartNodes";

interface FlowchartViewProps extends ViewStageProps {
  view: FlowchartViewDocument;
  layoutView?: FlowchartViewDocument;
}

function fallbackSourceNode() {
  return {
    type: "DOC_PAGE" as const,
    location: undefined,
    signature: undefined,
  };
}

function isInvocationExpansionSource(node: LinkGraphDocument["nodes"][number] | null | undefined): boolean {
  return node?.type === "FLOW_ACTION" &&
    node.metadata?.["flow.kind"] === "INVOCATION" &&
    Boolean(node.signature?.trim());
}

function resolveInvocationExpansionNodeId(
  node: LinkGraphDocument["nodes"][number] | null | undefined,
  fullGraphNodeIndex: Map<string, LinkGraphDocument["nodes"][number]>,
): string | null {
  if (!node) {
    return null;
  }
  if (isInvocationExpansionSource(node)) {
    return node.id;
  }
  for (const aliasNodeId of projectedAliasNodeIds(node)) {
    const aliasNode = fullGraphNodeIndex.get(aliasNodeId);
    if (isInvocationExpansionSource(aliasNode)) {
      return aliasNodeId;
    }
  }
  return null;
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

function resolveNodeOwnerSignature(node: LinkGraphDocument["nodes"][number] | null | undefined): string | null {
  if (!node) {
    return null;
  }
  return node.metadata?.["flow.ownerMethod"]?.trim()
    || node.signature?.trim()
    || null;
}

function scopeFlowchartGraphToAnchorMethod(
  graph: LinkGraphDocument,
  anchorNodeId: string | null | undefined,
): LinkGraphDocument {
  const anchorNode = graph.nodes.find((node) => node.id === anchorNodeId) ?? null;
  const anchorSignature = resolveNodeOwnerSignature(anchorNode);
  if (!anchorSignature) {
    return graph;
  }
  const ownerScopedNodes = graph.nodes.filter((node) => {
    if (node.id === anchorNodeId) {
      return true;
    }
    return resolveNodeOwnerSignature(node) === anchorSignature;
  });
  const ownerScopedNodeIds = new Set(ownerScopedNodes.map((node) => node.id));
  const ownerScopedCanonicalNodeIds = new Set(ownerScopedNodeIds);
  ownerScopedNodes.forEach((node) => {
    projectedAliasNodeIds(node).forEach((aliasNodeId) => ownerScopedCanonicalNodeIds.add(aliasNodeId));
  });
  const expansionScopedNodes = graph.nodes.filter((node) => {
    const sourceInvocationNodeId = node.metadata?.["linkGraph.expansion.sourceInvocationNodeId"]?.trim();
    return Boolean(sourceInvocationNodeId && ownerScopedCanonicalNodeIds.has(sourceInvocationNodeId));
  });
  const entryScopedNodes = graph.nodes.filter((node) => {
    if (ownerScopedNodeIds.has(node.id)) {
      return false;
    }
    if (node.metadata?.["flowchart.kind"] !== "ENTRY" && node.type !== "METHOD") {
      return false;
    }
    return graph.edges.some((edge) => edge.source === node.id && ownerScopedNodeIds.has(edge.target));
  });
  const scopedNodes = Array.from(new Map(
    [...ownerScopedNodes, ...entryScopedNodes, ...expansionScopedNodes].map((node) => [node.id, node]),
  ).values());
  if (scopedNodes.length === 0 || scopedNodes.length === graph.nodes.length) {
    return graph;
  }
  const scopedNodeIds = new Set(scopedNodes.map((node) => node.id));
  return {
    ...graph,
    nodes: scopedNodes,
    edges: graph.edges.filter((edge) => scopedNodeIds.has(edge.source) && scopedNodeIds.has(edge.target)),
  };
}

function resolveCurrentMethodNode(args: {
  nodes: LinkGraphDocument["nodes"];
  anchorNodeId: string | null | undefined;
  selectedNodeId: string | null | undefined;
}) {
  const { nodes, anchorNodeId, selectedNodeId } = args;
  const anchorNode = nodes.find((node) => node.id === anchorNodeId) ?? null;
  const selectedNode = nodes.find((node) => node.id === selectedNodeId) ?? null;
  const anchorSignature = resolveNodeOwnerSignature(anchorNode);
  const selectedSignature = resolveNodeOwnerSignature(selectedNode);
  const methodNodeForSignature = (signature: string | null) => {
    if (!signature) {
      return null;
    }
    return nodes.find((node) => node.type === "METHOD" && resolveNodeOwnerSignature(node) === signature) ?? null;
  };

  return methodNodeForSignature(anchorSignature)
    ?? methodNodeForSignature(selectedSignature)
    ?? nodes.find((node) => node.metadata?.["flowchart.kind"] === "ENTRY") ?? null
    ?? nodes.find((node) => node.type === "METHOD") ?? null
    ?? anchorNode;
}

function flowchartNodeActions(args: {
  nodeId: string;
  canOpenSource: boolean;
  editable: boolean;
  invocationExpansionActionLabel?: string | null;
  invocationExpansionNodeId?: string | null;
  expansionId?: string | null;
  onInspectNode: (nodeId: string) => void;
  onDeleteNode: (nodeId: string) => void;
  onRequestSourceNavigation: (nodeId: string) => void;
  onRequestBeautification: (selectedNodeId?: string) => void;
  onRequestAudit: (selectedNodeId?: string) => void;
  onOpenAudit: (selectedNodeId?: string) => void;
  onExpandInvocation: (nodeId: string) => void;
  onRemoveInvocationExpansion: (expansionId: string) => void;
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
  if (args.invocationExpansionActionLabel) {
    const invocationExpansionNodeId = args.invocationExpansionNodeId ?? args.nodeId;
    actions.push({
      id: "expand-invocation",
      label: args.invocationExpansionActionLabel,
      onSelect: () => {
        args.onExpandInvocation(invocationExpansionNodeId);
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
  if (args.expansionId) {
    const expansionId = args.expansionId;
    actions.push({
      id: "remove-invocation-expansion",
      label: "移除此展开",
      onSelect: () => {
        args.onRemoveInvocationExpansion(expansionId);
        args.onClose();
      },
    });
  }
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
  layoutView,
  selectedNodeId,
  focusNodeRequest = null,
  explanationFocusNodeId = null,
  draftChangedNodeIds = [],
  draftCompareProjection = null,
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
  onExpandInvocation = () => undefined,
  onRemoveInvocationExpansion = () => undefined,
}: FlowchartViewProps) {
  const nodeSizeRegistry = useMemo(() => createNodeSizeRegistry(), []);
  const presentedGraph = draftCompareProjection?.compareGraph ?? view.visibleGraph;
  const layoutSourceGraph = layoutView?.visibleGraph ?? view.visibleGraph;
  const scopedLayoutGraph = useMemo(
    () => scopeFlowchartGraphToAnchorMethod(layoutSourceGraph, view.anchorNodeId ?? null),
    [layoutSourceGraph, view.anchorNodeId],
  );
  const scopedPresentedGraph = useMemo(
    () => scopeFlowchartGraphToAnchorMethod(presentedGraph, view.anchorNodeId ?? null),
    [presentedGraph, view.anchorNodeId],
  );
  const viewGraph = useMemo(
    () => sanitizeFlowchartGraph(scopedLayoutGraph, view.anchorNodeId ?? null),
    [scopedLayoutGraph, view.anchorNodeId],
  );
  const presentedViewGraph = useMemo(
    () => sanitizeFlowchartGraph(scopedPresentedGraph, view.anchorNodeId ?? null),
    [scopedPresentedGraph, view.anchorNodeId],
  );
  const layoutAnchorNodeId = useMemo(
    () => resolveCurrentMethodNode({
      nodes: viewGraph.nodes,
      anchorNodeId: view.anchorNodeId ?? null,
      selectedNodeId,
    })?.id ?? view.anchorNodeId ?? null,
    [selectedNodeId, view.anchorNodeId, viewGraph.nodes],
  );
  const layoutState = useMeasuredLayout({
    graph: viewGraph,
    anchorNodeId: layoutAnchorNodeId,
    nodeSizeRegistry,
    layout: layoutFlowchartView,
    debugLabel: "flowchart",
  });

  const hiddenNodeIdSet = useMemo(() => new Set(hiddenNodeIds), [hiddenNodeIds]);
  const presentedNodesById = useMemo(
    () => new Map(presentedViewGraph.nodes.map((node) => [node.id, node])),
    [presentedViewGraph.nodes],
  );
  const visibleNodes = useMemo(
    () => layoutState.nodes
      .filter((node) => !hiddenNodeIdSet.has(node.id))
      .map((node) => {
        const presentedNode = presentedNodesById.get(node.id);
        if (!presentedNode) {
          return node;
        }
        return {
          ...node,
          ...presentedNode,
          id: node.id,
          position: node.position,
          metadata: {
            ...(node.metadata ?? {}),
            ...(presentedNode.metadata ?? {}),
          },
        };
      }),
    [hiddenNodeIdSet, layoutState.nodes, presentedNodesById],
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
  const fullGraphNodeIndex = useMemo(
    () => new Map(view.fullGraph.nodes.map((node) => [node.id, node])),
    [view.fullGraph.nodes],
  );
  const anchorNode = useMemo(
    () => visibleNodes.find((node) => node.id === view.anchorNodeId) ?? null,
    [view.anchorNodeId, visibleNodes],
  );
  const currentMethodNode = useMemo(
    () => resolveCurrentMethodNode({
      nodes: visibleNodes,
      anchorNodeId: view.anchorNodeId ?? null,
      selectedNodeId,
    }),
    [selectedNodeId, view.anchorNodeId, visibleNodes],
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
      draftCompareNodeStatuses: draftCompareProjection?.nodeStatuses,
      nodeSizeRegistry,
    }),
    [
      visibleNodes,
      visibleEdges,
      selectedNodeId,
      explanationFocusNodeId,
      draftChangedNodeIds,
      draftCompareProjection?.nodeStatuses,
      nodeSizeRegistry,
    ],
  );
  const flowEdges = useMemo(
    () => buildFlowchartEdges({
      edges: visibleEdges,
      nodeIndex,
      draftCompareEdgeStatuses: draftCompareProjection?.edgeStatuses,
    }),
    [draftCompareProjection?.edgeStatuses, visibleEdges, nodeIndex],
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
      {draftCompareProjection ? <DraftCompareSummary projection={draftCompareProjection} /> : null}
      <div className="canvas-reading-grid">
        <article className="canvas-reading-card is-anchor">
          <span className="canvas-reading-label">当前方法</span>
          <strong
            className="canvas-reading-title"
            title={view.summary.nodeCount > 0 ? (currentMethodNode?.title ?? anchorNode?.title ?? "流程图") : "流程图"}
          >
            {view.summary.nodeCount > 0 ? (currentMethodNode?.title ?? anchorNode?.title ?? "流程图") : "流程图"}
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
          {
            const node = nodeIndex.get(nodeId);
            const invocationExpansionNodeId = resolveInvocationExpansionNodeId(node, fullGraphNodeIndex);
            return flowchartNodeActions({
              nodeId,
              canOpenSource: canNavigateToSource(node ?? fallbackSourceNode()),
              editable: true,
              invocationExpansionActionLabel: invocationExpansionNodeId ? "展开被调方法" : null,
              invocationExpansionNodeId,
              expansionId: node?.metadata?.["linkGraph.expansion.id"] ?? null,
              onInspectNode,
              onDeleteNode,
              onRequestSourceNavigation,
              onRequestBeautification,
              onRequestAudit,
              onOpenAudit,
              onExpandInvocation,
              onRemoveInvocationExpansion,
              onFormatLayout: layoutState.requestRelayout,
              onClose: close,
            });
          }
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
