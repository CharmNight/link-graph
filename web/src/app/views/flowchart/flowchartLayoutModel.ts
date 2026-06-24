// 流程图布局模型：把节点分为"主节点"和若干"调用展开组"。
// 调用展开：用户在流程图上点开一个方法调用后，被调方法的内部流程会被展开成一组节点，
// 这组节点在布局上要作为一个整体被处理（位置相对、整体移动等）。
import type { LinkGraphEdge, LinkGraphNode } from "../../types";

/** 元数据 key：调用展开 ID（同一展开组的节点共享同一 ID）。 */
const EXPANSION_ID_KEY = "linkGraph.expansion.id";
/** 元数据 key：展开来源（哪个调用节点触发了本次展开）。 */
const EXPANSION_SOURCE_INVOCATION_NODE_ID_KEY = "linkGraph.expansion.sourceInvocationNodeId";
/** 元数据 key：展开组的根节点 ID（决定组内布局的起点）。 */
const EXPANSION_ROOT_NODE_ID_KEY = "linkGraph.expansion.rootNodeId";

/**
 * 单个调用展开组。
 *
 * 一个展开组对应"在某节点上展开了一次调用"，包含：
 * - 一组节点（被调方法的内部流程）；
 * - 调用边（从源节点到展开根节点的 CALL 边）；
 * - 内部边（展开组内部的边）。
 */
export interface FlowchartExpansionGroup {
  /** 展开组 ID（同 EXPANSION_ID_KEY）。 */
  expansionId: string;
  /** 展开来源调用节点 ID；可空。 */
  sourceInvocationNodeId: string | null;
  /** 展开组根节点 ID（布局起点）。 */
  rootNodeId: string | null;
  /** 组内全部节点 ID。 */
  nodeIds: string[];
  /** 调用边 ID 列表（外部 → 组内根节点）。 */
  callEdgeIds: string[];
  /** 内部边 ID 列表（组内节点之间的边）。 */
  internalEdgeIds: string[];
}

/** 流程图布局模型：主节点 + 展开组列表。 */
export interface FlowchartLayoutModel {
  /** 主节点 ID 列表（不属于任何展开组的节点）。 */
  mainNodeIds: string[];
  /** 全部展开组列表（按来源节点顺序排序）。 */
  expansionGroups: FlowchartExpansionGroup[];
}

/** 取节点/边上的展开组 ID；缺失或空时返回 null。 */
function expansionIdOf(nodeOrEdge: Pick<LinkGraphNode | LinkGraphEdge, "metadata">): string | null {
  return nodeOrEdge.metadata?.[EXPANSION_ID_KEY]?.trim() || null;
}

/** 取节点的展开来源调用节点 ID。 */
function sourceInvocationNodeIdOf(node: LinkGraphNode): string | null {
  return node.metadata?.[EXPANSION_SOURCE_INVOCATION_NODE_ID_KEY]?.trim() || null;
}

/** 取节点的展开根节点 ID。 */
function rootNodeIdOf(node: LinkGraphNode): string | null {
  return node.metadata?.[EXPANSION_ROOT_NODE_ID_KEY]?.trim() || null;
}

/**
 * 解析展开组的根节点 ID。
 *
 * 优先级：
 * 1) 元数据中显式声明的根（且确实存在于组内）；
 * 2) 第一个方法节点（METHOD 类型）；
 * 3) 不被任何内部边指向的节点（即没有"上游"）；
 * 4) 第一个节点。
 */
function resolveExpansionRootNodeId(nodes: LinkGraphNode[], internalEdges: LinkGraphEdge[]): string | null {
  // 优先用显式声明
  const explicitRootNodeId = nodes.map(rootNodeIdOf).find((nodeId): nodeId is string => Boolean(nodeId));
  if (explicitRootNodeId && nodes.some((node) => node.id === explicitRootNodeId)) {
    return explicitRootNodeId;
  }
  // 其次用方法节点
  const methodNode = nodes.find((node) => node.type === "METHOD");
  if (methodNode) {
    return methodNode.id;
  }
  // 再次：不被内部边指向的节点（即入口）
  const nodeIds = new Set(nodes.map((node) => node.id));
  const internalTargetIds = new Set(internalEdges.map((edge) => edge.target).filter((targetId) => nodeIds.has(targetId)));
  return nodes.find((node) => !internalTargetIds.has(node.id))?.id ?? nodes[0]?.id ?? null;
}

