// 流程图布局模型：把节点分为"主节点"和若干"调用展开组"。
// 调用展开：用户在流程图上点开一个方法调用后，被调方法的内部流程会被展开成一组节点，
// 这组节点在布局上要作为一个整体被处理（位置相对、整体移动等）。
import type { InvocationExpansionRegistry as ServerInvocationExpansionRegistry, LinkGraphNode } from "../../types";

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
  sceneState: FlowchartInvocationExpansionSceneState;
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
 * 把服务端权威调用展开 registry 转成流程图视图模型。
 *
 * parent/depth/owned/call/internal 边界由服务端统一计算；前端只根据当前画布节点补充摘要与折叠状态。
 */
export function buildFlowchartInvocationExpansionRegistry(args: {
  nodes: LinkGraphNode[];
  anchorNodeId?: string | null;
  sceneState?: Partial<FlowchartInvocationExpansionSceneState> | null;
  defaultCollapseSiblings?: boolean;
  serverRegistry?: ServerInvocationExpansionRegistry | null;
}): FlowchartInvocationExpansionRegistry {
  const serverRegistry = args.serverRegistry ?? { entries: [] };
  const nodeIndexOrder = new Map(args.nodes.map((node, index) => [node.id, index]));
  const nodeIndex = new Map(args.nodes.map((node) => [node.id, node]));
  const entryStubs: FlowchartInvocationExpansionEntry[] = serverRegistry.entries.map((entry) => {
    const ownedNodes = entry.ownedNodeIds.map((nodeId) => nodeIndex.get(nodeId)).filter((node): node is LinkGraphNode => Boolean(node));
    return {
      expansionId: entry.expansionId,
      sourceInvocationNodeId: entry.sourceInvocationNodeId ?? null,
      targetSignature: entry.targetSignature ?? null,
      rootNodeId: entry.rootNodeId ?? null,
      createdAt: entry.createdAt ?? null,
      parentExpansionId: entry.parentExpansionId ?? null,
      depth: Math.max(1, entry.depth),
      ownedNodeIds: [...entry.ownedNodeIds],
      borrowedNodeIds: [...entry.borrowedNodeIds],
      callEdgeIds: [...entry.callEdgeIds],
      internalEdgeIds: [...entry.internalEdgeIds],
      childExpansionIds: [...entry.childExpansionIds],
      state: "expanded" as const,
      summary: {
        nodeCount: entry.ownedNodeIds.length,
        branchCount: ownedNodes.filter(isBranchNode).length,
        returnCount: ownedNodes.filter(isReturnNode).length,
        childExpansionCount: entry.childExpansionIds.length,
        hasBorrowedRoot: Boolean(entry.rootNodeId && entry.borrowedNodeIds.includes(entry.rootNodeId)),
        approximate: false,
      },
      warnings: [...entry.warnings],
    } satisfies FlowchartInvocationExpansionEntry;
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

  return {
    entries: orderedEntries,
    entriesById,
    sceneState: normalizedSceneState,
  };
}
