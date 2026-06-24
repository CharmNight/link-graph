// 助理上下文（context）解析工具。
// 把"图状态 + 用户选择"转换为助理所需的上下文快照，
// 让助理会话能感知"当前用户在做什么、关注哪些节点"。
import type {
  AnalysisDisplayMode,
  AssistantContextSnapshot,
  LinkGraphDocument,
  LinkGraphNode,
  LinkGraphSceneId,
} from "../types";

/**
 * 把分析展示模式映射为对应的场景 ID。
 *
 * 一一对应关系：FACT_GRAPH → WORKSPACE_FACT 等。
 * 用于在场景 ID 缺失时按展示模式回退。
 */
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

/**
 * 解析助理应该关注的节点 ID 列表。
 *
 * 优先级：
 * 1) 多选集合（过滤掉图中已不存在的）；
 * 2) 单选选中节点；
 * 3) 锚点节点；
 * 4) 唯一节点（图中只有一个节点时直接选它）；
 * 5) 空（无法确定关注点）。
 */
export function resolveAssistantNodeIds(args: {
  graph: LinkGraphDocument;
  selectionGroupNodeIds: string[];
  selectedNodeId?: string | null;
  anchorNodeId?: string | null;
}): string[] {
  // 先建立图中存在的节点 ID 集合，避免选到已删除节点
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
  // 唯一节点直接选它
  return args.graph.nodes.length === 1 ? [args.graph.nodes[0].id] : [];
}

/**
 * 解析"作用范围标签"。
 *
 * 单个节点 → 节点标题；
 * 多个节点 → "N 个节点"；
 * 无节点 → fallback 文案。
 */
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

/**
 * 构造助理上下文快照。
 *
 * 场景 ID 缺失时按展示模式回退；
 * 方法签名缺失时填 null。
 *
 * @return 助理上下文快照
 */
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
    // 场景 ID 缺失时按展示模式回退
    currentSceneId: args.currentSceneId ?? sceneIdForAnalysisDisplayMode(args.analysisDisplayMode),
    selectedMethodSignature: args.selectedMethodSignature ?? null,
    scopeLabel: args.scopeLabel,
  };
}
