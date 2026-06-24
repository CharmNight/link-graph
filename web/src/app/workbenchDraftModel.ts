import type { DraftWorkbenchEntry, DraftWorkbenchState } from "./types";

/**
 * 在草稿工作台中按选中 ID 定位单条草稿条目。
 * 查找顺序：先在变更类条目中找，再到备注类条目中找；若 ID 为空或未命中，
 * 回退到首条变更条目，再回退到首条备注条目，确保 UI 始终能拿到一个可显示对象。
 * 全部为空时返回 null，调用方需要处理这种"无草稿可显示"的情形。
 */
export function selectDraftWorkbenchEntry(
  draftWorkbenchState: DraftWorkbenchState,
  selectedEntryId: string | null,
): DraftWorkbenchEntry | null {
  return draftWorkbenchState.draftChanges.find((entry) => entry.entryId === selectedEntryId)
    ?? draftWorkbenchState.draftNotes.find((entry) => entry.entryId === selectedEntryId)
    ?? draftWorkbenchState.draftChanges[0]
    ?? draftWorkbenchState.draftNotes[0]
    ?? null;
}

/**
 * 汇总所有草稿变更条目影响的节点 ID，结果去重。
 * 通过外部传入的解析回调把每条草稿条目映射到其涉及的目标节点集合，
 * 这样本函数只负责聚合与去重，节点解析逻辑由调用方按上下文决定。
 */
export function collectDraftChangedNodeIds(
  draftWorkbenchState: DraftWorkbenchState,
  resolveDraftEntryTargetNodeIds: (entry: DraftWorkbenchEntry | null) => string[],
): string[] {
  return Array.from(new Set(
    draftWorkbenchState.draftChanges.flatMap((entry) => resolveDraftEntryTargetNodeIds(entry)),
  ));
}
