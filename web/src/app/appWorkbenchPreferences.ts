export const WORKBENCH_LAYOUT_STORAGE_KEY = "linkGraph.hybridWorkbenchLayout";
export const DEFAULT_ASSISTANT_WORKBENCH_WIDTH = 420;

const MIN_ASSISTANT_WORKBENCH_WIDTH = 320;
const MAX_ASSISTANT_WORKBENCH_WIDTH = 720;

export interface HybridWorkbenchLayoutPreference {
  outlineCollapsed: boolean;
  workbenchWidth: number;
}

type WorkbenchLayoutStorage = Pick<Storage, "getItem" | "setItem">;

function defaultHybridWorkbenchLayoutPreference(): HybridWorkbenchLayoutPreference {
  return {
    outlineCollapsed: false,
    workbenchWidth: DEFAULT_ASSISTANT_WORKBENCH_WIDTH,
  };
}

function browserLayoutStorage(): WorkbenchLayoutStorage | null {
  if (typeof window === "undefined") {
    return null;
  }
  try {
    return window.localStorage;
  } catch {
    return null;
  }
}

export function clampAssistantWorkbenchWidth(width: number): number {
  return Math.max(MIN_ASSISTANT_WORKBENCH_WIDTH, Math.min(MAX_ASSISTANT_WORKBENCH_WIDTH, Math.round(width)));
}

export function readHybridWorkbenchLayoutPreference(
  storage: WorkbenchLayoutStorage | null = browserLayoutStorage(),
): HybridWorkbenchLayoutPreference {
  if (!storage) {
    return defaultHybridWorkbenchLayoutPreference();
  }
  try {
    const raw = storage.getItem(WORKBENCH_LAYOUT_STORAGE_KEY);
    if (!raw) {
      return defaultHybridWorkbenchLayoutPreference();
    }
    const parsed = JSON.parse(raw) as Partial<HybridWorkbenchLayoutPreference>;
    return {
      outlineCollapsed: parsed.outlineCollapsed === true,
      workbenchWidth: typeof parsed.workbenchWidth === "number"
        ? clampAssistantWorkbenchWidth(parsed.workbenchWidth)
        : DEFAULT_ASSISTANT_WORKBENCH_WIDTH,
    };
  } catch {
    return defaultHybridWorkbenchLayoutPreference();
  }
}

export function writeHybridWorkbenchLayoutPreference(
  preference: HybridWorkbenchLayoutPreference,
  storage: WorkbenchLayoutStorage | null = browserLayoutStorage(),
): void {
  if (!storage) {
    return;
  }
  try {
    storage.setItem(
      WORKBENCH_LAYOUT_STORAGE_KEY,
      JSON.stringify({
        outlineCollapsed: preference.outlineCollapsed === true,
        workbenchWidth: clampAssistantWorkbenchWidth(preference.workbenchWidth),
      }),
    );
  } catch {
    // Layout persistence is a convenience; rendering should not depend on storage availability.
  }
}
