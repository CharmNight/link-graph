import { useEffect, useState, type Dispatch, type SetStateAction } from "react";

import type { DraftWorkbenchState, LinkGraphBootstrapState } from "../types";

/**
 * 选区状态：当前选中的候选变更 / 风险线程 / 草稿条目，以及 draft 对比模式。
 *
 * P2-1 之后从 App.tsx 抽出，让组件聚焦于渲染与跨 hook 协作，不关心单个 useState 的拼装。
 *
 * - 初始 selectedDraftEntryId 从 bootstrap 第一条草稿派生，确保首次进入工作台就有一条选中
 * - draftCompareMode 控制草稿对比是「看结果 after」还是「看流程变化 compare」
 *   （入口已从旧 ChangeTray 迁移到 AI 工作台的「查看流程变化」按钮）
 * - 当 draftWorkbenchState 内容变化时，自动校正 selectedDraftEntryId 到仍存在的条目
 */
export interface SelectionState {
  /** 当前选中的候选变更条目 ID。 */
  selectedQaChangeId: string | null;
  setSelectedQaChangeId: Dispatch<SetStateAction<string | null>>;
  /** 当前选中的 QA 风险线程 ID。 */
  selectedQaThreadId: string | null;
  setSelectedQaThreadId: Dispatch<SetStateAction<string | null>>;
  /** 当前选中的草稿条目 ID。 */
  selectedDraftEntryId: string | null;
  setSelectedDraftEntryId: Dispatch<SetStateAction<string | null>>;
  /** 草稿对比模式（after 看结果 / compare 看流程变化）。 */
  draftCompareMode: "after" | "compare";
  setDraftCompareMode: Dispatch<SetStateAction<"after" | "compare">>;
}

/**
 * 从 [initialState] 派生初始选区，并在 [draftWorkbenchState] 变化时校正 selectedDraftEntryId。
 */
export function useSelectionState(
  initialState: LinkGraphBootstrapState,
  draftWorkbenchState: Pick<DraftWorkbenchState, "draftChanges" | "draftNotes">,
): SelectionState {
  const [selectedQaChangeId, setSelectedQaChangeId] = useState<string | null>(null);
  const [selectedQaThreadId, setSelectedQaThreadId] = useState<string | null>(null);
  const [selectedDraftEntryId, setSelectedDraftEntryId] = useState<string | null>(
    () => initialState.draftWorkbenchState?.draftChanges[0]?.entryId
      ?? initialState.draftWorkbenchState?.draftNotes[0]?.entryId
      ?? null,
  );
  const [draftCompareMode, setDraftCompareMode] = useState<"after" | "compare">("after");

  // draftWorkbenchState 内容变化时，校正 selectedDraftEntryId 到仍存在的条目
  useEffect(() => {
    setSelectedDraftEntryId((current) => {
      const allEntries = draftWorkbenchState.draftChanges.concat(draftWorkbenchState.draftNotes);
      if (current && allEntries.some((entry) => entry.entryId === current)) {
        return current;
      }
      return draftWorkbenchState.draftChanges[0]?.entryId
        ?? draftWorkbenchState.draftNotes[0]?.entryId
        ?? null;
    });
  }, [draftWorkbenchState.draftChanges, draftWorkbenchState.draftNotes]);

  return {
    selectedQaChangeId,
    setSelectedQaChangeId,
    selectedQaThreadId,
    setSelectedQaThreadId,
    selectedDraftEntryId,
    setSelectedDraftEntryId,
    draftCompareMode,
    setDraftCompareMode,
  };
}
