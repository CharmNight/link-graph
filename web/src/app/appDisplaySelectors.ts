import type {
  AnalysisDisplayMode,
  GraphProjectionIndex,
  IndexedGraphSummary,
  LinkGraphDocument,
} from "./types";
import type { WorkflowStage } from "./workflow/workflowStage";

/** 助理面板在工作流的某个阶段应聚焦的目标区域（解释、问答、采纳改动、代码）。 */
export type AssistantStageTarget = "explanation" | "qa" | "draft" | "code";

/** 某个展示模式下的图视图组合：可见图、完整图、锚点节点。 */
interface DisplayModeGraphView {
  /** 实际渲染到画布上的图（可能经过裁剪/投影）。 */
  visibleGraph: LinkGraphDocument;
  /** 该视图下的完整图，用于"显示全部"或回退场景。 */
  fullGraph: LinkGraphDocument;
  /** 视图的锚点节点 ID（例如类图中的主类），用于决定镜头中心。 */
  anchorNodeId?: string | null;
}

/** 各分析展示模式对应的图视图集合。键名暗示每种视图的来源。 */
export interface DisplayModeGraphDocuments {
  /** 事实图（fact）视图。 */
  factGraphView: DisplayModeGraphView;
  /** 流程图视图。 */
  flowchartView: DisplayModeGraphView;
  /** 资源关系图视图。 */
  resourceRelationView: DisplayModeGraphView;
  /** 架构图视图。 */
  architectureGraphView: DisplayModeGraphView;
  /** 类图视图。 */
  classDiagramView: DisplayModeGraphView;
  /** 审查图视图。 */
  reviewGraphView: DisplayModeGraphView;
}

/**
 * 根据当前展示模式给出对应的索引摘要。
 * 只有架构图、类图、审查图这三个视图存在索引摘要；其他模式返回 null。
 */
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
      // 其他视图没有对应的索引摘要，统一返回 null
      return null;
  }
}

/**
 * 根据当前展示模式选择对应的投影索引。
 * 不同视图各自维护一份投影索引（事实、流程、资源、架构、类图、审查），
 * 此处做集中分发，让上层不必关心具体视图与字段的对应关系。
 */
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
      // 默认走事实图投影，对应未识别的展示模式
      return factProjectionIndex ?? null;
  }
}

/**
 * 按展示模式从文档集合中取出对应的图视图组合。
 * 与 activeProjectionIndex 类似的集中分发，但返回完整的可见/完整/锚点三元组。
 */
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

/** 取出当前展示模式对应的可见图（实际渲染到画布上的版本）。 */
export function activeVisibleGraphForDisplayMode(
  analysisDisplayMode: AnalysisDisplayMode,
  documents: DisplayModeGraphDocuments,
): LinkGraphDocument {
  return activeGraphViewForDisplayMode(analysisDisplayMode, documents).visibleGraph;
}

/** 取出当前展示模式对应的完整图（未经裁剪的源数据版本）。 */
export function activeFullGraphForDisplayMode(
  analysisDisplayMode: AnalysisDisplayMode,
  documents: DisplayModeGraphDocuments,
): LinkGraphDocument {
  return activeGraphViewForDisplayMode(analysisDisplayMode, documents).fullGraph;
}

/** 取出当前展示模式对应的锚点节点 ID（缺失时返回 null）。 */
export function activeAnchorNodeIdForDisplayMode(
  analysisDisplayMode: AnalysisDisplayMode,
  documents: DisplayModeGraphDocuments,
): string | null {
  return activeGraphViewForDisplayMode(analysisDisplayMode, documents).anchorNodeId ?? null;
}

/**
 * 把工作流阶段映射到助理面板应聚焦的目标区域。
 * evidence（核验证据）阶段也复用 qa 区域，因为它的助理交互以问答为主。
 */
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
      // 证据核验阶段的助理操作和 qa 共用面板
      return "qa";
  }
}

/**
 * 判断当前展示是否为"项目级结构视图"。
 * 满足两个条件才算项目级：处于架构图视图，且索引摘要的范围标记为 PROJECT。
 * 用于决定是否显示项目级别特有的提示或操作入口。
 */
export function isProjectStructureDisplay(
  analysisDisplayMode: AnalysisDisplayMode,
  architectureSummary: IndexedGraphSummary | null | undefined,
): boolean {
  return analysisDisplayMode === "ARCHITECTURE_GRAPH" && architectureSummary?.scopeKind === "PROJECT";
}
