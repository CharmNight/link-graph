// 助理链路讲解历史控制器。
// 主要功能：管理"链路讲解结果"的历史记录，让用户可以回看之前的讲解、回到上一步等。
// 涉及多个 ref（pending 状态）与多个 state（讲解结果、历史、助理会话等）的协同更新。
import { useEffect, useRef, type Dispatch, type MutableRefObject, type SetStateAction } from "react";
import type {
  AssistantResultStore,
  AssistantSessionState,
  AssistantTurnRef,
  AsyncRequestState,
  GraphBeautificationResult,
  StepGranularity,
} from "../types";

/** 讲解请求模式：fresh = 全新开始；follow_up = 跟进上一轮。 */
type ExplanationRequestMode = "fresh" | "follow_up";

/** 历史记录中的单条快照。 */
interface ExplanationHistoryEntry {
  /** 讲解结果。 */
  result: GraphBeautificationResult;
  /** 请求状态（成功 / 失败等）。 */
  requestState: AsyncRequestState;
  /** 当时选中的步骤 ID。 */
  selectedStepId: string | null;
  /** 当时使用的粒度（业务 / 方法调用 / 代码语义）。 */
  granularity: StepGranularity;
  /** 会话标签。 */
  sessionLabel: string;
}

/** Hook 的入参集合。 */
interface UseAssistantExplanationHistoryArgs {
  /** 当前讲解结果。 */
  graphBeautificationResult: GraphBeautificationResult | null;
  /** 当前讲解请求状态。 */
  graphBeautificationRequestState: AsyncRequestState;
  /** 历史记录数组。 */
  explanationHistory: ExplanationHistoryEntry[];
  /** 本地覆盖 ref；标记"当前展示的是历史而非最新"。 */
  explanationLocalOverrideRef: MutableRefObject<boolean>;
  /** 待处理请求模式 ref。 */
  pendingExplanationRequestModeRef: MutableRefObject<ExplanationRequestMode | null>;
  /** 待处理会话标签 ref。 */
  pendingExplanationSessionLabelRef: MutableRefObject<string | null>;
  /** 待处理历史快照 ref。 */
  pendingExplanationHistoryEntryRef: MutableRefObject<ExplanationHistoryEntry | null>;
  /** 助理会话状态。 */
  assistantSessionState: AssistantSessionState;
  /** 助理结果仓库。 */
  assistantResultStore: AssistantResultStore;
  /** 设置当前选中步骤 ID。 */
  setSelectedExplanationStepId: Dispatch<SetStateAction<string | null>>;
  /** 设置悬停的步骤 ID。 */
  setHoveredExplanationStepId: Dispatch<SetStateAction<string | null>>;
  /** 设置粒度。 */
  setSelectedExplanationGranularity: Dispatch<SetStateAction<StepGranularity>>;
  /** 设置当前会话标签。 */
  setCurrentExplanationSessionLabel: Dispatch<SetStateAction<string>>;
  /** 设置历史数组。 */
  setExplanationHistory: Dispatch<SetStateAction<ExplanationHistoryEntry[]>>;
  /** 设置讲解结果。 */
  setGraphBeautificationResult: Dispatch<SetStateAction<GraphBeautificationResult | null>>;
  /** 设置讲解请求状态。 */
  setGraphBeautificationRequestState: Dispatch<SetStateAction<AsyncRequestState>>;
  /** 设置助理会话状态。 */
  setAssistantSessionState: Dispatch<SetStateAction<AssistantSessionState>>;
  /** 设置助理结果仓库。 */
  setAssistantResultStore: Dispatch<SetStateAction<AssistantResultStore>>;
}

/**
 * 链路讲解历史 Hook。
 *
 * 提供两个核心动作：
 * - handleOpenExplanationHistory：打开指定位置的历史；
 * - handleReturnToPreviousExplanation：返回上一个历史。
 *
 * 同时通过 effect 监听讲解请求状态变化，在请求成功后把"待处理"的历史/标签
 * 正式写入状态；失败时清理 pending。
 *
 * 自动维护步骤选中：当前步骤在新结果中不存在时回退到首步。
 */
