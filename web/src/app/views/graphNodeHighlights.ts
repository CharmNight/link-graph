export function resolveGraphNodeHighlightClassName(args: {
  baseClassName?: string;
  nodeId: string;
  explanationFocusNodeId?: string | null;
  draftChangedNodeIdSet?: ReadonlySet<string>;
}): string {
  const classes = args.baseClassName?.trim() ? [args.baseClassName.trim()] : [];
  if (args.explanationFocusNodeId && args.nodeId === args.explanationFocusNodeId) {
    classes.push("is-explanation-focus");
  }
  if (args.draftChangedNodeIdSet?.has(args.nodeId)) {
    classes.push("is-draft-change");
  }
  return classes.join(" ").trim();
}
