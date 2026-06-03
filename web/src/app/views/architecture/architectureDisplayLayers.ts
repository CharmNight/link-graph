export const ARCHITECTURE_DISPLAY_LAYER_ORDER = [
  "entry",
  "application",
  "domain",
  "data",
  "resource",
  "external",
] as const;

export type ArchitectureDisplayLayer = typeof ARCHITECTURE_DISPLAY_LAYER_ORDER[number];

export function architectureLaneSortValue(laneId: string): number {
  const index = ARCHITECTURE_DISPLAY_LAYER_ORDER.indexOf(laneId as ArchitectureDisplayLayer);
  return index >= 0 ? index : ARCHITECTURE_DISPLAY_LAYER_ORDER.length;
}
