import type {
  AnalysisDisplayMode,
  ArchitectureGraphViewDocument,
  ClassDiagramViewDocument,
  FactGraphViewDocument,
  FlowchartViewDocument,
  ResourceRelationViewDocument,
  ReviewGraphViewDocument,
} from "../types";
import { FactGraphView } from "../views/fact/FactGraphView";
import { FlowchartView } from "../views/flowchart/FlowchartView";
import { ResourceRelationView } from "../views/resource/ResourceRelationView";
import { ArchitectureGraphView } from "../views/architecture/ArchitectureGraphView";
import { ClassDiagramView } from "../views/class-diagram/ClassDiagramView";
import { ReviewGraphView } from "../views/review/ReviewGraphView";
import type { ViewStageProps } from "../views/viewStageProps";

interface AppGraphStageProps {
  analysisDisplayMode: AnalysisDisplayMode;
  stageProps: ViewStageProps;
  factGraphView: FactGraphViewDocument;
  presentedFlowchartView: FlowchartViewDocument;
  flowchartView: FlowchartViewDocument;
  resourceRelationView: ResourceRelationViewDocument;
  architectureGraphView: ArchitectureGraphViewDocument;
  classDiagramView: ClassDiagramViewDocument;
  reviewGraphView: ReviewGraphViewDocument;
}

export function AppGraphStage({
  analysisDisplayMode,
  stageProps,
  factGraphView,
  presentedFlowchartView,
  flowchartView,
  resourceRelationView,
  architectureGraphView,
  classDiagramView,
  reviewGraphView,
}: AppGraphStageProps) {
  if (analysisDisplayMode === "FLOWCHART") {
    return (
      <FlowchartView
        {...stageProps}
        view={presentedFlowchartView}
        layoutView={flowchartView}
      />
    );
  }
  if (analysisDisplayMode === "RESOURCE_RELATION_VIEW") {
    return (
      <ResourceRelationView
        {...stageProps}
        view={resourceRelationView}
      />
    );
  }
  if (analysisDisplayMode === "ARCHITECTURE_GRAPH") {
    return (
      <ArchitectureGraphView
        {...stageProps}
        view={architectureGraphView}
      />
    );
  }
  if (analysisDisplayMode === "CLASS_DIAGRAM") {
    return (
      <ClassDiagramView
        {...stageProps}
        view={classDiagramView}
      />
    );
  }
  if (analysisDisplayMode === "REVIEW_GRAPH") {
    return (
      <ReviewGraphView
        {...stageProps}
        view={reviewGraphView}
      />
    );
  }
  return (
    <FactGraphView
      {...stageProps}
      view={factGraphView}
    />
  );
}
