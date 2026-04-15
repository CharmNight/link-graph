export interface StructuredRichTextBlock {
  type: "paragraph" | "bullet" | "ordered";
  lines: string[];
}

export interface ParsedStructuredRichText {
  heading: string | null;
  summaryLines: string[];
  summarySentence: string | null;
  detailBlocks: StructuredRichTextBlock[];
  shouldCollapse: boolean;
}

function normalizeStructuredRichText(content: string): string {
  return content
    .replace(/\r\n/g, "\n")
    .replace(/([^\n])\s+(\d+)[.)]\s+/g, "$1\n$2. ")
    .replace(/\n(\d+)\)\s+/g, "\n$1. ")
    .trim();
}

function extractSummarySentence(lines: string[]): string | null {
  const fullText = lines.join(" ").trim();
  if (!fullText) {
    return null;
  }
  const sentenceMatch = fullText.match(/^(.+?[。！？!?])/);
  if (sentenceMatch) {
    return sentenceMatch[1];
  }
  if (fullText.length <= 44) {
    return fullText;
  }
  return `${fullText.slice(0, 44).trimEnd()}...`;
}

export function parseStructuredRichText(content: string): ParsedStructuredRichText | null {
  const normalized = normalizeStructuredRichText(content);
  if (!normalized) {
    return null;
  }

  const lines = normalized.split("\n").map((line) => line.trim());
  const blocks: Array<{ type: "heading" | "paragraph" | "bullet" | "ordered"; lines: string[] }> = [];
  let currentBlock: StructuredRichTextBlock | null = null;

  function flushCurrentBlock() {
    if (currentBlock && currentBlock.lines.length > 0) {
      blocks.push(currentBlock);
    }
    currentBlock = null;
  }

  for (const line of lines) {
    if (!line) {
      flushCurrentBlock();
      continue;
    }

    if (/^#{1,4}\s+/.test(line)) {
      flushCurrentBlock();
      blocks.push({ type: "heading", lines: [line.replace(/^#{1,4}\s+/, "")] });
      continue;
    }

    if (/^[-*•]\s+/.test(line)) {
      if (!currentBlock || currentBlock.type !== "bullet") {
        flushCurrentBlock();
        currentBlock = { type: "bullet", lines: [] };
      }
      currentBlock.lines.push(line.replace(/^[-*•]\s+/, ""));
      continue;
    }

    if (/^\d+[.)]\s+/.test(line)) {
      if (!currentBlock || currentBlock.type !== "ordered") {
        flushCurrentBlock();
        currentBlock = { type: "ordered", lines: [] };
      }
      currentBlock.lines.push(line.replace(/^\d+[.)]\s+/, ""));
      continue;
    }

    if (!currentBlock || currentBlock.type !== "paragraph") {
      flushCurrentBlock();
      currentBlock = { type: "paragraph", lines: [] };
    }
    currentBlock.lines.push(line);
  }

  flushCurrentBlock();

  const headingBlock = blocks[0]?.type === "heading" ? blocks[0] : null;
  const summaryParagraphIndex = blocks.findIndex((block, index) => (
    block.type === "paragraph" && (!headingBlock || index > 0)
  ));
  const summaryParagraph = summaryParagraphIndex >= 0 ? blocks[summaryParagraphIndex] : null;
  const detailBlocks = blocks.filter((block, index): block is StructuredRichTextBlock => {
    if (block.type === "heading") {
      return false;
    }
    return index !== summaryParagraphIndex;
  });

  return {
    heading: headingBlock?.lines[0] ?? null,
    summaryLines: summaryParagraph?.lines ?? (detailBlocks.length === 0 ? normalized.split("\n").filter(Boolean) : []),
    summarySentence: extractSummarySentence(summaryParagraph?.lines ?? []),
    detailBlocks,
    shouldCollapse: detailBlocks.length > 0,
  };
}
