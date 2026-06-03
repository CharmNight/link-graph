import type { AssistantIntent } from "../types";
import { ASSISTANT_INTENTS } from "./assistantModels";

interface AssistantIntentSelectorProps {
  activeIntent: AssistantIntent;
  onIntentChange: (intent: AssistantIntent) => void;
}

export function AssistantIntentSelector({
  activeIntent,
  onIntentChange,
}: AssistantIntentSelectorProps) {
  return (
    <div className="assistant-intent-selector" aria-label="AI 工作台 intent">
      {ASSISTANT_INTENTS.map((intent) => (
        <button
          key={intent.id}
          type="button"
          aria-pressed={activeIntent === intent.id}
          className={activeIntent === intent.id ? "assistant-intent-button active" : "assistant-intent-button"}
          onClick={() => onIntentChange(intent.id)}
        >
          {intent.label}
        </button>
      ))}
    </div>
  );
}
