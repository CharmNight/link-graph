import { describe, expect, it } from "vitest";
import {
  DEFAULT_ASSISTANT_WORKBENCH_WIDTH,
  clampAssistantWorkbenchWidth,
  readHybridWorkbenchLayoutPreference,
  writeHybridWorkbenchLayoutPreference,
} from "../../app/appWorkbenchPreferences";

class MemoryStorage {
  private readonly values = new Map<string, string>();

  getItem(key: string): string | null {
    return this.values.get(key) ?? null;
  }

  setItem(key: string, value: string): void {
    this.values.set(key, value);
  }
}

describe("appWorkbenchPreferences", () => {
  it("clamps assistant workbench width into the supported range", () => {
    expect(clampAssistantWorkbenchWidth(100)).toBe(320);
    expect(clampAssistantWorkbenchWidth(480.4)).toBe(480);
    expect(clampAssistantWorkbenchWidth(900)).toBe(720);
  });

  it("reads missing or invalid layout preferences as defaults", () => {
    expect(readHybridWorkbenchLayoutPreference(null)).toEqual({
      outlineCollapsed: false,
      workbenchWidth: DEFAULT_ASSISTANT_WORKBENCH_WIDTH,
    });

    const storage = new MemoryStorage();
    storage.setItem("linkGraph.hybridWorkbenchLayout", "{broken");

    expect(readHybridWorkbenchLayoutPreference(storage)).toEqual({
      outlineCollapsed: false,
      workbenchWidth: DEFAULT_ASSISTANT_WORKBENCH_WIDTH,
    });
  });

  it("persists and reloads normalized layout preferences", () => {
    const storage = new MemoryStorage();

    writeHybridWorkbenchLayoutPreference({
      outlineCollapsed: true,
      workbenchWidth: 900,
    }, storage);

    expect(readHybridWorkbenchLayoutPreference(storage)).toEqual({
      outlineCollapsed: true,
      workbenchWidth: 720,
    });
  });
});
