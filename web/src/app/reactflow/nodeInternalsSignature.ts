import type { LinkGraphNode } from "../types";

function stableMetadataSignature(metadata?: Record<string, string>): string {
  if (!metadata) {
    return "";
  }
  return Object.entries(metadata)
    .sort(([left], [right]) => left.localeCompare(right))
    .map(([key, value]) => `${key}=${value}`)
    .join("\u0000");
}

export function reactFlowNodeInternalsSignature(node: LinkGraphNode): string {
  return [
    node.id,
    node.type,
    node.title,
    node.signature ?? "",
    node.location ?? "",
    node.doc ?? "",
    node.inputs.join("\u0000"),
    node.outputs.join("\u0000"),
    node.diffStatus ?? "",
    stableMetadataSignature(node.metadata),
  ].join("\u0001");
}
