import { analysisDisplayModeLabel } from "../labels";
import type { AnalysisDisplayMode } from "../types";

interface GraphStageHeaderProps {
  analysisDisplayMode: AnalysisDisplayMode;
  onRequestAnalysisDisplayMode: (mode: AnalysisDisplayMode) => void;
}

type DisplayModeGroup = "link" | "index";

interface DisplayModeEntry {
  id: AnalysisDisplayMode;
  label: string;
  group: DisplayModeGroup;
}

// 主体链路（当前方法/资源出发生成） vs 项目级索引投影。
// 用圆点区分两类，让用户一眼看出哪些是当前链路、哪些是项目全局视图。
const DISPLAY_MODES: DisplayModeEntry[] = [
  { id: "FACT_GRAPH", label: "事实", group: "link" },
  { id: "FLOWCHART", label: "流程", group: "link" },
  { id: "RESOURCE_RELATION_VIEW", label: "资源", group: "link" },
  { id: "ARCHITECTURE_GRAPH", label: "项目结构", group: "index" },
  { id: "CLASS_DIAGRAM", label: "类图", group: "index" },
  { id: "REVIEW_GRAPH", label: "Review", group: "index" },
];

const GROUP_LABEL: Record<DisplayModeGroup, string> = {
  link: "当前链路",
  index: "项目索引",
};

export function GraphStageHeader({
  analysisDisplayMode,
  onRequestAnalysisDisplayMode,
}: GraphStageHeaderProps) {
  const groups: DisplayModeGroup[] = ["link", "index"];
  return (
    <header className="graph-stage-header">
      <div className="view-switch-group" role="group" aria-label="图谱模式">
        {groups.map((group, groupIndex) => (
          <div className="view-switch-segment" key={group}>
            {groupIndex > 0 ? (
              <span className="view-switch-group-label" aria-hidden="true">{GROUP_LABEL[group]}</span>
            ) : null}
            <div className="view-switch">
              {DISPLAY_MODES.filter((mode) => mode.group === group).map((mode) => (
                <button
                  key={mode.id}
                  type="button"
                  aria-pressed={analysisDisplayMode === mode.id}
                  aria-label={analysisDisplayModeLabel(mode.id)}
                  title={analysisDisplayModeLabel(mode.id)}
                  onClick={() => onRequestAnalysisDisplayMode(mode.id)}
                >
                  <span className={`view-switch-dot view-switch-dot-${group}`} aria-hidden="true" />
                  {mode.label}
                </button>
              ))}
            </div>
          </div>
        ))}
      </div>
    </header>
  );
}
