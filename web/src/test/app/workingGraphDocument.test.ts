import { describe, expect, it } from "vitest";
import { existsSync, readFileSync } from "node:fs";
import path from "node:path";

const REPO_ROOT = path.resolve(__dirname, "../../..");
const WORKING_GRAPH_DOCUMENT_PATH = path.join(REPO_ROOT, "src/app/workingGraphDocument.ts");
const SAMPLE_STATE_PATH = path.join(REPO_ROOT, "src/app/sampleState.ts");
const DEBUG_PATH = path.join(REPO_ROOT, "src/app/debug.ts");
const TEST_BOOTSTRAP_PATH = path.join(REPO_ROOT, "src/app/testBootstrapState.ts");

describe("workingGraphDocument architecture gate", () => {
  it("removes the legacy workingGraphDocument helper entirely", () => {
    expect(existsSync(WORKING_GRAPH_DOCUMENT_PATH)).toBe(false);
  });

  it("removes all imports of the legacy workingGraphDocument helper", () => {
    const sampleStateSource = readFileSync(SAMPLE_STATE_PATH, "utf8");
    const debugSource = readFileSync(DEBUG_PATH, "utf8");
    const testBootstrapSource = readFileSync(TEST_BOOTSTRAP_PATH, "utf8");

    expect(sampleStateSource).not.toContain("workingGraphDocument");
    expect(debugSource).not.toContain("workingGraphDocument");
    expect(testBootstrapSource).not.toContain("workingGraphDocument");
  });
});
