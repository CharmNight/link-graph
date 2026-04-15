import type { WorkbenchSectionId, WorkbenchSectionPreferences } from "../types";

export type WorkbenchTabId = "explanation" | "audit" | "draft";

interface WorkbenchSectionConstraint {
  id: WorkbenchSectionId;
  threshold: number;
}

export const AUDIT_WORKBENCH_SECTION_IDS: WorkbenchSectionId[] = [
  "audit.thread",
  "audit.request-status",
  "audit.composer",
  "audit.candidate-changes",
  "audit.investigation-leads",
];

const DEFAULT_SECTION_PREFERENCES: Record<WorkbenchSectionId, boolean> = {
  "explanation.step-list": true,
  "explanation.step-detail": true,
  "audit.request-status": false,
  "audit.thread": true,
  "audit.composer": false,
  "audit.candidate-changes": false,
  "audit.investigation-leads": false,
  "draft.change-list": true,
  "draft.note-list": false,
  "draft.detail": true,
};

const AUDIT_COLLAPSE_PRIORITY: WorkbenchSectionConstraint[] = [
  { id: "audit.request-status", threshold: 760 },
  { id: "audit.candidate-changes", threshold: 920 },
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
  hasAuditChanges?: boolean;
}

export function resolveEffectiveWorkbenchSectionPreferences({
  tab,
  preferences,
  layoutHeight,
  hasAuditChanges = false,
}: ResolveEffectiveWorkbenchSectionPreferencesArgs): WorkbenchSectionPreferences {
  const effective = defaultWorkbenchSectionPreferences();
  for (const sectionId of Object.keys(DEFAULT_SECTION_PREFERENCES) as WorkbenchSectionId[]) {
    effective[sectionId] = resolveWorkbenchSectionPreference(preferences, sectionId);
  }

  if (!hasAuditChanges) {
    effective["audit.candidate-changes"] = false;
  }

  if (!layoutHeight || layoutHeight <= 0) {
    return effective;
  }

  const constrainedSections = tab === "audit"
    ? AUDIT_COLLAPSE_PRIORITY
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
