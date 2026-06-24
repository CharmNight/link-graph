// 助理会话轮次的"引用 → 完整轮次"适配器。
// 助理会话状态只保存轻量的 turn ref（不含完整结果），
// 完整结果存放在 resultStore 里按 ID 取回；本模块负责把两者合并为渲染用的 AssistantTurn。
import type {
  AssistantResultStore,
  AssistantResultStoreEntry,
  AssistantSessionState,
  AssistantTurn,
  AssistantTurnRef,
} from "../types";

/** buildAssistantTurns 的入参。 */
interface BuildAssistantTurnsArgs {
  /** 助理会话状态（含轮次 ref 列表）。 */
  assistantSessionState: AssistantSessionState;
  /** 结果仓库；可空。 */
  assistantResultStore?: AssistantResultStore | null;
}

/**
 * 把会话中的轮次 ref 列表转换为完整轮次列表。
 * 单个 ref 转换委托给 [buildTurnFromRef]。
 */
export function buildAssistantTurns({
  assistantSessionState,
  assistantResultStore = null,
}: BuildAssistantTurnsArgs): AssistantTurn[] {
  const refs = assistantSessionState.turns ?? [];
  return refs.map((ref) => buildTurnFromRef(ref, assistantResultStore));
}

/**
 * 把单个轮次 ref 转换为完整轮次。
 * 在仓库中找到匹配结果时构造 storedTurn；找不到则构造 emptyTurn（带空字段）。
 */
function buildTurnFromRef(
  ref: AssistantTurnRef,
  assistantResultStore: AssistantResultStore | null | undefined,
): AssistantTurn {
  // 共享的基础字段（所有 kind 都有）
  const baseTurn = {
    turnId: ref.turnId,
    kind: ref.kind,
    intent: ref.intent ?? null,
    actionId: ref.actionId ?? null,
    createdAtEpochMillis: ref.createdAtEpochMillis,
    context: ref.context,
    failure: null,
  };
  const stored = resolveStoredResult(ref, assistantResultStore);
  if (stored) {
    return storedTurn(baseTurn, stored);
  }
  return emptyTurn(baseTurn);
}

/**
 * 按 resultId 在仓库中查找结果。
 * 同时校验 kind 是否匹配 ref（避免错配）。
 */
function resolveStoredResult(
  ref: AssistantTurnRef,
  resultStore: AssistantResultStore | null | undefined,
): AssistantResultStoreEntry | null {
  if (!ref.resultId || !resultStore) {
    return null;
  }
  const stored = resultStore[ref.resultId] ?? null;
  // kind 不匹配视为不存在，避免错误类型转换
  if (!stored || stored.kind !== ref.kind) {
    return null;
  }
  return stored;
}

/**
 * 为空轮次（结果尚未加载或不存在）填充 kind 对应的空字段。
 * 各 kind 有不同的字段集合，因此分支返回。
 */
function emptyTurn(
  baseTurn: Pick<AssistantTurn, "turnId" | "kind" | "intent" | "actionId" | "createdAtEpochMillis" | "context" | "failure">,
): AssistantTurn {
  switch (baseTurn.kind) {
    case "EXPLANATION":
      return {
        ...baseTurn,
        explanation: null,
      };
    case "QA":
      return {
        ...baseTurn,
        qa: null,
      };
    case "GENERATION_PLAN":
      return {
        ...baseTurn,
        generationPlan: null,
        generationDiscussionSession: null,
      };
    case "CODE_DRAFT":
      // CODE_DRAFT 也需要带上 generationPlan/DiscussionSession 字段（视图层会用到）
      return {
        ...baseTurn,
        generationPlan: null,
        generationDiscussionSession: null,
        codeDrafts: [],
        codeDraftWarnings: [],
      };
    case "CHECK_RESULT":
      return {
        ...baseTurn,
        check: null,
      };
  }
}

/**
 * 把仓库中的结果合并到轮次上。
 * 各 kind 有不同的字段集合，因此分支返回。
 */
function storedTurn(
  baseTurn: Pick<AssistantTurn, "turnId" | "kind" | "intent" | "actionId" | "createdAtEpochMillis" | "context" | "failure">,
  stored: AssistantResultStoreEntry,
): AssistantTurn {
  const failure = stored.failure ?? null;
  switch (stored.kind) {
    case "EXPLANATION":
      return {
        ...baseTurn,
        failure,
        explanation: stored.explanation ?? null,
      };
    case "QA":
      return {
        ...baseTurn,
        failure,
        qa: stored.qa ?? null,
      };
    case "GENERATION_PLAN":
      return {
        ...baseTurn,
        failure,
        generationPlan: stored.generationPlan ?? null,
        generationDiscussionSession: stored.generationDiscussionSession ?? null,
      };
    case "CODE_DRAFT":
      return {
        ...baseTurn,
        failure,
        generationPlan: stored.generationPlan ?? null,
        generationDiscussionSession: stored.generationDiscussionSession ?? null,
        codeDrafts: stored.codeDrafts ?? [],
        codeDraftWarnings: stored.codeDraftWarnings ?? [],
      };
    case "CHECK_RESULT":
      return {
        ...baseTurn,
        failure,
        check: stored.check ?? null,
      };
  }
}
