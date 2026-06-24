import { useCallback, useState } from "react";
import type { AssistantIntent } from "./assistantTypes";

/** useAssistantWorkbenchController 的入参。 */
interface UseAssistantWorkbenchControllerArgs {
  /** 当前意图（EXPLAIN_CODE / ASK_CODE 等）。 */
  activeIntent: AssistantIntent;
  /** 请求是否正在运行；运行中禁用提交。 */
  requestRunning: boolean;
  /** 受控的草稿文本；为空时使用本地状态。 */
  draft?: string;
  /** 草稿变化回调；为空时仅更新本地状态。 */
  onDraftChange?: (value: string) => void;
  /** 提交回调。 */
  onSubmit: (intent: AssistantIntent, prompt: string) => void;
}

/**
 * 助理工作台控制器 Hook。
 *
 * 管理：
 * - 草稿文本（支持受控/非受控两种模式）；
 * - 提交逻辑（空文本或运行中不允许提交，CHECK_CHANGE 例外）；
 * - canSubmit 派生值。
 *
 * @return 草稿 / 设值函数 / canSubmit / submit 函数
 */
export function useAssistantWorkbenchController({
  activeIntent,
  requestRunning,
  draft: controlledDraft,
  onDraftChange,
  onSubmit,
}: UseAssistantWorkbenchControllerArgs) {
  // 非受控模式下的本地草稿
  const [localDraft, setLocalDraft] = useState("");
  // 优先使用受控草稿
  const draft = controlledDraft ?? localDraft;

  /** 设置草稿：受控模式走回调，非受控模式走本地状态。 */
  const setDraft = useCallback((value: string) => {
    if (onDraftChange) {
      onDraftChange(value);
      return;
    }
    setLocalDraft(value);
  }, [onDraftChange]);

  /** 提交当前草稿：空文本（非 CHECK_CHANGE）或运行中时跳过。 */
  const submit = useCallback(() => {
    const prompt = draft.trim();
    // CHECK_CHANGE 不需要用户输入（检查当前改动即可）；其他意图要求非空
    if ((activeIntent !== "CHECK_CHANGE" && !prompt) || requestRunning) {
      return;
    }
    onSubmit(activeIntent, prompt);
  }, [activeIntent, draft, onSubmit, requestRunning]);

  return {
    draft,
    setDraft,
    // canSubmit：CHECK_CHANGE 总是允许（只要没在运行）；其他意图要求非空 + 没在运行
    canSubmit: (activeIntent === "CHECK_CHANGE" || draft.trim().length > 0) && !requestRunning,
    submit,
  };
}
