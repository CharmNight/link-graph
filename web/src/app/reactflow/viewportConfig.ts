// 视口调度与交互配置常量。
//
// 历史上这些值散落在 GraphFlowSurface、viewportPolicy、graphFlowViewportModel 中作为内联魔法数字。
// 现在集中到这里，让视口行为有单一可发现的真理源，调整时不必到处找常量。
//
// 下面的几何启发式参数已经过现有画布行为校验；只有 timing/animation 参数在画布"止血"迭代中改过。

/** 节点卡片高度的兜底值；无测量值时使用。 */
export const FALLBACK_NODE_CARD_HEIGHT = 156;

/** 短去抖：让连续图变更合并为一次 fit。 */
export const FIT_VIEW_DELAY_MS = 96;

/**
 * 二次 fit：布局稳定后再触发一次。
 *
 * 保留是为了兼容某些视图：节点尺寸测量是异步的，初次 fit 后还需要再 fit 一次。
 */
export const FIT_VIEW_RETRY_DELAY_MS = 220;

/** 画布外壳尺寸变化后重新 fit 的去抖窗口。 */
export const RESIZE_SETTLE_DELAY_MS = 140;

/** 宽图中聚焦单个锚点时使用的缩放级别。 */
export const WIDE_GRAPH_FOCUS_ZOOM = 0.76;

/** "可读 fit" 策略的缩放上下限：保证文字可读。 */
export const READABLE_FIT_MIN_ZOOM = 0.54;
/** "可读 fit" 策略的缩放上限。 */
export const READABLE_FIT_MAX_ZOOM = 0.82;
/** "可读 fit" 策略的最小留白（像素）。 */
export const READABLE_FIT_MIN_PADDING = 42;
/** "可读 fit" 策略的最大留白（像素）。 */
export const READABLE_FIT_MAX_PADDING = 96;

/** 缩放下限硬底：图可以缩小但永远不会消失。 */
export const GRAPH_SURFACE_MIN_ZOOM = 0.08;

/**
 * 视口移动（fit / focus / locate）的平滑过渡时长。
 *
 * 历史值是 0（硬切），让画布感觉跳跃。280ms 配合 [VIEWPORT_EASE] 缓动
 * 给出"响应但平稳"的运动感，与 P3 动画系统对齐，且不需要每次调用单独覆盖。
 */
export const VIEWPORT_TRANSITION_DURATION_MS = 280;

/** 视口过渡的 cubic-bezier 控制点。 */
export const VIEWPORT_EASE: [number, number, number, number] = [0.4, 0, 0.2, 1];

/**
 * 用户主动平移/缩放后的"宽限期"：在此期间抑制自动视口动作（选区聚焦、增量重 fit 等）。
 * 避免画布在用户刚交互完就"反向操作"用户。
 */
export const USER_INTERACTION_GUARD_MS = 600;
