import type { WorkbenchSectionId, WorkbenchSectionPreferences } from "../types";

export type WorkbenchTabId = "explanation" | "qa" | "draft";

interface WorkbenchSectionConstraint {
  id: WorkbenchSectionId;
  threshold: number;
}

export const QA_WORKBENCH_SECTION_IDS: WorkbenchSectionId[] = [
  "qa.composer",
  "qa.request-status",
  "qa.thread",
  "qa.candidate-changes",
  "qa.investigation-threads",
];

const DEFAULT_SECTION_PREFERENCES: Record<WorkbenchSectionId, boolean> = {
  "explanation.step-list": true,
  "explanation.step-detail": true,
  "qa.request-status": false,
  "qa.thread": true,
  "qa.composer": false,
  "qa.candidate-changes": false,
  "qa.investigation-threads": false,
  "draft.change-list": true,
  "draft.note-list": false,
  "draft.detail": true,
};

const QA_COLLAPSE_PRIORITY: WorkbenchSectionConstraint[] = [
  { id: "qa.request-status", threshold: 760 },
  { id: "qa.candidate-changes", threshold: 920 },
];

const DRAFT_COLLAPSE_PRIORITY: WorkbenchSectionConstraint[] = [
  { id: "draft.note-list", threshold: 780 },
];

export function defaultWorkbenchSectionPreferences(): WorkbenchSectionPreferences {
  return { ...DEFAULT_SECTION_PREFERENCES };
}

export function resolveWorkbenchSectionPreference(
  preferences: WorkbenchSectionPreferences | null | undefined,
  sectionId: WorkbenchSectionId,
): boolean {
  return preferences?.[sectionId] ?? DEFAULT_SECTION_PREFERENCES[sectionId];
}

interface ResolveEffectiveWorkbenchSectionPreferencesArgs {
  tab: WorkbenchTabId;
  preferences: WorkbenchSectionPreferences | null | undefined;
  layoutHeight?: number | null;
  hasQaChanges?: boolean;
}

export function resolveEffectiveWorkbenchSectionPreferences({
  tab,
  preferences,
  layoutHeight,
  hasQaChanges = false,
}: ResolveEffectiveWorkbenchSectionPreferencesArgs): WorkbenchSectionPreferences {
  const effective = defaultWorkbenchSectionPreferences();
  for (const sectionId of Object.keys(DEFAULT_SECTION_PREFERENCES) as WorkbenchSectionId[]) {
    effective[sectionId] = resolveWorkbenchSectionPreference(preferences, sectionId);
  }

  if (!hasQaChanges) {
    effective["qa.candidate-changes"] = false;
  }

  if (!layoutHeight || layoutHeight <= 0) {
    return effective;
  }

  const constrainedSections = tab === "qa"
    ? QA_COLLAPSE_PRIORITY
    : tab === "draft"
      ? DRAFT_COLLAPSE_PRIORITY
      : [];

  for (const section of constrainedSections) {
    if (layoutHeight < section.threshold && preferences?.[section.id] !== true) {
      effective[section.id] = false;
    }
  }

  return effective;
}
