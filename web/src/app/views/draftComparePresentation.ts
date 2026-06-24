// 草稿比对状态的视觉表达：根据 ADDED / REMOVED / MODIFIED 给节点和边上不同样式。
// 让用户在草稿比对视图里一眼看出哪些节点/边是新增的、删除的、修改的。
import type { DraftCompareStatus } from "../types";

/**
 * 生成节点的草稿比对 className。
 * 形如 "is-draft-compare-added"，CSS 按状态上色。
 * 无状态时返回空字符串（避免空格类名）。
 */
export function draftCompareNodeClassName(status?: DraftCompareStatus | null): string {
  if (!status) {
    return "";
  }
  return `is-draft-compare-${status.toLowerCase()}`;
}

/**
 * 生成边的 className：基础类名 + 草稿比对类名拼接。
 * 过滤空字符串避免多余空格。
 */
export function draftCompareEdgeClassName(baseClassName: string, status?: DraftCompareStatus | null): string {
  const compareClassName = draftCompareNodeClassName(status);
  return [baseClassName, compareClassName].filter((value) => value.length > 0).join(" ").trim();
}

/**
 * 生成边的内联样式：按状态调整描边颜色、宽度、虚线等。
 *
 * - ADDED：绿色实线，强调"新加的"；
 * - REMOVED：红色虚线，强调"删除的"；
 * - MODIFIED：橙色实线，强调"改动过的"。
 */
export function draftCompareEdgeStyle<T extends Record<string, unknown>>(
  baseStyle: T,
  status?: DraftCompareStatus | null,
): T {
  if (!status) {
    return baseStyle;
  }
  switch (status) {
    case "ADDED":
      return {
        ...baseStyle,
        stroke: "var(--edge-success)",
        strokeWidth: 2.4,
        opacity: 0.96,
      };
    case "REMOVED":
      return {
        ...baseStyle,
        stroke: "var(--edge-danger)",
        strokeWidth: 2.2,
        // 虚线表示删除的边
        strokeDasharray: "8 5",
        opacity: 0.96,
      };
    case "MODIFIED":
    default:
      return {
        ...baseStyle,
        stroke: "var(--edge-warning)",
        strokeWidth: 2.4,
        opacity: 0.96,
      };
  }
}

/**
 * 生成边端装饰物的颜色（用于箭头/菱形等）。
 * 与 [draftCompareEdgeStyle] 颜色保持一致。
 */
export function draftCompareMarkerColor(
  fallbackColor: string,
  status?: DraftCompareStatus | null,
): string {
  switch (status) {
    case "ADDED":
      // 与 edge-success 对应的深绿色
      return "#0e8b72";
    case "REMOVED":
      // 与 edge-danger 对应的暗红色
      return "#95403a";
    case "MODIFIED":
      // 与 edge-warning 对应的橙棕色
      return "#b8682f";
    default:
      return fallbackColor;
  }
}
