import { readFileSync } from "node:fs";
import path from "node:path";
import { describe, expect, it } from "vitest";

const APP_ROOT = path.resolve(import.meta.dirname, "../../app");

describe("frontend wording cleanup", () => {
  it("keeps draft-first wording for implementation suggestion and code diff entry points", () => {
    const primaryWorkflowAction = readFileSync(path.join(APP_ROOT, "appPrimaryWorkflowAction.ts"), "utf8");
    const workflowTaskbar = readFileSync(path.join(APP_ROOT, "components/WorkflowTaskbar.tsx"), "utf8");
    const generationTurnCard = readFileSync(path.join(APP_ROOT, "assistant/cards/GenerationTurnCard.tsx"), "utf8");
    const codeDraftTurnCard = readFileSync(path.join(APP_ROOT, "assistant/cards/CodeDraftTurnCard.tsx"), "utf8");

    expect(primaryWorkflowAction).toContain("生成实现建议");
    expect(primaryWorkflowAction).toContain("生成代码 diff");
    expect(workflowTaskbar).toContain("对比代码");

    expect(generationTurnCard).toContain("生成实现建议会先整理可审查方案");
    expect(generationTurnCard).toContain("实现建议追问");
    expect(generationTurnCard).toContain("生成实现建议");
    expect(generationTurnCard).toContain("生成代码 diff");

    expect(codeDraftTurnCard).toContain("还没有代码草稿");
    expect(codeDraftTurnCard).toContain("生成代码 diff");
    expect(codeDraftTurnCard).toContain("重新生成代码 diff");
    expect(codeDraftTurnCard).not.toContain("生成代码草稿");
  });
});
