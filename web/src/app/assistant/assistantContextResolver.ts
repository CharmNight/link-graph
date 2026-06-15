import type {
  AnalysisDisplayMode,
  AssistantContextSnapshot,
  LinkGraphDocument,
  LinkGraphNode,
  LinkGraphSceneId,
} from "../types";

export function sceneIdForAnalysisDisplayMode(
  analysisDisplayMode: AnalysisDisplayMode,
): LinkGraphSceneId {
  switch (analysisDisplayMode) {
    case "FACT_GRAPH":
      return "WORKSPACE_FACT";
    case "FLOWCHART":
      return "WORKSPACE_FLOWCHART";
    case "RESOURCE_RELATION_VIEW":
      return "WORKSPACE_RESOURCE_RELATION";
    case "ARCHITECTURE_GRAPH":
      return "WORKSPACE_ARCHITECTURE_GRAPH";
    case "CLASS_DIAGRAM":
      return "WORKSPACE_CLASS_DIAGRAM";
    case "REVIEW_GRAPH":
      return "WORKSPACE_REVIEW_GRAPH";
  }
}

export function resolveAssistantNodeIds(args: {
  graph: LinkGraphDocument;
  selectionGroupNodeIds: string[];
  selectedNodeId?: string | null;
  anchorNodeId?: string | null;
}): string[] {
  const nodeIds = new Set(args.graph.nodes.map((node) => node.id));
  const selectedGroup = args.selectionGroupNodeIds.filter((nodeId) => nodeIds.has(nodeId));
  if (selectedGroup.length > 0) {
    return selectedGroup;
  }
  if (args.selectedNodeId && nodeIds.has(args.selectedNodeId)) {
    return [args.selectedNodeId];
  }
  if (args.anchorNodeId && nodeIds.has(args.anchorNodeId)) {
    return [args.anchorNodeId];
  }
  return args.graph.nodes.length === 1 ? [args.graph.nodes[0].id] : [];
}

export function resolveAssistantScopeLabel(
  selectedNodeIds: string[],
  nodes: LinkGraphNode[],
  fallback: string,
): string {
  if (selectedNodeIds.length === 1) {
    return nodes.find((node) => node.id === selectedNodeIds[0])?.title ?? selectedNodeIds[0];
  }
  if (selectedNodeIds.length > 1) {
    return `${selectedNodeIds.length} 个节点`;
  }
  return fallback;
}

export function buildAssistantContextSnapshot(args: {
  selectedNodeIds: string[];
  selectedDiffItemIds: string[];
  analysisDisplayMode: AnalysisDisplayMode;
  currentSceneId?: LinkGraphSceneId | null;
  selectedMethodSignature?: string | null;
  scopeLabel: string;
}): AssistantContextSnapshot {
  return {
    selectedNodeIds: args.selectedNodeIds,
    selectedDiffItemIds: args.selectedDiffItemIds,
    analysisDisplayMode: args.analysisDisplayMode,
    currentSceneId: args.currentSceneId ?? sceneIdForAnalysisDisplayMode(args.analysisDisplayMode),
    selectedMethodSignature: args.selectedMethodSignature ?? null,
    scopeLabel: args.scopeLabel,
  };
}
