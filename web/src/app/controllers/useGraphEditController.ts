import type { Dispatch, MutableRefObject, SetStateAction } from "react";
import { publishGraphEditScript, publishLayoutChange } from "../api";
import { measureDuration, measureStart, summarizeGraph, traceLinkGraph } from "../debug";
import { clearStoredNodePosition, extractLayoutPayload, extractLayoutState, normalizeGraphNodes } from "../graphState";
import type {
  AnalysisDisplayMode,
  ArchitectureGraphViewDocument,
  ClassDiagramViewDocument,
  FactGraphViewDocument,
  GraphEditOperation,
  GraphEditScript,
  LinkGraphSceneId,
  FlowchartViewDocument,
  LinkGraphDocument,
  LinkGraphEdge,
  LinkGraphLayoutState,
  LinkGraphNode,
  ResourceRelationViewDocument,
  ReviewGraphViewDocument,
} from "../types";

interface UseGraphEditControllerArgs {
  nodes: LinkGraphNode[];
  edges: LinkGraphEdge[];
  selectedNodeId: string | null;
  detailNodeId: string | null;
  analysisDisplayMode: AnalysisDisplayMode;
  currentSceneId: LinkGraphSceneId;
  workspaceRevision: number | null;
  anchorNodeIdRef: MutableRefObject<string | null>;
  setNodes: Dispatch<SetStateAction<LinkGraphNode[]>>;
  setEdges: Dispatch<SetStateAction<LinkGraphEdge[]>>;
  setAnchorNodeId: Dispatch<SetStateAction<string | null>>;
  setSelectedNodeId: Dispatch<SetStateAction<string | null>>;
  setSceneLayoutState: Dispatch<SetStateAction<LinkGraphLayoutState>>;
  setCollapsedNodeIds: Dispatch<SetStateAction<string[]>>;
  setDetailNodeId: Dispatch<SetStateAction<string | null>>;
  setDraftGraph: Dispatch<SetStateAction<LinkGraphDocument | null>>;
  setFactGraphView: Dispatch<SetStateAction<FactGraphViewDocument>>;
  setFlowchartView: Dispatch<SetStateAction<FlowchartViewDocument>>;
  setResourceRelationView: Dispatch<SetStateAction<ResourceRelationViewDocument>>;
  setArchitectureGraphView: Dispatch<SetStateAction<ArchitectureGraphViewDocument>>;
  setClassDiagramView: Dispatch<SetStateAction<ClassDiagramViewDocument>>;
  setReviewGraphView: Dispatch<SetStateAction<ReviewGraphViewDocument>>;
  setQaTargetNodeIds: Dispatch<SetStateAction<string[]>>;
  clearLocalDerivedGraphState: () => void;
  syncManualNodeIdCounters: (nextNodes: Array<{ id: string }>) => void;
  resolveAnchorNodeId: (nodes: LinkGraphNode[], preferredNodeId: string | null) => string | null;
  syncFactGraphViewDocument: (
    current: FactGraphViewDocument,
    graph: LinkGraphDocument,
    anchorNodeId: string | null,
  ) => FactGraphViewDocument;
  deriveFlowchartSummary: (visibleGraph: LinkGraphDocument, fullGraph: LinkGraphDocument) => FlowchartViewDocument["summary"];
  deriveResourceRelationSummary: (visibleGraph: LinkGraphDocument) => ResourceRelationViewDocument["summary"];
  deriveArchitectureGraphSummary: (visibleGraph: LinkGraphDocument) => ArchitectureGraphViewDocument["summary"];
  deriveClassDiagramSummary: (visibleGraph: LinkGraphDocument) => ClassDiagramViewDocument["summary"];
  deriveReviewGraphSummary: (visibleGraph: LinkGraphDocument) => ReviewGraphViewDocument["summary"];
}

