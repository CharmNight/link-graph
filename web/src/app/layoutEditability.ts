import type { LinkGraphNode } from "./types";

export function canEditNodeLayout(node: LinkGraphNode): boolean {
  if (node.metadata?.["linkGraph.manual"] === "true") {
    return true;
  }
  switch (node.sourceTag) {
    case "DESIGN_BASELINE":
    case "DRAFT_MANUAL":
    case "DRAFT_AI":
      return true;
    default:
      return false;
  }
}
