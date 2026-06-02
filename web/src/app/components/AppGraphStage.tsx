import type {
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
import type { EditableStageProps, IndexedReadonlyStageProps } from "../views/viewStageProps";

type AppGraphStageCommonProps = {
  factGraphView: FactGraphViewDocument;
  presentedFlowchartView: FlowchartViewDocument;
  flowchartView: FlowchartViewDocument;
  resourceRelationView: ResourceRelationViewDocument;
  architectureGraphView: ArchitectureGraphViewDocument;
  classDiagramView: ClassDiagramViewDocument;
  reviewGraphView: ReviewGraphViewDocument;
};

type AppGraphStageProps =
  | (AppGraphStageCommonProps & { analysisDisplayMode: "FACT_GRAPH"; stageProps: EditableStageProps })
  | (AppGraphStageCommonProps & { analysisDisplayMode: "FLOWCHART"; stageProps: EditableStageProps })
  | (AppGraphStageCommonProps & { analysisDisplayMode: "RESOURCE_RELATION_VIEW"; stageProps: EditableStageProps })
  | (AppGraphStageCommonProps & { analysisDisplayMode: "ARCHITECTURE_GRAPH"; stageProps: IndexedReadonlyStageProps })
  | (AppGraphStageCommonProps & { analysisDisplayMode: "CLASS_DIAGRAM"; stageProps: IndexedReadonlyStageProps })
  | (AppGraphStageCommonProps & { analysisDisplayMode: "REVIEW_GRAPH"; stageProps: IndexedReadonlyStageProps });

export function AppGraphStage(props: AppGraphStageProps) {
  if (props.analysisDisplayMode === "FLOWCHART") {
    return (
      <FlowchartView
        {...props.stageProps}
        view={props.presentedFlowchartView}
        layoutView={props.flowchartView}
      />
    );
  }
  if (props.analysisDisplayMode === "RESOURCE_RELATION_VIEW") {
    return (
      <ResourceRelationView
        {...props.stageProps}
        view={props.resourceRelationView}
      />
    );
  }
  if (props.analysisDisplayMode === "ARCHITECTURE_GRAPH") {
    return (
      <ArchitectureGraphView
        {...props.stageProps}
        view={props.architectureGraphView}
      />
    );
  }
  if (props.analysisDisplayMode === "CLASS_DIAGRAM") {
    return (
      <ClassDiagramView
        {...props.stageProps}
        view={props.classDiagramView}
      />
    );
  }
  if (props.analysisDisplayMode === "REVIEW_GRAPH") {
    return (
      <ReviewGraphView
        {...props.stageProps}
        view={props.reviewGraphView}
      />
    );
  }
  return (
    <FactGraphView
      {...props.stageProps}
      view={props.factGraphView}
    />
  );
}
