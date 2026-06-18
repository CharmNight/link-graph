/**
 * Viewport scheduling & interaction configuration.
 *
 * Historically these values were scattered as inline magic numbers across
 * {@link GraphFlowSurface}, {@link viewportPolicy} and {@link graphFlowViewportModel}.
 * They are collected here so that viewport behaviour has a single, discoverable
 * source of truth and can be tuned without hunting for constants.
 *
 * NOTE: the geometry heuristics below were validated against the existing
 * canvas behaviour; only the timing/animation values changed as part of the
 * canvas "stop the bleed" pass (P0).
 */

/** Estimated node-card height used when no measured height is available. */
export const FALLBACK_NODE_CARD_HEIGHT = 156;

/** Small debounce so a burst of graph mutations collapses into a single fit. */
export const FIT_VIEW_DELAY_MS = 96;

/**
 * Second-chance fit fired after layout settles.
 *
 * Kept for backwards compatibility with views that need a re-fit once node
 * measurement settles (DOM sizing is asynchronous).
 */
export const FIT_VIEW_RETRY_DELAY_MS = 220;

/** Debounce window for re-fitting after the canvas shell resizes. */
export const RESIZE_SETTLE_DELAY_MS = 140;

/** Zoom used when focusing a single anchor on a wide graph. */
export const WIDE_GRAPH_FOCUS_ZOOM = 0.76;

/** Zoom clamps for the "readable-fit" policy (keeps text legible). */
export const READABLE_FIT_MIN_ZOOM = 0.54;
export const READABLE_FIT_MAX_ZOOM = 0.82;
export const READABLE_FIT_MIN_PADDING = 42;
export const READABLE_FIT_MAX_PADDING = 96;

/** Hard floor so the graph can be zoomed-out but never vanishes. */
export const GRAPH_SURFACE_MIN_ZOOM = 0.08;

/**
 * Smooth transition duration for viewport moves (fit / focus / locate).
 *
 * Was `0` (hard snap) which made the canvas feel janky. 280ms with the easing
 * curve in {@link VIEWPORT_EASE} gives a responsive-but-calm motion and lines
 * up with the P3 animation system without requiring per-call overrides.
 */
export const VIEWPORT_TRANSITION_DURATION_MS = 280;

/** `cubic-bezier` control points for viewport transitions. */
export const VIEWPORT_EASE: [number, number, number, number] = [0.4, 0, 0.2, 1];

/**
 * Grace window after a user-initiated pan/zoom during which automatic viewport
 * moves (selection focus, incremental re-fit) are suppressed. Prevents the
 * canvas from "fighting" the user right after they interact with it.
 */
export const USER_INTERACTION_GUARD_MS = 600;
