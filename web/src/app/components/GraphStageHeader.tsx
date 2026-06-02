import { analysisDisplayModeLabel } from "../labels";
import type { AnalysisDisplayMode } from "../types";
import type { WorkflowStage } from "../workflow/workflowStage";

interface GraphStageHeaderProps {
  analysisDisplayMode: AnalysisDisplayMode;
  activeStage: WorkflowStage;
  onRequestAnalysisDisplayMode: (mode: AnalysisDisplayMode) => void;
}

const DISPLAY_MODES: Array<{ id: AnalysisDisplayMode; label: string }> = [
  { id: "FACT_GRAPH", label: "事实" },
  { id: "FLOWCHART", label: "流程" },
  { id: "RESOURCE_RELATION_VIEW", label: "资源" },
  { id: "ARCHITECTURE_GRAPH", label: "项目结构" },
  { id: "CLASS_DIAGRAM", label: "类图" },
  { id: "REVIEW_GRAPH", label: "Review" },
];

export function GraphStageHeader({
  analysisDisplayMode,
  activeStage,
  onRequestAnalysisDisplayMode,
}: GraphStageHeaderProps) {
  return (
    <header className="graph-stage-header">
      <div className="graph-stage-title">
        <p className="eyebrow">Graph Stage</p>
        <h2>{graphStageTitle(analysisDisplayMode)}</h2>
        <p>{graphStageDescription(analysisDisplayMode, activeStage)}</p>
      </div>
      <div className="view-switch" role="group" aria-label="图谱模式">
        {DISPLAY_MODES.map((mode) => (
          <button
            key={mode.id}
            type="button"
            aria-pressed={analysisDisplayMode === mode.id}
            aria-label={analysisDisplayModeLabel(mode.id)}
            title={analysisDisplayModeLabel(mode.id)}
            onClick={() => onRequestAnalysisDisplayMode(mode.id)}
          >
            {mode.label}
          </button>
        ))}
      </div>
    </header>
  );
}

function graphStageTitle(mode: AnalysisDisplayMode): string {
  switch (mode) {
    case "FLOWCHART":
      return "流程图谱";
    case "RESOURCE_RELATION_VIEW":
      return "资源关系";
    case "ARCHITECTURE_GRAPH":
      return "项目结构";
    case "CLASS_DIAGRAM":
      return "类图";
    case "REVIEW_GRAPH":
      return "Review Graph";
    case "FACT_GRAPH":
    default:
      return "事实图谱";
  }
}

function graphStageDescription(mode: AnalysisDisplayMode, stage: WorkflowStage): string {
  if (mode === "REVIEW_GRAPH") {
    return "当前用于核对变更影响、上下游证据和可追溯测试。";
  }
  switch (stage) {
    case "understand":
      return "当前展示链路结构、入口和主路径。";
    case "evidence":
      return "当前展示源码、流程节点、资源证据和证据缺口。";
    case "qa":
      return "当前问答范围会绑定选中节点和相关证据。";
    case "draft":
      return "当前突出显示草稿影响节点和差异。";
    case "code":
      return "当前用于核对代码 diff 影响范围。";
  }
}
