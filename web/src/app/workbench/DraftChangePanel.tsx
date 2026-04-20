import type { DraftWorkbenchEntry } from "../types";

interface DraftChangePanelProps {
  changes: DraftWorkbenchEntry[];
  selectedEntryId?: string | null;
  onSelectEntry: (entryId: string) => void;
  showTitle?: boolean;
}

export function DraftChangePanel({
  changes,
  selectedEntryId,
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
          {changes.map((change) => (
            <button
              key={change.entryId}
              type="button"
              aria-pressed={selectedEntryId === change.entryId}
              className={selectedEntryId === change.entryId ? "workbench-draft-tab active" : "workbench-draft-tab"}
              onClick={() => onSelectEntry(change.entryId)}
              title={change.title}
              aria-label={`草稿条目：${change.title}`}
            >
              <span className="workbench-draft-tab-copy">
                <span className="workbench-candidate-tab-title">{change.title}</span>
                {shouldShowAfterStatePreview(change) ? (
                  <span className="workbench-draft-tab-preview">{change.afterState}</span>
                ) : null}
              </span>
              <span className="workbench-status-pill">变更</span>
            </button>
          ))}
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
