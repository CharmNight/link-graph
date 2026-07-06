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
/** 元数据 key：展开目标方法签名。 */
const EXPANSION_TARGET_SIGNATURE_KEY = "linkGraph.expansion.targetSignature";
/** 元数据 key：展开创建时间。 */
const EXPANSION_CREATED_AT_KEY = "linkGraph.expansion.createdAt";
/** 元数据 key：流程图投影来源节点 ID 列表。 */
const FLOWCHART_PROJECTED_FROM_NODE_IDS_KEY = "flowchart.projectedFromNodeIds";

export type FlowchartInvocationExpansionState = "expanded" | "collapsed";
export type FlowchartInvocationExpansionContextMode = "ACTIVE_CHAIN";

export interface FlowchartInvocationExpansionSummary {
  nodeCount: number;
  branchCount: number;
  returnCount: number;
  childExpansionCount: number;
  hasBorrowedRoot: boolean;
  approximate: boolean;
}

export interface FlowchartInvocationExpansionSceneState {
  activeExpansionId: string | null;
  activeExpansionPath: string[];
  collapsedExpansionIds: string[];
  activeSiblingByParentContext: Record<string, string>;
  blockPositions: Record<string, { x: number; y: number }>;
  lastChildStateByExpansionId: Record<string, unknown>;
  contextMode: FlowchartInvocationExpansionContextMode;
}

export interface FlowchartInvocationExpansionEntry {
  expansionId: string;
  sourceInvocationNodeId: string | null;
  targetSignature: string | null;
  rootNodeId: string | null;
  createdAt: string | null;
  parentExpansionId: string | null;
  depth: number;
  ownedNodeIds: string[];
  borrowedNodeIds: string[];
  ownedEdgeIds: string[];
  callEdgeIds: string[];
  internalEdgeIds: string[];
  childExpansionIds: string[];
  state: FlowchartInvocationExpansionState;
  summary: FlowchartInvocationExpansionSummary;
  warnings: string[];
}

export interface FlowchartInvocationExpansionRegistry {
  entries: FlowchartInvocationExpansionEntry[];
  entriesById: Record<string, FlowchartInvocationExpansionEntry>;
  parentContextIdsByExpansionId: Record<string, string>;
  childExpansionIdsByParentContext: Record<string, string[]>;
  sceneState: FlowchartInvocationExpansionSceneState;
}

interface FlowchartInvocationExpansionDraft {
  expansionId: string;
  taggedNodes: LinkGraphNode[];
  taggedEdges: LinkGraphEdge[];
  ownedNodeIds: Set<string>;
  ownedEdgeIds: Set<string>;
}

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

/** 取节点/边元数据中的字符串值，空白值视为缺失。 */
function metadataValue(
  nodeOrEdge: Pick<LinkGraphNode | LinkGraphEdge, "metadata">,
  key: string,
): string | null {
  return nodeOrEdge.metadata?.[key]?.trim() || null;
}

/** 从候选元数据对象中按顺序取第一个非空值。 */
function firstMetadataValue(
  candidates: Array<Pick<LinkGraphNode | LinkGraphEdge, "metadata">>,
  key: string,
): string | null {
  return candidates.map((candidate) => metadataValue(candidate, key)).find((value): value is string => Boolean(value)) ?? null;
}

/** 解析投影来源节点 ID 列表，支持逗号分隔的可读投影别名。 */
function projectedFromNodeIds(node: LinkGraphNode): string[] {
  return (node.metadata?.[FLOWCHART_PROJECTED_FROM_NODE_IDS_KEY] ?? "")
    .split(",")
    .map((value) => value.trim())
    .filter((value) => value.length > 0);
}

/** 根据节点元数据判断其是否表示分支节点。 */
function isBranchNode(node: LinkGraphNode): boolean {
  return node.metadata?.["flowchart.kind"] === "DECISION";
}

/** 根据节点类型和元数据判断其是否表示返回/终止节点。 */
function isReturnNode(node: LinkGraphNode): boolean {
  return node.type === "TERMINAL" || node.metadata?.["flow.kind"] === "RETURN";
}

