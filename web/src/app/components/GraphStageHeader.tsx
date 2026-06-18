import { analysisDisplayModeLabel } from "../labels";
import type { AnalysisDisplayMode } from "../types";

interface GraphStageHeaderProps {
  analysisDisplayMode: AnalysisDisplayMode;
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
  onRequestAnalysisDisplayMode,
}: GraphStageHeaderProps) {
  return (
    <header className="graph-stage-header">
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
