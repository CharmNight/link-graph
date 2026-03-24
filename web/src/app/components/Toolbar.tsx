interface ToolbarProps {
  search: string;
  onSearchChange: (value: string) => void;
  onAddNode: () => void;
  onExportMermaid: () => void;
  onRequestSync: () => void;
}

export function Toolbar({
  search,
  onSearchChange,
  onAddNode,
  onExportMermaid,
  onRequestSync,
}: ToolbarProps) {
  return (
    <header className="workspace-toolbar">
      <div>
        <p className="eyebrow">Link Graph Editor</p>
        <h1>Code, Mermaid, and Sync Preview</h1>
      </div>

      <div className="toolbar-actions">
        <input
          className="search-input"
          placeholder="Search nodes or paths"
          value={search}
          onChange={(event) => onSearchChange(event.target.value)}
        />
        <button type="button" className="ghost-button" onClick={onAddNode}>
          Add Node
        </button>
        <button type="button" className="ghost-button" onClick={onExportMermaid}>
          Export Mermaid
        </button>
        <button type="button" className="primary-button" onClick={onRequestSync}>
          Request Sync
        </button>
      </div>
    </header>
  );
}
