import { useMemo } from "react";
import { buildEdgeActions as buildSharedEdgeActions, buildPaneActions } from "../../components/graph/actions/actionSchema";
import { CanvasEmptyState } from "../../components/graph/CanvasEmptyState";
import { canEditProjectedEdge, canEditProjectedNode } from "../../graphProjectionPermissions";
import { nodeCardWidth } from "../../graphNodeSizing";
import { useGraphView } from "../../reactflow/useGraphView";
import { GraphFlowSurface } from "../../reactflow/GraphFlowSurface";
import { canNavigateToSource } from "../../sourceNavigation";
import type { ResourceRelationViewDocument } from "../../types";
import { DraftCompareSummary } from "../../components/DraftCompareSummary";
import type { EditableStageProps } from "../viewStageProps";
import { layoutResourceRelationView } from "./resourceRelationLayout";
import {
  buildResourceRelationEdges,
  buildResourceRelationNodes,
  RESOURCE_RELATION_NODE_TYPES,
} from "./resourceRelationNodes";

interface ResourceRelationViewProps extends EditableStageProps {
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
  onPrimeQuestionComposer: (selectedNodeId?: string) => void;
  onOpenQa: (selectedNodeId?: string) => void;
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

function resourceFallbackText(summary: ResourceRelationViewDocument["summary"]): string | null {
  switch (summary.fallbackReason) {
    case "NO_RESOURCE_UNITS":
      return "当前分析范围没有识别到资源单元。";
    case "NO_BINDING_RELATIONS":
      return `识别到 ${summary.resourceCount} 个资源单元，但没有发现代码与资源之间的绑定关系。`;
    default:
      return null;
  }
}

export function ResourceRelationView({
  view,
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
  onPrimeQuestionComposer = () => undefined,
  onOpenQa = () => undefined,
  onImportMermaid,
}: ResourceRelationViewProps) {
  const presentedGraph = draftCompareProjection?.compareGraph ?? view.visibleGraph;
  const {
    visibleNodes,
    visibleEdges,
    nodeIndex,
    nodeSizeRegistry,
    selectedNode,
    isLayoutLoading,
    requestRelayout,
  } = useGraphView({
    graph: presentedGraph,
    anchorNodeId: view.anchorNodeId ?? null,
    selectedNodeId,
    hiddenNodeIds,
    layout: layoutResourceRelationView,
    debugLabel: "resource",
  });

  const flowNodes = useMemo(
    () => buildResourceRelationNodes({
      nodes: visibleNodes,
      selectedNodeId,
      explanationFocusNodeId,
      draftChangedNodeIds,
      draftCompareNodeStatuses: draftCompareProjection?.nodeStatuses,
      projectionIndex: view.projectionIndex ?? null,
      nodeSizeRegistry,
    }),
    [
      visibleNodes,
      selectedNodeId,
      explanationFocusNodeId,
      draftChangedNodeIds,
      draftCompareProjection?.nodeStatuses,
      view.projectionIndex,
      nodeSizeRegistry,
    ],
  );
  const flowEdges = useMemo(
    () => buildResourceRelationEdges({
      edges: visibleEdges,
      draftCompareEdgeStatuses: draftCompareProjection?.edgeStatuses,
    }),
    [draftCompareProjection?.edgeStatuses, visibleEdges],
  );
  const fallbackText = resourceFallbackText(view.summary);

  const header = (
    <section className="canvas-reading-summary" aria-label="资源关系摘要">
      {draftCompareProjection ? <DraftCompareSummary projection={draftCompareProjection} /> : null}
      <div className="canvas-reading-grid">
        <article className="canvas-reading-card is-anchor">
          <span className="canvas-reading-label">主体视角</span>
          <strong className="canvas-reading-title">{selectedNode?.title ?? "资源关系图"}</strong>
          <span className="canvas-reading-detail">
            当前可见 {view.summary.visibleNodeCount} 个节点。
            {view.summary.relationCount != null ? ` 关系 ${view.summary.relationCount} 条。` : ""}
          </span>
          {fallbackText ? (
            <span className="canvas-reading-detail">{fallbackText}</span>
          ) : null}
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
          <CanvasEmptyState
            isLoading={isLayoutLoading}
            loadingTitle="正在整理资源关系"
            idleTitle="当前没有可展示的资源关系"
          />
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
            onFormatLayout: requestRelayout,
            onOpenQa,
            onClose: close,
          })
        }
        buildNodeActions={({ nodeId, close }) =>
          resourceNodeActions({
            nodeId,
            canOpenSource: canNavigateToSource(nodeIndex.get(nodeId) ?? fallbackSourceNode()),
            editable: canEditProjectedNode(view.projectionIndex, nodeId, "DELETE_NODE"),
            onInspectNode,
            onDeleteNode,
            onRequestSourceNavigation,
            onRequestBeautification,
            onPrimeQuestionComposer,
            onOpenQa,
            onFormatLayout: requestRelayout,
            onClose: close,
          })
        }
        buildEdgeActions={({ edgeId, close }) =>
          {
            const canInsertNode = canEditProjectedEdge(view.projectionIndex, edgeId, "INSERT_NODE_INTO_EDGE");
            return [
              ...(canInsertNode
                ? [
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
                  ]
                : []),
              ...buildSharedEdgeActions({
                analysisDisplayMode: "RESOURCE_RELATION_VIEW",
                editable: true,
                edgeId,
                canEditEdge: (command) => canEditProjectedEdge(view.projectionIndex, edgeId, command),
                onDeleteEdge,
                onClose: close,
              }),
            ];
          }
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
