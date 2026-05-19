import type { LinkGraphNode } from "./types";

/**
 * 代码节点优先按显式位置跳转；
 * 若位置缺失，但方法/类签名还在，则允许后端按 PSI 继续兜底定位。
 */
export function canNavigateToSource(node: Pick<LinkGraphNode, "type" | "location" | "signature">): boolean {
  if (Boolean(node.location?.trim())) {
    return true;
  }
  return (
    node.type === "METHOD" ||
    node.type === "CLASS" ||
    node.type === "INTERFACE" ||
    node.type === "ENUM" ||
    node.type === "ANNOTATION" ||
    node.type === "RECORD" ||
    node.type === "OBJECT" ||
    node.type === "EXTERNAL_CLASS"
  ) && Boolean(node.signature?.trim());
}
