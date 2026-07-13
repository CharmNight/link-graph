// 图状态相关的纯函数工具集合。
// 主要职责：
// - 节点坐标在 metadata 与 position 字段之间的双向同步；
// - 引导态加载时合并新旧节点的坐标与边路由；
// - 计算图语义/布局签名用于变更检测；
// - 提取下游子树、布局载荷等结构化数据。
// 所有函数都是纯函数，便于测试与并发安全。
import {
  measureDuration,
  measureStart,
  summarizeGraph,
  traceLinkGraph,
} from "./debug";
import { FifoQueue } from "./fifoQueue";
import { canEditNodeLayout } from "./layoutEditability";
import type {
  AnalysisDisplayMode,
  GraphPosition,
  LinkGraphDocument,
  LinkGraphEdge,
  LinkGraphLayoutState,
  LinkGraphNode,
} from "./types";

/**
 * 当节点缺少显式坐标时的兜底位置生成器。
 * 把节点按索引排成 3 列网格，留出足够的间距避免重叠。
 * @param index 节点在无坐标序列中的索引
 */
export function fallbackDesignPosition(index: number): GraphPosition {
  return {
    x: 120 + (index % 3) * 420,
    y: 120 + Math.floor(index / 3) * 220,
  };
}

/**
 * 默认使用 FACT_GRAPH 模式为节点补充已存储坐标。
 * 等价于调用 [withStoredNodePositionForMode] 并省略模式参数。
 */
export function withStoredNodePosition(node: LinkGraphNode): LinkGraphNode {
  return withStoredNodePositionForMode(node);
}

/**
 * 根据展示模式决定是否把 metadata 中的 ui.x/ui.y 同步到节点的 position 字段。
 *
 * 规则：
 * - 当前模式不允许编辑该节点布局 → 清除存储坐标（避免展示与权限错配）；
 * - 节点已有 position → 直接返回；
 * - metadata 提供合法坐标 → 写入 position；
 * - 否则把 position 显式置为 undefined，表示"确实没有坐标"。
 */
export function withStoredNodePositionForMode(
  node: LinkGraphNode,
  analysisDisplayMode: AnalysisDisplayMode = "FACT_GRAPH",
): LinkGraphNode {
  // 当前模式不允许该节点编辑布局：清掉存储坐标避免渲染层错误使用
  if (!canEditNodeLayout(node, analysisDisplayMode)) {
    return clearStoredNodePosition(node);
  }
  // 已经有 position 字段，无需补
  if (node.position) {
    return node;
  }
  // 尝试从 metadata 中恢复坐标
  const x = Number(node.metadata?.["ui.x"]);
  const y = Number(node.metadata?.["ui.y"]);
  if (Number.isFinite(x) && Number.isFinite(y)) {
    return {
      ...node,
      position: { x, y },
    };
  }
  // metadata 中也没有坐标：显式置为 undefined 表示确实没有
  return {
    ...node,
    position: undefined,
  };
}

/**
 * 把给定坐标同时写到节点的 position 字段和 metadata（ui.x/ui.y）。
 * 双写保证序列化（只持久化 metadata）与渲染（读 position）两侧都能拿到一致数据。
 */
