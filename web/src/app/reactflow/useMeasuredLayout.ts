// 测量后布局 Hook：根据图与节点尺寸变化驱动布局流程。
// 主要职责：
// - 监听图（节点/边）、锚点、折叠节点、节点尺寸等变化；
// - 判断变化类型（graph / measurement / position / manual）；
// - 决定是否需要重新布局；如果不需要则跳过（性能优化）；
// - 调用调用方提供的 layout 函数执行布局；
// - 处理布局过程中的失败回退、增量位置保留、边路由复用等。
import { useEffect, useMemo, useRef, useState } from "react";
import { measureDuration, measureStart, summarizeGraph, traceLinkGraph } from "../debug";
import {
  defaultNodeSizeRegistry,
  type NodeMeasuredSize,
  type NodeSizeRegistry,
} from "../graph/nodeSizeRegistry";
import type { GraphPosition, LinkGraphDocument, LinkGraphEdge, LinkGraphNode } from "../types";

/** 布局触发原因：图变化 / 测量变化 / 手动触发 / 位置变化。 */
export type LayoutTriggerReason = "graph" | "measurement" | "manual" | "position";

/** 单次布局请求的输入。 */
export interface MeasuredLayoutRequest {
  /** 完整图文档。 */
  graph: LinkGraphDocument;
  /** 节点列表（与 graph.nodes 一致）。 */
  nodes: LinkGraphNode[];
  /** 边列表（与 graph.edges 一致）。 */
  edges: LinkGraphEdge[];
  /** 锚点节点 ID。 */
  anchorNodeId?: string | null;
  /** 节点尺寸快照。 */
  sizeSnapshot: ReadonlyMap<string, NodeMeasuredSize>;
  /** 触发原因。 */
  reason: LayoutTriggerReason;
}

/** 节点尺寸签名的计算函数类型。 */
export type LayoutSizeSignatureResolver = (
  nodes: LinkGraphNode[],
  sizeSnapshot: ReadonlyMap<string, NodeMeasuredSize>,
) => string;

/** useMeasuredLayout 的配置项。 */
export interface UseMeasuredLayoutOptions {
  /** 待布局的图文档。 */
  graph: LinkGraphDocument;
  /** 锚点节点 ID；可空。 */
  anchorNodeId?: string | null;
  /** 已折叠节点 ID 列表；可空。 */
  collapsedNodeIds?: string[];
  /** 节点尺寸注册表；默认使用全局单例。 */
  nodeSizeRegistry?: NodeSizeRegistry;
  /** 实际执行布局的函数；由调用方提供。 */
  layout: (request: MeasuredLayoutRequest) => Promise<{ nodes: LinkGraphNode[]; edges: LinkGraphEdge[] }>;
  /** 自定义尺寸签名计算函数；默认使用内置实现。 */
  layoutSizeSignature?: LayoutSizeSignatureResolver;
  /** 是否在位置变化时触发布局；默认 false。 */
  layoutOnPositionChange?: boolean;
  /** 调试标签（用于埋点区分多个使用方）。 */
  debugLabel?: string;
  /**
   * 重置键。变化时丢弃所有 layoutState（含 seed），强制从零重新布局。
   * 用于语义切换场景（如类图"查看使用处"切换 target），避免旧节点/边残留。
   */
  resetKey?: string | null;
}

/** useMeasuredLayout 的返回值。 */
export interface UseMeasuredLayoutResult {
  /** 布局后的节点列表。 */
  nodes: LinkGraphNode[];
  /** 布局后的边列表。 */
  edges: LinkGraphEdge[];
  /** 是否正在布局中。 */
  layoutPending: boolean;
  /** 当前节点尺寸快照，供视口覆盖层等非布局消费者复用实测尺寸。 */
  sizeSnapshot?: ReadonlyMap<string, NodeMeasuredSize>;
  /** 手动触发重新布局。 */
  requestRelayout: () => void;
}

/** 布局状态：节点、边、是否布局中。 */
interface LayoutState {
  /** 布局后的节点。 */
  nodes: LinkGraphNode[];
  /** 布局后的边。 */
  edges: LinkGraphEdge[];
  /** 是否布局中。 */
  layoutPending: boolean;
}

