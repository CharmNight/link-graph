import type { AnalysisDisplayMode, LinkGraphBootstrapState, LinkGraphDocument } from "./types";

const EMPTY_DOCUMENT: LinkGraphDocument = {
  nodes: [],
  edges: [],
};

export function resolveWorkingGraphDocument(
  state: LinkGraphBootstrapState,
  displayMode: AnalysisDisplayMode = state.analysisDisplayMode ?? "FACT_GRAPH",
): LinkGraphDocument {
  switch (displayMode) {
    case "FACT_GRAPH":
      return state.workingGraph ?? state.visibleGraph ?? EMPTY_DOCUMENT;
    case "FLOWCHART":
      return state.workingGraph ?? state.flowchartView?.fullGraph ?? state.visibleGraph ?? EMPTY_DOCUMENT;
    case "RESOURCE_RELATION_VIEW":
      return state.workingGraph ?? state.visibleGraph ?? EMPTY_DOCUMENT;
    default:
      return state.workingGraph ?? state.visibleGraph ?? EMPTY_DOCUMENT;
  }
}