export function syncNodePosition(node: LinkGraphNode, position: GraphPosition): LinkGraphNode {
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

/**
 * 解析节点当前坐标。优先读 position 字段，缺失时回退到 metadata。
 * 都没有时返回 null，调用方需要自行决定兜底策略（例如用 fallbackDesignPosition）。
 */
export function resolveNodePosition(node: LinkGraphNode | undefined): GraphPosition | null {
  if (node?.position) {
    return node.position;
  }
  const x = Number(node?.metadata?.["ui.x"]);
  const y = Number(node?.metadata?.["ui.y"]);
  if (Number.isFinite(x) && Number.isFinite(y)) {
    return { x, y };
  }
  return null;
}

/**
 * 清除节点上存储的坐标信息（position 与 metadata 中的 ui.x/ui.y）。
 * 用于节点变为不可编辑、或被显式重置位置时。
 * 返回的新节点 metadata 为空时会被置为 undefined，避免持有空对象。
 */
export function clearStoredNodePosition(node: LinkGraphNode): LinkGraphNode {
  const nextMetadata = { ...(node.metadata ?? {}) };
  delete nextMetadata["ui.x"];
  delete nextMetadata["ui.y"];
  return {
    ...node,
    position: undefined,
    metadata: Object.keys(nextMetadata).length > 0 ? nextMetadata : undefined,
  };
}

/**
 * 对一份待渲染的图节点做坐标归一化（按展示模式补齐/清除 position），
 * 同时埋点记录输入输出规模与耗时，便于排查渲染慢的问题。
 */
export function normalizeGraphNodes(
  nextNodes: LinkGraphNode[],
  nextEdges: LinkGraphEdge[],
  anchorNodeId?: string | null,
  analysisDisplayMode: AnalysisDisplayMode = "FACT_GRAPH",
): LinkGraphNode[] {
  const startedAt = measureStart();
  const positionedNodes = nextNodes.map((node) => withStoredNodePositionForMode(node, analysisDisplayMode));
  traceLinkGraph("app.normalizeGraphNodes.passThrough", {
    analysisDisplayMode,
    anchorNodeId: anchorNodeId ?? null,
    inputGraph: summarizeGraph({ nodes: nextNodes, edges: nextEdges }),
    outputGraph: summarizeGraph({ nodes: positionedNodes, edges: nextEdges }),
    durationMs: measureDuration(startedAt),
  });
  return positionedNodes;
}

/**
 * 引导态合并：把待加载节点与当前节点的坐标按优先级组合。
 *
 * 单节点优先级：
 * 1) 节点允许编辑布局且布局状态中有坐标 → 用布局状态坐标；
 * 2) 节点允许编辑布局且自身有坐标 → 用自身坐标；
 * 3) 允许复用当前坐标且当前图中有同 ID 节点带坐标 → 用当前坐标；
 * 4) 其余情况 → 保持节点原样。
 * 不可编辑节点会先清除存储坐标，避免渲染层误用。
 */
export function applyBootstrapNodePositions(
  nextNodes: LinkGraphNode[],
  currentNodes: LinkGraphNode[],
  layoutState?: LinkGraphLayoutState | null,
  reuseCurrentPositions = true,
  analysisDisplayMode: AnalysisDisplayMode = "FACT_GRAPH",
): LinkGraphNode[] {
  // 索引当前节点以便按 id 查询
  const currentNodeById = new Map(currentNodes.map((node) => [node.id, node]));
  return nextNodes.map((node) => {
    const layoutEditableNode = canEditNodeLayout(node, analysisDisplayMode);
    // 不可编辑节点：清除坐标字段避免渲染层误用
    const baseNode = layoutEditableNode ? node : clearStoredNodePosition(node);
    // 优先使用 layoutState 中的坐标（这是后端权威布局）
    const layoutPosition = layoutState?.positions[node.id];
    if (layoutEditableNode && layoutPosition) {
      return syncNodePosition(baseNode, layoutPosition);
    }
    // 其次保持节点自带坐标
    const existingPosition = resolveNodePosition(baseNode);
    if (layoutEditableNode && existingPosition) {
      return syncNodePosition(baseNode, existingPosition);
    }
    // 不允许复用当前坐标：原样返回
    if (!reuseCurrentPositions) {
      return baseNode;
    }
    // 最后兜底：复用当前图中同 id 节点的坐标，避免节点跳来跳去
    const currentPosition = resolveNodePosition(currentNodeById.get(node.id) ?? baseNode);
    return currentPosition ? syncNodePosition(baseNode, currentPosition) : baseNode;
  });
}

/**
 * 判断两条边是否可以共享同一路由。
 * 必须满足 id/类型/端点/handle/标签全部相同，否则视为不同边，
 * 避免复用过期路由导致连线错位。
 */
function canReuseStoredEdgeRoute(nextEdge: LinkGraphEdge, currentEdge: LinkGraphEdge): boolean {
  return nextEdge.id === currentEdge.id
    && nextEdge.type === currentEdge.type
    && nextEdge.source === currentEdge.source
    && nextEdge.target === currentEdge.target
    && (nextEdge.sourceHandle ?? "") === (currentEdge.sourceHandle ?? "")
    && (nextEdge.targetHandle ?? "") === (currentEdge.targetHandle ?? "")
    && (nextEdge.label ?? "") === (currentEdge.label ?? "");
}

/**
 * 引导态合并：把待加载边与当前边的路由合并。
 * 若两边被判定为同一逻辑边且当前边已有路由、新边没有，则复用当前边的路由，
 * 避免每次刷新都触发完整重路由带来的视觉抖动。
 */
export function applyBootstrapEdgeRoutes(
  nextEdges: LinkGraphEdge[],
  currentEdges: LinkGraphEdge[],
  reuseCurrentRoutes = true,
): LinkGraphEdge[] {
  // 关闭复用或任一侧为空：直接返回新边
  if (!reuseCurrentRoutes || nextEdges.length === 0 || currentEdges.length === 0) {
    return nextEdges;
  }
  const currentEdgeById = new Map(currentEdges.map((edge) => [edge.id, edge]));
  return nextEdges.map((edge) => {
    const currentEdge = currentEdgeById.get(edge.id);
    // 当前边没有路由、新边已有路由、或两边逻辑不同 → 不复用
    if (!currentEdge?.route || edge.route || !canReuseStoredEdgeRoute(edge, currentEdge)) {
      return edge;
    }
    return {
      ...edge,
      route: currentEdge.route,
    };
  });
}

/**
 * 规范化语义元数据：剔除 ui./layout. 前缀的字段。
 * 这些前缀的字段是渲染层专用（坐标、布局缓存等），不属于"语义"，
 * 在计算语义签名时需要排除，否则会因纯渲染状态导致签名变化。
 */
function normalizeSemanticMetadata(
  metadata?: Record<string, string>,
): Record<string, string> | undefined {
  if (!metadata) {
    return undefined;
  }
  const nextMetadata = Object.fromEntries(
    Object.entries(metadata).filter(([key]) => !key.startsWith("ui.") && !key.startsWith("layout.")),
  );
  return Object.keys(nextMetadata).length > 0 ? nextMetadata : undefined;
}

/**
 * 计算节点的语义签名：把所有"语义相关"字段拼接成单行字符串。
 * 用于判断两次节点数据是否在语义层面相同（与位置/路由等无关）。
 */
function nodeSemanticSignature(node: LinkGraphNode): string {
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
    JSON.stringify(normalizeSemanticMetadata(node.metadata) ?? {}),
  ].join("|");
}

