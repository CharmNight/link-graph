import { readFileSync } from "node:fs";
import path from "node:path";
import { describe, expect, it } from "vitest";

const REPO_ROOT = path.resolve(__dirname, "../../..");

function read(relativePath: string) {
  return readFileSync(path.join(REPO_ROOT, relativePath), "utf8");
}

function interfaceBlock(source: string, interfaceName: string) {
  const match = source.match(new RegExp(`export interface ${interfaceName} \\{([\\s\\S]*?)\\n\\}`, "m"));
  return match?.[0] ?? "";
}

describe("scene-state architecture gate", () => {
  it("moves bootstrap transport to canonical workspace graphs plus scene states", () => {
    const source = read("src/app/types.ts");
    const graphTypesSource = read("src/app/graphTypes.ts");
    const bootstrapStateSource = interfaceBlock(source, "LinkGraphBootstrapState");

    expect(source).toContain("LinkGraphSceneId");
    expect(graphTypesSource).toContain("export type LinkGraphSceneId");
    expect(bootstrapStateSource).toContain("currentSceneId:");
    expect(bootstrapStateSource).toContain("sceneStates:");
    expect(bootstrapStateSource).toContain("workspaceGraph:");
    expect(bootstrapStateSource).toContain("workspaceBaseGraph:");
    expect(bootstrapStateSource).toContain("semanticFactGraph:");
    expect(bootstrapStateSource).not.toContain("visibleGraph:");
    expect(bootstrapStateSource).not.toContain("workingGraph:");
    expect(bootstrapStateSource).not.toContain("referenceWorkingGraph");
    expect(bootstrapStateSource).not.toContain("referenceFactGraph");
    expect(bootstrapStateSource).not.toContain("currentSceneSelection");
    expect(bootstrapStateSource).not.toContain("currentSceneAnchor");
    expect(bootstrapStateSource).not.toContain("layoutState:");
    expect(bootstrapStateSource).not.toContain("selectedNodeId:");
  });

  it("updates the frontend state shell to read selection and layout from scene states", () => {
    const appSource = read("src/app/App.tsx");
    const workbenchStateSource = read("src/app/controllers/useWorkbenchState.ts");
    const workbenchDerivedStateSource = read("src/app/controllers/useWorkbenchDerivedState.ts");
    const bootstrapProjectionSource = read("src/app/controllers/useBootstrapProjectionState.ts");

    expect(appSource).not.toContain("publishGraphChange");
    expect(appSource).not.toContain("resolveReferenceWorkingGraph");
    expect(appSource).not.toContain("resolveReferenceFactGraph");
    expect(appSource).not.toContain("initialState.selectedNodeId");
    expect(workbenchStateSource).toContain("sceneStates");
    expect(workbenchStateSource).toContain("currentSceneId");
    expect(workbenchStateSource).not.toContain("selectedNodeId: initialState.selectedNodeId");
    expect(workbenchDerivedStateSource).toContain("workspaceBaseGraph");
    expect(workbenchDerivedStateSource).toContain("semanticFactGraph");
    expect(workbenchDerivedStateSource).not.toContain("referenceWorkingGraph");
    expect(workbenchDerivedStateSource).not.toContain("factGraph:");
    expect(bootstrapProjectionSource).toContain("nextState.sceneStates");
    expect(bootstrapProjectionSource).toContain("nextState.currentSceneId");
    expect(bootstrapProjectionSource).not.toContain("nextState.layoutState");
    expect(bootstrapProjectionSource).not.toContain("nextState.selectedNodeId");
  });
});
