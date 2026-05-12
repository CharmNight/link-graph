import { describe, expect, it } from "vitest";
import {
  beautificationBoundaryDescription,
  generationSourceLabel,
  llmResultSourceLabel,
  patchResultBoundaryDescription,
} from "../../app/labels";

describe("labels", () => {
  it("labels LOCAL_RULE as local deterministic or rule-based output", () => {
    expect(llmResultSourceLabel("LOCAL_RULE")).toBe("本地规则");
    expect(generationSourceLabel("LOCAL_RULE")).toBe("规则生成");
    expect(patchResultBoundaryDescription("LOCAL_RULE")).toContain("本地规则分析");
    expect(beautificationBoundaryDescription("LOCAL_RULE")).toContain("本地规则整理");
  });
});
