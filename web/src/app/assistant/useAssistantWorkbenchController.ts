import { useCallback, useState } from "react";
import type { AssistantIntent } from "../types";

interface UseAssistantWorkbenchControllerArgs {
  activeIntent: AssistantIntent;
  requestRunning: boolean;
  draft?: string;
  onDraftChange?: (value: string) => void;
  onSubmit: (intent: AssistantIntent, prompt: string) => void;
}

export function useAssistantWorkbenchController({
  activeIntent,
  requestRunning,
  draft: controlledDraft,
  onDraftChange,
  onSubmit,
}: UseAssistantWorkbenchControllerArgs) {
  const [localDraft, setLocalDraft] = useState("");
  const draft = controlledDraft ?? localDraft;

  const setDraft = useCallback((value: string) => {
    if (onDraftChange) {
      onDraftChange(value);
      return;
    }
    setLocalDraft(value);
  }, [onDraftChange]);

  const submit = useCallback(() => {
    const prompt = draft.trim();
    if ((activeIntent !== "CHECK_CHANGE" && !prompt) || requestRunning) {
      return;
    }
    onSubmit(activeIntent, prompt);
  }, [activeIntent, draft, onSubmit, requestRunning]);

  return {
    draft,
    setDraft,
    canSubmit: (activeIntent === "CHECK_CHANGE" || draft.trim().length > 0) && !requestRunning,
    submit,
  };
}
