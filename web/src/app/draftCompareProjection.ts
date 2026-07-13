import type {
  DraftCompareProjection,
  DraftCompareStatus,
  DraftWorkbenchEntry,
  LinkGraphDocument,
  LinkGraphEdge,
  LinkGraphNode,
  ResultEvidenceReference,
} from "./types";

/** 构造草稿比对投影所需的全部入参，包含当前比对模式、被选中的草稿条目以及可见图、参考图、工作图三份图数据。 */
interface BuildDraftCompareProjectionArgs {
  compareMode: "after" | "compare";
  selectedEntry: DraftWorkbenchEntry | null;
  visibleGraph: LinkGraphDocument;
  referenceGraph?: LinkGraphDocument | null;
  workingGraph: LinkGraphDocument;
}

/** 单个节点或边在比对过程中的中间表示，记录当前图与参考图各自的 id 以及计算出的差异状态。 */
interface DraftCompareElement {
  currentId: string | null;
  referenceId: string | null;
  status: DraftCompareStatus;
}

// 已删除节点在比对图中重新出现时使用的前缀，用于和真实节点区分开
const GHOST_NODE_PREFIX = "draft-ghost-node:";
// 已删除边在比对图中重新出现时使用的前缀，避免与原图边冲突
const GHOST_EDGE_PREFIX = "draft-ghost-edge:";
// 流程图节点 metadata 上记录其投影来源节点 id 列表的 key
const FLOWCHART_ALIAS_IDS_KEY = "flowchart.projectedFromNodeIds";

/** 比对图的可变容器，节点和边都附带 id 到下标的索引，便于在合并比对结果时按 id 快速定位或追加。 */
interface OrderedCompareGraph {
  nodes: LinkGraphNode[];
  nodeIndexById: Map<string, number>;
  edges: LinkGraphEdge[];
  edgeIndexById: Map<string, number>;
}

/**
 * 草稿比对投影的入口：在 compare 模式下，针对选中的 CHANGE 草稿条目，把工作图与参考图的差异投影到当前可见图上。
 * 返回带状态标注的比对图以及统计摘要，供 UI 高亮展示前后差异；当条件不满足或无差异时返回 null。
 */
