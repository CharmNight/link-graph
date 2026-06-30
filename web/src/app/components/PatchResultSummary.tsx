import { llmResultSourceLabel } from "../labels";
import type { GraphPatchResult } from "../types";
import { EvidenceFindingsSection } from "./EvidenceFindingsSection";

/** PatchResultSummary 组件的入参。 */
interface PatchResultSummaryProps {
  /** 卡片标题。 */
  title: string;
  /** 待展示的图补丁结果。 */
  result: GraphPatchResult;
  /** 通过 artifactId 解析文本的回调。 */
  resolveArtifactText?: (artifactId: string) => string | null;
  /** 按需加载 artifact 的回调。 */
  onRequestArtifact?: (artifactId: string) => void;
}

/**
 * 把回答文本拆分为结构化段：核心结论 / 关键影响 / 建议动作 / 补充说明。
 *
 * 优先按"标题段落"解析（核心结论: ... / 关键影响: ... 等）；
 * 解析失败时退化到按句子切分（单句、两句、多句的不同处理）。
 */
function splitAnswer(answer: string): {
  summary: string;
  impacts: string[];
  actions: string[];
  notes: string[];
} {
  // 切行、去空白、过滤空行
  const normalizedLines = answer
    .split(/\r?\n/)
    .map((line) => line.trim())
    .filter(Boolean);
  // 先尝试按结构化标题解析
  const structured = parseStructuredLines(normalizedLines);
  if (structured) {
    return structured;
  }

  // 兜底：按句子（。！？!? 分隔）切分
  const segments = (answer.match(/[^。！？!?]+[。！？!?]?/g) ?? [answer])
    .map((segment) => segment.trim())
    .filter(Boolean);

  if (segments.length === 0) {
    return {
      summary: "暂无可展示的回答。",
      impacts: [],
      actions: [],
      notes: [],
    };
  }

  // 单句：只有摘要
  if (segments.length === 1) {
    return {
      summary: segments[0]!,
      impacts: [],
      actions: [],
      notes: [],
    };
  }

  // 两句：摘要 + 一个动作
  if (segments.length === 2) {
    return {
      summary: segments[0]!,
      impacts: [],
      actions: [segments[1]!],
      notes: [],
    };
  }

  // 多句：首句摘要，中间为影响，末句为动作
  return {
    summary: segments[0]!,
    impacts: segments.slice(1, -1),
    actions: [segments[segments.length - 1]!],
    notes: [],
  };
}

/**
 * 按"标题段落"解析回答。
 *
 * 识别四种段落：核心结论 / 关键影响 / 建议动作 / 注意事项。
 * 段落标题支持多种别名（例如"结论"、"总结"、"摘要"都识别为 summary）。
 * 任一行命中标题正则后，后续行进入对应段落，直到下一个标题。
 *
 * 完全没匹配到任何标题时返回 null（让调用方走句子切分兜底）。
 */
function parseStructuredLines(lines: string[]): {
  summary: string;
  impacts: string[];
  actions: string[];
  notes: string[];
} | null {
  if (lines.length === 0) {
    return null;
  }
  type SectionKey = "summary" | "impacts" | "actions" | "notes";
  const structured = {
    summary: "",
    impacts: [] as string[],
    actions: [] as string[],
    notes: [] as string[],
  };
  let currentSection: SectionKey | null = null;
  // 标记是否至少匹配到一个标题；都没匹配则返回 null
  let usedHeading = false;

  // 段落匹配规则：标题 + 可选内联内容
  const sectionMatchers: Array<{ key: SectionKey; pattern: RegExp }> = [
    { key: "summary", pattern: /^(?:核心结论|结论|总结|摘要)[:：]?\s*(.*)$/ },
    { key: "impacts", pattern: /^(?:关键影响|影响|风险|问题)[:：]?\s*(.*)$/ },
    { key: "actions", pattern: /^(?:建议动作|处理建议|修复建议|下一步|建议|动作)[:：]?\s*(.*)$/ },
    { key: "notes", pattern: /^(?:注意事项|注意|前提|限制|备注)[:：]?\s*(.*)$/ },
  ];

  /** 去除列表项前缀（-、*、•、数字.），保留正文。 */
  const normalizeListItem = (value: string) => value.replace(/^[-*•]\s+/, "").replace(/^\d+[.)]\s+/, "").trim();

  /** 把一条原始文本加入到指定段落。 */
  const pushToSection = (section: SectionKey, rawValue: string) => {
    const value = normalizeListItem(rawValue);
    if (!value) {
      return;
    }
    // summary 是单字符串，多次写入时拼接
    if (section === "summary") {
      structured.summary = structured.summary ? `${structured.summary} ${value}`.trim() : value;
      return;
    }
    structured[section].push(value);
  };

  lines.forEach((line) => {
    const matchedSection = sectionMatchers.find(({ pattern }) => pattern.test(line));
    if (matchedSection) {
      usedHeading = true;
      const matched = line.match(matchedSection.pattern);
      currentSection = matchedSection.key;
      // 标题行可能直接带内联内容（"结论：xxx"）
      const inlineValue = matched?.[1]?.trim() ?? "";
      if (inlineValue) {
        pushToSection(currentSection, inlineValue);
      }
      return;
    }

    // 未进入任何段落时，把当前行视为 summary
    if (!currentSection) {
      pushToSection("summary", line);
      return;
    }

    pushToSection(currentSection, line);
  });

  // 一条标题都没匹配：让调用方走兜底
  if (!usedHeading) {
    return null;
  }

  return {
    summary: structured.summary || "暂无可展示的回答。",
    impacts: structured.impacts,
    actions: structured.actions,
    notes: structured.notes,
  };
}

