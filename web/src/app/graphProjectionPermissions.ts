import type {
  GraphEditCommandKind,
  GraphProjectionIndex,
  LinkGraphNode,
} from "./types";

/**
 * 判断在当前投影下，指定节点是否可执行某种编辑命令（增/删/改等）。
 *
 * 规则：
 * - 没有投影索引（例如处于非投影视图）时，所有命令默认放行；
 * - ADD_NODE 始终允许，因为新增节点不依赖投影映射；
 * - 其他命令需要查询投影中该节点的映射信息，且命令类型在节点允许的命令集合内。
 */
export function canEditProjectedNode(
  projectionIndex: GraphProjectionIndex | null | undefined,
  nodeId: string,
  command: GraphEditCommandKind,
): boolean {
  // 无投影上下文视为自由编辑模式
  if (!projectionIndex) {
    return true;
  }
  // 新增节点不会破坏已有映射，统一放行
  if (command === "ADD_NODE") {
    return true;
  }
  // 已存在节点：依据投影中预先声明的可编辑命令集合判定
  const mapping = projectionIndex?.nodeMappings?.[nodeId];
  return mapping?.editableCommandKinds.includes(command) ?? false;
}

/**
 * 判断在当前投影下，指定边是否可执行某种编辑命令。
 *
 * 规则：
 * - 没有投影索引时所有命令放行；
 * - 有投影时必须查询该边的映射，且命令在允许集合内。
 * 与节点不同的是边没有"新增一律放行"的特例，因为新增边往往受节点约束。
 */
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

/**
 * 判断节点的布局（位置、尺寸）是否可在当前投影下手工调整。
 *
 * 满足以下任一条件即允许：
 * 1) 节点本身允许 UPDATE_NODE 命令（即内容可编辑，布局自然也可编辑）；
 * 2) 节点在投影中被标记为 INDEXED_READONLY —— 这类节点内容来自索引、不可改，
 *    但出于排版考虑允许用户拖动其位置以调整可视化效果。
 */
export function canEditProjectedNodeLayout(
  projectionIndex: GraphProjectionIndex | null | undefined,
  node: LinkGraphNode,
): boolean {
  return canEditProjectedNode(projectionIndex, node.id, "UPDATE_NODE")
    || isIndexedReadonlyLayoutNode(projectionIndex, node.id);
}

/**
 * 判断节点在投影中的映射是否为"索引来源的只读节点"。
 * 这类节点的内容固定（来自代码索引），但其位置允许手工调整，
 * 用于在保证数据正确性的同时给用户保留排版自由度。
 */
function isIndexedReadonlyLayoutNode(
  projectionIndex: GraphProjectionIndex | null | undefined,
  nodeId: string,
): boolean {
  const mapping = projectionIndex?.nodeMappings?.[nodeId];
  return mapping?.mappingKind === "INDEXED_READONLY";
}