/** 一次布局触发的快照（用于判断下次触发是否真的变化）。 */
interface LayoutTriggerSnapshot {
  /** 图签名（节点/边的语义签名）。 */
  graphSignature: string;
  /** 折叠节点签名（排序后的 ID 拼接）。 */
  collapsedSignature: string;
  /** 位置签名（节点 ID + 坐标）。 */
  positionSignature: string;
  /** 手动触发计数（每次手动 relayout +1）。 */
  manualNonce: number;
}

/**
 * 规范化元数据：剔除 ui. 与 layout. 前缀字段（这些是渲染/布局专用，不属于语义）。
 * 用于在计算签名时排除纯渲染状态。
 */
function normalizeLayoutMetadata(metadata?: Record<string, string>): Record<string, string> | undefined {
  if (!metadata) {
    return undefined;
  }
  const nextMetadata = Object.fromEntries(
    Object.entries(metadata).filter(([key]) => !key.startsWith("ui.") && !key.startsWith("layout.")),
  );
  return Object.keys(nextMetadata).length > 0 ? nextMetadata : undefined;
}

/** 计算节点语义签名（id/type/title/...metadata）。 */
function nodeSignature(node: LinkGraphNode): string {
  return [
    node.id,
    node.type,
    node.title,
    node.location ?? "",
    node.signature ?? "",
    node.inputs.join(","),
    node.outputs.join(","),
    node.doc ?? "",
    node.confidence,
    node.binding,
    node.diffStatus ?? "",
    node.provenance ?? "",
    JSON.stringify(normalizeLayoutMetadata(node.metadata) ?? {}),
  ].join("|");
}

/** 计算边语义签名。 */
function edgeSignature(edge: LinkGraphEdge): string {
  return [
    edge.id,
    edge.type,
    edge.source,
    edge.target,
    edge.sourceHandle ?? "",
    edge.targetHandle ?? "",
    edge.label ?? "",
    JSON.stringify(edge.metadata ?? {}),
    edge.provenance ?? "",
  ].join("|");
}

/**
 * 判断两组节点在"语义 + 位置"层面是否等价。
 * 用于检测"图虽然变了，但只是无关紧要的微调"的场景。
 */
function areNodeSetsEquivalent(left: LinkGraphNode[], right: LinkGraphNode[]): boolean {
  if (left.length !== right.length) {
    return false;
  }
  const rightIndex = new Map(right.map((node) => [node.id, node]));
  return left.every((node) => {
    const candidate = rightIndex.get(node.id);
    if (!candidate) {
      return false;
    }
    const position = resolvePosition(node);
    const candidatePosition = resolvePosition(candidate);
    // 语义签名一致 + 位置一致（或都为空）才算等价
    return nodeSignature(node) === nodeSignature(candidate)
      && (
        (!position && !candidatePosition)
        || (position && candidatePosition && position.x === candidatePosition.x && position.y === candidatePosition.y)
      );
  });
}

/**
 * 判断"保留节点等价"：nextNodes 中的所有保留节点（与 currentNodes 同 ID）应与 currentNodes 完全等价。
 * 用于增量节点添加场景的快速检测。
 */
function areRetainedNodesEquivalent(nextNodes: LinkGraphNode[], currentNodes: LinkGraphNode[]): boolean {
  const currentNodeIndex = new Map(currentNodes.map((node) => [node.id, node]));
  // 取 nextNodes 中存在同 ID 的节点（即"保留节点"）
  const retainedNodes = nextNodes.filter((node) => currentNodeIndex.has(node.id));
  return retainedNodes.length === currentNodes.length && areNodeSetsEquivalent(retainedNodes, currentNodes);
}

/** 判断两组边是否在语义层面等价。 */
function areEdgeSetsEquivalent(left: LinkGraphEdge[], right: LinkGraphEdge[]): boolean {
  if (left.length !== right.length) {
    return false;
  }
  const rightIndex = new Map(right.map((edge) => [edge.id, edge]));
  return left.every((edge) => {
    const candidate = rightIndex.get(edge.id);
    return candidate != null && edgeSignature(edge) === edgeSignature(candidate);
  });
}

/** 计算整张图的语义签名（节点签名 + 边签名，分别排序后拼接）。 */
function graphSignature(graph: LinkGraphDocument): string {
  return [
    graph.nodes.map(nodeSignature).sort().join("::"),
    graph.edges.map(edgeSignature).sort().join("::"),
  ].join("##");
}

