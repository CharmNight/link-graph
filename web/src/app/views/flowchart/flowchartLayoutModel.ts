import type { LinkGraphEdge, LinkGraphNode } from "../../types";

const EXPANSION_ID_KEY = "linkGraph.expansion.id";
const EXPANSION_SOURCE_INVOCATION_NODE_ID_KEY = "linkGraph.expansion.sourceInvocationNodeId";
const EXPANSION_ROOT_NODE_ID_KEY = "linkGraph.expansion.rootNodeId";

export interface FlowchartExpansionGroup {
  expansionId: string;
  sourceInvocationNodeId: string | null;
  rootNodeId: string | null;
  nodeIds: string[];
  callEdgeIds: string[];
  internalEdgeIds: string[];
}

export interface FlowchartLayoutModel {
  mainNodeIds: string[];
  expansionGroups: FlowchartExpansionGroup[];
}

function expansionIdOf(nodeOrEdge: Pick<LinkGraphNode | LinkGraphEdge, "metadata">): string | null {
  return nodeOrEdge.metadata?.[EXPANSION_ID_KEY]?.trim() || null;
}

function sourceInvocationNodeIdOf(node: LinkGraphNode): string | null {
  return node.metadata?.[EXPANSION_SOURCE_INVOCATION_NODE_ID_KEY]?.trim() || null;
}

function rootNodeIdOf(node: LinkGraphNode): string | null {
  return node.metadata?.[EXPANSION_ROOT_NODE_ID_KEY]?.trim() || null;
}

function resolveExpansionRootNodeId(nodes: LinkGraphNode[], internalEdges: LinkGraphEdge[]): string | null {
  const explicitRootNodeId = nodes.map(rootNodeIdOf).find((nodeId): nodeId is string => Boolean(nodeId));
  if (explicitRootNodeId && nodes.some((node) => node.id === explicitRootNodeId)) {
    return explicitRootNodeId;
  }
  const methodNode = nodes.find((node) => node.type === "METHOD");
  if (methodNode) {
    return methodNode.id;
  }
  const nodeIds = new Set(nodes.map((node) => node.id));
  const internalTargetIds = new Set(internalEdges.map((edge) => edge.target).filter((targetId) => nodeIds.has(targetId)));
  return nodes.find((node) => !internalTargetIds.has(node.id))?.id ?? nodes[0]?.id ?? null;
}

export function buildFlowchartLayoutModel(
  nodes: LinkGraphNode[],
  edges: LinkGraphEdge[],
): FlowchartLayoutModel {
  const nodeIndexOrder = new Map(nodes.map((node, index) => [node.id, index]));
  const expansionNodeIds = new Set<string>();
  const nodesByExpansionId = new Map<string, LinkGraphNode[]>();

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

  const mainNodeIds = nodes
    .filter((node) => !expansionNodeIds.has(node.id))
    .map((node) => node.id);

  const expansionGroups = Array.from(nodesByExpansionId.entries()).map(([expansionId, groupNodes]) => {
    const groupNodeIds = new Set(groupNodes.map((node) => node.id));
    const sourceInvocationNodeId = groupNodes.map(sourceInvocationNodeIdOf).find((nodeId): nodeId is string => Boolean(nodeId)) ?? null;
    const callEdgeIds = edges
      .filter((edge) => edge.type === "CALL" && (
        expansionIdOf(edge) === expansionId ||
        (sourceInvocationNodeId !== null && edge.source === sourceInvocationNodeId && groupNodeIds.has(edge.target))
      ))
      .map((edge) => edge.id);
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