/**
 * 清洗警告文本：去除 "/path/to/file.java" 等路径片段，
 * 让警告在 UI 上更易读。
 */
function readableWarning(warning: string): string {
  // 去掉 ")" 或 "）" 之前的 " / xxx.yy" 形式路径
  return warning.replace(/\s*\/\s*[a-z0-9_.-]+(?=[)）])/gi, "");
}

/**
 * 图补丁结果摘要卡片。
 *
 * 把 LLM 返回的回答拆分为多个结构化段（核心结论、关键影响、建议动作、补充说明、注意事项），
 * 并展示证据发现列表。让用户能快速浏览关键信息而不是阅读整段原文。
 */
export function PatchResultSummary({
  title,
  result,
}: PatchResultSummaryProps) {
  const sections = splitAnswer(result.answer);
  const patchSummary = result.patch?.summary?.trim() || null;
  // 仅当 patchSummary 与已展示内容不重复时才显示"草稿写回建议"段
  const shouldShowPatchSummary = Boolean(
    patchSummary &&
      patchSummary !== sections.summary &&
      !sections.impacts.includes(patchSummary) &&
      !sections.actions.includes(patchSummary),
  );
  // 清洗警告，去除路径噪音
  const warnings = result.warnings.map(readableWarning);

  return (
    <article className="answer-card">
      <div className="preview-head">
        <strong>{title}</strong>
        <span className="toolbar-chip">来源 {llmResultSourceLabel(result.source)}</span>
      </div>

      <div className="answer-structure">
        <section className="answer-section">
          <strong>提问</strong>
          <p>{result.question}</p>
        </section>

        <section className="answer-section">
          <strong>核心结论</strong>
          <p>{sections.summary}</p>
        </section>

        <EvidenceFindingsSection findings={result.findings ?? []} />

        {sections.impacts.length > 0 ? (
          <section className="answer-section">
            <strong>关键影响</strong>
            <ul className="answer-list">
              {sections.impacts.map((impact, index) => (
                <li key={`${impact}-${index}`} className="muted">{impact}</li>
              ))}
            </ul>
          </section>
        ) : null}

        {sections.actions.length > 0 ? (
          <section className="answer-section">
            <strong>建议动作</strong>
            <ul className="answer-list">
              {sections.actions.map((action, index) => (
                <li key={`${action}-${index}`} className="muted">{action}</li>
              ))}
            </ul>
          </section>
        ) : null}

        {shouldShowPatchSummary && patchSummary ? (
          <section className="answer-section">
            <strong>草稿写回建议</strong>
            <p>{patchSummary}</p>
          </section>
        ) : null}

        {sections.notes.length > 0 ? (
          <section className="answer-section">
            <strong>补充说明</strong>
            <ul className="answer-list">
              {sections.notes.map((note, index) => (
                <li key={`${note}-${index}`} className="muted">{note}</li>
              ))}
            </ul>
          </section>
        ) : null}

        {warnings.length > 0 ? (
          <section className="answer-section">
            <strong>注意事项</strong>
            <ul className="answer-list warning-list">
              {warnings.map((warning, index) => (
                <li key={`${warning}-${index}`} className="muted">
                  {warning}
                </li>
              ))}
            </ul>
          </section>
        ) : null}
      </div>
    </article>
  );
}