/** 计算节点尺寸签名（id → 宽x高）。 */
function sizeSignature(
  nodes: LinkGraphNode[],
  sizeSnapshot: ReadonlyMap<string, NodeMeasuredSize>,
): string {
  return nodes
    .map((node) => {
      const size = sizeSnapshot.get(node.id);
      return `${node.id}:${size?.width ?? ""}x${size?.height ?? ""}`;
    })
    .join("::");
}

/** 计算节点位置签名（id → x:y）。 */
function positionSignature(nodes: LinkGraphNode[]): string {
  return nodes
    .map((node) => {
      const position = resolvePosition(node);
      return `${node.id}:${position?.x ?? ""}:${position?.y ?? ""}`;
    })
    .join("::");
}

/** 解析节点坐标；优先用 position，缺失时回退到 metadata 中的 ui.x/ui.y。 */
function resolvePosition(node?: LinkGraphNode | null): GraphPosition | null {
  if (!node) {
    return null;
  }
  if (node.position) {
    return node.position;
  }
  const x = Number(node.metadata?.["ui.x"]);
  const y = Number(node.metadata?.["ui.y"]);
  return Number.isFinite(x) && Number.isFinite(y) ? { x, y } : null;
}

/** 兜底坐标：按索引排成 4 列网格。 */
function fallbackLayoutPosition(index: number): GraphPosition {
  return {
    x: 120 + (index % 4) * 360,
    y: 96 + Math.floor(index / 4) * 220,
  };
}

/** 判断所有节点是否都已有合法坐标。 */
function hasResolvedLayoutPositions(nodes: LinkGraphNode[]): boolean {
  return nodes.length > 0 && nodes.every((node) => resolvePosition(node) !== null);
}

/** 把坐标写入节点的 position 与 metadata（双写）。 */
function syncNodePosition(node: LinkGraphNode, position: GraphPosition): LinkGraphNode {
  return {
    ...node,
    position,
    metadata: {
      ...(node.metadata ?? {}),
      "ui.x": String(position.x),
      "ui.y": String(position.y),
    },
  };
}

/** 从节点的 metadata 中提取 layout. 前缀字段（保留布局参数）。 */
function layoutMetadataFrom(node?: LinkGraphNode | null): Record<string, string> | undefined {
  if (!node?.metadata) {
    return undefined;
  }
  const nextMetadata = Object.fromEntries(
    Object.entries(node.metadata).filter(([key]) => key.startsWith("layout.")),
  );
  return Object.keys(nextMetadata).length > 0 ? nextMetadata : undefined;
}

/** 合并前一次节点的 layout. 元数据到新节点（保留布局参数）。 */
function mergeSeedNodeMetadata(
  node: LinkGraphNode,
  previousNode?: LinkGraphNode | null,
): Record<string, string> | undefined {
  const preservedLayoutMetadata = layoutMetadataFrom(previousNode);
  if (!preservedLayoutMetadata) {
    return node.metadata;
  }
  return {
    ...preservedLayoutMetadata,
    ...(node.metadata ?? {}),
  };
}

/** 把节点 + 上一次节点 + 坐标 合并为带 seed 信息的节点。 */
function seedNodeFromPrevious(
  node: LinkGraphNode,
  previousNode: LinkGraphNode | undefined,
  position: GraphPosition,
): LinkGraphNode {
  return {
    ...node,
    position,
    metadata: {
      ...(mergeSeedNodeMetadata(node, previousNode) ?? {}),
      "ui.x": String(position.x),
      "ui.y": String(position.y),
    },
  };
}

/**
 * 给下一轮布局提供 seed：基于上一次布局结果保留位置与 layout. 元数据。
 *
 * 策略（按节点）：
 * - 有显式坐标 → 用显式坐标；
 * - 上一次有坐标 → 用上一次坐标；
 * - 都没有 → 至少保留 layout. 元数据（如果有）。
 *
 * 这样在节点 minor 变化时（例如标题改了）能复用旧布局位置，避免抖动。
 */