export function useAssistantExplanationHistory(args: UseAssistantExplanationHistoryArgs) {
  // 结果序号：用于生成本地结果 ID；与助理会话的 nextResultSequence 取最大值同步
  const historyResultSequenceRef = useRef(
    Math.max(args.assistantSessionState.nextResultSequence ?? 1, args.assistantSessionState.turns.length + 1),
  );

  /** 清空所有 pending 状态。 */
  function resetPendingExplanationRequest() {
    args.pendingExplanationRequestModeRef.current = null;
    args.pendingExplanationSessionLabelRef.current = null;
    args.pendingExplanationHistoryEntryRef.current = null;
  }

  /**
   * 构造一条历史轮次（AssistantTurnRef）。
   * 用 turnId 标识唯一性，包含 EXPLANATION kind 与上下文。
   */
  function makeHistoryTurn(
    session: AssistantSessionState,
    resultId: string,
    createdAtEpochMillis: number,
  ): AssistantTurnRef {
    return {
      turnId: `explanationHistory:explanation:${session.turns.length + 1}:${createdAtEpochMillis}`,
      kind: "EXPLANATION",
      sourceMessageType: "explanationHistory",
      resultId,
      createdAtEpochMillis,
      context: session.context,
    };
  }

  /**
   * 生成下一个本地结果 ID。
   * 同时与助理会话的 nextResultSequence、当前 ref 取最大；
   * 如果生成的 ID 已经在仓库中存在则继续递增直到唯一。
   */
  function nextHistoryResultId(): string {
    let nextSequence = Math.max(
      historyResultSequenceRef.current,
      args.assistantSessionState.nextResultSequence ?? 1,
      args.assistantSessionState.turns.length + 1,
    );
    let resultId = `explanation-history:local:${nextSequence}`;
    // 确保唯一：如果已存在则继续递增
    while (Object.prototype.hasOwnProperty.call(args.assistantResultStore, resultId)) {
      nextSequence += 1;
      resultId = `explanation-history:local:${nextSequence}`;
    }
    historyResultSequenceRef.current = nextSequence + 1;
    return resultId;
  }

  /**
   * 把一份历史快照放入仓库与会话。
   * 返回新分配的 resultId。
   */
  function putHistoryResult(snapshot: ExplanationHistoryEntry): string {
    const resultId = nextHistoryResultId();
    const createdAtEpochMillis = Date.now();
    // 写入结果仓库
    args.setAssistantResultStore((current) => ({
      ...current,
      [resultId]: {
        kind: "EXPLANATION",
        explanation: snapshot.result,
      },
    }));
    // 在会话中添加一条轮次
    args.setAssistantSessionState((current) => {
      const nextTurn = makeHistoryTurn(current, resultId, createdAtEpochMillis);
      return {
        ...current,
        activeIntent: "EXPLAIN_CODE",
        nextResultSequence: Math.max(current.nextResultSequence ?? 1, historyResultSequenceRef.current),
        turns: current.turns.concat(nextTurn),
      };
    });
    return resultId;
  }

  /**
   * 打开指定位置的历史。
   *
   * 把该历史写入结果仓库 + 会话；
   * 同时切换讲解结果 / 请求状态 / 选中步骤 / 粒度 / 会话标签为该历史的状态；
   * 从历史数组中移除该位置之后的所有历史（"穿越"会丢弃未来分支）。
   */
  function handleOpenExplanationHistory(historyIndex: number) {
    args.setExplanationHistory((current) => {
      const snapshot = current[historyIndex];
      if (!snapshot) {
        return current;
      }
      // 标记"当前展示的是历史覆盖"
      args.explanationLocalOverrideRef.current = true;
      resetPendingExplanationRequest();
      // 把该历史写入仓库与会话
      putHistoryResult(snapshot);
      // 切换各 state 到该历史的状态
      args.setGraphBeautificationResult(snapshot.result);
      args.setGraphBeautificationRequestState(snapshot.requestState);
      args.setSelectedExplanationStepId(snapshot.selectedStepId);
      args.setSelectedExplanationGranularity(snapshot.granularity);
      args.setCurrentExplanationSessionLabel(snapshot.sessionLabel);
      args.setHoveredExplanationStepId(null);
      // 切掉该位置之后的历史
      return current.slice(0, historyIndex);
    });
  }

  /** 返回上一个历史（即数组末尾的那一条）。 */
  function handleReturnToPreviousExplanation() {
    handleOpenExplanationHistory(args.explanationHistory.length - 1);
  }

  // 请求失败时清理 pending
  useEffect(() => {
    if (args.graphBeautificationRequestState.phase !== "FAILED" && args.graphBeautificationRequestState.phase !== "TIMED_OUT") {
      return;
    }
    resetPendingExplanationRequest();
  }, [args.graphBeautificationRequestState.phase]);

  // 请求成功时落地 pending
  useEffect(() => {
    if (args.graphBeautificationRequestState.phase !== "SUCCEEDED" || args.graphBeautificationResult == null) {
      return;
    }
    const pendingMode = args.pendingExplanationRequestModeRef.current;
    const pendingSessionLabel = args.pendingExplanationSessionLabelRef.current;
    const pendingHistoryEntry = args.pendingExplanationHistoryEntryRef.current;

    // fresh 模式：清空历史（重新开始）
    if (pendingMode === "fresh") {
      args.setExplanationHistory([]);
    }
    // follow_up 模式：把上一轮作为历史追加
    if (pendingMode === "follow_up" && pendingHistoryEntry) {
      args.setExplanationHistory((current) => current.concat(pendingHistoryEntry));
    }
    // 落地会话标签
    if (pendingSessionLabel) {
      args.setCurrentExplanationSessionLabel(pendingSessionLabel);
    }

    resetPendingExplanationRequest();
  }, [args.graphBeautificationRequestState.phase, args.graphBeautificationResult]);

  // 当前选中步骤在新结果中不存在时回退到首步
  useEffect(() => {
    const firstStepId = args.graphBeautificationResult?.steps?.[0]?.stepId ?? null;
    args.setSelectedExplanationStepId((current) => {
      if (!args.graphBeautificationResult?.steps?.length) {
        return null;
      }
      return args.graphBeautificationResult.steps.some((step) => step.stepId === current) ? current : firstStepId;
    });
    // 悬停步骤也做类似清理
    args.setHoveredExplanationStepId((current) =>
      args.graphBeautificationResult?.steps?.some((step) => step.stepId === current) ? current : null,
    );
  }, [args.graphBeautificationResult]);

  // 粒度跟随讲解结果
  useEffect(() => {
    if (!args.graphBeautificationResult?.granularity) {
      return;
    }
    args.setSelectedExplanationGranularity(args.graphBeautificationResult.granularity);
  }, [args.graphBeautificationResult?.granularity]);

  return {
    handleReturnToPreviousExplanation,
    handleOpenExplanationHistory,
  };
}
