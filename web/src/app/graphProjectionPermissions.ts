import type {
  GraphEditCommandKind,
  GraphProjectionIndex,
  LinkGraphNode,
} from "./types";

export function canEditProjectedNode(
  projectionIndex: GraphProjectionIndex | null | undefined,
  nodeId: string,
  command: GraphEditCommandKind,
): boolean {
  if (!projectionIndex) {
    return true;
  }
  if (command === "ADD_NODE") {
    return true;
  }
  const mapping = projectionIndex?.nodeMappings?.[nodeId];
  return mapping?.editableCommandKinds.includes(command) ?? false;
}

export function canEditProjectedEdge(
  projectionIndex: GraphProjectionIndex | null | undefined,
  edgeId: string,
  command: GraphEditCommandKind,
): boolean {
  if (!projectionIndex) {
    return true;
  }
  const mapping = projectionIndex?.edgeMappings?.[edgeId];
  return mapping?.editableCommandKinds.includes(command) ?? false;
}

export function canEditProjectedNodeLayout(
  projectionIndex: GraphProjectionIndex | null | undefined,
  node: LinkGraphNode,
): boolean {
  return canEditProjectedNode(projectionIndex, node.id, "UPDATE_NODE")
    || isIndexedReadonlyLayoutNode(projectionIndex, node.id);
}

function isIndexedReadonlyLayoutNode(
  projectionIndex: GraphProjectionIndex | null | undefined,
  nodeId: string,
): boolean {
  const mapping = projectionIndex?.nodeMappings?.[nodeId];
  return mapping?.mappingKind === "INDEXED_READONLY";
}
