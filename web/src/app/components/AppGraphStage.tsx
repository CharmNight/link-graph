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

/**
 * AppGraphStage 的公共 props：所有视图都需要的视图文档集合。
 */
type AppGraphStageCommonProps = {
  /** 事实图视图。 */
  factGraphView: FactGraphViewDocument;
  /** 流程图视图（已做可读性裁剪的版本，用于实际渲染）。 */
  presentedFlowchartView: FlowchartViewDocument;
  /** 流程图视图（原始布局版本，用于布局算法）。 */
  flowchartView: FlowchartViewDocument;
  /** 资源关系图视图。 */
  resourceRelationView: ResourceRelationViewDocument;
  /** 架构图视图。 */
  architectureGraphView: ArchitectureGraphViewDocument;
  /** 类图视图。 */
  classDiagramView: ClassDiagramViewDocument;
  /** 审查图视图。 */
  reviewGraphView: ReviewGraphViewDocument;
};

/**
 * AppGraphStage 的联合 props：按展示模式区分 Editable / IndexedReadonly 两类 stageProps。
 * 这种联合让调用方按模式分发时类型安全。
 */
type AppGraphStageProps =
  | (AppGraphStageCommonProps & { analysisDisplayMode: "FACT_GRAPH"; stageProps: EditableStageProps })
  | (AppGraphStageCommonProps & { analysisDisplayMode: "FLOWCHART"; stageProps: EditableStageProps })
  | (AppGraphStageCommonProps & { analysisDisplayMode: "RESOURCE_RELATION_VIEW"; stageProps: EditableStageProps })
  | (AppGraphStageCommonProps & { analysisDisplayMode: "ARCHITECTURE_GRAPH"; stageProps: IndexedReadonlyStageProps })
  | (AppGraphStageCommonProps & { analysisDisplayMode: "CLASS_DIAGRAM"; stageProps: IndexedReadonlyStageProps })
  | (AppGraphStageCommonProps & { analysisDisplayMode: "REVIEW_GRAPH"; stageProps: IndexedReadonlyStageProps });

/**
 * 图谱舞台组件：按当前展示模式分发到具体的视图组件。
 *
 * 这是图谱视图的统一入口，让上层只需要决定"展示哪个模式"，
 * 不需要关心每个模式对应哪个组件。本组件内部按 analysisDisplayMode 做分发。
 */
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
  // 兜底：FACT_GRAPH（事实图）
  return (
    <FactGraphView
      {...props.stageProps}
      view={props.factGraphView}
    />
  );
}