export function useGraphEditController(args: UseGraphEditControllerArgs) {
  function buildGraphEditScript(
    previousNodes: LinkGraphNode[],
    previousEdges: LinkGraphEdge[],
    nextNodes: LinkGraphNode[],
    nextEdges: LinkGraphEdge[],
  ): GraphEditScript {
    const previousNodesById = new Map(previousNodes.map((node) => [node.id, node]));
    const previousEdgesById = new Map(previousEdges.map((edge) => [edge.id, edge]));
    const operations: GraphEditOperation[] = [];

    for (const node of nextNodes) {
      const previousNode = previousNodesById.get(node.id);
      if (JSON.stringify(previousNode ?? null) !== JSON.stringify(node)) {
        operations.push({
          type: "UPSERT_NODE",
          node,
        });
      }
    }
    for (const node of previousNodes) {
      if (!nextNodes.some((currentNode) => currentNode.id === node.id)) {
        operations.push({
          type: "REMOVE_NODE",
          nodeId: node.id,
        });
      }
    }
    for (const edge of nextEdges) {
      const previousEdge = previousEdgesById.get(edge.id);
      if (JSON.stringify(previousEdge ?? null) !== JSON.stringify(edge)) {
        operations.push({
          type: "UPSERT_EDGE",
          edge,
        });
      }
    }
    for (const edge of previousEdges) {
      if (!nextEdges.some((currentEdge) => currentEdge.id === edge.id)) {
        operations.push({
          type: "REMOVE_EDGE",
          edgeId: edge.id,
        });
      }
    }

    return {
      sceneId: args.currentSceneId,
      baseWorkspaceRevision: args.workspaceRevision ?? 0,
      operations,
    };
  }

  function syncGraph(
    nextNodes: LinkGraphNode[],
    nextEdges: LinkGraphEdge[],
    nextSelectedNodeId: string | null = args.selectedNodeId,
    options?: {
      forceRelayout?: boolean;
    },
  ) {
    const startedAt = measureStart();
    const nextAnchorNodeId = args.resolveAnchorNodeId(
      nextNodes,
      args.anchorNodeIdRef.current ?? nextSelectedNodeId,
    );
    const nodesForLayout = options?.forceRelayout ? nextNodes.map(clearStoredNodePosition) : nextNodes;
    const laidOutNodes = normalizeGraphNodes(
      nodesForLayout,
      nextEdges,
      nextAnchorNodeId,
      args.analysisDisplayMode,
    );
    traceLinkGraph("app.syncGraph", {
      nextAnchorNodeId,
      nextSelectedNodeId,
      inputGraph: summarizeGraph({ nodes: nodesForLayout, edges: nextEdges }),
      laidOutGraph: summarizeGraph({ nodes: laidOutNodes, edges: nextEdges }),
      durationMs: measureDuration(startedAt),
    });
    args.setNodes(laidOutNodes);
    args.setEdges(nextEdges);
    args.setAnchorNodeId(nextAnchorNodeId);
    args.setSelectedNodeId(nextSelectedNodeId);
    args.setSceneLayoutState(extractLayoutState(laidOutNodes));
    args.syncManualNodeIdCounters(laidOutNodes);
    args.setCollapsedNodeIds((current) => current.filter((nodeId) => laidOutNodes.some((node) => node.id === nodeId)));
    if (args.detailNodeId && !laidOutNodes.some((node) => node.id === args.detailNodeId)) {
      args.setDetailNodeId(null);
    }
    args.setDraftGraph((current) => ({
      ...(current ?? { nodes: [], edges: [] }),
      nodes: laidOutNodes,
      edges: nextEdges,
    }));
    if (args.analysisDisplayMode === "FACT_GRAPH") {
      args.setFactGraphView((current) =>
        args.syncFactGraphViewDocument(current, { nodes: laidOutNodes, edges: nextEdges }, nextAnchorNodeId),
      );
    } else if (args.analysisDisplayMode === "FLOWCHART") {
      const nextGraph = { nodes: laidOutNodes, edges: nextEdges };
      args.setFlowchartView((current) => ({
        ...current,
        visibleGraph: nextGraph,
        fullGraph: nextGraph,
        anchorNodeId: nextAnchorNodeId,
        summary: args.deriveFlowchartSummary(nextGraph, nextGraph),
      }));
    } else if (args.analysisDisplayMode === "RESOURCE_RELATION_VIEW") {
      const nextGraph = { nodes: laidOutNodes, edges: nextEdges };
      args.setResourceRelationView((current) => ({
        ...current,
        visibleGraph: nextGraph,
        fullGraph: nextGraph,
        anchorNodeId: nextAnchorNodeId,
        summary: args.deriveResourceRelationSummary(nextGraph),
      }));
    } else if (args.analysisDisplayMode === "ARCHITECTURE_GRAPH") {
      const nextGraph = { nodes: laidOutNodes, edges: nextEdges };
      args.setArchitectureGraphView((current) => ({
        ...current,
        visibleGraph: nextGraph,
        fullGraph: nextGraph,
        anchorNodeId: nextAnchorNodeId,
        summary: args.deriveArchitectureGraphSummary(nextGraph),
      }));
    } else if (args.analysisDisplayMode === "CLASS_DIAGRAM") {
      const nextGraph = { nodes: laidOutNodes, edges: nextEdges };
      args.setClassDiagramView((current) => ({
        ...current,
        visibleGraph: nextGraph,
        fullGraph: nextGraph,
        anchorNodeId: nextAnchorNodeId,
        summary: args.deriveClassDiagramSummary(nextGraph),
      }));
    } else if (args.analysisDisplayMode === "REVIEW_GRAPH") {
      const nextGraph = { nodes: laidOutNodes, edges: nextEdges };
      args.setReviewGraphView((current) => ({
        ...current,
        visibleGraph: nextGraph,
        fullGraph: nextGraph,
        anchorNodeId: nextAnchorNodeId,
        summary: args.deriveReviewGraphSummary(nextGraph),
      }));
    }
    args.setQaTargetNodeIds((current) => current.filter((nodeId) => laidOutNodes.some((node) => node.id === nodeId)));
    args.clearLocalDerivedGraphState();
    publishGraphEditScript(
      buildGraphEditScript(
        args.nodes,
        args.edges,
        laidOutNodes,
        nextEdges,
      ),
    );
    publishLayoutChange(extractLayoutPayload(laidOutNodes));
  }

  return {
    syncGraph,
  };
}
