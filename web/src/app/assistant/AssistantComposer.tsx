import type { AssistantActionId, AssistantContextSnapshot, AssistantIntent } from "../types";
import { AssistantActionSelector } from "./AssistantActionSelector";
import { assistantComposerPlaceholder, isClassDiagramAssistantContext } from "./assistantModels";
import { assistantActionLabel } from "./assistantActionRegistry";

interface AssistantComposerProps {
  activeIntent: AssistantIntent;
  activeActionId: AssistantActionId;
  context?: AssistantContextSnapshot | null;
  draft: string;
  canSubmit: boolean;
  requestRunning: boolean;
  onActionChange: (actionId: AssistantActionId) => void;
  onDraftChange: (value: string) => void;
  onSubmit: () => void;
}

export function AssistantComposer({
  activeIntent,
  activeActionId,
  context,
  draft,
  canSubmit,
  requestRunning,
  onActionChange,
  onDraftChange,
  onSubmit,
}: AssistantComposerProps) {
  const activeActionLabel = assistantActionLabel(activeActionId, context);
  const isClassDiagram = isClassDiagramAssistantContext(context);
  const inputLabel = isClassDiagram ? "AI 类图工作台输入框" : "AI 工作台输入框";
  const submitLabel = requestRunning
    ? isClassDiagram ? "AI 类图工作台处理中" : "AI 代码工作台处理中"
    : isClassDiagram ? "发送到 AI 类图工作台" : "发送到 AI 代码工作台";

  return (
    <form
      className="assistant-composer assistant-composer-sticky"
      data-testid="assistant-composer"
      onSubmit={(event) => {
        event.preventDefault();
        onSubmit();
      }}
    >
      <div className="assistant-send-type-head">
        <div>
          <p className="eyebrow">发送动作</p>
          <strong>发送为</strong>
        </div>
        <span className="tag active">{activeActionLabel}</span>
      </div>
      <AssistantActionSelector activeActionId={activeActionId} context={context} onActionChange={onActionChange} />
      <p className="muted assistant-composer-help">
        切换发送动作只会改变下一次提交，不会改动上方已返回结果。
      </p>
      <label className="sr-only" htmlFor="assistant-composer-input">{inputLabel}</label>
      <textarea
        id="assistant-composer-input"
        aria-label={inputLabel}
        rows={3}
        value={draft}
        placeholder={assistantComposerPlaceholder(activeIntent, context)}
        onChange={(event) => onDraftChange(event.target.value)}
      />
      <div className="assistant-composer-actions">
        <span className="muted">
          {isClassDiagram ? "下一次类图发送上下文随图谱选择更新" : "下一次发送上下文随图谱选择更新"}
        </span>
        <button
          type="submit"
          className="primary-button"
          disabled={!canSubmit}
          aria-label={submitLabel}
        >
          {requestRunning ? "处理中" : "发送"}
        </button>
      </div>
    </form>
  );
}
