import { normalizeWorkbenchWording } from "./labels";
import type { AsyncRequestState, OperationFeedback } from "./types";

/**
 * 工具栏反馈信息的统一载体。在 [OperationFeedback] 基础上额外携带 source 字段，
 * 让上层 UI 知道这条反馈来自异步请求聚合还是单次操作结果，便于做来源相关的样式区分。
 */
export interface ToolbarFeedback extends OperationFeedback {
  /** 反馈来源：异步请求聚合（多状态合并）或单次操作结果。 */
  source: "async-request" | "operation-feedback";
}

/** 解析工具栏反馈所需的所有上下文参数。 */
interface ResolveToolbarFeedbackArgs {
  /** 单次操作结果反馈，优先级最低，作为兜底。 */
  operationFeedback?: OperationFeedback | null;
  /** 当前所有相关的异步请求状态列表，按时间聚合后选出"最有意义"的一项。 */
  requestStates: Array<AsyncRequestState | null | undefined>;
  /** 最近一次桥接消息的类型字符串，用于判断是否应当优先展示异步请求状态。 */
  lastMessageType?: string | null;
}

/**
 * 触发"优先展示异步请求状态"的消息类型集合。
 * 这些消息对应不同业务域的关键产物（如问答结果、链路讲解、生成计划等），
 * 一旦最近一次消息属于其中之一，工具栏应优先展示对应的异步请求状态而非单次操作反馈。
 */
const ASYNC_LAST_MESSAGE_TYPES = new Set([
  "requestAssistantTask",
  "qaResult",
  "diffReviewResult",
  "graphBeautificationResult",
  "generationPlanResult",
  "generationPlanDiscussionResult",
  "requestCodeDrafts",
]);

/** 经过过滤后"有展示价值"的异步状态：携带原状态、可读消息以及用于排序的时间戳。 */
interface MeaningfulAsyncState {
  /** 原始异步请求状态。 */
  state: AsyncRequestState;
  /** 已组装、归一化后的人类可读消息。 */
  message: string;
  /** 用于跨状态比较新旧的时间戳。 */
  sortTimestamp: number;
}

/**
 * 当请求成功但未显式给出消息时，根据场景给出兜底成功文案。
 * 兜底文案刻意强调"已更新 xx"，让用户知道结果已经写到对应面板。
 */
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
      // 未识别场景做一次通用文案归一化，至少保留"已完成"的语义
      return normalizeWorkbenchWording(`${scene.trim()}已完成。`);
  }
}

/**
 * 把单条异步请求状态转成面向用户的主消息。
 * 优先使用状态自带的 statusMessage；其次失败/超时使用 errorMessage；
 * 成功/运行中按场景生成兜底文案；空闲或无信息时返回 null。
 */
export function resolveAsyncRequestPrimaryMessage(state: AsyncRequestState): string | null {
  // 显式提供的消息优先
  const explicitMessage = state.statusMessage?.trim();
  if (explicitMessage) {
    return normalizeWorkbenchWording(explicitMessage);
  }

  // 失败/超时优先展示错误信息
  if (state.phase === "FAILED" || state.phase === "TIMED_OUT") {
    return state.errorMessage?.trim() ? normalizeWorkbenchWording(state.errorMessage.trim()) : null;
  }

  // 之后所有兜底文案都需要 scene 作为基础
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

/**
 * 组装异步请求状态的最终展示文案：有 scene 时加前缀"场景：消息"，
 * 让工具栏能区分多个并发请求的来源。
 */
function requestMessage(state: AsyncRequestState): string | null {
  const primary = resolveAsyncRequestPrimaryMessage(state);
  if (!primary) {
    return null;
  }
  return state.scene?.trim() ? `${normalizeWorkbenchWording(state.scene.trim())}：${primary}` : primary;
}

/**
 * 根据异步请求的相位与执行模式推断反馈等级。
 * - 失败/超时 -> ERROR
 * - 触发了回退或非默认执行模式 -> WARNING（提醒用户结果是兜底）
 * - 运行中 -> INFO
 * - 成功 -> SUCCESS
 */
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

/**
 * 计算用于排序的时间戳，规则：
 * - 运行中：使用预览最新更新时间，缺失时回退到开始时间，让"有新进度"的请求排前面；
 * - 其他相位：按完成时间 > 预览时间 > 开始时间的优先级取值。
 */
function requestTimestamp(state: AsyncRequestState): number {
  if (state.phase === "RUNNING") {
    return state.previewUpdatedAtEpochMillis ?? state.startedAtEpochMillis ?? 0;
  }
  return state.finishedAtEpochMillis ?? state.previewUpdatedAtEpochMillis ?? state.startedAtEpochMillis ?? 0;
}

/**
 * 把原始请求状态转换为"有意义"的状态包装：null/IDLE 或无消息的状态被丢弃，
 * 避免在工具栏上展示空状态。
 */
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

/**
 * 在多个并发异步请求中选出"最新且有展示价值"的一个。
 * 优先返回运行中的请求（提示用户正在进行的工作），否则返回最新结束的请求。
 */
function latestMeaningfulAsyncState(states: Array<AsyncRequestState | null | undefined>): MeaningfulAsyncState | null {
  const meaningfulStates = states
    .map(toMeaningfulState)
    .filter((state): state is MeaningfulAsyncState => state != null);
  if (meaningfulStates.length === 0) {
    return null;
  }

  // 运行中的状态优先展示
  const runningStates = meaningfulStates.filter((item) => item.state.phase === "RUNNING");
  const candidates = runningStates.length > 0 ? runningStates : meaningfulStates;
  // 在候选集中按时间戳取最新
  return candidates.reduce((latest, current) => (
    current.sortTimestamp >= latest.sortTimestamp ? current : latest
  ));
}

/**
 * 工具栏反馈的核心解析函数：综合多个异步请求状态与单次操作反馈，
 * 决定最终展示哪一条消息、以什么等级展示。
 *
 * 决策流程：
 * 1) 若没有任何有意义的异步状态，则展示单次操作反馈或返回 null；
 * 2) 否则判断是否应当"优先展示异步状态"：运行中/失败/超时、操作反馈为空、
 *    或最近一次消息属于关键产物类，都触发优先；
 * 3) 不优先时保留操作反馈，仅附加 source 字段。
 */
export function resolveToolbarFeedback({
  operationFeedback = null,
  requestStates,
  lastMessageType = null,
}: ResolveToolbarFeedbackArgs): ToolbarFeedback | null {
  const latestAsyncState = latestMeaningfulAsyncState(requestStates);
  // 没有可展示的异步状态时退回到操作反馈
  if (!latestAsyncState) {
    return operationFeedback ? { ...operationFeedback, source: "operation-feedback" } : null;
  }

  const { state, message } = latestAsyncState;
  // 决定是否要让异步状态压过操作反馈成为当前展示项
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

  // 否则保留操作反馈作为展示项，仅附加来源标记
  return {
    ...operationFeedback,
    source: "operation-feedback",
  };
}