/** 场景状态缺失时的默认 ACTIVE_CHAIN 状态。 */
export function emptyFlowchartInvocationExpansionSceneState(): FlowchartInvocationExpansionSceneState {
  return {
    activeExpansionId: null,
    activeExpansionPath: [],
    collapsedExpansionIds: [],
    activeSiblingByParentContext: {},
    blockPositions: {},
    lastChildStateByExpansionId: {},
    contextMode: "ACTIVE_CHAIN",
  };
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

/** 获取或创建展开草稿对象，用于合并来自节点与边的元数据。 */
function expansionDraftFor(
  draftsById: Map<string, FlowchartInvocationExpansionDraft>,
  expansionId: string,
): FlowchartInvocationExpansionDraft {
  const existingDraft = draftsById.get(expansionId);
  if (existingDraft) {
    return existingDraft;
  }
  const draft: FlowchartInvocationExpansionDraft = {
    expansionId,
    taggedNodes: [],
    taggedEdges: [],
    ownedNodeIds: new Set(),
    ownedEdgeIds: new Set(),
  };
  draftsById.set(expansionId, draft);
  return draft;
}

/** 排序展开 ID 列表，保证 sibling 激活和渲染顺序稳定。 */
function sortExpansionEntries(
  entries: FlowchartInvocationExpansionEntry[],
  nodeIndexOrder: Map<string, number>,
): FlowchartInvocationExpansionEntry[] {
  return [...entries].sort((left, right) => {
    const leftSourceOrder = left.sourceInvocationNodeId ? nodeIndexOrder.get(left.sourceInvocationNodeId) ?? Number.MAX_SAFE_INTEGER : Number.MAX_SAFE_INTEGER;
    const rightSourceOrder = right.sourceInvocationNodeId ? nodeIndexOrder.get(right.sourceInvocationNodeId) ?? Number.MAX_SAFE_INTEGER : Number.MAX_SAFE_INTEGER;
    if (leftSourceOrder !== rightSourceOrder) {
      return leftSourceOrder - rightSourceOrder;
    }
    if ((left.createdAt ?? "") !== (right.createdAt ?? "")) {
      return (left.createdAt ?? "").localeCompare(right.createdAt ?? "");
    }
    if ((left.targetSignature ?? "") !== (right.targetSignature ?? "")) {
      return (left.targetSignature ?? "").localeCompare(right.targetSignature ?? "");
    }
    return left.expansionId.localeCompare(right.expansionId);
  });
}

/** 计算 parent context 的稳定 key。 */
function parentContextId(entry: Pick<FlowchartInvocationExpansionEntry, "parentExpansionId">, anchorNodeId: string | null | undefined): string {
  return entry.parentExpansionId ? `expansion:${entry.parentExpansionId}` : `root:${anchorNodeId ?? "flowchart"}`;
}

/** 由 active expansion 向根部回溯得到 active path。 */
function activePathFor(
  activeExpansionId: string | null,
  entriesById: Record<string, FlowchartInvocationExpansionEntry>,
): string[] {
  if (!activeExpansionId || !entriesById[activeExpansionId]) {
    return [];
  }
  const path: string[] = [];
  const visited = new Set<string>();
  let currentId: string | null = activeExpansionId;
  while (currentId && entriesById[currentId] && !visited.has(currentId)) {
    visited.add(currentId);
    path.unshift(currentId);
    currentId = entriesById[currentId]!.parentExpansionId;
  }
  return path;
}

/** 构建规范化后的展开场景状态，保证每个 parent context 默认只有一个展开 sibling。 */
function normalizeInvocationExpansionSceneState(args: {
  entries: FlowchartInvocationExpansionEntry[];
  anchorNodeId?: string | null;
  sceneState?: Partial<FlowchartInvocationExpansionSceneState> | null;
  defaultCollapseSiblings?: boolean;
}): FlowchartInvocationExpansionSceneState {
  const baseState = {
    ...emptyFlowchartInvocationExpansionSceneState(),
    ...(args.sceneState ?? {}),
    activeSiblingByParentContext: {
      ...(args.sceneState?.activeSiblingByParentContext ?? {}),
    },
    blockPositions: {
      ...(args.sceneState?.blockPositions ?? {}),
    },
    lastChildStateByExpansionId: {
      ...(args.sceneState?.lastChildStateByExpansionId ?? {}),
    },
  };
  const entryIds = new Set(args.entries.map((entry) => entry.expansionId));
  const childrenByParentContext = new Map<string, string[]>();
  args.entries.forEach((entry) => {
    const contextId = parentContextId(entry, args.anchorNodeId);
    const siblingIds = childrenByParentContext.get(contextId) ?? [];
    siblingIds.push(entry.expansionId);
    childrenByParentContext.set(contextId, siblingIds);
  });

  const entriesById = Object.fromEntries(args.entries.map((entry) => [entry.expansionId, entry]));
  const collapsedExpansionIds = new Set(
    (baseState.collapsedExpansionIds ?? []).filter((expansionId) => entryIds.has(expansionId)),
  );
  const requestedActiveSiblingByParentContext: Record<string, string> = {
    ...baseState.activeSiblingByParentContext,
  };
  [...(baseState.activeExpansionPath ?? []), baseState.activeExpansionId]
    .filter((expansionId): expansionId is string => Boolean(expansionId && entryIds.has(expansionId)))
    .forEach((expansionId) => {
      if (collapsedExpansionIds.has(expansionId)) {
        return;
      }
      const entry = entriesById[expansionId];
      if (!entry) {
        return;
      }
      requestedActiveSiblingByParentContext[parentContextId(entry, args.anchorNodeId)] = expansionId;
    });

  const activeSiblingByParentContext: Record<string, string> = {};
  childrenByParentContext.forEach((siblingIds, contextId) => {
    const requestedActiveSiblingId = requestedActiveSiblingByParentContext[contextId];
    const activeSiblingId = requestedActiveSiblingId &&
      siblingIds.includes(requestedActiveSiblingId) &&
      !collapsedExpansionIds.has(requestedActiveSiblingId)
      ? requestedActiveSiblingId
      : siblingIds.find((siblingId) => !collapsedExpansionIds.has(siblingId));
    if (activeSiblingId) {
      activeSiblingByParentContext[contextId] = activeSiblingId;
    }
  });

  if (args.defaultCollapseSiblings !== false) {
    childrenByParentContext.forEach((siblingIds, contextId) => {
      const activeSiblingId = activeSiblingByParentContext[contextId];
      siblingIds.forEach((siblingId) => {
        if (siblingId !== activeSiblingId) {
          collapsedExpansionIds.add(siblingId);
        }
      });
      if (activeSiblingId) {
        collapsedExpansionIds.delete(activeSiblingId);
      }
    });
  }
  const activeExpansionId = baseState.activeExpansionId &&
    entryIds.has(baseState.activeExpansionId) &&
    !collapsedExpansionIds.has(baseState.activeExpansionId)
    ? baseState.activeExpansionId
    : args.entries.find((entry) => activeSiblingByParentContext[parentContextId(entry, args.anchorNodeId)] === entry.expansionId)?.expansionId ?? null;

  return {
    activeExpansionId,
    activeExpansionPath: activePathFor(activeExpansionId, entriesById),
    collapsedExpansionIds: Array.from(collapsedExpansionIds),
    activeSiblingByParentContext,
    blockPositions: Object.fromEntries(
      Object.entries(baseState.blockPositions ?? {}).filter(([expansionId]) => entryIds.has(expansionId)),
    ),
    lastChildStateByExpansionId: Object.fromEntries(
      Object.entries(baseState.lastChildStateByExpansionId ?? {}).filter(([expansionId]) => entryIds.has(expansionId)),
    ),
    contextMode: "ACTIVE_CHAIN",
  };
}

/**
 * 从完整流程图构造调用展开 registry。
 *
 * registry 是视图模型：depth、parent、collapsed/active sibling 等都在这里派生，
 * 不写入 GraphDocument 的语义元数据。
 */
export function buildFlowchartInvocationExpansionRegistry(args: {
  nodes: LinkGraphNode[];
  edges: LinkGraphEdge[];
  anchorNodeId?: string | null;
  sceneState?: Partial<FlowchartInvocationExpansionSceneState> | null;
  defaultCollapseSiblings?: boolean;
}): FlowchartInvocationExpansionRegistry {
  const nodeIndex = new Map(args.nodes.map((node) => [node.id, node]));
  const edgeIndex = new Map(args.edges.map((edge) => [edge.id, edge]));
  const nodeIndexOrder = new Map(args.nodes.map((node, index) => [node.id, index]));
  const draftsById = new Map<string, FlowchartInvocationExpansionDraft>();

  args.nodes.forEach((node) => {
    const expansionId = expansionIdOf(node);
    if (!expansionId) {
      return;
    }
    const draft = expansionDraftFor(draftsById, expansionId);
    draft.taggedNodes.push(node);
    draft.ownedNodeIds.add(node.id);
  });
  args.edges.forEach((edge) => {
    const expansionId = expansionIdOf(edge);
    if (!expansionId) {
      return;
    }
    const draft = expansionDraftFor(draftsById, expansionId);
    draft.taggedEdges.push(edge);
    draft.ownedEdgeIds.add(edge.id);
  });

  const ownerExpansionIdByNodeId = new Map<string, string>();
  draftsById.forEach((draft) => {
    draft.ownedNodeIds.forEach((nodeId) => {
      ownerExpansionIdByNodeId.set(nodeId, draft.expansionId);
      const node = nodeIndex.get(nodeId);
      if (!node) {
        return;
      }
      projectedFromNodeIds(node).forEach((aliasNodeId) => {
        ownerExpansionIdByNodeId.set(aliasNodeId, draft.expansionId);
      });
    });
  });

  const entryDrafts = Array.from(draftsById.values());
  const entryStubs: FlowchartInvocationExpansionEntry[] = entryDrafts.map((draft) => {
    const metadataCandidates = [...draft.taggedNodes, ...draft.taggedEdges];
    const sourceInvocationNodeId = firstMetadataValue(metadataCandidates, EXPANSION_SOURCE_INVOCATION_NODE_ID_KEY);
    const explicitRootNodeId = firstMetadataValue(metadataCandidates, EXPANSION_ROOT_NODE_ID_KEY);
    const ownedNodes = Array.from(draft.ownedNodeIds).map((nodeId) => nodeIndex.get(nodeId)).filter((node): node is LinkGraphNode => Boolean(node));
    const ownedEdges = Array.from(draft.ownedEdgeIds).map((edgeId) => edgeIndex.get(edgeId)).filter((edge): edge is LinkGraphEdge => Boolean(edge));
    const ownedNodeIdSet = new Set(draft.ownedNodeIds);
    const rootNodeId = explicitRootNodeId
      ?? resolveExpansionRootNodeId(ownedNodes, ownedEdges.filter((edge) => ownedNodeIdSet.has(edge.source) && ownedNodeIdSet.has(edge.target)));
    const callEdgeIds = args.edges
      .filter((edge) => edge.type === "CALL" && (
        expansionIdOf(edge) === draft.expansionId ||
        (
          sourceInvocationNodeId !== null &&
          edge.source === sourceInvocationNodeId &&
          (
            ownedNodeIdSet.has(edge.target) ||
            (rootNodeId !== null && edge.target === rootNodeId)
          )
        )
      ))
      .map((edge) => edge.id);
    const callEdgeIdSet = new Set(callEdgeIds);
    const borrowedNodeIds = new Set<string>();
    if (rootNodeId && nodeIndex.has(rootNodeId) && !ownedNodeIdSet.has(rootNodeId)) {
      borrowedNodeIds.add(rootNodeId);
    }
    ownedEdges
      .filter((edge) => !callEdgeIdSet.has(edge.id))
      .forEach((edge) => {
        [edge.source, edge.target].forEach((nodeId) => {
          if (nodeIndex.has(nodeId) && !ownedNodeIdSet.has(nodeId)) {
            borrowedNodeIds.add(nodeId);
          }
        });
      });
    const boundaryNodeIds = new Set([...ownedNodeIdSet, ...borrowedNodeIds]);
    const internalEdgeIds = args.edges
      .filter((edge) => boundaryNodeIds.has(edge.source) && boundaryNodeIds.has(edge.target) && !callEdgeIdSet.has(edge.id))
      .map((edge) => edge.id);
    const parentExpansionId = sourceInvocationNodeId ? ownerExpansionIdByNodeId.get(sourceInvocationNodeId) ?? null : null;
    return {
      expansionId: draft.expansionId,
      sourceInvocationNodeId,
      targetSignature: firstMetadataValue(metadataCandidates, EXPANSION_TARGET_SIGNATURE_KEY),
      rootNodeId,
      createdAt: firstMetadataValue(metadataCandidates, EXPANSION_CREATED_AT_KEY),
      parentExpansionId: parentExpansionId === draft.expansionId ? null : parentExpansionId,
      depth: 1,
      ownedNodeIds: Array.from(draft.ownedNodeIds),
      borrowedNodeIds: Array.from(borrowedNodeIds),
      ownedEdgeIds: Array.from(draft.ownedEdgeIds),
      callEdgeIds,
      internalEdgeIds,
      childExpansionIds: [],
      state: "expanded" as const,
      summary: {
        nodeCount: 0,
        branchCount: 0,
        returnCount: 0,
        childExpansionCount: 0,
        hasBorrowedRoot: Boolean(rootNodeId && borrowedNodeIds.has(rootNodeId)),
        approximate: false,
      },
      warnings: [] as string[],
    } satisfies FlowchartInvocationExpansionEntry;
  });

  const stubsById = new Map(entryStubs.map((entry) => [entry.expansionId, entry]));
  const depthFor = (entry: FlowchartInvocationExpansionEntry, visiting: Set<string> = new Set()): number => {
    const parentId = entry.parentExpansionId;
    if (!parentId) {
      return 1;
    }
    const parent = stubsById.get(parentId);
    if (!parent || visiting.has(entry.expansionId)) {
      entry.warnings.push("cycle-or-missing-parent");
      entry.parentExpansionId = null;
      return 1;
    }
    visiting.add(entry.expansionId);
    return depthFor(parent, visiting) + 1;
  };

  entryStubs.forEach((entry) => {
    entry.depth = depthFor(entry);
  });
  entryStubs.forEach((entry) => {
    if (!entry.parentExpansionId) {
      return;
    }
    const parent = stubsById.get(entry.parentExpansionId);
    if (parent) {
      parent.childExpansionIds.push(entry.expansionId);
    }
  });

  entryStubs.forEach((entry) => {
    const ownedNodes = entry.ownedNodeIds.map((nodeId) => nodeIndex.get(nodeId)).filter((node): node is LinkGraphNode => Boolean(node));
    entry.childExpansionIds = sortExpansionEntries(
      entry.childExpansionIds.map((expansionId) => stubsById.get(expansionId)).filter((child): child is FlowchartInvocationExpansionEntry => Boolean(child)),
      nodeIndexOrder,
    ).map((child) => child.expansionId);
    entry.summary = {
      nodeCount: entry.ownedNodeIds.length,
      branchCount: ownedNodes.filter(isBranchNode).length,
      returnCount: ownedNodes.filter(isReturnNode).length,
      childExpansionCount: entry.childExpansionIds.length,
      hasBorrowedRoot: Boolean(entry.rootNodeId && entry.borrowedNodeIds.includes(entry.rootNodeId)),
      approximate: false,
    };
  });

  const orderedEntries = sortExpansionEntries(entryStubs, nodeIndexOrder);
  const normalizedSceneState = normalizeInvocationExpansionSceneState({
    entries: orderedEntries,
    anchorNodeId: args.anchorNodeId,
    sceneState: args.sceneState,
    defaultCollapseSiblings: args.defaultCollapseSiblings,
  });
  const collapsedExpansionIds = new Set(normalizedSceneState.collapsedExpansionIds);
  orderedEntries.forEach((entry) => {
    entry.state = collapsedExpansionIds.has(entry.expansionId) ? "collapsed" : "expanded";
  });
  const entriesById = Object.fromEntries(orderedEntries.map((entry) => [entry.expansionId, entry]));
  const parentContextIdsByExpansionId = Object.fromEntries(
    orderedEntries.map((entry) => [entry.expansionId, parentContextId(entry, args.anchorNodeId)]),
  );
  const childExpansionIdsByParentContext: Record<string, string[]> = {};
  orderedEntries.forEach((entry) => {
    const contextId = parentContextIdsByExpansionId[entry.expansionId]!;
    childExpansionIdsByParentContext[contextId] = [
      ...(childExpansionIdsByParentContext[contextId] ?? []),
      entry.expansionId,
    ];
  });

  return {
    entries: orderedEntries,
    entriesById,
    parentContextIdsByExpansionId,
    childExpansionIdsByParentContext,
    sceneState: normalizedSceneState,
  };
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
