import { useRef, useState } from "react";
import type { GraphBeautificationResult, AsyncRequestState, StepGranularity } from "../types";

export type ExplanationRequestMode = "fresh" | "follow_up";

export interface ExplanationHistoryEntry {
  result: GraphBeautificationResult;
  requestState: AsyncRequestState;
  selectedStepId: string | null;
  granularity: StepGranularity;
  sessionLabel: string;
}

export const DEFAULT_EXPLANATION_SESSION_LABEL = "当前链路讲解";

/**
 * 讲解相关的本地状态 + ref 集合（P2-1 前端架构拆分）。
 *
 * 从 App.tsx 抽出的独立 hook，封装讲解步骤选择、粒度切换、历史栈和
 * 待处理钻取目标的全部状态。App.tsx 通过解构消费，减少主组件 state 声明数量。
 */
export function useExplanationState(graphBeautificationResult: GraphBeautificationResult | undefined | null) {
  const [selectedExplanationStepId, setSelectedExplanationStepId] = useState<string | null>(
    () => graphBeautificationResult?.steps?.[0]?.stepId ?? null,
  );
  const [selectedExplanationGranularity, setSelectedExplanationGranularity] = useState<StepGranularity>(
    () => graphBeautificationResult?.granularity ?? "BUSINESS",
  );
  const [explanationHistory, setExplanationHistory] = useState<ExplanationHistoryEntry[]>([]);
  const [currentExplanationSessionLabel, setCurrentExplanationSessionLabel] = useState(DEFAULT_EXPLANATION_SESSION_LABEL);
  const [hoveredExplanationStepId, setHoveredExplanationStepId] = useState<string | null>(null);
  const pendingExplanationDrillTargetRef = useRef<string | null>(null);
  const explanationLocalOverrideRef = useRef(false);
  const pendingExplanationRequestModeRef = useRef<ExplanationRequestMode | null>(null);
  const pendingExplanationSessionLabelRef = useRef<string | null>(null);
  const pendingExplanationHistoryEntryRef = useRef<ExplanationHistoryEntry | null>(null);

  return {
    selectedExplanationStepId,
    setSelectedExplanationStepId,
    selectedExplanationGranularity,
    setSelectedExplanationGranularity,
    explanationHistory,
    setExplanationHistory,
    currentExplanationSessionLabel,
    setCurrentExplanationSessionLabel,
    hoveredExplanationStepId,
    setHoveredExplanationStepId,
    pendingExplanationDrillTargetRef,
    explanationLocalOverrideRef,
    pendingExplanationRequestModeRef,
    pendingExplanationSessionLabelRef,
    pendingExplanationHistoryEntryRef,
  };
}

export type ExplanationState = ReturnType<typeof useExplanationState>;
