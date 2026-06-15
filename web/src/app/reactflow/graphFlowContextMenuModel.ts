import type { GraphPosition } from "../types";

const CONTEXT_MENU_SAFE_MARGIN = 16;
const CONTEXT_MENU_ESTIMATED_WIDTH = 240;
const CONTEXT_MENU_ESTIMATED_HEIGHT = 360;

export interface ContextMenuViewport {
  width: number;
  height: number;
}

export interface ContextMenuRect {
  left: number;
  top: number;
}

export type GraphFlowContextMenuState =
  | {
      kind: "pane";
      x: number;
      y: number;
      position?: GraphPosition;
    }
  | {
      kind: "node";
      x: number;
      y: number;
      nodeId: string;
    }
  | {
      kind: "edge";
      x: number;
      y: number;
      edgeId: string;
    };

export function resolveContextMenuPoint(
  x: number,
  y: number,
  viewport: ContextMenuViewport | null | undefined,
): { x: number; y: number } {
  if (!viewport) {
    return { x, y };
  }
  return {
    x: Math.min(
      Math.max(CONTEXT_MENU_SAFE_MARGIN, x),
      Math.max(
        CONTEXT_MENU_SAFE_MARGIN,
        viewport.width - CONTEXT_MENU_ESTIMATED_WIDTH - CONTEXT_MENU_SAFE_MARGIN,
      ),
    ),
    y: Math.min(
      Math.max(CONTEXT_MENU_SAFE_MARGIN, y),
      Math.max(
        CONTEXT_MENU_SAFE_MARGIN,
        viewport.height - CONTEXT_MENU_ESTIMATED_HEIGHT - CONTEXT_MENU_SAFE_MARGIN,
      ),
    ),
  };
}

export function resolvePanePositionFromRect(
  clientX: number,
  clientY: number,
  rect: ContextMenuRect | null | undefined,
): GraphPosition | undefined {
  if (!rect) {
    return undefined;
  }
  return {
    x: Math.max(0, clientX - rect.left),
    y: Math.max(0, clientY - rect.top),
  };
}