export function buildDraftCompareProjection({
  compareMode,
  selectedEntry,
  visibleGraph,
  referenceGraph,
  workingGraph,
}: BuildDraftCompareProjectionArgs): DraftCompareProjection | null {
  if (compareMode !== "compare" || selectedEntry?.kind !== "CHANGE" || referenceGraph == null) {
    return null;
  }

  // 当前草稿条目涉及到的节点 id 集合，作为本次比对的限定范围
  const scopeNodeIds = collectScopedNodeIds(selectedEntry);
  if (scopeNodeIds.size === 0) {
    return null;
  }

  // 范围内节点的差异列表
  const nodeComparisons = buildNodeComparisons(scopeNodeIds, referenceGraph, workingGraph);
  // 范围内边的差异列表
  const edgeComparisons = buildEdgeComparisons(scopeNodeIds, referenceGraph, workingGraph);
  if (nodeComparisons.length === 0 && edgeComparisons.length === 0) {
    return null;
  }

  // 可见图中存在的节点 id 集合，用于判断比对结果是否需要计入隐藏统计
  const visibleNodeIds = new Set(visibleGraph.nodes.map((node) => node.id));
  // 可见图中存在的边 id 集合，过滤掉流程图入口合成边避免误统计
  const visibleEdgeIds = new Set(
    visibleGraph.edges
      .filter((edge) => edge.metadata?.["flowchart.synthetic"] !== "entry-edge")
      .map((edge) => edge.id),
  );
  // 参考图节点按 id 索引，便于按 id 取出原始节点信息
  const referenceNodesById = new Map(referenceGraph.nodes.map((node) => [node.id, node]));
  // 工作图节点按 id 索引，便于按 id 取出最新节点信息
  const workingNodesById = new Map(workingGraph.nodes.map((node) => [node.id, node]));
  // 以可见图为初始骨架，逐步合并比对结果形成最终比对图
  const compareGraph = createOrderedCompareGraph(visibleGraph);
  // 当前不在可见图中、但因属于本次范围而被投影出来的节点数量
  let hiddenNodeCount = 0;
  // 当前不在可见图中、但因属于本次范围而被投影出来的边数量
  let hiddenEdgeCount = 0;

  // 比对图中每个节点 id 对应的差异状态
  const nodeStatuses: Record<string, DraftCompareStatus> = {};
  for (const comparison of nodeComparisons) {
    const projectedVisibleNodeId = resolveProjectedVisibleNodeId(
      visibleGraph.nodes,
      comparison.currentId ?? comparison.referenceId,
    );
    if (comparison.status === "REMOVED" && comparison.referenceId) {
      const ghostNode = ensureGhostNode({
        compareGraph,
        referenceNodesById,
        entryId: selectedEntry.entryId,
        referenceNodeId: comparison.referenceId,
      });
      if (ghostNode) {
        nodeStatuses[ghostNode.id] = "REMOVED";
        hiddenNodeCount += projectedVisibleNodeId == null ? 1 : 0;
      }
      continue;
    }
    if (!comparison.currentId) {
      continue;
    }
    const currentNode = workingNodesById.get(comparison.currentId);
    if (!currentNode) {
      continue;
    }
    if (projectedVisibleNodeId) {
      const visibleNode = compareGraph.nodes[compareGraph.nodeIndexById.get(projectedVisibleNodeId) ?? -1] ?? null;
      const compareNode = visibleNode ? mergeProjectedPresentationNode(visibleNode, currentNode) : currentNode;
      upsertCompareNode(compareGraph, compareNode);
      nodeStatuses[compareNode.id] = comparison.status;
      continue;
    }
    upsertCompareNode(compareGraph, currentNode);
    if (currentNode || visibleNodeIds.has(comparison.currentId)) {
      nodeStatuses[comparison.currentId] = comparison.status;
    }
    hiddenNodeCount += visibleNodeIds.has(comparison.currentId) ? 0 : 1;
  }

  // 比对图中每条边 id 对应的差异状态
  const edgeStatuses: Record<string, DraftCompareStatus> = {};
  for (const comparison of edgeComparisons) {
    if (comparison.status === "REMOVED" && comparison.referenceId) {
      const referenceEdge = referenceGraph.edges.find((edge) => edge.id === comparison.referenceId);
      if (!referenceEdge) {
        continue;
      }
      const sourceId = ensureEdgeEndpointNode({
        nodeId: referenceEdge.source,
        entryId: selectedEntry.entryId,
        compareGraph,
        referenceNodesById,
        workingNodesById,
        removed: !workingNodesById.has(referenceEdge.source),
      });
      const targetId = ensureEdgeEndpointNode({
        nodeId: referenceEdge.target,
        entryId: selectedEntry.entryId,
        compareGraph,
        referenceNodesById,
        workingNodesById,
        removed: !workingNodesById.has(referenceEdge.target),
      });
      const ghostEdgeId = `${GHOST_EDGE_PREFIX}${selectedEntry.entryId}:${referenceEdge.id}`;
      upsertCompareEdge(compareGraph, {
        ...referenceEdge,
        id: ghostEdgeId,
        source: sourceId,
        target: targetId,
        metadata: {
          ...(referenceEdge.metadata ?? {}),
          "draft.compare.ghost": "true",
          "draft.compare.referenceEdgeId": referenceEdge.id,
        },
      });
      edgeStatuses[ghostEdgeId] = "REMOVED";
      hiddenEdgeCount += 1;
      continue;
    }
    if (!comparison.currentId) {
      continue;
    }
    const currentEdge = workingGraph.edges.find((edge) => edge.id === comparison.currentId);
    if (currentEdge && !compareGraph.edgeIndexById.has(currentEdge.id)) {
      upsertCompareEdge(compareGraph, currentEdge);
    }
    if (currentEdge || visibleEdgeIds.has(comparison.currentId)) {
      edgeStatuses[comparison.currentId] = comparison.status;
    }
    hiddenEdgeCount += visibleEdgeIds.has(comparison.currentId) ? 0 : 1;
  }

  return {
    entryId: selectedEntry.entryId,
    entryTitle: selectedEntry.title,
    compareGraph: {
      nodes: compareGraph.nodes,
      edges: compareGraph.edges,
    },
    nodeStatuses,
    edgeStatuses,
    summary: {
      scopeNodeCount: scopeNodeIds.size,
      visibleNodeCount: Object.keys(nodeStatuses).length,
      visibleEdgeCount: Object.keys(edgeStatuses).length,
      hiddenNodeCount,
      hiddenEdgeCount,
    },
  };
}

