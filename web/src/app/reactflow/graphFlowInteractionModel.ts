import { DEFAULT_NODE_CARD_WIDTH } from "../graphNodeSizing";
import { VIEWPORT_EASE, VIEWPORT_TRANSITION_DURATION_MS } from "./viewportConfig";
import type { GraphPosition } from "../types";

/**
 * Interaction helpers extracted from {@link GraphFlowSurface} so the surface
 * component stays focused on wiring React Flow, and the geometry / viewport
 * math lives in a pure, testable module (mirrors the existing
 * `graphFlowContextMenuModel` / `graphFlowViewportModel` split).
 */

export interface ViewportTransition {
  duration: number;
  easing: number[];
}

/** Shared transition options for every viewport move (one motion language). */
export const VIEWPORT_TRANSITION: ViewportTransition = {
  duration: VIEWPORT_TRANSITION_DURATION_MS,
  easing: VIEWPORT_EASE,
};

export const DEFAULT_NODE_VIEWPORT_SIZE = { width: DEFAULT_NODE_CARD_WIDTH, height: 156 };

export type { GraphPosition };
