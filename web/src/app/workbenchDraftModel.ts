import type { DraftWorkbenchEntry, DraftWorkbenchState } from "./types";

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

export function collectDraftChangedNodeIds(
  draftWorkbenchState: DraftWorkbenchState,
  resolveDraftEntryTargetNodeIds: (entry: DraftWorkbenchEntry | null) => string[],
): string[] {
  return Array.from(new Set(
    draftWorkbenchState.draftChanges.flatMap((entry) => resolveDraftEntryTargetNodeIds(entry)),
  ));
}
