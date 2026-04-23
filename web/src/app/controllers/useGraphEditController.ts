import type { Dispatch, MutableRefObject, SetStateAction } from "react";
import { publishGraphChange, publishLayoutChange } from "../api";
import { measureDuration, measureStart, summarizeGraph, traceLinkGraph } from "../debug";
import { clearStoredNodePosition, extractLayoutPayload, normalizeGraphNodes } from "../graphState";
import type {
  AnalysisDisplayMode,
  FactGraphViewDocument,
  FlowchartViewDocument,
  LinkGraphDocument,
  LinkGraphEdge,
  LinkGraphNode,
  ResourceRelationViewDocument,
} from "../types";

interface UseGraphEditControllerArgs {
  selectedNodeId: string | null;
  detailNodeId: string | null;
  analysisDisplayMode: AnalysisDisplayMode;
  anchorNodeIdRef: MutableRefObject<string | null>;
  setNodes: Dispatch<SetStateAction<LinkGraphNode[]>>;
  setEdges: Dispatch<SetStateAction<LinkGraphEdge[]>>;
  setAnchorNodeId: Dispatch<SetStateAction<string | null>>;
  setSelectedNodeId: Dispatch<SetStateAction<string | null>>;
  setCollapsedNodeIds: Dispatch<SetStateAction<string[]>>;
  setDetailNodeId: Dispatch<SetStateAction<string | null>>;
  setDraftGraph: Dispatch<SetStateAction<LinkGraphDocument | null>>;
  setFactGraphView: Dispatch<SetStateAction<FactGraphViewDocument>>;
  setFlowchartView: Dispatch<SetStateAction<FlowchartViewDocument>>;
  setResourceRelationView: Dispatch<SetStateAction<ResourceRelationViewDocument>>;
  setAuditTargetNodeIds: Dispatch<SetStateAction<string[]>>;
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
}

export function useGraphEditController(args: UseGraphEditControllerArgs) {
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
    }
    args.setAuditTargetNodeIds((current) => current.filter((nodeId) => laidOutNodes.some((node) => node.id === nodeId)));
    args.clearLocalDerivedGraphState();
    publishGraphChange(laidOutNodes, nextEdges);
    publishLayoutChange(extractLayoutPayload(laidOutNodes));
  }

  return {
    syncGraph,
  };
}
