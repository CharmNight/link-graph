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

/**
 * AppGraphStagePanel 的入参。
 *
 * 把图谱舞台所需的全部上下文打包：视图文档、编辑回调、状态信息等。
 */
interface AppGraphStagePanelProps {
  /** 当前展示模式。 */
  analysisDisplayMode: AnalysisDisplayMode;
  /** 当前视图的实际图。 */
  activeViewGraph: LinkGraphDocument;
  /** 完整节点数。 */
  fullNodeCount: number;
  /** 是否处于讲解聚焦状态。 */
  hasExplanationFocus: boolean;
  /** 草稿变更涉及的节点数。 */
  draftChangedNodeCount: number;
  /** 草稿比对投影。 */
  draftCompareProjection: DraftCompareProjection | null;
  /** 代码 diff 状态。 */
  codeDiffStatus: CodeDiffStatus;
  /** 可编辑模式的视图 props。 */
  editableStageProps: EditableStageProps;
  /** 索引只读模式的视图 props。 */
  indexedReadonlyStageProps: IndexedReadonlyStageProps;
  /** 各视图文档。 */
  factGraphView: FactGraphViewDocument;
  presentedFlowchartView: FlowchartViewDocument;
  flowchartView: FlowchartViewDocument;
  resourceRelationView: ResourceRelationViewDocument;
  architectureGraphView: ArchitectureGraphViewDocument;
  classDiagramView: ClassDiagramViewDocument;
  reviewGraphView: ReviewGraphViewDocument;
  /** 切换展示模式的回调。 */
  onRequestAnalysisDisplayMode: (mode: AnalysisDisplayMode) => void;
}

/**
 * 图谱舞台面板：把头部、画布区与页脚组合为一个完整的图谱工作区。
 *
 * 这是图谱视图的顶层布局组件：
 * - 头部：展示模式切换器（GraphStageHeader）；
 * - 画布：按当前模式分发到具体视图（AppGraphStage）；
 * - 页脚：图例与统计（GraphStageFooter）。
 *
 * 按当前模式选择使用 EditableStageProps 还是 IndexedReadonlyStageProps。
 */
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
  // 所有视图共用的视图文档集合
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
        {/* 索引只读模式（架构/类图/审查）使用 indexedReadonlyStageProps */}
        {analysisDisplayMode === "ARCHITECTURE_GRAPH" ||
        analysisDisplayMode === "CLASS_DIAGRAM" ||
        analysisDisplayMode === "REVIEW_GRAPH" ? (
          <AppGraphStage
            analysisDisplayMode={analysisDisplayMode}
            stageProps={indexedReadonlyStageProps}
            {...commonViewProps}
          />
        ) : (
          /* 可编辑模式（事实/流程/资源关系）使用 editableStageProps */
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
