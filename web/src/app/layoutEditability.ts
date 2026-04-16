import type { AnalysisDisplayMode, LinkGraphNode } from "./types";

export function canEditNodeLayout(
  node: LinkGraphNode,
  analysisDisplayMode?: AnalysisDisplayMode,
): boolean {
  if (analysisDisplayMode === "FLOWCHART") {
    return true;
  }
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
