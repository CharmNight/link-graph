import { Fragment, useState, type ReactNode } from "react";
import type { AsyncRequestState, AuditConversationMessage } from "../types";
import { parseStructuredRichText } from "../structuredRichText";

interface AuditConversationProps {
  messages: AuditConversationMessage[];
  requestState?: AsyncRequestState | null;
}

function renderInlineContent(text: string): ReactNode[] {
  const nodes: ReactNode[] = [];
  const pattern = /(\*\*[^*]+\*\*|`[^`]+`)/g;
  let cursor = 0;
  let match: RegExpExecArray | null;

  while ((match = pattern.exec(text)) !== null) {
    if (match.index > cursor) {
      nodes.push(text.slice(cursor, match.index));
    }
    const token = match[0];
    if (token.startsWith("**")) {
      nodes.push(<strong key={`${match.index}-bold`}>{token.slice(2, -2)}</strong>);
    } else {
      nodes.push(<code key={`${match.index}-code`}>{token.slice(1, -1)}</code>);
    }
    cursor = match.index + token.length;
  }

  if (cursor < text.length) {
    nodes.push(text.slice(cursor));
  }

  return nodes;
}

function isSuggestionLine(line: string): boolean {
  return /^(建议|建议动作|建议处理|建议修改|可改为|应改为|应该|可以|修复建议|变更建议)/.test(line);
}

function isRiskLine(line: string): boolean {
  return /^(风险|风险点|问题|注意|隐患|异常风险|边界风险)/.test(line);
}

interface AuditContentBlock {
  type: "paragraph" | "bullet" | "ordered";
  lines: string[];
}

interface ParsedAuditContent {
  heading: string | null;
  summaryLines: string[];
  summarySentence: string | null;
  remainingBlocks: AuditContentBlock[];
  shouldCollapse: boolean;
}

type AuditBulletBlockKind = "risk" | "suggestion" | "generic";

function classifyBulletBlock(lines: string[]): AuditBulletBlockKind {
  if (lines.length === 0) {
    return "generic";
  }
  if (lines.every((line) => isSuggestionLine(line))) {
    return "suggestion";
  }
  if (lines.every((line) => isRiskLine(line))) {
    return "risk";
  }
  return "generic";
}

function classifyBulletLine(line: string): AuditBulletBlockKind {
  if (isSuggestionLine(line)) {
    return "suggestion";
  }
  if (isRiskLine(line)) {
    return "risk";
  }
  return "generic";
}

function splitBulletLineGroups(lines: string[]): Array<{ kind: AuditBulletBlockKind; lines: string[] }> {
  const groups: Array<{ kind: AuditBulletBlockKind; lines: string[] }> = [];
  lines.forEach((line) => {
    const kind = classifyBulletLine(line);
    const currentGroup = groups[groups.length - 1];
    if (currentGroup && currentGroup.kind === kind) {
      currentGroup.lines.push(line);
      return;
    }
    groups.push({ kind, lines: [line] });
  });
  return groups;
}

function parseStructuredAssistantContent(content: string): ParsedAuditContent | null {
  const parsed = parseStructuredRichText(content);
  if (!parsed) {
    return null;
  }
  return {
    heading: parsed.heading,
    summaryLines: parsed.summaryLines,
    summarySentence: parsed.summarySentence,
    remainingBlocks: parsed.detailBlocks,
    shouldCollapse: parsed.shouldCollapse,
  };
}

function renderStructuredAssistantContent(content: string, expanded: boolean, onToggleExpanded: () => void) {
  const parsed = parseStructuredAssistantContent(content);
  if (!parsed) {
    return null;
  }

  const {
    heading,
    summaryLines,
    summarySentence,
    remainingBlocks,
    shouldCollapse,
  } = parsed;
  const orderedStepCount = remainingBlocks
    .filter((block) => block.type === "ordered")
    .reduce((count, block) => count + block.lines.length, 0);
  const riskCount = remainingBlocks
    .filter((block) => block.type === "bullet")
    .reduce((count, block) => count + block.lines.filter((line) => classifyBulletLine(line) === "risk").length, 0);
  const suggestionCount = remainingBlocks
    .filter((block) => block.type === "bullet")
    .reduce((count, block) => count + block.lines.filter((line) => classifyBulletLine(line) === "suggestion").length, 0);

  const summarySection = (
    <section className={expanded ? "audit-rich-section audit-rich-section-summary expanded" : "audit-rich-section audit-rich-section-summary"}>
      <div className="audit-rich-summary-head">
        <div className="audit-rich-summary-title">
          <div className="audit-rich-section-label">结论</div>
          {heading ? <h4>{heading}</h4> : null}
        </div>
        {shouldCollapse ? (
          <button type="button" className="ghost-button compact" onClick={onToggleExpanded}>
            {expanded ? "收起详细说明" : "展开详细说明"}
          </button>
        ) : null}
      </div>
      {summaryLines.length > 0 ? (
        <p>
          {(shouldCollapse && !expanded ? [summarySentence ?? summaryLines.join(" ")] : summaryLines).map((line, lineIndex) => (
            <Fragment key={`summary-${lineIndex}`}>
              {lineIndex > 0 ? " " : null}
              {renderInlineContent(line)}
            </Fragment>
          ))}
        </p>
      ) : null}
      {shouldCollapse ? (
        <div className="audit-rich-summary-stats">
          {orderedStepCount > 0 ? <span className="badge">{orderedStepCount} 步</span> : null}
          {riskCount > 0 ? <span className="badge">{riskCount} 风险</span> : null}
          {suggestionCount > 0 ? <span className="badge">{suggestionCount} 建议</span> : null}
        </div>
      ) : null}
    </section>
  );

  if (shouldCollapse && !expanded) {
    return <div className="audit-rich-text is-collapsed">{summarySection}</div>;
  }

  const detailSections: ReactNode[] = [];
  let blockIndex = 0;
  while (blockIndex < remainingBlocks.length) {
    const block = remainingBlocks[blockIndex]!;

    if (block.type === "ordered") {
      detailSections.push(
        <section key={`ordered-${blockIndex}`} className="audit-rich-section">
          <div className="audit-rich-section-label">执行步骤</div>
          <ol className="audit-rich-list ordered timeline" aria-label="执行步骤">
            {block.lines.map((line, lineIndex) => (
              <li key={`ordered-${blockIndex}-${lineIndex}`}>{renderInlineContent(line)}</li>
            ))}
          </ol>
        </section>,
      );
      blockIndex += 1;
      continue;
    }

    if (block.type === "bullet") {
      splitBulletLineGroups(block.lines).forEach((group, groupIndex) => {
        const sectionLabel = group.kind === "risk"
          ? "风险提醒"
          : group.kind === "suggestion"
            ? "建议动作"
            : "补充说明";
        detailSections.push(
          <section
            key={`bullet-${blockIndex}-${groupIndex}`}
            className={
              group.kind === "risk"
                ? "audit-rich-section risk"
                : group.kind === "suggestion"
                  ? "audit-rich-section suggestion"
                  : "audit-rich-section"
            }
          >
            <div
              className={
                group.kind === "risk"
                  ? "audit-rich-section-label risk"
                  : group.kind === "suggestion"
                    ? "audit-rich-section-label suggestion"
                    : "audit-rich-section-label"
              }
            >
              {sectionLabel}
            </div>
            <ul className="audit-rich-list">
              {group.lines.map((line, lineIndex) => (
                <li key={`bullet-${blockIndex}-${groupIndex}-${lineIndex}`}>{renderInlineContent(line)}</li>
              ))}
            </ul>
          </section>,
        );
      });
      blockIndex += 1;
      continue;
    }

    const paragraphBlocks: string[][] = [];
    const appendedGenericBulletBlocks: string[][] = [];
    while (blockIndex < remainingBlocks.length) {
      const currentBlock = remainingBlocks[blockIndex]!;
      if (currentBlock.type === "paragraph") {
        paragraphBlocks.push(currentBlock.lines);
        blockIndex += 1;
        continue;
      }
      if (currentBlock.type === "bullet" && classifyBulletBlock(currentBlock.lines) === "generic") {
        appendedGenericBulletBlocks.push(currentBlock.lines);
        blockIndex += 1;
        continue;
      }
      break;
    }

    detailSections.push(
      <section key={`paragraph-${blockIndex}`} className="audit-rich-section">
        <div className="audit-rich-section-label">补充说明</div>
        {paragraphBlocks.map((paragraphLines, paragraphIndex) => (
          <p key={`paragraph-${blockIndex}-${paragraphIndex}`}>
            {paragraphLines.map((line, lineIndex) => (
              <Fragment key={`paragraph-${blockIndex}-${paragraphIndex}-${lineIndex}`}>
                {lineIndex > 0 ? " " : null}
                {renderInlineContent(line)}
              </Fragment>
            ))}
          </p>
        ))}
        {appendedGenericBulletBlocks.map((lines, genericIndex) => (
          <ul key={`generic-${blockIndex}-${genericIndex}`} className="audit-rich-list">
            {lines.map((line, lineIndex) => (
              <li key={`generic-${blockIndex}-${genericIndex}-${lineIndex}`}>{renderInlineContent(line)}</li>
            ))}
          </ul>
        ))}
      </section>,
    );
  }

  return (
    <div className="audit-rich-text is-expanded">
      {summarySection}
      <div className="audit-rich-scroll-shell">{detailSections}</div>
    </div>
  );
}

function AssistantAuditMessage({ content }: { content: string }) {
  const parsed = parseStructuredAssistantContent(content);
  const [expanded, setExpanded] = useState(() => !parsed?.shouldCollapse);

  return renderStructuredAssistantContent(content, expanded, () => setExpanded((current) => !current));
}

export function AuditConversation({ messages, requestState = null }: AuditConversationProps) {
  const requestPreview = requestState?.previewText?.trim() || null;
  const requestRunning = requestState?.phase === "RUNNING";

  return (
    <div className={messages.length === 0 ? "workbench-chat-stream is-empty" : "workbench-chat-stream"}>
      {messages.length === 0 ? (
        <div className="workbench-chat-empty">
          {requestRunning ? (
            <>
              <p>
                <strong>正在接收审计回答。</strong>
                流式内容会先在“请求状态”里持续更新，完成后会落到审计会话中。
              </p>
              {requestPreview ? (
                <pre className="request-state-preview workbench-chat-pending-preview">{requestPreview}</pre>
              ) : null}
            </>
          ) : (
            <p>
              <strong>当前还没有审计消息。</strong>
              可先输入你的问题，或者围绕当前范围继续追问。
            </p>
          )}
        </div>
      ) : (
        messages.map((message) => (
          <article
            key={message.messageId}
            className={message.role === "USER" ? "workbench-chat-message user" : "workbench-chat-message assistant"}
          >
            <div className="workbench-chat-message-head">
              <span className="workbench-chat-role">{message.role === "USER" ? "你" : "审计助手"}</span>
              {message.role === "ASSISTANT" ? <span className="workbench-chat-tag">结构化回答</span> : null}
            </div>
            <div className="workbench-chat-message-body">
              {message.role === "ASSISTANT"
                ? <AssistantAuditMessage content={message.content} />
                : <p>{message.content}</p>}
            </div>
          </article>
        ))
      )}
    </div>
  );
}
