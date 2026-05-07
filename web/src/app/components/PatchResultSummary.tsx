import { llmResultSourceLabel, patchResultBoundaryDescription } from "../labels";
import type { GraphPatchResult } from "../types";
import { EvidenceFindingsSection } from "./EvidenceFindingsSection";
import { RequestPromptDisclosure } from "./RequestPromptDisclosure";

interface PatchResultSummaryProps {
  title: string;
  result: GraphPatchResult;
  resolveArtifactText?: (artifactId: string) => string | null;
  onRequestArtifact?: (artifactId: string) => void;
}

function splitAnswer(answer: string): {
  summary: string;
  impacts: string[];
  actions: string[];
  notes: string[];
} {
  const normalizedLines = answer
    .split(/\r?\n/)
    .map((line) => line.trim())
    .filter(Boolean);
  const structured = parseStructuredLines(normalizedLines);
  if (structured) {
    return structured;
  }

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

  if (segments.length === 1) {
    return {
      summary: segments[0]!,
      impacts: [],
      actions: [],
      notes: [],
    };
  }

  if (segments.length === 2) {
    return {
      summary: segments[0]!,
      impacts: [],
      actions: [segments[1]!],
      notes: [],
    };
  }

  return {
    summary: segments[0]!,
    impacts: segments.slice(1, -1),
    actions: [segments[segments.length - 1]!],
    notes: [],
  };
}

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
  let usedHeading = false;

  const sectionMatchers: Array<{ key: SectionKey; pattern: RegExp }> = [
    { key: "summary", pattern: /^(?:核心结论|结论|总结|摘要)[:：]?\s*(.*)$/ },
    { key: "impacts", pattern: /^(?:关键影响|影响|风险|问题)[:：]?\s*(.*)$/ },
    { key: "actions", pattern: /^(?:建议动作|处理建议|修复建议|下一步|建议|动作)[:：]?\s*(.*)$/ },
    { key: "notes", pattern: /^(?:注意事项|注意|前提|限制|备注)[:：]?\s*(.*)$/ },
  ];

  const normalizeListItem = (value: string) => value.replace(/^[-*•]\s+/, "").replace(/^\d+[.)]\s+/, "").trim();

  const pushToSection = (section: SectionKey, rawValue: string) => {
    const value = normalizeListItem(rawValue);
    if (!value) {
      return;
    }
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
      const inlineValue = matched?.[1]?.trim() ?? "";
      if (inlineValue) {
        pushToSection(currentSection, inlineValue);
      }
      return;
    }

    if (!currentSection) {
      pushToSection("summary", line);
      return;
    }

    pushToSection(currentSection, line);
  });

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

function readableWarning(warning: string): string {
  return warning.replace(/\s*\/\s*[a-z0-9_.-]+(?=[)）])/gi, "");
}

export function PatchResultSummary({
  title,
  result,
  resolveArtifactText,
  onRequestArtifact,
}: PatchResultSummaryProps) {
  const sections = splitAnswer(result.answer);
  const patchSummary = result.patch?.summary?.trim() || null;
  const shouldShowPatchSummary = Boolean(
    patchSummary &&
      patchSummary !== sections.summary &&
      !sections.impacts.includes(patchSummary) &&
      !sections.actions.includes(patchSummary),
  );
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

        <section className="answer-section">
          <strong>真实性边界</strong>
          <p>{patchResultBoundaryDescription(result.source)}</p>
        </section>

        <EvidenceFindingsSection findings={result.findings ?? []} />

        {sections.impacts.length > 0 ? (
          <section className="answer-section">
            <strong>关键影响</strong>
            <ul className="answer-list">
              {sections.impacts.map((impact) => (
                <li key={impact} className="muted">{impact}</li>
              ))}
            </ul>
          </section>
        ) : null}

        {sections.actions.length > 0 ? (
          <section className="answer-section">
            <strong>建议动作</strong>
            <ul className="answer-list">
              {sections.actions.map((action) => (
                <li key={action} className="muted">{action}</li>
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
              {sections.notes.map((note) => (
                <li key={note} className="muted">{note}</li>
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

        {result.promptPreview?.trim() || result.promptPreviewArtifactId ? (
          <section className="answer-section">
            <div className="preview-head">
              <strong>调试用提示词</strong>
            </div>
            <RequestPromptDisclosure
              promptPreview={result.promptPreview ?? null}
              promptPreviewArtifactId={result.promptPreviewArtifactId ?? null}
              promptPreviewAvailable={Boolean(result.promptPreview?.trim() || result.promptPreviewArtifactId)}
              resolveArtifactText={resolveArtifactText}
              onRequestArtifact={onRequestArtifact}
            />
          </section>
        ) : null}
      </div>
    </article>
  );
}
