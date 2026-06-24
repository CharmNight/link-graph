// 图编辑控制器：负责把前端图变更（增删节点/边、位置变化等）同步到所有相关状态与后端。
// 核心动作：syncGraph——把新节点/边集合写入节点列表、各视图文档、草稿图、桥接等。
import type { Dispatch, MutableRefObject, SetStateAction } from "react";
import { publishGraphEditRequest, publishLayoutChange } from "../api";
import { measureDuration, measureStart, summarizeGraph, traceLinkGraph } from "../debug";
import { clearStoredNodePosition, extractLayoutPayload, extractLayoutState, normalizeGraphNodes } from "../graphState";
import type {
  AnalysisDisplayMode,
  ArchitectureGraphViewDocument,
  ClassDiagramViewDocument,
  FactGraphViewDocument,
  GraphEditOperation,
  GraphEditRequest,
  LinkGraphSceneId,
  FlowchartViewDocument,
  LinkGraphDocument,
  LinkGraphEdge,
  LinkGraphLayoutState,
  LinkGraphNode,
  ResourceRelationViewDocument,
  ReviewGraphViewDocument,
} from "../types";

/** useGraphEditController 的入参。 */
interface UseGraphEditControllerArgs {
  /** 当前画布节点列表。 */
  nodes: LinkGraphNode[];
  /** 当前画布边列表。 */
  edges: LinkGraphEdge[];
  /** 当前选中节点 ID。 */
  selectedNodeId: string | null;
  /** 当前检查节点 ID。 */
  detailNodeId: string | null;
  /** 当前展示模式。 */
  analysisDisplayMode: AnalysisDisplayMode;
  /** 当前场景 ID。 */
  currentSceneId: LinkGraphSceneId;
  /** 工作台版本号。 */
  workspaceRevision: number | null;
  /** 锚点节点 ID 的 ref。 */
  anchorNodeIdRef: MutableRefObject<string | null>;
  /** 设置节点列表。 */
  setNodes: Dispatch<SetStateAction<LinkGraphNode[]>>;
  /** 设置边列表。 */
  setEdges: Dispatch<SetStateAction<LinkGraphEdge[]>>;
  /** 设置锚点节点 ID。 */
  setAnchorNodeId: Dispatch<SetStateAction<string | null>>;
  /** 设置选中节点 ID。 */
  setSelectedNodeId: Dispatch<SetStateAction<string | null>>;
  /** 设置场景布局状态。 */
  setSceneLayoutState: Dispatch<SetStateAction<LinkGraphLayoutState>>;
  /** 设置折叠节点 ID 列表。 */
  setCollapsedNodeIds: Dispatch<SetStateAction<string[]>>;
  /** 设置检查节点 ID。 */
  setDetailNodeId: Dispatch<SetStateAction<string | null>>;
  /** 设置草稿图。 */
  setDraftGraph: Dispatch<SetStateAction<LinkGraphDocument | null>>;
  /** 设置事实图视图。 */
  setFactGraphView: Dispatch<SetStateAction<FactGraphViewDocument>>;
  /** 设置流程图视图。 */
  setFlowchartView: Dispatch<SetStateAction<FlowchartViewDocument>>;
  /** 设置资源关系图视图。 */
  setResourceRelationView: Dispatch<SetStateAction<ResourceRelationViewDocument>>;
  /** 设置架构图视图。 */
  setArchitectureGraphView: Dispatch<SetStateAction<ArchitectureGraphViewDocument>>;
  /** 设置类图视图。 */
  setClassDiagramView: Dispatch<SetStateAction<ClassDiagramViewDocument>>;
  /** 设置审查图视图。 */
  setReviewGraphView: Dispatch<SetStateAction<ReviewGraphViewDocument>>;
  /** 设置 QA 目标节点 ID 列表。 */
  setQaTargetNodeIds: Dispatch<SetStateAction<string[]>>;
  /** 清空本地派生图状态。 */
  clearLocalDerivedGraphState: () => void;
  /** 同步手工节点 ID 计数器。 */
  syncManualNodeIdCounters: (nextNodes: Array<{ id: string }>) => void;
  /** 解析锚点节点 ID。 */
  resolveAnchorNodeId: (nodes: LinkGraphNode[], preferredNodeId: string | null) => string | null;
  /** 把工作图同步到事实图视图。 */
  syncFactGraphViewDocument: (
    current: FactGraphViewDocument,
    graph: LinkGraphDocument,
    anchorNodeId: string | null,
  ) => FactGraphViewDocument;
  /** 派生流程图摘要。 */
  deriveFlowchartSummary: (
    visibleGraph: LinkGraphDocument,
    fullGraph: LinkGraphDocument,
    currentSummary?: FlowchartViewDocument["summary"],
  ) => FlowchartViewDocument["summary"];
  /** 派生资源关系图摘要。 */
  deriveResourceRelationSummary: (visibleGraph: LinkGraphDocument) => ResourceRelationViewDocument["summary"];
}

/**
 * 图编辑控制器 Hook。
 *
 * 主要提供 syncGraph 函数：把一份新的节点/边集合同步到所有相关位置——
 * 节点列表、各视图文档（按当前模式分发）、草稿图、桥接（编辑请求 + 布局变化）等。
 *
 * syncGraph 内部会做坐标归一化、锚点解析、失效引用清理（折叠节点、检查节点等），
 * 确保同步后状态保持自洽。
 */