/** 计算边的语义签名。与节点签名同理，但拼接边的语义字段。 */
function edgeSemanticSignature(edge: LinkGraphEdge): string {
  return [
    edge.id,
    edge.type,
    edge.source,
    edge.target,
    edge.label ?? "",
    JSON.stringify(edge.metadata ?? {}),
    edge.provenance ?? "",
  ].join("|");
}

/**
 * 计算整份图文档的语义签名。
 * 节点与边分别排序后再拼接，保证签名的稳定性与顺序无关。
 * 用于判断两次引导态更新是否在语义层面有变化（位置变化不会改变签名）。
 */
export function graphSemanticSignature(document: LinkGraphDocument): string {
  return [
    document.nodes.map(nodeSemanticSignature).sort().join("::"),
    document.edges.map(edgeSemanticSignature).sort().join("::"),
  ].join("##");
}

/**
 * 计算节点的布局签名：仅基于 id 与坐标。
 * 用于判断两次更新之间坐标是否发生变化，触发 React Flow 的重布局。
 */
export function graphLayoutSignature(nodes: LinkGraphNode[]): string {
  return nodes
    .map((node) => {
      const position = resolveNodePosition(node);
      return [node.id, position?.x ?? "", position?.y ?? ""].join("|");
    })
    .sort()
    .join("::");
}

/**
 * 在保持其他字段不变的前提下，把"仅布局变化"的坐标从 nextNodes 应用到 currentNodes。
 * 用于增量布局更新：只挪位置、不改语义，避免触发不必要的语义重渲染。
 * 没有任何节点坐标变化时直接返回原数组，避免无谓的引用变化。
 */
