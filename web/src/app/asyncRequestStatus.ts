import { normalizeWorkbenchWording } from "./labels";
import type { AsyncRequestState, OperationFeedback } from "./types";

export interface ToolbarFeedback extends OperationFeedback {
  source: "async-request" | "operation-feedback";
}

interface ResolveToolbarFeedbackArgs {
  operationFeedback?: OperationFeedback | null;
  requestStates: Array<AsyncRequestState | null | undefined>;
  lastMessageType?: string | null;
}

const ASYNC_LAST_MESSAGE_TYPES = new Set([
  "requestAssistantTask",
  "qaResult",
  "diffReviewResult",
  "graphBeautificationResult",
  "generationPlanResult",
  "generationPlanDiscussionResult",
  "requestCodeDrafts",
]);

interface MeaningfulAsyncState {
  state: AsyncRequestState;
  message: string;
  sortTimestamp: number;
}

function fallbackSuccessMessage(scene: string): string {
  switch (scene.trim()) {
    case "链路讲解":
      return "链路讲解完成，已更新步骤列表";
    case "问答":
      return "问答完成，已更新结果";
    case "差异问答":
      return "差异问答完成，已更新分析结果";
    case "实现计划":
      return "实现建议完成，已更新实现建议";
    case "代码草稿":
      return "代码 diff 完成，已更新 diff 内容";
    default:
      return normalizeWorkbenchWording(`${scene.trim()}已完成。`);
  }
}

export function resolveAsyncRequestPrimaryMessage(state: AsyncRequestState): string | null {
  const explicitMessage = state.statusMessage?.trim();
  if (explicitMessage) {
    return normalizeWorkbenchWording(explicitMessage);
  }

  if (state.phase === "FAILED" || state.phase === "TIMED_OUT") {
    return state.errorMessage?.trim() ? normalizeWorkbenchWording(state.errorMessage.trim()) : null;
  }

  const scene = state.scene?.trim();
  if (!scene) {
    return null;
  }

  if (state.phase === "SUCCEEDED") {
    return normalizeWorkbenchWording(fallbackSuccessMessage(scene));
  }
  if (state.phase === "RUNNING") {
    return normalizeWorkbenchWording(`${scene}进行中。`);
  }
  return null;
}

function requestMessage(state: AsyncRequestState): string | null {
  const primary = resolveAsyncRequestPrimaryMessage(state);
  if (!primary) {
    return null;
  }
  return state.scene?.trim() ? `${normalizeWorkbenchWording(state.scene.trim())}：${primary}` : primary;
}

function requestLevel(state: AsyncRequestState): OperationFeedback["level"] {
  if (state.phase === "FAILED" || state.phase === "TIMED_OUT") {
    return "ERROR";
  }
  if (
    state.fallbackUsed
    || state.executionMode === "DISABLED"
    || state.executionMode === "REMOTE_FALLBACK"
  ) {
    return "WARNING";
  }
  if (state.phase === "RUNNING") {
    return "INFO";
  }
  return "SUCCESS";
}

function requestTimestamp(state: AsyncRequestState): number {
  if (state.phase === "RUNNING") {
    return state.previewUpdatedAtEpochMillis ?? state.startedAtEpochMillis ?? 0;
  }
  return state.finishedAtEpochMillis ?? state.previewUpdatedAtEpochMillis ?? state.startedAtEpochMillis ?? 0;
}

function toMeaningfulState(state: AsyncRequestState | null | undefined): MeaningfulAsyncState | null {
  if (!state || state.phase === "IDLE") {
    return null;
  }
  const message = requestMessage(state);
  if (!message) {
    return null;
  }
  return {
    state,
    message,
    sortTimestamp: requestTimestamp(state),
  };
}

function latestMeaningfulAsyncState(states: Array<AsyncRequestState | null | undefined>): MeaningfulAsyncState | null {
  const meaningfulStates = states
    .map(toMeaningfulState)
    .filter((state): state is MeaningfulAsyncState => state != null);
  if (meaningfulStates.length === 0) {
    return null;
  }

  const runningStates = meaningfulStates.filter((item) => item.state.phase === "RUNNING");
  const candidates = runningStates.length > 0 ? runningStates : meaningfulStates;
  return candidates.reduce((latest, current) => (
    current.sortTimestamp >= latest.sortTimestamp ? current : latest
  ));
}

export function resolveToolbarFeedback({
  operationFeedback = null,
  requestStates,
  lastMessageType = null,
}: ResolveToolbarFeedbackArgs): ToolbarFeedback | null {
  const latestAsyncState = latestMeaningfulAsyncState(requestStates);
  if (!latestAsyncState) {
    return operationFeedback ? { ...operationFeedback, source: "operation-feedback" } : null;
  }

  const { state, message } = latestAsyncState;
  const shouldPreferAsyncState = state.phase === "RUNNING"
    || state.phase === "FAILED"
    || state.phase === "TIMED_OUT"
    || operationFeedback == null
    || (lastMessageType != null && ASYNC_LAST_MESSAGE_TYPES.has(lastMessageType));

  if (shouldPreferAsyncState) {
    return {
      level: requestLevel(state),
      message,
      source: "async-request",
    };
  }

  return {
    ...operationFeedback,
    source: "operation-feedback",
  };
}