export function useGraphEditController(args: UseGraphEditControllerArgs) {
  /**
   * 根据前后节点/边差异构造编辑请求。
   *
   * 用 JSON.stringify 做对象比较（简单可靠）：
   * - 新增/修改的节点 → UPSERT_NODE；
   * - 删除的节点 → REMOVE_NODE；
   * - 边同理。
   */
  function buildGraphEditRequest(
    previousNodes: LinkGraphNode[],
    previousEdges: LinkGraphEdge[],
    nextNodes: LinkGraphNode[],
    nextEdges: LinkGraphEdge[],
  ): GraphEditRequest {
    const previousNodesById = new Map(previousNodes.map((node) => [node.id, node]));
    const previousEdgesById = new Map(previousEdges.map((edge) => [edge.id, edge]));
    const operations: GraphEditOperation[] = [];

    // 新增/修改的节点
    for (const node of nextNodes) {
      const previousNode = previousNodesById.get(node.id);
      if (JSON.stringify(previousNode ?? null) !== JSON.stringify(node)) {
        operations.push({
          type: "UPSERT_NODE",
          node,
        });
      }
    }
    // 删除的节点
    for (const node of previousNodes) {
      if (!nextNodes.some((currentNode) => currentNode.id === node.id)) {
        operations.push({
          type: "REMOVE_NODE",
          nodeId: node.id,
        });
      }
    }
    // 新增/修改的边
    for (const edge of nextEdges) {
      const previousEdge = previousEdgesById.get(edge.id);
      if (JSON.stringify(previousEdge ?? null) !== JSON.stringify(edge)) {
        operations.push({
          type: "UPSERT_EDGE",
          edge,
        });
      }
    }
    // 删除的边
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
      source: "FRONTEND",
    };
  }

  /**
   * 同步一份新的节点/边集合到所有相关位置。
   *
   * 流程：
   * 1) 解析锚点节点；
   * 2) 做坐标归一化（可选 forceRelayout 强制清除原坐标）；
   * 3) 埋点：记录输入输出规模与耗时；
   * 4) 写入节点/边/锚点/选中/布局状态；
   * 5) 清理失效引用（折叠节点、检查节点、QA 目标节点等）；
   * 6) 写入草稿图；
   * 7) 按当前展示模式更新对应视图文档；
   * 8) 派发编辑请求与布局变化给桥接（让后端持久化）。
   *
   * @param nextNodes 新节点列表
   * @param nextEdges 新边列表
   * @param nextSelectedNodeId 新选中节点 ID；默认沿用当前
   * @param options 可选参数（forceRelayout 等）
   */
  function syncGraph(
    nextNodes: LinkGraphNode[],
    nextEdges: LinkGraphEdge[],
    nextSelectedNodeId: string | null = args.selectedNodeId,
    options?: {
      forceRelayout?: boolean;
    },
  ) {
    const startedAt = measureStart();
    // 解析锚点：优先用当前锚点；缺失时回退到选中节点
    const nextAnchorNodeId = args.resolveAnchorNodeId(
      nextNodes,
      args.anchorNodeIdRef.current ?? nextSelectedNodeId,
    );
    // 强制重布局时清掉原坐标
    const nodesForLayout = options?.forceRelayout ? nextNodes.map(clearStoredNodePosition) : nextNodes;
    // 坐标归一化：把 metadata 中的 ui.x/ui.y 同步到 position
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
    // 清理失效的折叠节点（节点已删除时折叠记录也要清掉）
    args.setCollapsedNodeIds((current) => current.filter((nodeId) => laidOutNodes.some((node) => node.id === nodeId)));
    // 检查节点已删除时清空检查状态
    if (args.detailNodeId && !laidOutNodes.some((node) => node.id === args.detailNodeId)) {
      args.setDetailNodeId(null);
    }
    // 同步草稿图：保留草稿补丁等字段，只更新节点/边
    args.setDraftGraph((current) => ({
      ...(current ?? { nodes: [], edges: [] }),
      nodes: laidOutNodes,
      edges: nextEdges,
    }));
    // 按展示模式更新对应视图文档
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
        summary: args.deriveFlowchartSummary(nextGraph, nextGraph, current.summary),
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
        summary: current.summary,
      }));
    } else if (args.analysisDisplayMode === "CLASS_DIAGRAM") {
      const nextGraph = { nodes: laidOutNodes, edges: nextEdges };
      args.setClassDiagramView((current) => ({
        ...current,
        visibleGraph: nextGraph,
        fullGraph: nextGraph,
        anchorNodeId: nextAnchorNodeId,
        summary: current.summary,
      }));
    } else if (args.analysisDisplayMode === "REVIEW_GRAPH") {
      const nextGraph = { nodes: laidOutNodes, edges: nextEdges };
      args.setReviewGraphView((current) => ({
        ...current,
        visibleGraph: nextGraph,
        fullGraph: nextGraph,
        anchorNodeId: nextAnchorNodeId,
        summary: current.summary,
      }));
    }
    // 清理 QA 目标节点中的失效引用
    args.setQaTargetNodeIds((current) => current.filter((nodeId) => laidOutNodes.some((node) => node.id === nodeId)));
    args.clearLocalDerivedGraphState();
    // 派发编辑请求与布局变化给后端
    publishGraphEditRequest(
      buildGraphEditRequest(
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