export function applyLayoutOnlyNodePositions(
  currentNodes: LinkGraphNode[],
  nextNodes: LinkGraphNode[],
): LinkGraphNode[] {
  const nextNodeById = new Map(nextNodes.map((node) => [node.id, node]));
  // 跟踪本轮是否有节点坐标实际变化，用于决定返回值
  let changed = false;
  const positionedNodes = currentNodes.map((currentNode) => {
    const nextNode = nextNodeById.get(currentNode.id);
    // 下一次没有该节点：保持不变
    if (!nextNode) {
      return currentNode;
    }
    const nextPosition = resolveNodePosition(nextNode);
    // 下一次该节点无坐标：保持不变
    if (!nextPosition) {
      return currentNode;
    }
    const currentPosition = resolveNodePosition(currentNode);
    // 坐标未变：保持不变
    if (currentPosition?.x === nextPosition.x && currentPosition?.y === nextPosition.y) {
      return currentNode;
    }
    changed = true;
    return syncNodePosition(currentNode, nextPosition);
  });
  return changed ? positionedNodes : currentNodes;
}

/** 类型守卫：判断 revision 是否为有效数字。用于区分"未给版本号"与"版本号为 0"。 */
export function hasRevision(revision?: number): revision is number {
  return Number.isFinite(revision);
}

/**
 * 抽取节点的布局载荷数组：只输出有坐标的节点。
 * 用于把布局变化发送给后端持久化，无坐标的节点不需要进入载荷。
 */
export function extractLayoutPayload(nodes: LinkGraphNode[]): Array<{ nodeId: string; x: number; y: number }> {
  return nodes.flatMap((node) => (node.position
    ? [{
        nodeId: node.id,
        x: node.position.x,
        y: node.position.y,
      }]
    : []));
}

/**
 * 把节点列表转换为 LinkGraphLayoutState 结构（id → 坐标映射）。
 * 内部基于 [extractLayoutPayload] 实现，便于复用过滤逻辑。
 */
export function extractLayoutState(nodes: LinkGraphNode[]): LinkGraphLayoutState {
  return {
    positions: Object.fromEntries(
      extractLayoutPayload(nodes).map(({ nodeId, x, y }) => [nodeId, { x, y }]),
    ),
  };
}

/**
 * 顺序敏感地比较两个节点 ID 列表是否完全相等。
 * 用于判断选中节点集合是否变化（顺序也是语义的一部分，例如焦点顺序）。
 */
export function sameNodeIdList(left: string[], right: string[]): boolean {
  if (left.length !== right.length) {
    return false;
  }
  return left.every((value, index) => value === right[index]);
}

/**
 * 从指定根节点出发，沿边的方向收集所有下游节点 ID（包含根节点）。
 *
 * 使用 FIFO 队列做广度优先遍历，并通过 Set 去重避免成环导致死循环。
 * 用于"折叠/展开下游"、"高亮影响面"等视图操作。
 *
 * @param rootNodeId 起点节点 ID；若不存在则返回空集
 * @param nodes 当前图节点列表（用于校验根节点是否存在）
 * @param edges 当前图边列表（决定下游关系）
 */
export function collectDownstreamSubtreeNodeIds(
  rootNodeId: string,
  nodes: LinkGraphNode[],
  edges: LinkGraphEdge[],
): Set<string> {
  const existingNodeIds = new Set(nodes.map((node) => node.id));
  // 根节点不存在时直接返回空集
  if (!existingNodeIds.has(rootNodeId)) {
    return new Set();
  }

  // 预构邻接表：source → target 列表
  const outgoing = new Map<string, string[]>();
  edges.forEach((edge) => {
    const nextTargets = outgoing.get(edge.source) ?? [];
    nextTargets.push(edge.target);
    outgoing.set(edge.source, nextTargets);
  });

  const collected = new Set<string>();
  const queue = new FifoQueue([rootNodeId]);
  while (true) {
    const nodeId = queue.dequeue();
    if (!nodeId) {
      break;
    }
    // 已访问过的节点跳过，避免环路
    if (collected.has(nodeId)) {
      continue;
    }
    collected.add(nodeId);
    // 把当前节点的下游加入待访问队列
    (outgoing.get(nodeId) ?? []).forEach((targetId) => {
      if (!collected.has(targetId)) {
        queue.enqueue(targetId);
      }
    });
  }
  return collected;
}