function seedLayoutNodes(nextNodes: LinkGraphNode[], previousNodes: LinkGraphNode[]): LinkGraphNode[] {
  const previousNodeIndex = new Map(previousNodes.map((node) => [node.id, node]));
  return nextNodes.map((node) => {
    const previousNode = previousNodeIndex.get(node.id);
    // 节点本身已有坐标：用之
    const explicitPosition = resolvePosition(node);
    if (explicitPosition) {
      return seedNodeFromPrevious(node, previousNode, explicitPosition);
    }
    // 节点没坐标但上次有：用上次的
    const previousPosition = resolvePosition(previousNode);
    if (previousPosition) {
      return seedNodeFromPrevious(node, previousNode, previousPosition);
    }
    // 都没有：只合并 layout. 元数据（如果有的话）
    const mergedMetadata = mergeSeedNodeMetadata(node, previousNode);
    if (mergedMetadata === node.metadata) {
      return node;
    }
    return {
      ...node,
      metadata: mergedMetadata,
    };
  });
}

/** 给所有节点补上坐标；缺失时用兜底坐标。 */
function ensureResolvedLayoutPositions(nodes: LinkGraphNode[]): LinkGraphNode[] {
  return nodes.map((node, index) => syncNodePosition(node, resolvePosition(node) ?? fallbackLayoutPosition(index)));
}

/** 判断两条边是否可以共享路由（id/type/端点/handle/label 一致）。 */
function canReuseSeededRoute(nextEdge: LinkGraphEdge, previousEdge: LinkGraphEdge): boolean {
  return nextEdge.id === previousEdge.id
    && nextEdge.type === previousEdge.type
    && nextEdge.source === previousEdge.source
    && nextEdge.target === previousEdge.target
    && (nextEdge.sourceHandle ?? "") === (previousEdge.sourceHandle ?? "")
    && (nextEdge.targetHandle ?? "") === (previousEdge.targetHandle ?? "")
    && (nextEdge.label ?? "") === (previousEdge.label ?? "");
}

/** 判断两个坐标是否相等（非空 + 完全一致）。 */
function positionsEqual(left: GraphPosition | null, right: GraphPosition | null): boolean {
  return left !== null
    && right !== null
    && left.x === right.x
    && left.y === right.y;
}

/** 判断边的两个端点位置在新旧两次中是否完全一致（决定能否复用路由）。 */
function canReuseEndpointRoute(
  edge: LinkGraphEdge,
  nextNodeIndex?: ReadonlyMap<string, LinkGraphNode>,
  previousNodeIndex?: ReadonlyMap<string, LinkGraphNode>,
): boolean {
  if (!nextNodeIndex || !previousNodeIndex) {
    return true;
  }
  const nextSourcePosition = resolvePosition(nextNodeIndex.get(edge.source));
  const previousSourcePosition = resolvePosition(previousNodeIndex.get(edge.source));
  const nextTargetPosition = resolvePosition(nextNodeIndex.get(edge.target));
  const previousTargetPosition = resolvePosition(previousNodeIndex.get(edge.target));
  return positionsEqual(nextSourcePosition, previousSourcePosition)
    && positionsEqual(nextTargetPosition, previousTargetPosition);
}

/**
 * 给下一轮布局提供 seed 边：复用上一次的路由。
 *
 * 复用条件：
 * - 旧边有路由 + 新边没有路由；
 * - 新旧边可以共享路由（语义一致）；
 * - 端点位置完全一致（端点变了的话路由也要重算）。
 */
function seedLayoutEdges(
  nextEdges: LinkGraphEdge[],
  previousEdges: LinkGraphEdge[],
  nextNodes?: LinkGraphNode[],
  previousNodes?: LinkGraphNode[],
): LinkGraphEdge[] {
  const previousEdgeIndex = new Map(previousEdges.map((edge) => [edge.id, edge]));
  const nextNodeIndex = nextNodes ? new Map(nextNodes.map((node) => [node.id, node])) : undefined;
  const previousNodeIndex = previousNodes ? new Map(previousNodes.map((node) => [node.id, node])) : undefined;
  return nextEdges.map((edge) => {
    const previousEdge = previousEdgeIndex.get(edge.id);
    // 任一复用条件不满足：保持新边原样
    if (
      !previousEdge?.route
      || edge.route
      || !canReuseSeededRoute(edge, previousEdge)
      || !canReuseEndpointRoute(edge, nextNodeIndex, previousNodeIndex)
    ) {
      return edge;
    }
    // 复用旧路由
    return {
      ...edge,
      route: previousEdge.route,
    };
  });
}

