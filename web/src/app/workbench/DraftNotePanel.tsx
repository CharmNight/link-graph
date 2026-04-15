import type { DraftWorkbenchEntry } from "../types";

interface DraftNotePanelProps {
  notes: DraftWorkbenchEntry[];
  selectedEntryId?: string | null;
  onSelectEntry: (entryId: string) => void;
  showTitle?: boolean;
}

export function DraftNotePanel({
  notes,
  selectedEntryId,
  onSelectEntry,
  showTitle = true,
}: DraftNotePanelProps) {
  return (
    <section className="workbench-draft-section">
      {showTitle ? (
        <div className="workbench-section-title-row">
          <h3>草稿说明项</h3>
          <span className="badge">{notes.length}</span>
        </div>
      ) : null}
      {notes.length > 0 ? (
        <div className="workbench-draft-list" aria-label="草稿说明列表">
          {notes.map((note) => (
            <button
              key={note.entryId}
              type="button"
              aria-pressed={selectedEntryId === note.entryId}
              className={selectedEntryId === note.entryId ? "workbench-draft-tab note active" : "workbench-draft-tab note"}
              aria-label={`草稿条目：${note.title}`}
              onClick={() => onSelectEntry(note.entryId)}
            >
              <span className="workbench-candidate-tab-title">{note.title}</span>
              <span className="workbench-status-pill">说明</span>
            </button>
          ))}
        </div>
      ) : (
        <p className="muted">当前还没有草稿说明项。</p>
      )}
    </section>
  );
}
