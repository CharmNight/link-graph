import { readFileSync } from "node:fs";
import path from "node:path";
import { describe, expect, it } from "vitest";

const APP_ROOT = path.resolve(import.meta.dirname, "../../app");

describe("frontend wording cleanup", () => {
  it("keeps draft-first wording for implementation suggestion and code diff entry points", () => {
    const toolbar = readFileSync(path.join(APP_ROOT, "components/Toolbar.tsx"), "utf8");
    const generationPlanPanel = readFileSync(path.join(APP_ROOT, "components/GenerationPlanPanel.tsx"), "utf8");
    const codeDraftPanel = readFileSync(path.join(APP_ROOT, "components/CodeDraftPanel.tsx"), "utf8");

    expect(toolbar).toContain("生成实现建议");
    expect(toolbar).toContain("生成代码 diff");
    expect(toolbar).not.toContain("生成计划");
    expect(toolbar).not.toContain("生成草稿");

    expect(generationPlanPanel).toContain("实现建议会基于当前草稿快照生成");
    expect(generationPlanPanel).toContain("继续追问这份实现建议");
    expect(generationPlanPanel).toContain("生成实现建议");
    expect(generationPlanPanel).not.toContain("前往问答风险");
    expect(generationPlanPanel).not.toContain("计划阶段准入状态尚未就绪");

    expect(codeDraftPanel).toContain("生成代码 diff");
    expect(codeDraftPanel).toContain("代码 diff 工作台");
    expect(codeDraftPanel).toContain("处理阻塞风险");
    expect(codeDraftPanel).not.toContain("生成代码草稿");
  });
});
