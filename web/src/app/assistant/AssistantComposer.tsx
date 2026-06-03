import type { AssistantIntent } from "../types";
import { assistantComposerPlaceholder, assistantIntentLabel } from "./assistantModels";

interface AssistantComposerProps {
  activeIntent: AssistantIntent;
  draft: string;
  canSubmit: boolean;
  requestRunning: boolean;
  onDraftChange: (value: string) => void;
  onSubmit: () => void;
}

export function AssistantComposer({
  activeIntent,
  draft,
  canSubmit,
  requestRunning,
  onDraftChange,
  onSubmit,
}: AssistantComposerProps) {
  return (
    <form
      className="assistant-composer assistant-composer-sticky"
      data-testid="assistant-composer"
      onSubmit={(event) => {
        event.preventDefault();
        onSubmit();
      }}
    >
      <label className="sr-only" htmlFor="assistant-composer-input">AI 工作台输入框</label>
      <textarea
        id="assistant-composer-input"
        aria-label="AI 工作台输入框"
        rows={3}
        value={draft}
        placeholder={assistantComposerPlaceholder(activeIntent)}
        onChange={(event) => onDraftChange(event.target.value)}
      />
      <div className="assistant-composer-actions">
        <span className="muted">{assistantIntentLabel(activeIntent)}</span>
        <button
          type="submit"
          className="primary-button"
          disabled={!canSubmit}
          aria-label={requestRunning ? "AI 代码工作台处理中" : "发送到 AI 代码工作台"}
        >
          {requestRunning ? "处理中" : "发送"}
        </button>
      </div>
    </form>
  );
}