/** 汇总草稿条目中显式声明的目标节点、图补丁里出现的节点与边端点、以及证据引用指向的节点，得到本次比对需要覆盖的全部节点 id。 */
function collectScopedNodeIds(entry: DraftWorkbenchEntry): Set<string> {
  const scopeNodeIds = new Set(entry.targetNodeIds);
  for (const operation of entry.graphPatch?.operations ?? []) {
    if (operation.node?.id) {
      scopeNodeIds.add(operation.node.id);
    }
    if (operation.edge?.source) {
      scopeNodeIds.add(operation.edge.source);
    }
    if (operation.edge?.target) {
      scopeNodeIds.add(operation.edge.target);
    }
  }
  for (const reference of entry.evidence.flatMap((finding) => finding.references)) {
    addReferenceNodeId(scopeNodeIds, reference);
  }
  return scopeNodeIds;
}

/** 将单条证据引用所指向的节点 id（去除空白后）加入范围集合，空值会被忽略。 */
function addReferenceNodeId(scopeNodeIds: Set<string>, reference: ResultEvidenceReference) {
  if (reference.nodeId?.trim()) {
    scopeNodeIds.add(reference.nodeId.trim());
  }
}

/** 以可见图为底本克隆出可变的比对图容器，并建立节点、边的 id 到下标的索引。 */
function createOrderedCompareGraph(visibleGraph: LinkGraphDocument): OrderedCompareGraph {
  return {
    nodes: [...visibleGraph.nodes],
    nodeIndexById: new Map(visibleGraph.nodes.map((node, index) => [node.id, index])),
    edges: [...visibleGraph.edges],
    edgeIndexById: new Map(visibleGraph.edges.map((edge, index) => [edge.id, index])),
  };
}

/** 在比对图中按 id 插入或更新节点：已存在则替换，不存在则追加并维护索引。 */
function upsertCompareNode(compareGraph: OrderedCompareGraph, node: LinkGraphNode) {
  const existingIndex = compareGraph.nodeIndexById.get(node.id);
  if (existingIndex == null) {
    compareGraph.nodes.push(node);
    compareGraph.nodeIndexById.set(node.id, compareGraph.nodes.length - 1);
    return;
  }
  compareGraph.nodes[existingIndex] = node;
}

/** 在比对图中按 id 插入或更新边：已存在则替换，不存在则追加并维护索引。 */
function upsertCompareEdge(compareGraph: OrderedCompareGraph, edge: LinkGraphEdge) {
  const existingIndex = compareGraph.edgeIndexById.get(edge.id);
  if (existingIndex == null) {
    compareGraph.edges.push(edge);
    compareGraph.edgeIndexById.set(edge.id, compareGraph.edges.length - 1);
    return;
  }
  compareGraph.edges[existingIndex] = edge;
}

/** 从节点 metadata 中解析出投影来源的别名节点 id 列表，用于把工作图节点映射到流程图等投影视图的对应节点上。 */
function projectedAliasNodeIds(node: LinkGraphNode): string[] {
  const rawAliasNodeIds = node.metadata?.[FLOWCHART_ALIAS_IDS_KEY];
  if (!rawAliasNodeIds) {
    return [];
  }
  return rawAliasNodeIds
    .split(",")
    .map((value) => value.trim())
    .filter((value) => value.length > 0);
}

