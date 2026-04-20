import type { DraftCompareStatus } from "../types";

export function resolveGraphNodeHighlightClassName(args: {
  baseClassName?: string;
  nodeId: string;
  explanationFocusNodeId?: string | null;
  draftChangedNodeIdSet?: ReadonlySet<string>;
  draftCompareStatus?: DraftCompareStatus;
}): string {
  const classes = args.baseClassName?.trim() ? [args.baseClassName.trim()] : [];
  if (args.explanationFocusNodeId && args.nodeId === args.explanationFocusNodeId) {
    classes.push("is-explanation-focus");
  }
  if (args.draftChangedNodeIdSet?.has(args.nodeId)) {
    classes.push("is-draft-change");
  }
  if (args.draftCompareStatus) {
    classes.push(`is-draft-compare-${args.draftCompareStatus.toLowerCase()}`);
  }
  return classes.join(" ").trim();
}
