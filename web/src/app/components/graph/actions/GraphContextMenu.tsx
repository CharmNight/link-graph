import type { GraphContextMenuAction } from "./actionSchema";

interface GraphContextMenuProps {
  x: number;
  y: number;
  actions: GraphContextMenuAction[];
}

export function GraphContextMenu({
  x,
  y,
  actions,
}: GraphContextMenuProps) {
  return (
    <div
      role="menu"
      className="canvas-context-menu"
      style={{
        left: x,
        top: y,
      }}
    >
      {actions.map((action) => (
        <button
          key={action.id}
          type="button"
          role="menuitem"
          onClick={action.onSelect}
        >
          {action.label}
        </button>
      ))}
    </div>
  );
}
