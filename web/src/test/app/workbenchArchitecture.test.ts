import { describe, expect, it } from "vitest";
import { existsSync, readFileSync, readdirSync, statSync } from "node:fs";
import path from "node:path";

const REPO_ROOT = path.resolve(__dirname, "../../..");

function read(relativePath: string) {
  return readFileSync(path.join(REPO_ROOT, relativePath), "utf8");
}

function listFiles(root: string): string[] {
  if (!existsSync(root)) {
    return [];
  }
  return readdirSync(root).flatMap((entry) => {
    const resolved = path.join(root, entry);
    if (statSync(resolved).isDirectory()) {
      return listFiles(resolved);
    }
    return [resolved];
  });
}

describe("workbench architecture", () => {
  it("converges the right side on the final assistant shell without removed page modules", () => {
    const appSource = read("src/app/App.tsx");
    const shellSource = read("src/app/assistant/AssistantWorkbenchShell.tsx");
    const composerSource = read("src/app/assistant/AssistantComposer.tsx");
    const threadSource = read("src/app/assistant/AssistantThread.tsx");
    const generationCardSource = read("src/app/assistant/cards/GenerationTurnCard.tsx");
    const typesSource = read("src/app/types.ts");
    const apiSource = read("src/app/api.ts");
    const derivedStateSource = read("src/app/controllers/useWorkbenchDerivedState.ts");
    const bootstrapProjectionSource = read("src/app/controllers/useBootstrapProjectionState.ts");
    const removedPageModuleNames = [
      ["App", "Workbench", "Panels"],
      ["Qa", "Tab"],
      ["Explanation", "Tab"],
      ["Generation", "Plan", "Panel"],
    ].map((parts) => parts.join(""));
    const removedControllerNames = [
      ["use", "Qa", "Workbench", "Controller"],
      ["use", "Explanation", "Workbench", "Controller"],
    ].map((parts) => parts.join(""));
    const removedViewStateNames = [
      ["Qa", "Workbench", "State"],
      ["Explanation", "Workbench", "State"],
    ].map((parts) => parts.join(""));
    const removedShellPropName = ["leg", "acy", "Workbench", "Content"].join("");
    const removedSectionPreferenceNames = [
      ["Workbench", "Section", "Preferences"],
      ["workbench", "Section", "Preferences"],
      ["update", "Workbench", "Section", "Preference"],
    ].map((parts) => parts.join(""));

    expect(appSource).toContain("AssistantWorkbenchShell");
    for (const removedName of removedPageModuleNames) {
      expect(appSource).not.toContain(removedName);
    }
    for (const removedName of removedControllerNames) {
      expect(appSource).not.toContain(removedName);
      expect(existsSync(path.join(REPO_ROOT, "src/app/controllers", `${removedName}.ts`))).toBe(false);
    }
    for (const removedName of removedViewStateNames) {
      expect(typesSource).not.toContain(removedName);
      expect(derivedStateSource).not.toContain(removedName);
    }
    for (const removedName of removedSectionPreferenceNames) {
      expect(typesSource).not.toContain(removedName);
      expect(apiSource).not.toContain(removedName);
      expect(bootstrapProjectionSource).not.toContain(removedName);
    }
    expect(appSource).not.toContain(removedShellPropName);

    expect(shellSource).not.toContain(removedShellPropName);
    expect(shellSource).toContain("<AssistantContextBar");
    expect(shellSource).not.toContain("<AssistantActionSelector");
    expect(shellSource).toContain("<AssistantThread");
    expect(shellSource).toContain("<AssistantComposer");
    expect(shellSource).toContain("onActionChange={onActionChange}");
    expect(composerSource).toContain("<AssistantActionSelector");
    expect(composerSource).toContain("发送为");

    expect(threadSource).toContain("ExplanationTurnCard");
    expect(threadSource).toContain("QaTurnCard");
    expect(threadSource).toContain("CandidateChangeCard");
    expect(threadSource).toContain("RiskThreadCard");
    expect(threadSource).toContain("GenerationTurnCard");
    expect(threadSource).toContain("CodeDraftTurnCard");
    expect(threadSource).toContain("CheckTurnCard");
    expect(generationCardSource).not.toContain("<textarea");
  });

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

  it("keeps the top-level app shell below the next decomposition threshold", () => {
    const appSource = read("src/app/App.tsx");

    expect(appSource.split("\n").length).toBeLessThan(1460);
  });

  it("keeps graph primitives out of the monolithic frontend type barrel", () => {
    const typesSource = read("src/app/types.ts");
    const graphTypesSource = read("src/app/graphTypes.ts");

    expect(typesSource.split("\n").length).toBeLessThan(1250);
    expect(typesSource).toContain("./graphTypes");
    expect(graphTypesSource).toContain("export interface LinkGraphNode");
    expect(graphTypesSource).toContain("export interface LinkGraphDocument");
    expect(typesSource).not.toMatch(/^export interface LinkGraphNode\b/m);
    expect(typesSource).not.toMatch(/^export interface GraphPatch\b/m);
  });

  it("centralizes workbench artifact status rules outside UI derivations and hooks", () => {
    const primaryWorkflowActionSource = read("src/app/appPrimaryWorkflowAction.ts");
    const hybridDerivationsSource = read("src/app/components/hybridDerivations.ts");
    const derivedStateSource = read("src/app/controllers/useWorkbenchDerivedState.ts");

    expect(primaryWorkflowActionSource).toContain("workbenchStatusModel");
    expect(hybridDerivationsSource).toContain("workbenchStatusModel");
    expect(derivedStateSource).toContain("workbenchStatusModel");
    expect(primaryWorkflowActionSource).not.toContain("\"MISSING\" | \"RUNNING\" | \"FRESH\" | \"STALE\" | \"FAILED\"");
    expect(hybridDerivationsSource).not.toContain("\"MISSING\" | \"RUNNING\" | \"FRESH\" | \"STALE\" | \"FAILED\"");
    expect(derivedStateSource).not.toContain("\"MISSING\" | \"RUNNING\" | \"FRESH\" | \"STALE\" | \"FAILED\"");
  });

  it("keeps assistant stage target typing in display selectors", () => {
    const displaySelectorsSource = read("src/app/appDisplaySelectors.ts");
    const derivedStateSource = read("src/app/controllers/useWorkbenchDerivedState.ts");

    expect(displaySelectorsSource).toContain("export type AssistantStageTarget");
    expect(derivedStateSource).toContain("type AssistantStageTarget");
    expect(derivedStateSource).not.toContain("type AssistantStageTarget = \"explanation\" | \"qa\" | \"draft\" | \"code\"");
  });

  it("does not keep obsolete assistant workbench prototype documents as implementation guidance", () => {
    const docsRoot = path.join(REPO_ROOT, "../docs/internal");
    const obsoleteFragments = [
      ["Assistant", "Intent", "Selector"].join(""),
      ["App", "Workbench", "Panels"].join(""),
      ["Qa", "Tab"].join(""),
      ["Explanation", "Tab"].join(""),
      ["Generation", "Plan", "Panel"].join(""),
      ["Code", "Draft", "Panel"].join(""),
      ["legacy", "Workbench", "Content"].join(""),
    ];
    const offenders = listFiles(docsRoot)
      .filter((file) => [".md", ".html"].includes(path.extname(file)))
      .flatMap((file) => {
        const source = readFileSync(file, "utf8");
        return obsoleteFragments
          .filter((fragment) => source.includes(fragment))
          .map((fragment) => `${path.relative(path.join(REPO_ROOT, ".."), file)} contains ${fragment}`);
      });

    expect(offenders).toEqual([]);
  });

  it("does not keep obsolete investigation lead label helpers", () => {
    const labelsSource = read("src/app/labels.ts");

    expect(labelsSource).not.toContain("investigationLeadStatusLabel");
    expect(labelsSource).not.toContain("InvestigationLead");
  });
});