/** 在可见图节点中按 id 直接匹配，匹配不到再借助投影别名查找，返回对应的可见节点 id；用于把比对结果对应回可见图上的展示节点。 */
function resolveProjectedVisibleNodeId(
  visibleNodes: LinkGraphNode[],
  nodeId: string | null,
): string | null {
  if (!nodeId) {
    return null;
  }
  const directMatch = visibleNodes.find((visibleNode) => visibleNode.id === nodeId);
  if (directMatch) {
    return directMatch.id;
  }
  return visibleNodes.find((visibleNode) => projectedAliasNodeIds(visibleNode).includes(nodeId))?.id ?? null;
}

/** 把工作图节点的最新内容合并到可见图节点上，同时保留可见节点原本的 id、坐标以及布局相关的展示属性，避免覆盖前端布局信息。 */
function mergeProjectedPresentationNode(
  visibleNode: LinkGraphNode,
  nextNode: LinkGraphNode,
): LinkGraphNode {
  return {
    ...visibleNode,
    ...nextNode,
    id: visibleNode.id,
    position: visibleNode.position ?? nextNode.position,
    metadata: {
      ...(visibleNode.metadata ?? {}),
      ...(nextNode.metadata ?? {}),
    },
  };
}

/** 保证比对图中存在一个用于表示已删除节点的"幽灵节点"：若已存在则直接复用，否则基于参考节点克隆并打上 ghost 元数据后插入比对图。 */
function ensureGhostNode(args: {
  compareGraph: OrderedCompareGraph;
  referenceNodesById: Map<string, LinkGraphNode>;
  entryId: string;
  referenceNodeId: string;
}): LinkGraphNode | null {
  const referenceNode = args.referenceNodesById.get(args.referenceNodeId);
  if (!referenceNode) {
    return null;
  }
  const ghostNodeId = `${GHOST_NODE_PREFIX}${args.entryId}:${referenceNode.id}`;
  const existingIndex = args.compareGraph.nodeIndexById.get(ghostNodeId);
  const existing = existingIndex == null ? null : args.compareGraph.nodes[existingIndex] ?? null;
  if (existing) {
    return existing;
  }
  const ghostNode: LinkGraphNode = {
    ...referenceNode,
    id: ghostNodeId,
    metadata: {
      ...(referenceNode.metadata ?? {}),
      "draft.compare.ghost": "true",
      "draft.compare.referenceNodeId": referenceNode.id,
    },
  };
  upsertCompareNode(args.compareGraph, ghostNode);
  return ghostNode;
}

/** 为一条比对边的端点找到合适的展示节点：若端点节点仍存在于工作图，则使用其工作版本；否则用幽灵节点表示已删除的端点，并返回最终使用的节点 id。 */
function ensureEdgeEndpointNode(args: {
  nodeId: string;
  entryId: string;
  compareGraph: OrderedCompareGraph;
  referenceNodesById: Map<string, LinkGraphNode>;
  workingNodesById: Map<string, LinkGraphNode>;
  removed: boolean;
}): string {
  if (!args.removed && args.workingNodesById.has(args.nodeId)) {
    const workingNode = args.workingNodesById.get(args.nodeId)!;
    if (!args.compareGraph.nodeIndexById.has(workingNode.id)) {
      upsertCompareNode(args.compareGraph, workingNode);
    }
    return workingNode.id;
  }
  const ghostNode = ensureGhostNode({
    compareGraph: args.compareGraph,
    referenceNodesById: args.referenceNodesById,
    entryId: args.entryId,
    referenceNodeId: args.nodeId,
  });
  return ghostNode?.id ?? args.nodeId;
}

