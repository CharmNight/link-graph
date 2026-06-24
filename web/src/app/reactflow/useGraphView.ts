// 图谱视图管线 Hook：所有视图（事实/流程/资源/架构/类图/审查）共享的布局后处理。
// 职责：注册尺寸注册表 → 跑测量布局 → 过滤隐藏节点 → 构建索引 → 查找选中节点。
// 各视图只需提供差异部分（布局函数、尺寸签名、预处理图），通用 tail 由本 Hook 统一处理。
import { useMemo } from "react";
import type { LinkGraphDocument, LinkGraphEdge, LinkGraphNode } from "../types";
import { createNodeSizeRegistry, type NodeSizeRegistry } from "../graph/nodeSizeRegistry";
import {
  useMeasuredLayout,
  type LayoutSizeSignatureResolver,
  type MeasuredLayoutRequest,
} from "./useMeasuredLayout";

/** useGraphView 的配置项。 */
export interface UseGraphViewOptions {
  /** 待布局的图文档。 */
  graph: LinkGraphDocument;
  /** 锚点节点 ID；可空。 */
  anchorNodeId?: string | null;
  /** 当前选中节点 ID。 */
  selectedNodeId?: string | null;
  /** 被隐藏的节点 ID 列表（不进入 visibleNodes/edges）。 */
  hiddenNodeIds?: string[];
  /** 已折叠节点 ID 列表（传递给布局引擎）。 */
  collapsedNodeIds?: string[];
  /** 共享尺寸注册表；省略时自动创建。 */
  nodeSizeRegistry?: NodeSizeRegistry;
  /** 视图专属布局函数。 */
  layout: (request: MeasuredLayoutRequest) => Promise<{ nodes: LinkGraphNode[]; edges: LinkGraphEdge[] }>;
  /** 视图专属尺寸签名（跳过无意义的 relayout）。 */
  layoutSizeSignature?: LayoutSizeSignatureResolver;
  layoutOnPositionChange?: boolean;
  debugLabel?: string;
}

/** useGraphView 的返回值。 */
export interface UseGraphViewResult {
  /** 布局引擎输出的原始节点/边（未做隐藏过滤）。 */
  layoutNodes: LinkGraphNode[];
  layoutEdges: LinkGraphEdge[];
  /** 隐藏过滤后的可见节点/边。 */
  visibleNodes: LinkGraphNode[];
  visibleEdges: LinkGraphEdge[];
  /** 可见节点 ID → 节点 的查找索引。 */
  nodeIndex: Map<string, LinkGraphNode>;
  /** 选中节点对象（在可见集合中存在时）。 */
  selectedNode: LinkGraphNode | null;
  /** 非空图布局进行中（用于加载空态判定）。 */
  isLayoutLoading: boolean;
  /** 共享尺寸注册表。 */
  nodeSizeRegistry: NodeSizeRegistry;
  /** 布局是否进行中。 */
  layoutPending: boolean;
  /** 手动触发重新布局。 */
  requestRelayout: () => void;
}

/**
 * 图谱视图管线的共享 tail Hook。
 *
 * 之前每个视图（Fact/Flowchart/Resource/Architecture/ClassDiagram/Review）
 * 都内联了相同的脚手架代码：注册尺寸注册表、跑 useMeasuredLayout、
 * 过滤隐藏节点、构建索引、查找选中节点、计算加载标志。
 * 本 Hook 把这些相同逻辑集中到一处，让每个视图只声明差异部分。
 *
 * 注意：本 Hook 故意不构建 flowNodes/flowEdges——那些使用视图专属 builder，
 * 留在各视图内部。视图从本 Hook 的返回值中读取 visibleNodes/visibleEdges/nodeIndex/nodeSizeRegistry。
 */
export function useGraphView({
  graph,
  anchorNodeId,
  selectedNodeId,
  hiddenNodeIds = [],
  collapsedNodeIds,
  nodeSizeRegistry,
  layout,
  layoutSizeSignature,
  layoutOnPositionChange,
  debugLabel,
}: UseGraphViewOptions): UseGraphViewResult {
  // 尺寸注册表：外部传入时复用，否则惰性创建
  const registry = useMemo(() => nodeSizeRegistry ?? createNodeSizeRegistry(), [nodeSizeRegistry]);
  // 运行测量布局
  const layoutState = useMeasuredLayout({
    graph,
    anchorNodeId,
    collapsedNodeIds,
    nodeSizeRegistry: registry,
    layout,
    layoutSizeSignature,
    layoutOnPositionChange,
    debugLabel,
  });

  // 隐藏节点集合
  const hiddenNodeIdSet = useMemo(() => new Set(hiddenNodeIds), [hiddenNodeIds]);
  // 过滤后的可见节点
  const visibleNodes = useMemo(
    () => layoutState.nodes.filter((node) => !hiddenNodeIdSet.has(node.id)),
    [layoutState.nodes, hiddenNodeIdSet],
  );
  // 过滤后的可见边（端点任一被隐藏则隐藏）
  const visibleEdges = useMemo(
    () => layoutState.edges.filter(
      (edge) => !hiddenNodeIdSet.has(edge.source) && !hiddenNodeIdSet.has(edge.target),
    ),
    [layoutState.edges, hiddenNodeIdSet],
  );
  // 加载中判定：图非空 + 布局进行中 + 布局结果为空
  const isLayoutLoading = layoutState.layoutPending && graph.nodes.length > 0 && layoutState.nodes.length === 0;
  // 构建 ID → 节点 索引
  const nodeIndex = useMemo(
    () => new Map(visibleNodes.map((node) => [node.id, node])),
    [visibleNodes],
  );
  // 查找选中节点
  const selectedNode = useMemo(
    () => (selectedNodeId ? (visibleNodes.find((node) => node.id === selectedNodeId) ?? null) : null),
    [visibleNodes, selectedNodeId],
  );

  return {
    layoutNodes: layoutState.nodes,
    layoutEdges: layoutState.edges,
    visibleNodes,
    visibleEdges,
    nodeIndex,
    selectedNode,
    isLayoutLoading,
    nodeSizeRegistry: registry,
    layoutPending: layoutState.layoutPending,
    requestRelayout: layoutState.requestRelayout,
  };
}
