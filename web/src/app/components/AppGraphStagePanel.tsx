import { activeIndexedGraphSummary } from "../appDisplaySelectors";
import type {
  AnalysisDisplayMode,
  ArchitectureGraphViewDocument,
  ClassDiagramViewDocument,
  DraftCompareProjection,
  FactGraphViewDocument,
  FlowchartViewDocument,
  LinkGraphDocument,
  ResourceRelationViewDocument,
  ReviewGraphViewDocument,
} from "../types";
import type { EditableStageProps, IndexedReadonlyStageProps } from "../views/viewStageProps";
import type { CodeDiffStatus } from "../workbenchStatusModel";
import { AppGraphStage } from "./AppGraphStage";
import { GraphStageFooter } from "./GraphStageFooter";
import { GraphStageHeader } from "./GraphStageHeader";

interface AppGraphStagePanelProps {
  analysisDisplayMode: AnalysisDisplayMode;
  activeViewGraph: LinkGraphDocument;
  fullNodeCount: number;
  hasExplanationFocus: boolean;
  draftChangedNodeCount: number;
  draftCompareProjection: DraftCompareProjection | null;
  codeDiffStatus: CodeDiffStatus;
  editableStageProps: EditableStageProps;
  indexedReadonlyStageProps: IndexedReadonlyStageProps;
  factGraphView: FactGraphViewDocument;
  presentedFlowchartView: FlowchartViewDocument;
  flowchartView: FlowchartViewDocument;
  resourceRelationView: ResourceRelationViewDocument;
  architectureGraphView: ArchitectureGraphViewDocument;
  classDiagramView: ClassDiagramViewDocument;
  reviewGraphView: ReviewGraphViewDocument;
  onRequestAnalysisDisplayMode: (mode: AnalysisDisplayMode) => void;
}

export function AppGraphStagePanel({
  analysisDisplayMode,
  activeViewGraph,
  fullNodeCount,
  hasExplanationFocus,
  draftChangedNodeCount,
  draftCompareProjection,
  codeDiffStatus,
  editableStageProps,
  indexedReadonlyStageProps,
  factGraphView,
  presentedFlowchartView,
  flowchartView,
  resourceRelationView,
  architectureGraphView,
  classDiagramView,
  reviewGraphView,
  onRequestAnalysisDisplayMode,
}: AppGraphStagePanelProps) {
  const commonViewProps = {
    factGraphView,
    presentedFlowchartView,
    flowchartView,
    resourceRelationView,
    architectureGraphView,
    classDiagramView,
    reviewGraphView,
  };

  return (
    <section className="graph-stage" aria-label="图谱舞台">
      <GraphStageHeader
        analysisDisplayMode={analysisDisplayMode}
        onRequestAnalysisDisplayMode={onRequestAnalysisDisplayMode}
      />
      <div className="graph-stage-canvas">
        {analysisDisplayMode === "ARCHITECTURE_GRAPH" ||
        analysisDisplayMode === "CLASS_DIAGRAM" ||
        analysisDisplayMode === "REVIEW_GRAPH" ? (
          <AppGraphStage
            analysisDisplayMode={analysisDisplayMode}
            stageProps={indexedReadonlyStageProps}
            {...commonViewProps}
          />
        ) : (
          <AppGraphStage
            analysisDisplayMode={analysisDisplayMode}
            stageProps={editableStageProps}
            {...commonViewProps}
          />
        )}
      </div>
      <GraphStageFooter
        analysisDisplayMode={analysisDisplayMode}
        activeViewGraph={activeViewGraph}
        fullNodeCount={fullNodeCount}
        hasExplanationFocus={hasExplanationFocus}
        draftChangedNodeCount={draftChangedNodeCount}
        draftCompareProjection={draftCompareProjection}
        indexedSummary={activeIndexedGraphSummary(
          analysisDisplayMode,
          architectureGraphView.summary.indexed ?? null,
          classDiagramView.summary.indexed ?? null,
          reviewGraphView.summary.indexed ?? null,
        )}
        codeDiffStatus={codeDiffStatus}
      />
    </section>
  );
}