/** 遍历范围内的每个节点 id，对照参考图与工作图判断是新增、删除、修改还是无变化，输出节点级别的差异列表。 */
function buildNodeComparisons(
  scopeNodeIds: ReadonlySet<string>,
  referenceGraph: LinkGraphDocument,
  workingGraph: LinkGraphDocument,
): DraftCompareElement[] {
  const referenceNodesById = new Map(referenceGraph.nodes.map((node) => [node.id, node]));
  const workingNodesById = new Map(workingGraph.nodes.map((node) => [node.id, node]));
  const comparisons: DraftCompareElement[] = [];

  for (const nodeId of Array.from(scopeNodeIds).sort()) {
    const referenceNode = referenceNodesById.get(nodeId);
    const workingNode = workingNodesById.get(nodeId);
    if (referenceNode == null && workingNode == null) {
      continue;
    }
    if (referenceNode == null && workingNode != null) {
      comparisons.push({ currentId: workingNode.id, referenceId: null, status: "ADDED" });
      continue;
    }
    if (referenceNode != null && workingNode == null) {
      comparisons.push({ currentId: null, referenceId: referenceNode.id, status: "REMOVED" });
      continue;
    }
    if (!referenceNode || !workingNode || !nodesDiffer(referenceNode, workingNode)) {
      continue;
    }
    comparisons.push({ currentId: workingNode.id, referenceId: referenceNode.id, status: "MODIFIED" });
  }

  return comparisons;
}

/** 在范围内对边进行差异判定：先按 id 配对判定修改，再对未配对的参考边尝试用模糊匹配找回修改目标，剩余的分别归为新增或删除。 */
function buildEdgeComparisons(
  scopeNodeIds: ReadonlySet<string>,
  referenceGraph: LinkGraphDocument,
  workingGraph: LinkGraphDocument,
): DraftCompareElement[] {
  const referenceEdges = referenceGraph.edges.filter((edge) => edgeTouchesScope(edge, scopeNodeIds));
  const workingEdges = workingGraph.edges.filter((edge) => edgeTouchesScope(edge, scopeNodeIds));
  const remainingReference = new Map(referenceEdges.map((edge) => [edge.id, edge]));
  const remainingWorking = new Map(workingEdges.map((edge) => [edge.id, edge]));
  const comparisons: DraftCompareElement[] = [];

  for (const edgeId of Array.from(remainingReference.keys()).filter((candidateEdgeId) => remainingWorking.has(candidateEdgeId)).sort()) {
    const referenceEdge = remainingReference.get(edgeId);
    const workingEdge = remainingWorking.get(edgeId);
    if (!referenceEdge || !workingEdge) {
      continue;
    }
    remainingReference.delete(edgeId);
    remainingWorking.delete(edgeId);
    if (edgesDiffer(referenceEdge, workingEdge)) {
      comparisons.push({
        currentId: workingEdge.id,
        referenceId: referenceEdge.id,
        status: "MODIFIED",
      });
    }
  }

  for (const referenceEdge of Array.from(remainingReference.values()).sort((left, right) => left.id.localeCompare(right.id))) {
    const matchedWorking = findModifiedEdgeCandidate(referenceEdge, Array.from(remainingWorking.values()));
    if (matchedWorking == null) {
      continue;
    }
    remainingReference.delete(referenceEdge.id);
    remainingWorking.delete(matchedWorking.id);
    comparisons.push({
      currentId: matchedWorking.id,
      referenceId: referenceEdge.id,
      status: "MODIFIED",
    });
  }

  for (const workingEdge of Array.from(remainingWorking.values()).sort((left, right) => left.id.localeCompare(right.id))) {
    comparisons.push({
      currentId: workingEdge.id,
      referenceId: null,
      status: "ADDED",
    });
  }
  for (const referenceEdge of Array.from(remainingReference.values()).sort((left, right) => left.id.localeCompare(right.id))) {
    comparisons.push({
      currentId: null,
      referenceId: referenceEdge.id,
      status: "REMOVED",
    });
  }

  return comparisons;
}

/** 判断一条边的源节点或目标节点是否落在本次比对范围内，用于过滤出相关边。 */
function edgeTouchesScope(edge: LinkGraphEdge, scopeNodeIds: ReadonlySet<string>): boolean {
  return scopeNodeIds.has(edge.source) || scopeNodeIds.has(edge.target);
}

