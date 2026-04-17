import { useMemo } from "react";
import { buildEdgeActions as buildSharedEdgeActions, buildPaneActions } from "../../components/graph/actions/actionSchema";
import { createNodeSizeRegistry } from "../../graph/nodeSizeRegistry";
import { nodeCardWidth } from "../../graphNodeSizing";
import { useMeasuredLayout } from "../../reactflow/useMeasuredLayout";
import { GraphFlowSurface } from "../../reactflow/GraphFlowSurface";
import { canNavigateToSource } from "../../sourceNavigation";
import type { ResourceRelationViewDocument } from "../../types";
import type { ViewStageProps } from "../viewStageProps";
import { layoutResourceRelationView } from "./resourceRelationLayout";
import {
  buildResourceRelationEdges,
  buildResourceRelationNodes,
  RESOURCE_RELATION_NODE_TYPES,
} from "./resourceRelationNodes";

interface ResourceRelationViewProps extends ViewStageProps {
  view: ResourceRelationViewDocument;
}

function fallbackSourceNode() {
  return {
    type: "DOC_PAGE" as const,
    location: undefined,
    signature: undefined,
  };
}

function resourceNodeActions(args: {
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
      label: "讲解当前资源关系",
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
      label: "重新整理资源关系",
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

function resourceSummaryText(laneCounts: Record<string, number>) {
  const items = Object.entries(laneCounts);
  if (items.length === 0) {
    return "当前没有资源节点。";
  }
  return items
    .map(([lane, count]) => `${lane} ${count}`)
    .join(" · ");
}

export function ResourceRelationView({
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
}: ResourceRelationViewProps) {
  const nodeSizeRegistry = useMemo(() => createNodeSizeRegistry(), []);
  const layoutState = useMeasuredLayout({
    graph: view.visibleGraph,
    anchorNodeId: view.anchorNodeId ?? null,
    nodeSizeRegistry,
    layout: layoutResourceRelationView,
    debugLabel: "resource",
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
  const isLayoutLoading = layoutState.layoutPending && view.visibleGraph.nodes.length > 0 && layoutState.nodes.length === 0;
  const nodeIndex = useMemo(
    () => new Map(visibleNodes.map((node) => [node.id, node])),
    [visibleNodes],
  );
  const selectedNode = useMemo(
    () => visibleNodes.find((node) => node.id === selectedNodeId) ?? null,
    [visibleNodes, selectedNodeId],
  );
  const flowNodes = useMemo(
    () => buildResourceRelationNodes({
      nodes: visibleNodes,
      selectedNodeId,
      explanationFocusNodeId,
      draftChangedNodeIds,
      nodeSizeRegistry,
    }),
    [visibleNodes, selectedNodeId, explanationFocusNodeId, draftChangedNodeIds, nodeSizeRegistry],
  );
  const flowEdges = useMemo(
    () => buildResourceRelationEdges({ edges: visibleEdges }),
    [visibleEdges],
  );

  const header = (
    <section className="canvas-reading-summary" aria-label="资源关系摘要">
      <div className="canvas-reading-grid">
        <article className="canvas-reading-card is-anchor">
          <span className="canvas-reading-label">主体视角</span>
          <strong className="canvas-reading-title">{selectedNode?.title ?? "资源关系图"}</strong>
          <span className="canvas-reading-detail">
            当前可见 {view.summary.visibleNodeCount} 个节点。
          </span>
        </article>
        <article className="canvas-reading-card">
          <span className="canvas-reading-label">Lane 分布</span>
          <strong className="canvas-reading-title">资源类型统计</strong>
          <span className="canvas-reading-detail">{resourceSummaryText(view.summary.laneCounts)}</span>
        </article>
      </div>
    </section>
  );

  return (
    <section className="graph-stage-view resource-relation-view" data-testid="resource-relation-view">
      <GraphFlowSurface
        nodes={visibleNodes}
        edges={visibleEdges}
        flowNodes={flowNodes}
        flowEdges={flowEdges}
        nodeTypes={RESOURCE_RELATION_NODE_TYPES}
        viewportMode="RESOURCE_RELATION_VIEW"
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
              <strong>正在整理资源关系</strong>
              <p className="muted">资源分析已完成，正在计算稳定布局。</p>
            </div>
          ) : (
            <div className="canvas-empty-state">
              <strong>当前没有可展示的资源关系</strong>
              <p className="muted">请先完成分析，再查看代码与资源之间的依赖关系。</p>
            </div>
          )
        )}
        buildPaneActions={({ position, hasGroupedSelection, visibleNodeCount, close }) =>
          buildPaneActions({
            analysisDisplayMode: "RESOURCE_RELATION_VIEW",
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
          resourceNodeActions({
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
              analysisDisplayMode: "RESOURCE_RELATION_VIEW",
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
        nodeViewportSize={(node) => ({ width: nodeCardWidth(node), height: 156 })}
      />
    </section>
  );
}
