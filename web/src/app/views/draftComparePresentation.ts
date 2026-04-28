import type { DraftCompareStatus } from "../types";

export function draftCompareNodeClassName(status?: DraftCompareStatus | null): string {
  if (!status) {
    return "";
  }
  return `is-draft-compare-${status.toLowerCase()}`;
}

export function draftCompareEdgeClassName(baseClassName: string, status?: DraftCompareStatus | null): string {
  const compareClassName = draftCompareNodeClassName(status);
  return [baseClassName, compareClassName].filter((value) => value.length > 0).join(" ").trim();
}

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
        stroke: "#0e8b72",
        strokeWidth: 2.4,
        opacity: 0.96,
      };
    case "REMOVED":
      return {
        ...baseStyle,
        stroke: "#95403a",
        strokeWidth: 2.2,
        strokeDasharray: "8 5",
        opacity: 0.96,
      };
    case "MODIFIED":
    default:
      return {
        ...baseStyle,
        stroke: "#b8682f",
        strokeWidth: 2.4,
        opacity: 0.96,
      };
  }
}

export function draftCompareMarkerColor(
  fallbackColor: string,
  status?: DraftCompareStatus | null,
): string {
  switch (status) {
    case "ADDED":
      return "#0e8b72";
    case "REMOVED":
      return "#95403a";
    case "MODIFIED":
      return "#b8682f";
    default:
      return fallbackColor;
  }
}
