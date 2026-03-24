import { useEffect, useState } from "react";
import type { LinkGraphNode } from "../types";

interface PropertyPanelProps {
  selectedNode: LinkGraphNode | null;
  onUpdateNode: (node: LinkGraphNode) => void;
  onDeleteNode: (nodeId: string) => void;
}

export function PropertyPanel({ selectedNode, onUpdateNode, onDeleteNode }: PropertyPanelProps) {
  const [draft, setDraft] = useState<LinkGraphNode | null>(selectedNode);

  useEffect(() => {
    setDraft(selectedNode);
  }, [selectedNode]);

  if (!draft) {
    return (
      <aside className="side-panel property-panel empty-panel">
        <p className="eyebrow">Properties</p>
        <h2>No node selected</h2>
        <p>Pick a graph node to edit its title, signature, and notes.</p>
      </aside>
    );
  }

  return (
    <aside className="side-panel property-panel">
      <p className="eyebrow">Properties</p>
      <h2>{draft.id}</h2>

      <label>
        Title
        <input
          aria-label="Title"
          value={draft.title}
          onChange={(event) => setDraft({ ...draft, title: event.target.value })}
        />
      </label>

      <label>
        Signature
        <textarea
          aria-label="Signature"
          value={draft.signature ?? ""}
          onChange={(event) => setDraft({ ...draft, signature: event.target.value })}
        />
      </label>

      <label>
        Doc
        <textarea
          aria-label="Doc"
          value={draft.doc ?? ""}
          onChange={(event) => setDraft({ ...draft, doc: event.target.value })}
        />
      </label>

      <div className="panel-actions">
        <button type="button" className="primary-button" onClick={() => onUpdateNode(draft)}>
          Save Changes
        </button>
        <button type="button" className="ghost-button" onClick={() => onDeleteNode(draft.id)}>
          Delete Node
        </button>
      </div>
    </aside>
  );
}
