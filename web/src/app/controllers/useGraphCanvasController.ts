import { startTransition, type Dispatch, type MutableRefObject, type SetStateAction } from "react";
import { publishLayoutChange } from "../api";
import { measureDuration, measureStart, traceLinkGraph } from "../debug";
import { canEditNodeLayout } from "../layoutEditability";
import type {
  AnalysisDisplayMode,
  FactGraphViewDocument,
  FlowchartViewDocument,
  GraphPosition,
  LinkGraphDocument,
  LinkGraphEdge,
  LinkGraphNode,
  OperationFeedback,
  ResourceRelationViewDocument,
} from "../types";

interface UseGraphCanvasControllerArgs {
  nodes: LinkGraphNode[];
  edges: LinkGraphEdge[];
  selectedNodeId: string | null;
  detailNodeId: string | null;
  analysisDisplayMode: AnalysisDisplayMode;
  collapsedNodeIds: string[];
  nextManualNodeIdRef: MutableRefObject<number>;
  anchorNodeIdRef: MutableRefObject<string | null>;
  setNodes: Dispatch<SetStateAction<LinkGraphNode[]>>;
  setDraftGraph: Dispatch<SetStateAction<LinkGraphDocument | null>>;
  setFactGraphView: Dispatch<SetStateAction<FactGraphViewDocument>>;
  setFlowchartView: Dispatch<SetStateAction<FlowchartViewDocument>>;
  setResourceRelationView: Dispatch<SetStateAction<ResourceRelationViewDocument>>;
  setCollapsedNodeIds: Dispatch<SetStateAction<string[]>>;
  setSelectionGroupNodeIds: Dispatch<SetStateAction<string[]>>;
  setAuditTargetNodeIds: Dispatch<SetStateAction<string[]>>;
  setSelectedNodeId: Dispatch<SetStateAction<string | null>>;
  setDetailNodeId: Dispatch<SetStateAction<string | null>>;
  setOperationFeedback: Dispatch<SetStateAction<OperationFeedback | null>>;
  syncGraph: (
    nextNodes: LinkGraphNode[],
    nextEdges: LinkGraphEdge[],
    nextSelectedNodeId?: string | null,
    options?: { forceRelayout?: boolean },
  ) => void;
  fallbackDesignPosition: (index: number) => GraphPosition;
  resolveNodePosition: (node: LinkGraphNode) => GraphPosition | null;
  syncNodePosition: (node: LinkGraphNode, position: GraphPosition) => LinkGraphNode;
  collectDownstreamSubtreeNodeIds: (nodeId: string, nodes: LinkGraphNode[], edges: LinkGraphEdge[]) => Set<string>;
  applyLayoutUpdatesToGraphDocument: (
    graph: LinkGraphDocument,
    updates: Array<{ id: string; position: GraphPosition }>,
  ) => LinkGraphDocument;
  syncFactGraphViewDocument: (
    current: FactGraphViewDocument,
    graph: LinkGraphDocument,
    anchorNodeId: string | null,
  ) => FactGraphViewDocument;
  syncFlowchartViewLayout: (
    current: FlowchartViewDocument,
    updates: Array<{ id: string; position: GraphPosition }>,
  ) => FlowchartViewDocument;
  syncResourceRelationViewLayout: (
    current: ResourceRelationViewDocument,
    updates: Array<{ id: string; position: GraphPosition }>,
  ) => ResourceRelationViewDocument;
  resolveCollapsedDescendantSummary: (
    nodes: LinkGraphNode[],
    edges: LinkGraphEdge[],
    collapsedNodeIds: string[],
  ) => { hiddenNodeIds: Set<string>; descendantCountByNodeId: Record<string, number> };
}