/**
 * 构造流程图布局模型。
 *
 * 流程：
 * 1) 按展开组 ID 把节点分组；
 * 2) 不属于任何展开组的节点 → mainNodeIds；
 * 3) 每个展开组：计算根节点、调用边、内部边；
 * 4) 展开组按"来源节点在主流程中的位置"排序（让展开组在布局中按调用顺序排列）。
 *
 * @param nodes 全部节点
 * @param edges 全部边
 * @return 布局模型
 */
export function buildFlowchartLayoutModel(
  nodes: LinkGraphNode[],
  edges: LinkGraphEdge[],
): FlowchartLayoutModel {
  // 节点 ID → 在 nodes 数组中的位置（用于展开组排序）
  const nodeIndexOrder = new Map(nodes.map((node, index) => [node.id, index]));
  // 所有属于某展开组的节点 ID
  const expansionNodeIds = new Set<string>();
  // 展开组 ID → 组内节点列表
  const nodesByExpansionId = new Map<string, LinkGraphNode[]>();

  // 第一步：按展开组 ID 分组
  nodes.forEach((node) => {
    const expansionId = expansionIdOf(node);
    if (!expansionId) {
      return;
    }
    expansionNodeIds.add(node.id);
    const groupNodes = nodesByExpansionId.get(expansionId) ?? [];
    groupNodes.push(node);
    nodesByExpansionId.set(expansionId, groupNodes);
  });

  // 第二步：主节点 = 不属于任何展开组的节点
  const mainNodeIds = nodes
    .filter((node) => !expansionNodeIds.has(node.id))
    .map((node) => node.id);

  // 第三步：构造展开组对象
  const expansionGroups = Array.from(nodesByExpansionId.entries()).map(([expansionId, groupNodes]) => {
    const groupNodeIds = new Set(groupNodes.map((node) => node.id));
    // 来源调用节点 ID（取第一个非空值）
    const sourceInvocationNodeId = groupNodes.map(sourceInvocationNodeIdOf).find((nodeId): nodeId is string => Boolean(nodeId)) ?? null;
    // 调用边：CALL 类型，且满足以下任一：
    // - 边本身属于本展开组（EXPANSION_ID_KEY 命中）；
    // - 边从来源调用节点出发、目标在组内。
    const callEdgeIds = edges
      .filter((edge) => edge.type === "CALL" && (
        expansionIdOf(edge) === expansionId ||
        (sourceInvocationNodeId !== null && edge.source === sourceInvocationNodeId && groupNodeIds.has(edge.target))
      ))
      .map((edge) => edge.id);
    // 内部边：起点和终点都在组内
    const internalEdges = edges.filter((edge) => groupNodeIds.has(edge.source) && groupNodeIds.has(edge.target));
    return {
      expansionId,
      sourceInvocationNodeId,
      rootNodeId: resolveExpansionRootNodeId(groupNodes, internalEdges),
      nodeIds: groupNodes.map((node) => node.id),
      callEdgeIds,
      internalEdgeIds: internalEdges.map((edge) => edge.id),
    };
  }).sort((left, right) => {
    // 按来源节点在主流程中的位置排序；同位置时按 ID 字典序兜底
    const leftSourceOrder = left.sourceInvocationNodeId ? nodeIndexOrder.get(left.sourceInvocationNodeId) ?? Number.MAX_SAFE_INTEGER : Number.MAX_SAFE_INTEGER;
    const rightSourceOrder = right.sourceInvocationNodeId ? nodeIndexOrder.get(right.sourceInvocationNodeId) ?? Number.MAX_SAFE_INTEGER : Number.MAX_SAFE_INTEGER;
    if (leftSourceOrder !== rightSourceOrder) {
      return leftSourceOrder - rightSourceOrder;
    }
    return left.expansionId.localeCompare(right.expansionId);
  });

  return {
    mainNodeIds,
    expansionGroups,
  };
}
