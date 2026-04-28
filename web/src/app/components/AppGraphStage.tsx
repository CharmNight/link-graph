import type {
  AnalysisDisplayMode,
  FactGraphViewDocument,
  FlowchartViewDocument,
  ResourceRelationViewDocument,
} from "../types";
import { FactGraphView } from "../views/fact/FactGraphView";
import { FlowchartView } from "../views/flowchart/FlowchartView";
import { ResourceRelationView } from "../views/resource/ResourceRelationView";
import type { ViewStageProps } from "../views/viewStageProps";

interface AppGraphStageProps {
  analysisDisplayMode: AnalysisDisplayMode;
  stageProps: ViewStageProps;
  factGraphView: FactGraphViewDocument;
  presentedFlowchartView: FlowchartViewDocument;
  flowchartView: FlowchartViewDocument;
  resourceRelationView: ResourceRelationViewDocument;
}

export function AppGraphStage({
  analysisDisplayMode,
  stageProps,
  factGraphView,
  presentedFlowchartView,
  flowchartView,
  resourceRelationView,
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
  return (
    <FactGraphView
      {...stageProps}
      view={factGraphView}
    />
  );
}
