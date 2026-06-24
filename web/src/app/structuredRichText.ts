/**
 * 一段结构化富文本的最小渲染单元：段落、无序项目符号、有序编号列表。
 * 由 [parseStructuredRichText] 解析时生成，UI 按类型选择不同的渲染样式。
 */
export interface StructuredRichTextBlock {
  /** 块类型：段落 / 无序 / 有序。 */
  type: "paragraph" | "bullet" | "ordered";
  /** 该块包含的纯文本行（已去除前缀标记）。 */
  lines: string[];
}

/**
 * 富文本解析结果：拆分为标题、摘要、详细内容若干块，
 * 并给出"是否应折叠"的提示让 UI 决定初始展开状态。
 */
export interface ParsedStructuredRichText {
  /** 解析出的标题，缺失时为 null。 */
  heading: string | null;
  /** 摘要段落的多行文本。 */
  summaryLines: string[];
  /** 由摘要拼接压缩出的单句摘要，便于卡片列表等紧凑场景展示。 */
  summarySentence: string | null;
  /** 详细内容块序列。 */
  detailBlocks: StructuredRichTextBlock[];
  /** 是否建议初始折叠详细内容。 */
  shouldCollapse: boolean;
}

/**
 * 对原始富文本做归一化：
 * - 把 Windows 换行统一为 \n；
 * - 把行内出现的"数字."或"数字)"形式重新折行，修复 LLM 输出常见粘连问题；
 * - 去除首尾空白。
 * 归一化后的文本才进入块解析流程。
 */
function normalizeStructuredRichText(content: string): string {
  return content
    .replace(/\r\n/g, "\n")
    // 行内的"数字."或"数字)"且后面跟着空白时，把它前移到新行首，使其被识别为有序项
    .replace(/([^\n])\s+(\d+)[.)]\s+/g, "$1\n$2. ")
    // 已在新行但用右括号"数字)"形式的也统一为"数字."形式
    .replace(/\n(\d+)\)\s+/g, "\n$1. ")
    .trim();
}

/**
 * 从多行摘要中抽取一个"单句摘要"。
 * 优先匹配第一个中英文句末标点之前的内容；若没有合适的句子且全文较短则原样返回；
 * 较长时截取前 44 字符并补省略号。
 */
function extractSummarySentence(lines: string[]): string | null {
  const fullText = lines.join(" ").trim();
  if (!fullText) {
    return null;
  }
  // 优先取到第一个句末标点之前的句子
  const sentenceMatch = fullText.match(/^(.+?[。！？!?])/);
  if (sentenceMatch) {
    return sentenceMatch[1];
  }
  // 没有句末标点但内容本身很短，整段返回
  if (fullText.length <= 44) {
    return fullText;
  }
  // 较长时硬截断并标注省略
  return `${fullText.slice(0, 44).trimEnd()}...`;
}

/**
 * 把一段纯文本解析为结构化富文本。
 *
 * 流程：
 * 1) 归一化文本；
 * 2) 按行扫描，依据行首特征分类为标题（#/##/...）、无序（-、*、•）、有序（数字.或)）、普通段落；
 * 3) 同类相邻行合并为同一块，不同类之间自动断块；
 * 4) 把首个标题与首个非标题段落提取为 heading/summary，剩余作为 detailBlocks。
 *
 * 文本为空或仅空白时返回 null，由调用方决定空态展示。
 */
export function parseStructuredRichText(content: string): ParsedStructuredRichText | null {
  const normalized = normalizeStructuredRichText(content);
  if (!normalized) {
    return null;
  }

  const lines = normalized.split("\n").map((line) => line.trim());
  // 解析过程中的临时块类型，比导出的 StructuredRichTextBlock 多一个 heading
  const blocks: Array<{ type: "heading" | "paragraph" | "bullet" | "ordered"; lines: string[] }> = [];
  // 当前正在累积的块；遇到不同类型时需要先把当前块 flush 再起新块
  let currentBlock: StructuredRichTextBlock | null = null;

  /** 把当前累积的块（若有内容）提交到 blocks 数组，然后清空 currentBlock。 */
  function flushCurrentBlock() {
    if (currentBlock && currentBlock.lines.length > 0) {
      blocks.push(currentBlock);
    }
    currentBlock = null;
  }

  for (const line of lines) {
    // 空行作为块分隔符
    if (!line) {
      flushCurrentBlock();
      continue;
    }

    // 标题行：1~4 个 # 加空格开头
    if (/^#{1,4}\s+/.test(line)) {
      flushCurrentBlock();
      blocks.push({ type: "heading", lines: [line.replace(/^#{1,4}\s+/, "")] });
      continue;
    }

    // 无序符号：-、*、• 加空格开头
    if (/^[-*•]\s+/.test(line)) {
      if (!currentBlock || currentBlock.type !== "bullet") {
        flushCurrentBlock();
        currentBlock = { type: "bullet", lines: [] };
      }
      currentBlock.lines.push(line.replace(/^[-*•]\s+/, ""));
      continue;
    }

    // 有序编号：数字 + . 或 ) 加空格开头
    if (/^\d+[.)]\s+/.test(line)) {
      if (!currentBlock || currentBlock.type !== "ordered") {
        flushCurrentBlock();
        currentBlock = { type: "ordered", lines: [] };
      }
      currentBlock.lines.push(line.replace(/^\d+[.)]\s+/, ""));
      continue;
    }

    // 普通段落
    if (!currentBlock || currentBlock.type !== "paragraph") {
      flushCurrentBlock();
      currentBlock = { type: "paragraph", lines: [] };
    }
    currentBlock.lines.push(line);
  }

  // 扫描结束前 flush 残留块
  flushCurrentBlock();

  // 第一个块是 heading 时单独抽出
  const headingBlock = blocks[0]?.type === "heading" ? blocks[0] : null;
  // 抽取第一个非 heading 段落作为摘要段落
  const summaryParagraphIndex = blocks.findIndex((block, index) => (
    block.type === "paragraph" && (!headingBlock || index > 0)
  ));
  const summaryParagraph = summaryParagraphIndex >= 0 ? blocks[summaryParagraphIndex] : null;
  // 详细块 = 排除 heading 与摘要段落后的其余块
  const detailBlocks = blocks.filter((block, index): block is StructuredRichTextBlock => {
    if (block.type === "heading") {
      return false;
    }
    return index !== summaryParagraphIndex;
  });

  return {
    heading: headingBlock?.lines[0] ?? null,
    // 没有摘要段落但有详细块时直接返回空数组；既无摘要也无详细块时退回原始归一化文本
    summaryLines: summaryParagraph?.lines ?? (detailBlocks.length === 0 ? normalized.split("\n").filter(Boolean) : []),
    summarySentence: extractSummarySentence(summaryParagraph?.lines ?? []),
    detailBlocks,
    // 存在详细块时建议初始折叠，避免一次展示过多内容
    shouldCollapse: detailBlocks.length > 0,
  };
}
