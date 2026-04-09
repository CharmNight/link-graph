type ManualNodeKind = "METHOD" | "DOC_PAGE";

const METHOD_ID_PATTERN = /^design:(\d+)$/;
const DOC_PAGE_ID_PATTERN = /^design-note:(\d+)$/;

function maxSuffix(nodes: Array<{ id: string }>, pattern: RegExp): number {
  return nodes.reduce((maxValue, node) => {
    const matched = pattern.exec(node.id);
    if (!matched) {
      return maxValue;
    }
    const nextValue = Number(matched[1]);
    return Number.isFinite(nextValue) ? Math.max(maxValue, nextValue) : maxValue;
  }, 0);
}

export function nextManualNodeSequence(
  nodes: Array<{ id: string }>,
): number {
  return (
    Math.max(
      nodes.length,
      maxSuffix(nodes, METHOD_ID_PATTERN),
      maxSuffix(nodes, DOC_PAGE_ID_PATTERN),
    ) + 1
  );
}

export function createManualNodeIdAllocator(
  nodes: Array<{ id: string }>,
): (kind: ManualNodeKind) => string {
  let nextId = nextManualNodeSequence(nodes);

  return (kind: ManualNodeKind) => {
    if (kind === "DOC_PAGE") {
      return `design-note:${nextId++}`;
    }
    return `design:${nextId++}`;
  };
}