export function useGraphCanvasController(args: UseGraphCanvasControllerArgs) {
  function buildManualNode(
    kind: "METHOD" | "DOC_PAGE",
    nextIndex: number,
    position: GraphPosition,
  ): LinkGraphNode {
    if (kind === "DOC_PAGE") {
      return {
        id: `design-note:${nextIndex}`,
        type: "DOC_PAGE",
        title: `说明${nextIndex}`,
        inputs: [],
        outputs: [],
        doc: "请填写业务说明",
        certainty: "PROVEN",
        bindingStatus: "DESIGN_ONLY",
        sourceTag: "DRAFT_MANUAL",
        position,
        metadata: {
          "linkGraph.manual": "true",
          "ui.x": String(position.x),
          "ui.y": String(position.y),
        },
      };
    }
    return {
      id: `design:${nextIndex}`,
      type: "METHOD",
      title: `新方法${nextIndex}`,
      inputs: [],
      outputs: [],
      certainty: "PROVEN",
      bindingStatus: "DESIGN_ONLY",
      sourceTag: "DRAFT_MANUAL",
      position,
      metadata: {
        "linkGraph.manual": "true",
        "ui.x": String(position.x),
        "ui.y": String(position.y),
      },
    };
  }

  function routeMidpoint(route?: LinkGraphEdge["route"]): GraphPosition | null {
    const points = route?.sections.flatMap((section) => [
      section.startPoint,
      ...(section.bendPoints ?? []),
      section.endPoint,
    ]) ?? [];
    if (points.length < 2) {
      return null;
    }
    const segments = points.slice(1).map((point, index) => {
      const startPoint = points[index]!;
      const endPoint = point;
      return {
        startPoint,
        endPoint,
        length: Math.hypot(endPoint.x - startPoint.x, endPoint.y - startPoint.y),
      };
    });
    const totalLength = segments.reduce((sum, segment) => sum + segment.length, 0);
    if (totalLength <= 0) {
      return points[Math.floor(points.length / 2)] ?? null;
    }
    const midpointOffset = totalLength / 2;
    let traversed = 0;
    for (const segment of segments) {
      if (traversed + segment.length >= midpointOffset) {
        const ratio = (midpointOffset - traversed) / segment.length;
        return {
          x: segment.startPoint.x + (segment.endPoint.x - segment.startPoint.x) * ratio,
          y: segment.startPoint.y + (segment.endPoint.y - segment.startPoint.y) * ratio,
        };
      }
      traversed += segment.length;
    }
    return segments[segments.length - 1]?.endPoint ?? null;
  }

  function resolveEdgeInsertPosition(edge: LinkGraphEdge): GraphPosition {
    const routePosition = routeMidpoint(edge.route);
    if (routePosition) {
      return routePosition;
    }
    const sourceNode = args.nodes.find((node) => node.id === edge.source);
    const targetNode = args.nodes.find((node) => node.id === edge.target);
    const sourcePosition = sourceNode ? args.resolveNodePosition(sourceNode) : null;
    const targetPosition = targetNode ? args.resolveNodePosition(targetNode) : null;
    if (sourcePosition && targetPosition) {
      return {
        x: (sourcePosition.x + targetPosition.x) / 2,
        y: (sourcePosition.y + targetPosition.y) / 2,
      };
    }
    return args.fallbackDesignPosition(args.nodes.length);
  }

  function handleAddNode(kind: "METHOD" | "DOC_PAGE", position?: GraphPosition) {
    startTransition(() => {
      const nextPosition = position ?? args.fallbackDesignPosition(args.nodes.length);
      const nextIndex = args.nextManualNodeIdRef.current++;
      const nextNode = buildManualNode(kind, nextIndex, nextPosition);
      args.syncGraph([...args.nodes, nextNode], args.edges, nextNode.id);
      args.setDetailNodeId(nextNode.id);
    });
  }

  function handleDeleteNode(nodeId: string) {
    startTransition(() => {
      const nextNodes = args.nodes.filter((node) => node.id !== nodeId);
      const nextEdges = args.edges.filter((edge) => edge.source !== nodeId && edge.target !== nodeId);
      args.syncGraph(nextNodes, nextEdges, args.selectedNodeId === nodeId ? null : args.selectedNodeId);
    });
  }

  function handleDeleteNodeSubtree(nodeId: string) {
    startTransition(() => {
      const deletedNodeIds = args.collectDownstreamSubtreeNodeIds(nodeId, args.nodes, args.edges);
      if (deletedNodeIds.size === 0) {
        return;
      }
      const nextNodes = args.nodes.filter((node) => !deletedNodeIds.has(node.id));
      const nextEdges = args.edges.filter((edge) => !deletedNodeIds.has(edge.source) && !deletedNodeIds.has(edge.target));
      const nextSelectedNodeId = args.selectedNodeId && deletedNodeIds.has(args.selectedNodeId) ? null : args.selectedNodeId;
      args.syncGraph(nextNodes, nextEdges, nextSelectedNodeId);
    });
  }

  function handleUpdateNode(nextNode: LinkGraphNode) {
    startTransition(() => {
      const previousNode = args.nodes.find((node) => node.id === nextNode.id);
      const mergedNode = previousNode?.position ? args.syncNodePosition(nextNode, previousNode.position) : nextNode;
      args.syncGraph(
        args.nodes.map((node) => (node.id === mergedNode.id ? mergedNode : node)),
        args.edges,
        mergedNode.id,
      );
      args.setDetailNodeId(mergedNode.id);
    });
  }

  function handleCreateEdge(
    sourceId: string,
    targetId: string,
    sourceHandle?: string | null,
    targetHandle?: string | null,
  ) {
    startTransition(() => {
      const nextEdgeId = `design-link:${sourceId}->${targetId}`;
      if (args.edges.some((edge) =>
        edge.source === sourceId
        && edge.target === targetId
        && (edge.sourceHandle ?? null) === (sourceHandle ?? null)
        && (edge.targetHandle ?? null) === (targetHandle ?? null)
      )) {
        return;
      }
      const nextEdgeType = args.analysisDisplayMode === "FLOWCHART" ? "CONTROL_FLOW" : "CALL";
      args.syncGraph(
        args.nodes,
        [
          ...args.edges,
          {
            id: nextEdgeId,
            type: nextEdgeType,
            source: sourceId,
            target: targetId,
            sourceHandle: sourceHandle ?? null,
            targetHandle: targetHandle ?? null,
            sourceTag: "DRAFT_MANUAL",
          },
        ],
      );
    });
  }

  function handleDeleteEdge(edgeId: string) {
    startTransition(() => {
      args.syncGraph(
        args.nodes,
        args.edges.filter((edge) => edge.id !== edgeId),
      );
    });
  }

  function handleInsertNodeIntoEdge(edgeId: string, kind: "METHOD" | "DOC_PAGE") {
    startTransition(() => {
      const targetEdge = args.edges.find((edge) => edge.id === edgeId);
      if (!targetEdge) {
        return;
      }
      const nextIndex = args.nextManualNodeIdRef.current++;
      const nextNode = buildManualNode(kind, nextIndex, resolveEdgeInsertPosition(targetEdge));
      const nextEdgeType = args.analysisDisplayMode === "FLOWCHART" ? "CONTROL_FLOW" : targetEdge.type;
      const nextEdges = args.edges
        .filter((edge) => edge.id !== edgeId)
        .concat(
          {
            id: `${edgeId}:before`,
            type: nextEdgeType,
            source: targetEdge.source,
            target: nextNode.id,
            label: targetEdge.label,
            metadata: targetEdge.metadata,
            sourceTag: "DRAFT_MANUAL",
          },
          {
            id: `${edgeId}:after`,
            type: nextEdgeType,
            source: nextNode.id,
            target: targetEdge.target,
            sourceTag: "DRAFT_MANUAL",
          },
        );
      args.syncGraph([...args.nodes, nextNode], nextEdges, nextNode.id);
      args.setDetailNodeId(nextNode.id);
    });
  }

  function handleMoveNode(nodeId: string, position: GraphPosition) {
    const currentNode = args.nodes.find((node) => node.id === nodeId);
    if (!currentNode || !canEditNodeLayout(currentNode, args.analysisDisplayMode)) {
      return;
    }
    startTransition(() => {
      const layoutUpdates = [{ id: nodeId, position }];
      const nextNodes = args.nodes.map((node) => (node.id === nodeId ? args.syncNodePosition(node, position) : node));
      args.setNodes(nextNodes);
      args.setDraftGraph((current) => current
        ? args.applyLayoutUpdatesToGraphDocument(current, layoutUpdates)
        : current);
      if (args.analysisDisplayMode === "FACT_GRAPH") {
        args.setFactGraphView((current) =>
          args.syncFactGraphViewDocument(
            current,
            { nodes: nextNodes, edges: args.edges },
            current.anchorNodeId ?? args.anchorNodeIdRef.current ?? null,
          ),
        );
      } else if (args.analysisDisplayMode === "FLOWCHART") {
        args.setFlowchartView((current) => args.syncFlowchartViewLayout(current, layoutUpdates));
      } else if (args.analysisDisplayMode === "RESOURCE_RELATION_VIEW") {
        args.setResourceRelationView((current) => args.syncResourceRelationViewLayout(current, layoutUpdates));
      }
      traceLinkGraph("app.layoutPublished", {
        reason: "single-node-drag",
        updateCount: 1,
        nodeIds: [nodeId],
        durationMs: measureDuration(measureStart()),
      });
      publishLayoutChange([
        {
          nodeId,
          x: position.x,
          y: position.y,
        },
      ]);
    });
  }

  function handleMoveNodes(updates: Array<{ id: string; position: GraphPosition }>) {
    const editableUpdates = updates.filter((update) => {
      const currentNode = args.nodes.find((node) => node.id === update.id);
      return Boolean(currentNode && canEditNodeLayout(currentNode, args.analysisDisplayMode));
    });
    if (editableUpdates.length === 0) {
      return;
    }
    startTransition(() => {
      const updateMap = new Map(editableUpdates.map((item) => [item.id, item.position]));
      const nextNodes = args.nodes.map((node) => {
        const nextPosition = updateMap.get(node.id);
        return nextPosition ? args.syncNodePosition(node, nextPosition) : node;
      });
      args.setNodes(nextNodes);
      args.setDraftGraph((current) => current
        ? args.applyLayoutUpdatesToGraphDocument(current, editableUpdates)
        : current);
      if (args.analysisDisplayMode === "FACT_GRAPH") {
        args.setFactGraphView((current) =>
          args.syncFactGraphViewDocument(
            current,
            { nodes: nextNodes, edges: args.edges },
            current.anchorNodeId ?? args.anchorNodeIdRef.current ?? null,
          ),
        );
      } else if (args.analysisDisplayMode === "FLOWCHART") {
        args.setFlowchartView((current) => args.syncFlowchartViewLayout(current, editableUpdates));
      } else if (args.analysisDisplayMode === "RESOURCE_RELATION_VIEW") {
        args.setResourceRelationView((current) => args.syncResourceRelationViewLayout(current, editableUpdates));
      }
      traceLinkGraph("app.layoutPublished", {
        reason: "group-drag",
        updateCount: editableUpdates.length,
        nodeIds: editableUpdates.slice(0, 8).map((update) => update.id),
      });
      publishLayoutChange(
        editableUpdates.map((update) => ({
          nodeId: update.id,
          x: update.position.x,
          y: update.position.y,
        })),
      );
    });
  }

  function handleToggleCollapseNode(nodeId: string) {
    startTransition(() => {
      const nextCollapsedNodeIds = args.collapsedNodeIds.includes(nodeId)
        ? args.collapsedNodeIds.filter((item) => item !== nodeId)
        : [...args.collapsedNodeIds, nodeId];
      const nextHiddenNodeIds = args.resolveCollapsedDescendantSummary(args.nodes, args.edges, nextCollapsedNodeIds).hiddenNodeIds;

      args.setCollapsedNodeIds(nextCollapsedNodeIds);
      args.setSelectionGroupNodeIds((current) => current.filter((item) => !nextHiddenNodeIds.has(item)));
      args.setAuditTargetNodeIds((current) => current.filter((item) => !nextHiddenNodeIds.has(item)));
      if (args.selectedNodeId && nextHiddenNodeIds.has(args.selectedNodeId)) {
        args.setSelectedNodeId(nodeId);
      }
      if (args.detailNodeId && nextHiddenNodeIds.has(args.detailNodeId)) {
        args.setDetailNodeId(null);
      }
    });
  }

  function handleFormatLayout() {
    startTransition(() => {
      args.syncGraph(args.nodes, args.edges, args.selectedNodeId, { forceRelayout: true });
      args.setOperationFeedback({
        level: "INFO",
        message: "已重新整理当前画布布局。",
      });
    });
  }

  return {
    handleAddNode,
    handleDeleteNode,
    handleDeleteNodeSubtree,
    handleUpdateNode,
    handleCreateEdge,
    handleDeleteEdge,
    handleInsertNodeIntoEdge,
    handleMoveNode,
    handleMoveNodes,
    handleToggleCollapseNode,
    handleFormatLayout,
  };
}
