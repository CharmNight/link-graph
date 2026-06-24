import type { LinkGraphNode } from "./types";

/**
 * 可基于签名定位源码的节点类型集合。当节点缺少显式位置信息时，
 * 只要节点类型属于此集合（例如方法、类、接口等有符号定义的实体），
 * 后端就可以通过 PSI 按签名反查定位。
 */
const SIGNATURE_NAVIGABLE_NODE_TYPES = new Set([
  "METHOD",
  "CLASS",
  "INTERFACE",
  "ENUM",
  "ANNOTATION",
  "RECORD",
  "OBJECT",
  "EXTERNAL_CLASS",
]);

/**
 * 代码节点优先按显式位置跳转；
 * 若位置缺失，但方法/类签名还在，则允许后端按 PSI 继续兜底定位。
 */
export function canNavigateToSource(node: Pick<LinkGraphNode, "type" | "location" | "signature" | "metadata">): boolean {
  // 节点自带精确位置（文件路径+偏移等）时一定可跳转
  if (Boolean(node.location?.trim())) {
    return true;
  }
  // 元数据中带有文件路径或虚拟文件 URL 时也可跳转，覆盖导入图等无 location 但记录了来源的场景
  if (Boolean(node.metadata?.["source.navigation.filePath"]?.trim() || node.metadata?.["source.navigation.virtualFileUrl"]?.trim())) {
    return true;
  }
  // 元数据里允许覆盖节点类型（例如把 EXTERNAL_CLASS 视为 CLASS 处理）
  const anchorNodeType = node.metadata?.["source.navigation.nodeType"]?.trim() || node.type;
  // 类型属于可签名定位的实体且元数据提供了签名，则交由后端 PSI 兜底
  if (
    Boolean(node.metadata?.["source.navigation.signature"]?.trim()) &&
    SIGNATURE_NAVIGABLE_NODE_TYPES.has(anchorNodeType)
  ) {
    return true;
  }
  // 兜底：节点类型本身可签名定位、且节点上有 signature 字段
  return SIGNATURE_NAVIGABLE_NODE_TYPES.has(node.type) && Boolean(node.signature?.trim());
}
