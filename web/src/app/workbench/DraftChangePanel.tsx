import type { DraftWorkbenchEntry } from "../types";
import { draftFlowChangePillClassName, draftFlowChangePills, type DraftFlowChangeSummary } from "./draftFlowChangeSummary";

interface DraftChangePanelProps {
  changes: DraftWorkbenchEntry[];
  selectedEntryId?: string | null;
  compareMode?: "after" | "compare";
  selectedFlowChangeSummary?: DraftFlowChangeSummary | null;
  onSelectEntry: (entryId: string) => void;
  showTitle?: boolean;
}

export function DraftChangePanel({
  changes,
  selectedEntryId,
  compareMode = "after",
  selectedFlowChangeSummary = null,
  onSelectEntry,
  showTitle = true,
}: DraftChangePanelProps) {
  return (
    <section className="workbench-draft-section">
      {showTitle ? (
        <div className="workbench-section-title-row">
          <h3>草稿变更项</h3>
          <span className="badge">{changes.length}</span>
        </div>
      ) : null}
      {changes.length > 0 ? (
        <div className="workbench-draft-selector" aria-label="草稿变更列表">
          {changes.map((change) => {
            const isSelected = selectedEntryId === change.entryId;
            const flowChangePills = compareMode === "compare" && isSelected
              ? draftFlowChangePills(selectedFlowChangeSummary)
              : [];
            return (
              <button
                key={change.entryId}
                type="button"
                aria-pressed={isSelected}
                className={isSelected ? "workbench-draft-tab active" : "workbench-draft-tab"}
                onClick={() => onSelectEntry(change.entryId)}
                title={change.title}
                aria-label={`草稿条目：${change.title}`}
              >
                <span className="workbench-draft-tab-copy">
                  <span className="workbench-candidate-tab-title">{change.title}</span>
                  {compareMode === "compare" && flowChangePills.length > 0 ? (
                    <span className="workbench-draft-flow-summary" aria-label="流程变化摘要">
                      {flowChangePills.map((pill) => (
                        <span key={pill.label} className={draftFlowChangePillClassName(pill)}>{pill.label}</span>
                      ))}
                    </span>
                  ) : shouldShowAfterStatePreview(change) ? (
                    <span className="workbench-draft-tab-preview">{change.afterState}</span>
                  ) : null}
                </span>
                <span className="workbench-status-pill">{compareMode === "compare" && isSelected ? "流程变化" : "变更"}</span>
              </button>
            );
          })}
        </div>
      ) : (
        <p className="muted">当前还没有确认的草稿变更。</p>
      )}
    </section>
  );
}

function shouldShowAfterStatePreview(change: DraftWorkbenchEntry): change is DraftWorkbenchEntry & { afterState: string } {
  const afterState = change.afterState?.trim();
  if (!afterState) {
    return false;
  }
  return afterState !== change.title.trim();
}
