import type { LinkGraphNode } from "./types";

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
  if (Boolean(node.location?.trim())) {
    return true;
  }
  if (Boolean(node.metadata?.["source.navigation.filePath"]?.trim() || node.metadata?.["source.navigation.virtualFileUrl"]?.trim())) {
    return true;
  }
  const anchorNodeType = node.metadata?.["source.navigation.nodeType"]?.trim() || node.type;
  if (
    Boolean(node.metadata?.["source.navigation.signature"]?.trim()) &&
    SIGNATURE_NAVIGABLE_NODE_TYPES.has(anchorNodeType)
  ) {
    return true;
  }
  return SIGNATURE_NAVIGABLE_NODE_TYPES.has(node.type) && Boolean(node.signature?.trim());
}
