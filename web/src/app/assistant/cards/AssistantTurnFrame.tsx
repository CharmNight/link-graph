// 助理轮次的公共框架组件：轮次头部（AssistantTurnHeader）和问答对（AssistantQuestionAnswer）。
// 这些组件被多个轮次卡片（QaTurnCard、GenerationTurnCard、CheckTurnCard 等）共用。
import { analysisDisplayModeLabel } from "../../labels";
import type { AnalysisDisplayMode, AssistantContextSnapshot, AssistantTurn } from "../../types";
import { assistantTurnKindLabel, isClassDiagramAssistantContext } from "../assistantModels";

/** AssistantTurnHeader 组件的入参。 */
interface AssistantTurnHeaderProps {
  /** 轮次对象。 */
  turn: AssistantTurn;
  /** 轮次序号（0-based）；可空。 */
  turnIndex?: number;
  /** 是否为最新轮次。 */
  isLatest?: boolean;
}

/** AssistantQuestionAnswer 组件的入参。 */
interface AssistantQuestionAnswerProps {
  /** 用户问题。 */
  question: string;
  /** AI 回答。 */
  answer: string;
}

/** 把展示模式字符串转为中文标签。 */
function displayModeLabel(mode?: string | null): string | null {
  if (!mode) {
    return null;
  }
  return analysisDisplayModeLabel(mode as AnalysisDisplayMode);
}

/**
 * 把方法签名压缩为"Owner.method"形式（取最后两段）。
 * 例如 "com.example.Foo.bar" → "Foo.bar"。
 */
function compactMethodSignature(signature: string): string {
  const trimmed = signature.trim();
  // 去掉参数部分，只保留方法路径
  const methodPath = trimmed.includes("(") ? trimmed.slice(0, trimmed.indexOf("(")) : trimmed;
  const pathParts = methodPath.split(".").filter(Boolean);
  if (pathParts.length >= 2) {
    return pathParts.slice(-2).join(".");
  }
  return methodPath || trimmed;
}

/**
 * 从上下文中提取作用域标签。
 * 优先用 scopeLabel；如果是方法签名则压缩；缺失时用签名压缩。
 */
function contextScopeLabel(context: AssistantContextSnapshot): string | null {
  const trimmedScope = context.scopeLabel?.trim();
  const trimmedSignature = context.selectedMethodSignature?.trim();
  if (!trimmedScope && trimmedSignature) {
    return compactMethodSignature(trimmedSignature);
  }
  if (!trimmedScope) {
    return null;
  }
  if (trimmedSignature && trimmedScope === trimmedSignature) {
    return compactMethodSignature(trimmedSignature);
  }
  if (trimmedScope.includes("(")) {
    return compactMethodSignature(trimmedScope);
  }
  if (trimmedScope.split(".").filter(Boolean).length > 2) {
    return compactMethodSignature(trimmedScope);
  }
  return trimmedScope;
}

/** 把时间戳转为"HH:MM"格式的短时间标签。 */
function turnTimeLabel(createdAtEpochMillis: number): string | null {
  if (!Number.isFinite(createdAtEpochMillis) || createdAtEpochMillis <= 0) {
    return null;
  }
  const date = new Date(createdAtEpochMillis);
  if (Number.isNaN(date.getTime())) {
    return null;
  }
  return `${String(date.getHours()).padStart(2, "0")}:${String(date.getMinutes()).padStart(2, "0")}`;
}

/**
 * 助理轮次头部组件。
 *
 * 展示：轮次序号 + 轮次种类标签 + 最新标记 + 时间 + 历史上下文 chips（模式/作用域/节点数/改动数）。
 * 被所有轮次卡片复用，保证头部样式统一。
 */
export function AssistantTurnHeader({ turn, turnIndex, isLatest = false }: AssistantTurnHeaderProps) {
  const context = turn.context;
  const modeLabel = displayModeLabel(context.analysisDisplayMode);
  const scopeLabel = contextScopeLabel(context);
  const isClassDiagram = isClassDiagramAssistantContext(context);
  const nodeCountLabel = isClassDiagram
    ? `类图节点 ${context.selectedNodeIds.length}`
    : `节点 ${context.selectedNodeIds.length}`;
  const timeLabel = turnTimeLabel(turn.createdAtEpochMillis);
  return (
    <header className="assistant-turn-frame-head">
      <div className="assistant-turn-title-row">
        <div className="assistant-turn-title">
          {turnIndex != null ? <span className="assistant-turn-sequence">第 {turnIndex + 1} 轮</span> : null}
          <span className="assistant-turn-kind">{assistantTurnKindLabel(turn.kind, context, turn.intent, turn.actionId)}</span>
        </div>
        <div className="assistant-turn-status">
          {isLatest ? <span className="assistant-latest-badge">最新</span> : null}
          {timeLabel ? <span className="muted">{timeLabel}</span> : null}
        </div>
      </div>
      {/* 历史上下文 chips */}
      <div className="assistant-turn-context-row" aria-label="历史上下文">
        <span className="assistant-context-prefix">历史上下文</span>
        {modeLabel ? <span className="toolbar-chip assistant-context-chip">{modeLabel}</span> : null}
        {scopeLabel ? <span className="toolbar-chip assistant-context-chip">{scopeLabel}</span> : null}
        <span className="toolbar-chip assistant-context-chip">{nodeCountLabel}</span>
        {/* 类图模式不展示改动数 */}
        {isClassDiagram ? null : (
          <span className="toolbar-chip assistant-context-chip">改动 {context.selectedDiffItemIds.length}</span>
        )}
      </div>
    </header>
  );
}

/**
 * 问答对组件：以"你问 / AI 答"的形式展示一组问答。
 * 被 QA 轮次和检查轮次复用。
 */
export function AssistantQuestionAnswer({ question, answer }: AssistantQuestionAnswerProps) {
  return (
    <section className="assistant-qa-exchange" aria-label="本轮问答">
      <div className="assistant-message assistant-message-user">
        <span className="assistant-message-label">你问</span>
        <p className="assistant-question assistant-result-text">{question}</p>
      </div>
      <div className="assistant-message assistant-message-assistant">
        <span className="assistant-message-label">AI 答</span>
        <p className="assistant-result-text">{answer}</p>
      </div>
    </section>
  );
}