/**
 * 判断是否为"增量添加已有坐标节点"的场景。
 *
 * 满足条件：
 * - 新节点数大于旧节点数（有节点加入）；
 * - 保留节点（同 ID）与旧节点完全等价；
 * - 新加入节点都有坐标；
 * - 新加入节点都不是调用展开产物（避免误判）。
 *
 * 这种场景下可以跳过重新布局，避免无意义的重算。
 */
function isIncrementalPositionedNodeAddition(nextNodes: LinkGraphNode[], currentNodes: LinkGraphNode[]): boolean {
  if (nextNodes.length <= currentNodes.length) {
    return false;
  }
  if (!areRetainedNodesEquivalent(nextNodes, currentNodes)) {
    return false;
  }
  const currentNodeIds = new Set(currentNodes.map((node) => node.id));
  const addedNodes = nextNodes.filter((node) => !currentNodeIds.has(node.id));
  return addedNodes.length > 0
    && addedNodes.every((node) => Boolean(resolvePosition(node)))
    && addedNodes.every((node) => !node.metadata?.["linkGraph.expansion.id"]);
}

/** 判断边列表中是否含调用展开边（这类边触发条件更严格）。 */
function hasInvocationExpansionEdges(edges: LinkGraphEdge[]): boolean {
  return edges.some((edge) => Boolean(edge.metadata?.["linkGraph.expansion.id"]));
}

/**
 * 测量后布局 Hook：根据图与节点尺寸变化驱动布局流程。
 *
 * 工作流程：
 * 1) 监听图、锚点、折叠节点、节点尺寸、手动 nonce、resetKey 等变化；
 * 2) 计算多种签名（图/折叠/位置/尺寸）判断变化类型；
 * 3) 应用多种"跳过布局"优化（边只增删、增量节点添加、空图等）；
 * 4) 必要时调用调用方提供的 layout 函数；
 * 5) 处理失败回退、增量位置保留、边路由复用等。
 *
 * @return 当前布局结果（节点/边/是否布局中）+ 手动触发函数
 */