/** 在剩余工作边中为参考边寻找最可能对应的修改目标：要求类型一致且相似度评分大于零，并确保最高分唯一突出，避免歧义匹配。 */
function findModifiedEdgeCandidate(
  referenceEdge: LinkGraphEdge,
  workingEdges: LinkGraphEdge[],
): LinkGraphEdge | null {
  const scored = workingEdges
    .filter((candidate) => candidate.type === referenceEdge.type)
    .map((candidate) => ({ candidate, score: edgeSimilarityScore(referenceEdge, candidate) }))
    .filter(({ score }) => score > 0)
    .sort((left, right) => {
      if (right.score !== left.score) {
        return right.score - left.score;
      }
      return left.candidate.id.localeCompare(right.candidate.id);
    });
  const best = scored[0];
  if (!best) {
    return null;
  }
  const secondBest = scored[1];
  return secondBest == null || best.score > secondBest.score ? best.candidate : null;
}

/** 给两条同类型边打相似度分：端点相同权重最高，归一化后的标签相同再加分，用于辅助模糊匹配。 */
function edgeSimilarityScore(left: LinkGraphEdge, right: LinkGraphEdge): number {
  let score = 0;
  if (left.source === right.source) {
    score += 4;
  }
  if (left.target === right.target) {
    score += 4;
  }
  if (normalizedText(left.label) === normalizedText(right.label) && normalizedText(left.label).length > 0) {
    score += 2;
  }
  return score;
}

/** 综合比较两个节点的类型、标题、签名、输入输出、文档、绑定状态等业务字段以及元数据，判断节点是否发生实质性变更。 */
function nodesDiffer(referenceNode: LinkGraphNode, workingNode: LinkGraphNode): boolean {
  return referenceNode.type !== workingNode.type
    || referenceNode.title !== workingNode.title
    || referenceNode.location !== workingNode.location
    || referenceNode.signature !== workingNode.signature
    || !sameStringArray(referenceNode.inputs, workingNode.inputs)
    || !sameStringArray(referenceNode.outputs, workingNode.outputs)
    || normalizedText(referenceNode.doc) !== normalizedText(workingNode.doc)
    || referenceNode.confidence !== workingNode.confidence
    || referenceNode.binding !== workingNode.binding
    || referenceNode.provenance !== workingNode.provenance
    || !sameMetadata(referenceNode.metadata, workingNode.metadata);
}

/** 比较两条边的类型、端点、标签、来源标签以及元数据，判断边是否发生实质性变更。 */
function edgesDiffer(referenceEdge: LinkGraphEdge, workingEdge: LinkGraphEdge): boolean {
  return referenceEdge.type !== workingEdge.type
    || referenceEdge.source !== workingEdge.source
    || referenceEdge.target !== workingEdge.target
    || normalizedText(referenceEdge.label) !== normalizedText(workingEdge.label)
    || referenceEdge.provenance !== workingEdge.provenance
    || !sameMetadata(referenceEdge.metadata, workingEdge.metadata);
}

/** 按顺序逐一比较两个字符串数组是否完全相等，长度不同直接判为不等。 */
function sameStringArray(left: string[], right: string[]): boolean {
  if (left.length !== right.length) {
    return false;
  }
  return left.every((value, index) => value === right[index]);
}

/** 对文本做 trim 并把空值归一化为空串，用于忽略前后空白造成的虚假差异。 */
function normalizedText(value?: string | null): string {
  return value?.trim() ?? "";
}

/** 在剔除展示类元数据后比较两份元数据是否一致，避免因纯布局/界面信息变化而误判节点或边发生了变更。 */
function sameMetadata(
  left?: Record<string, string>,
  right?: Record<string, string>,
): boolean {
  const normalizedLeft = comparableMetadata(left);
  const normalizedRight = comparableMetadata(right);
  if (normalizedLeft.size !== normalizedRight.size) {
    return false;
  }
  for (const [key, value] of normalizedLeft) {
    if (normalizedRight.get(key) !== value) {
      return false;
    }
  }
  return true;
}

/** 把元数据转换为可比较形式：剔除以 ui. 和 layout. 开头的展示类 key，其余键值对放入 Map 供精确比对。 */
function comparableMetadata(metadata?: Record<string, string>): Map<string, string> {
  const comparable = new Map<string, string>();
  for (const [key, value] of Object.entries(metadata ?? {})) {
    if (key.startsWith("ui.") || key.startsWith("layout.")) {
      continue;
    }
    comparable.set(key, value);
  }
  return comparable;
}
