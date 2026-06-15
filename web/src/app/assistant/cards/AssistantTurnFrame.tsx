import { analysisDisplayModeLabel } from "../../labels";
import type { AnalysisDisplayMode, AssistantContextSnapshot, AssistantTurn } from "../../types";
import { assistantTurnKindLabel, isClassDiagramAssistantContext } from "../assistantModels";

interface AssistantTurnHeaderProps {
  turn: AssistantTurn;
  turnIndex?: number;
  isLatest?: boolean;
}

interface AssistantQuestionAnswerProps {
  question: string;
  answer: string;
}

function displayModeLabel(mode?: string | null): string | null {
  if (!mode) {
    return null;
  }
  return analysisDisplayModeLabel(mode as AnalysisDisplayMode);
}

function compactMethodSignature(signature: string): string {
  const trimmed = signature.trim();
  const methodPath = trimmed.includes("(") ? trimmed.slice(0, trimmed.indexOf("(")) : trimmed;
  const pathParts = methodPath.split(".").filter(Boolean);
  if (pathParts.length >= 2) {
    return pathParts.slice(-2).join(".");
  }
  return methodPath || trimmed;
}

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
      <div className="assistant-turn-context-row" aria-label="历史上下文">
        <span className="assistant-context-prefix">历史上下文</span>
        {modeLabel ? <span className="toolbar-chip assistant-context-chip">{modeLabel}</span> : null}
        {scopeLabel ? <span className="toolbar-chip assistant-context-chip">{scopeLabel}</span> : null}
        <span className="toolbar-chip assistant-context-chip">{nodeCountLabel}</span>
        {isClassDiagram ? null : (
          <span className="toolbar-chip assistant-context-chip">改动 {context.selectedDiffItemIds.length}</span>
        )}
      </div>
    </header>
  );
}

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
