import { describe, expect, it } from "vitest";
import { parseStructuredRichText } from "./structuredRichText";

describe("parseStructuredRichText", () => {
  it("does not mark heading plus one summary paragraph as expandable", () => {
    const parsed = parseStructuredRichText("### 结论\n这里只需要一句总结。");

    expect(parsed).not.toBeNull();
    expect(parsed?.heading).toBe("结论");
    expect(parsed?.summaryLines).toEqual(["这里只需要一句总结。"]);
    expect(parsed?.detailBlocks).toEqual([]);
    expect(parsed?.shouldCollapse).toBe(false);
  });

  it("marks ordered follow-up details as expandable", () => {
    const parsed = parseStructuredRichText("### 上传链路\n先总结一句。\n\n1) 读取目录\n2) 写入文件");

    expect(parsed?.shouldCollapse).toBe(true);
    expect(parsed?.detailBlocks).toHaveLength(1);
    expect(parsed?.detailBlocks[0]?.type).toBe("ordered");
  });

  it("keeps bullet details as real detail blocks", () => {
    const parsed = parseStructuredRichText("### 风险判断\n当前结论。\n\n- 风险点：异常吞掉\n- 建议：统一失败响应");

    expect(parsed?.shouldCollapse).toBe(true);
    expect(parsed?.detailBlocks).toHaveLength(1);
    expect(parsed?.detailBlocks[0]?.type).toBe("bullet");
  });

  it("treats plain text without extra detail blocks as fully visible", () => {
    const parsed = parseStructuredRichText("这是一个没有标题、没有列表、没有额外详情的回答。");

    expect(parsed?.summaryLines).toEqual(["这是一个没有标题、没有列表、没有额外详情的回答。"]);
    expect(parsed?.detailBlocks).toEqual([]);
    expect(parsed?.shouldCollapse).toBe(false);
  });
});
