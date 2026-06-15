import type { AssistantActionId, AssistantContextSnapshot } from "../types";
import { assistantActionOptions } from "./assistantModels";

interface AssistantActionSelectorProps {
  activeActionId: AssistantActionId;
  context?: AssistantContextSnapshot | null;
  onActionChange: (actionId: AssistantActionId) => void;
}

export function AssistantActionSelector({
  activeActionId,
  context,
  onActionChange,
}: AssistantActionSelectorProps) {
  const actions = assistantActionOptions(context);
  return (
    <div className="assistant-send-type-selector" aria-label="发送动作">
      {actions.map((action) => (
        <button
          key={action.id}
          type="button"
          aria-pressed={activeActionId === action.id}
          className={activeActionId === action.id ? "assistant-intent-button active" : "assistant-intent-button"}
          onClick={() => onActionChange(action.id)}
        >
          {action.label}
        </button>
      ))}
    </div>
  );
}
