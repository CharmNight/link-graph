import { describe, expect, it } from "vitest";
import { readFileSync } from "node:fs";
import path from "node:path";

const REPO_ROOT = path.resolve(__dirname, "../../..");

function read(relativePath: string) {
  return readFileSync(path.join(REPO_ROOT, relativePath), "utf8");
}

describe("workbench architecture", () => {
  it("keeps bootstrap projection fully typed", () => {
    const source = read("src/app/controllers/useBootstrapProjectionState.ts");

    expect(source).not.toContain("SetStateAction<any");
    expect(source).not.toContain(": any");
  });

  it("uses explicit grouped workbench state slices instead of setter flood", () => {
    const stateHookSource = read("src/app/controllers/useWorkbenchState.ts");
    const bootstrapSource = read("src/app/controllers/useBootstrapProjectionState.ts");
    const appSource = read("src/app/App.tsx");

    expect(stateHookSource).toContain("WorkbenchProjectionState");
    expect(stateHookSource).toContain("WorkbenchCanvasState");
    expect(bootstrapSource).toContain("projectionState:");
    expect(bootstrapSource).toContain("setProjectionState:");
    expect(appSource).toContain("projectionState");
  });
});