export function useMeasuredLayout({
  graph,
  anchorNodeId = null,
  collapsedNodeIds = [],
  nodeSizeRegistry = defaultNodeSizeRegistry,
  layout,
  layoutSizeSignature = sizeSignature,
  layoutOnPositionChange = false,
  debugLabel = "graph",
  resetKey = null,
}: UseMeasuredLayoutOptions): UseMeasuredLayoutResult {
  // 手动触发 nonce：每次 requestRelayout +1
  const [manualNonce, setManualNonce] = useState(0);
  // 注册表修订号：节点尺寸变化时 +1
  const [registryRevision, setRegistryRevision] = useState(() => nodeSizeRegistry.currentRevision());
  // resetKey 变化时完全清空 layoutState，丢弃所有 seed（旧节点位置 / 旧边 route）。
  const previousResetKeyRef = useRef<string | null>(resetKey);
  // 节点尺寸快照（依赖注册表修订号重新计算）
  const measuredSizes = useMemo(() => {
    // 显式读取修订号，表达快照需要随尺寸注册表更新而刷新。
    void registryRevision;
    return nodeSizeRegistry.snapshot();
  }, [nodeSizeRegistry, registryRevision]);
  // 各种签名（依赖图等输入）
  const nextGraphSignature = useMemo(() => graphSignature(graph), [graph]);
  const nextCollapsedSignature = useMemo(
    () => [...new Set(collapsedNodeIds)].sort().join("|"),
    [collapsedNodeIds],
  );
  const nextSizeSignature = useMemo(
    () => layoutSizeSignature(graph.nodes, measuredSizes),
    [graph.nodes, layoutSizeSignature, measuredSizes],
  );
  const nextPositionSignature = useMemo(() => positionSignature(graph.nodes), [graph.nodes]);
  // 仅在 layoutOnPositionChange 时跟踪位置签名
  const trackedPositionSignature = layoutOnPositionChange ? nextPositionSignature : "";

  // 初始 layoutState：基于初始图尝试 seed（若有坐标则可立即渲染）
  const [layoutState, setLayoutState] = useState<LayoutState>(() => ({
    nodes: hasResolvedLayoutPositions(seedLayoutNodes(graph.nodes, []))
      ? seedLayoutNodes(graph.nodes, [])
      : [],
    edges: hasResolvedLayoutPositions(seedLayoutNodes(graph.nodes, []))
      ? seedLayoutEdges(graph.edges, [])
      : [],
    layoutPending: graph.nodes.length > 0 && !hasResolvedLayoutPositions(seedLayoutNodes(graph.nodes, [])),
  }));

  // 同步 ref，避免布局 effect 读到清空前的旧 layoutState
  const latestLayoutStateRef = useRef<LayoutState>(layoutState);
  // resetKey 变化（语义切换，如类图 usage target 切换）：完全清空 layoutState
  useEffect(() => {
    if (previousResetKeyRef.current === resetKey) {
      return;
    }
    previousResetKeyRef.current = resetKey;
    const cleared: LayoutState = {
      nodes: [],
      edges: [],
      layoutPending: graph.nodes.length > 0,
    };
    latestLayoutStateRef.current = cleared;
    setLayoutState(cleared);
  }, [resetKey, graph.nodes.length]);

  // 触发快照 ref：用于判断下次触发原因
  const triggerRef = useRef<LayoutTriggerSnapshot | null>(null);
  // 请求版本号：丢弃过期布局结果
  const requestVersionRef = useRef(0);
  // 最新图/尺寸 ref：避免 effect 闭包捕获旧值
  const latestGraphRef = useRef(graph);
  const latestMeasuredSizesRef = useRef(measuredSizes);

  latestLayoutStateRef.current = layoutState;
  latestGraphRef.current = graph;
  latestMeasuredSizesRef.current = measuredSizes;

  // 订阅节点尺寸注册表变化
  useEffect(() => {
    return nodeSizeRegistry.subscribe(() => {
      setRegistryRevision(nodeSizeRegistry.currentRevision());
    });
  }, [nodeSizeRegistry]);

  // 同步 seed 到 layoutState（不触发布局，只是更新内部状态）
  useEffect(() => {
    setLayoutState((current) => ({
      nodes: hasResolvedLayoutPositions(seedLayoutNodes(graph.nodes, current.nodes))
        ? seedLayoutNodes(graph.nodes, current.nodes)
        : [],
      edges: hasResolvedLayoutPositions(seedLayoutNodes(graph.nodes, current.nodes))
        ? seedLayoutEdges(
            graph.edges,
            current.edges,
            seedLayoutNodes(graph.nodes, current.nodes),
            current.nodes,
          )
        : [],
      layoutPending: graph.nodes.length > 0
        ? current.layoutPending || !hasResolvedLayoutPositions(seedLayoutNodes(graph.nodes, current.nodes))
        : false,
    }));
  }, [graph.edges, graph.nodes, nextPositionSignature]);

  // 主布局 effect：触发实际布局
  useEffect(() => {
    const startedAt = measureStart();
    const nextGraph = latestGraphRef.current;
    const nextMeasuredSizes = latestMeasuredSizesRef.current;
    const currentLayoutState = latestLayoutStateRef.current;
    // seed 节点/边：基于上次布局结果保留位置与路由
    const seededNodes = seedLayoutNodes(nextGraph.nodes, currentLayoutState.nodes);
    const seededEdges = seedLayoutEdges(nextGraph.edges, currentLayoutState.edges, seededNodes, currentLayoutState.nodes);
    const previousTrigger = triggerRef.current;
    // 判断触发原因
    const reason: LayoutTriggerReason = !previousTrigger
      ? "graph"
      : manualNonce !== previousTrigger.manualNonce
        ? "manual"
        : nextGraphSignature !== previousTrigger.graphSignature || nextCollapsedSignature !== previousTrigger.collapsedSignature
          ? "graph"
          : trackedPositionSignature !== previousTrigger.positionSignature
            ? "position"
            : "measurement";
    // 记录本次触发快照
    triggerRef.current = {
      graphSignature: nextGraphSignature,
      collapsedSignature: nextCollapsedSignature,
      positionSignature: trackedPositionSignature,
      manualNonce,
    };

    // 优化 1：仅边变化（节点未变）且无调用展开边 → 跳过布局
    const edgeOnlyGraphChange = reason === "graph"
      && currentLayoutState.nodes.length > 0
      && areNodeSetsEquivalent(seededNodes, currentLayoutState.nodes)
      && !areEdgeSetsEquivalent(seededEdges, currentLayoutState.edges);

    if (edgeOnlyGraphChange && !hasInvocationExpansionEdges(nextGraph.edges)) {
      setLayoutState({
        nodes: seededNodes,
        edges: seededEdges,
        layoutPending: false,
      });
      traceLinkGraph("useMeasuredLayout.skipEdgeOnlyLayout", {
        debugLabel,
        anchorNodeId,
        graph: summarizeGraph(nextGraph),
        durationMs: measureDuration(startedAt),
      });
      return;
    }

    // 优化 2：增量添加已有坐标节点 → 跳过布局
    const positionedNodeAdditionChange = reason === "graph"
      && previousTrigger !== null
      && nextCollapsedSignature === previousTrigger.collapsedSignature
      && isIncrementalPositionedNodeAddition(seededNodes, currentLayoutState.nodes);

    if (positionedNodeAdditionChange) {
      setLayoutState({
        nodes: seededNodes,
        edges: seededEdges,
        layoutPending: false,
      });
      traceLinkGraph("useMeasuredLayout.skipPositionedNodeAdditionLayout", {
        debugLabel,
        anchorNodeId,
        graph: summarizeGraph(nextGraph),
        durationMs: measureDuration(startedAt),
      });
      return;
    }

    // 优化 3：空图 → 清空节点（保留边以便回放）
    if (nextGraph.nodes.length === 0) {
      const clearedEdges = seedLayoutEdges(nextGraph.edges, currentLayoutState.edges, [], currentLayoutState.nodes);
      setLayoutState((current) => {
        // 已经是空状态：原样返回避免无意义更新
        if (current.nodes.length === 0 && areEdgeSetsEquivalent(current.edges, clearedEdges) && !current.layoutPending) {
          return current;
        }
        return {
          nodes: [],
          edges: clearedEdges,
          layoutPending: false,
        };
      });
      return;
    }

    // 进入实际布局流程：先标记 layoutPending + 用 seed 占位（若有坐标）
    const renderableSeededLayout = hasResolvedLayoutPositions(seededNodes);
    setLayoutState({
      nodes: renderableSeededLayout ? seededNodes : [],
      edges: renderableSeededLayout ? seededEdges : [],
      layoutPending: true,
    });

    // 递增请求版本号，过期结果会被丢弃
    const requestVersion = requestVersionRef.current + 1;
    requestVersionRef.current = requestVersion;

    traceLinkGraph("useMeasuredLayout.start", {
      debugLabel,
      reason,
      anchorNodeId,
      graph: summarizeGraph(nextGraph),
    });

    // 调用调用方提供的 layout 函数
    void layout({
      graph: nextGraph,
      nodes: nextGraph.nodes,
      edges: nextGraph.edges,
      anchorNodeId,
      sizeSnapshot: nextMeasuredSizes,
      reason,
    })
      .then((laidOutGraph) => {
        // 过期请求：丢弃结果
        if (requestVersionRef.current !== requestVersion) {
          return;
        }
        setLayoutState({
          nodes: laidOutGraph.nodes,
          edges: laidOutGraph.edges,
          layoutPending: false,
        });
        traceLinkGraph("useMeasuredLayout.complete", {
          debugLabel,
          reason,
          graph: summarizeGraph({ nodes: laidOutGraph.nodes, edges: laidOutGraph.edges }),
          durationMs: measureDuration(startedAt),
        });
      })
      .catch((error: unknown) => {
        // 失败：用兜底坐标保证可渲染
        if (requestVersionRef.current !== requestVersion) {
          return;
        }
        const fallbackNodes = ensureResolvedLayoutPositions(seededNodes);
        setLayoutState((current) => ({
          ...current,
          nodes: fallbackNodes,
          edges: seedLayoutEdges(nextGraph.edges, current.edges, fallbackNodes, current.nodes),
          layoutPending: false,
        }));
        traceLinkGraph("useMeasuredLayout.failed", {
          debugLabel,
          reason,
          errorMessage: error instanceof Error ? error.message : String(error),
          durationMs: measureDuration(startedAt),
        });
      });
  }, [
    anchorNodeId,
    debugLabel,
    layout,
    layoutOnPositionChange,
    manualNonce,
    nextCollapsedSignature,
    nextGraphSignature,
    trackedPositionSignature,
    nextSizeSignature,
    resetKey,
  ]);

  return {
    nodes: layoutState.nodes,
    edges: layoutState.edges,
    layoutPending: layoutState.layoutPending,
    sizeSnapshot: measuredSizes,
    // 手动触发：递增 nonce 让 effect 重新跑
    requestRelayout: () => {
      setManualNonce((current) => current + 1);
    },
  };
}
