// 画布交互辅助工具：从 GraphFlowSurface 组件中抽出的几何/视口计算常量。
// 把这些纯数据抽到独立模块让组件只关心 React Flow 的接线，
// 几何/视口数学放在可测试的纯模块中（与 graphFlowContextMenuModel / graphFlowViewportModel 的拆分一致）。
import { DEFAULT_NODE_CARD_WIDTH } from "../graphNodeSizing";
import { VIEWPORT_EASE, VIEWPORT_TRANSITION_DURATION_MS } from "./viewportConfig";
import type { GraphPosition } from "../types";

/** 视口过渡参数。 */
export interface ViewportTransition {
  /** 过渡时长（毫秒）。 */
  duration: number;
  /** 缓动曲线控制点（cubic-bezier）。 */
  easing: number[];
}

/** 统一的视口过渡参数：让所有视口移动（fit/focus/locate）使用同一种动画语言。 */
export const VIEWPORT_TRANSITION: ViewportTransition = {
  duration: VIEWPORT_TRANSITION_DURATION_MS,
  easing: VIEWPORT_EASE,
};

/** 节点的默认视口尺寸（宽=默认卡片宽度，高=156）。用于计算 fit / focus 的目标缩放。 */
export const DEFAULT_NODE_VIEWPORT_SIZE = { width: DEFAULT_NODE_CARD_WIDTH, height: 156 };

export type { GraphPosition };
