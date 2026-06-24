import type { AssistantActionId } from "./assistantTypes";
import type { AssistantContextSnapshot } from "../types";
import { assistantActionOptions } from "./assistantModels";

/** AssistantActionSelector 组件的入参。 */
interface AssistantActionSelectorProps {
  /** 当前激活的动作 ID。 */
  activeActionId: AssistantActionId;
  /** 当前上下文（用于按展示模式动态选择可用动作）。 */
  context?: AssistantContextSnapshot | null;
  /** 切换动作的回调。 */
  onActionChange: (actionId: AssistantActionId) => void;
}

/**
 * 助理动作选择器：按当前上下文渲染可用动作按钮。
 *
 * 按钮样式区分激活/非激活态；点击触发 onActionChange。
 * 按钮集合由 assistantActionOptions 动态生成（不同模式下的可用动作不同）。
 */
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
