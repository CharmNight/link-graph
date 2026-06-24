// 架构图展示层定义。
// 架构图按"层"（entry/application/domain/data/resource/external）组织节点，
// 每层对应一条横向泳道，让用户能从上到下看到请求从入口流到外部资源的过程。

/**
 * 架构图展示层的固定顺序（从上到下）。
 *
 * 顺序反映"请求从入口到外部资源"的流向：
 * entry（HTTP/RPC 入口）→ application（业务服务）→ domain（领域模型）→
 * data（持久层）→ resource（外部资源：MQ/Cache 等）→ external（外部依赖）。
 */
export const ARCHITECTURE_DISPLAY_LAYER_ORDER = [
  "entry",
  "application",
  "domain",
  "data",
  "resource",
  "external",
] as const;

/** 架构图展示层类型别名（从常量数组派生）。 */
export type ArchitectureDisplayLayer = typeof ARCHITECTURE_DISPLAY_LAYER_ORDER[number];

/**
 * 取泳道排序值。
 * 已知泳道返回其索引（越小越靠上）；未知泳道返回末位（沉底）。
 *
 * 用于 ELK 布局中按泳道顺序排列节点。
 */
export function architectureLaneSortValue(laneId: string): number {
  const index = ARCHITECTURE_DISPLAY_LAYER_ORDER.indexOf(laneId as ArchitectureDisplayLayer);
  return index >= 0 ? index : ARCHITECTURE_DISPLAY_LAYER_ORDER.length;
}
