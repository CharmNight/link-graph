import type { DraftCompareStatus } from "../types";

/**
 * 解析节点的高亮 className。
 *
 * 综合多个高亮来源决定一个节点应携带哪些高亮类：
 * - 讲解焦点（is-explanation-focus）：链路讲解中正在被聚焦的节点；
 * - 草稿变更（is-draft-change）：被草稿改动影响的节点；
 * - 草稿比对状态（is-draft-compare-{status}）：差异比对下的状态颜色。
 *
 * 多个高亮可以叠加，CSS 按优先级选择展示哪种视觉。
 *
 * @param args 高亮来源与基础 className
 * @return 拼接后的 className 字符串（可能为空）
 */
export function resolveGraphNodeHighlightClassName(args: {
  baseClassName?: string;
  nodeId: string;
  explanationFocusNodeId?: string | null;
  draftChangedNodeIdSet?: ReadonlySet<string>;
  draftCompareStatus?: DraftCompareStatus;
}): string {
  // 起始：用户传入的基础 className（可为空）
  const classes = args.baseClassName?.trim() ? [args.baseClassName.trim()] : [];
  // 当前节点是讲解焦点时加上对应类
  if (args.explanationFocusNodeId && args.nodeId === args.explanationFocusNodeId) {
    classes.push("is-explanation-focus");
  }
  // 当前节点在草稿变更集合中时加上对应类
  if (args.draftChangedNodeIdSet?.has(args.nodeId)) {
    classes.push("is-draft-change");
  }
  // 携带草稿比对状态时按状态生成类名
  if (args.draftCompareStatus) {
    classes.push(`is-draft-compare-${args.draftCompareStatus.toLowerCase()}`);
  }
  return classes.join(" ").trim();
}
