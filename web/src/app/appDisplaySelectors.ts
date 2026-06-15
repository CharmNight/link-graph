import type {
  AnalysisDisplayMode,
  GraphProjectionIndex,
  IndexedGraphSummary,
  LinkGraphDocument,
} from "./types";
import type { WorkflowStage } from "./workflow/workflowStage";

export type AssistantStageTarget = "explanation" | "qa" | "draft" | "code";

interface DisplayModeGraphView {
  visibleGraph: LinkGraphDocument;
  fullGraph: LinkGraphDocument;
  anchorNodeId?: string | null;
}

export interface DisplayModeGraphDocuments {
  factGraphView: DisplayModeGraphView;
  flowchartView: DisplayModeGraphView;
  resourceRelationView: DisplayModeGraphView;
  architectureGraphView: DisplayModeGraphView;
  classDiagramView: DisplayModeGraphView;
  reviewGraphView: DisplayModeGraphView;
}

export function activeIndexedGraphSummary(
  analysisDisplayMode: AnalysisDisplayMode,
  architectureSummary: IndexedGraphSummary | null,
  classDiagramSummary: IndexedGraphSummary | null,
  reviewSummary: IndexedGraphSummary | null,
): IndexedGraphSummary | null {
  switch (analysisDisplayMode) {
    case "ARCHITECTURE_GRAPH":
      return architectureSummary;
    case "CLASS_DIAGRAM":
      return classDiagramSummary;
    case "REVIEW_GRAPH":
      return reviewSummary;
    default:
      return null;
  }
}

export function activeProjectionIndex(
  analysisDisplayMode: AnalysisDisplayMode,
  factProjectionIndex: GraphProjectionIndex | null | undefined,
  flowchartProjectionIndex: GraphProjectionIndex | null | undefined,
  resourceProjectionIndex: GraphProjectionIndex | null | undefined,
  architectureProjectionIndex: GraphProjectionIndex | null | undefined,
  classDiagramProjectionIndex: GraphProjectionIndex | null | undefined,
  reviewProjectionIndex: GraphProjectionIndex | null | undefined,
): GraphProjectionIndex | null {
  switch (analysisDisplayMode) {
    case "FLOWCHART":
      return flowchartProjectionIndex ?? null;
    case "RESOURCE_RELATION_VIEW":
      return resourceProjectionIndex ?? null;
    case "ARCHITECTURE_GRAPH":
      return architectureProjectionIndex ?? null;
    case "CLASS_DIAGRAM":
      return classDiagramProjectionIndex ?? null;
    case "REVIEW_GRAPH":
      return reviewProjectionIndex ?? null;
    case "FACT_GRAPH":
    default:
      return factProjectionIndex ?? null;
  }
}

function activeGraphViewForDisplayMode(
  analysisDisplayMode: AnalysisDisplayMode,
  documents: DisplayModeGraphDocuments,
): DisplayModeGraphView {
  switch (analysisDisplayMode) {
    case "FLOWCHART":
      return documents.flowchartView;
    case "RESOURCE_RELATION_VIEW":
      return documents.resourceRelationView;
    case "ARCHITECTURE_GRAPH":
      return documents.architectureGraphView;
    case "CLASS_DIAGRAM":
      return documents.classDiagramView;
    case "REVIEW_GRAPH":
      return documents.reviewGraphView;
    case "FACT_GRAPH":
    default:
      return documents.factGraphView;
  }
}

export function activeVisibleGraphForDisplayMode(
  analysisDisplayMode: AnalysisDisplayMode,
  documents: DisplayModeGraphDocuments,
): LinkGraphDocument {
  return activeGraphViewForDisplayMode(analysisDisplayMode, documents).visibleGraph;
}

export function activeFullGraphForDisplayMode(
  analysisDisplayMode: AnalysisDisplayMode,
  documents: DisplayModeGraphDocuments,
): LinkGraphDocument {
  return activeGraphViewForDisplayMode(analysisDisplayMode, documents).fullGraph;
}

export function activeAnchorNodeIdForDisplayMode(
  analysisDisplayMode: AnalysisDisplayMode,
  documents: DisplayModeGraphDocuments,
): string | null {
  return activeGraphViewForDisplayMode(analysisDisplayMode, documents).anchorNodeId ?? null;
}

export function workflowStageToAssistantTarget(stage: WorkflowStage): AssistantStageTarget {
  switch (stage) {
    case "understand":
      return "explanation";
    case "qa":
      return "qa";
    case "draft":
      return "draft";
    case "code":
      return "code";
    case "evidence":
      return "qa";
  }
}

export function isProjectStructureDisplay(
  analysisDisplayMode: AnalysisDisplayMode,
  architectureSummary: IndexedGraphSummary | null | undefined,
): boolean {
  return analysisDisplayMode === "ARCHITECTURE_GRAPH" && architectureSummary?.scopeKind === "PROJECT";
}
