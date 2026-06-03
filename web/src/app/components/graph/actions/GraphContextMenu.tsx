import { useLayoutEffect, useMemo, useRef, useState } from "react";
import { createPortal } from "react-dom";
import type { GraphContextMenuAction } from "./actionSchema";

interface GraphContextMenuProps {
  x: number;
  y: number;
  actions: GraphContextMenuAction[];
}

const VIEWPORT_MARGIN = 12;
const FALLBACK_MENU_WIDTH = 220;
const FALLBACK_MENU_ITEM_HEIGHT = 38;
const FALLBACK_MENU_CHROME_HEIGHT = 16;

function clampMenuCoordinate(value: number, size: number, viewportSize: number): number {
  const maxValue = Math.max(VIEWPORT_MARGIN, viewportSize - size - VIEWPORT_MARGIN);
  return Math.max(VIEWPORT_MARGIN, Math.min(value, maxValue));
}

export function GraphContextMenu({
  x,
  y,
  actions,
}: GraphContextMenuProps) {
  const menuRef = useRef<HTMLDivElement | null>(null);
  const [menuSize, setMenuSize] = useState(() => ({
    width: FALLBACK_MENU_WIDTH,
    height: Math.max(FALLBACK_MENU_CHROME_HEIGHT, (actions.length * FALLBACK_MENU_ITEM_HEIGHT) + FALLBACK_MENU_CHROME_HEIGHT),
  }));

  useLayoutEffect(() => {
    const menuNode = menuRef.current;
    if (!menuNode) {
      return;
    }
    const rect = menuNode.getBoundingClientRect();
    if (rect.width <= 0 || rect.height <= 0) {
      return;
    }
    setMenuSize((current) => {
      const nextWidth = Math.round(rect.width);
      const nextHeight = Math.round(rect.height);
      if (current.width === nextWidth && current.height === nextHeight) {
        return current;
      }
      return { width: nextWidth, height: nextHeight };
    });
  }, [actions.length]);

  const position = useMemo(() => ({
    left: clampMenuCoordinate(x, menuSize.width, window.innerWidth),
    top: clampMenuCoordinate(y, menuSize.height, window.innerHeight),
  }), [menuSize.height, menuSize.width, x, y]);

  const menu = (
    <div
      ref={menuRef}
      role="menu"
      className="canvas-context-menu"
      style={{
        left: position.left,
        top: position.top,
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

  return typeof document === "undefined" ? menu : createPortal(menu, document.body);
}
